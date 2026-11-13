package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.inyogeshwar.codesathi.data.PreferencesStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * On-device local models (Phase 6, Step 5) — MediaPipe LLM Inference.
 *
 * VERIFIED (2026-11-13, before any code was written):
 * - Artifact `com.google.mediapipe:tasks-genai:0.10.27` exists on Google
 *   Maven (dl.google.com/dl/android/maven2/com/google/mediapipe/
 *   tasks-genai/0.10.27/tasks-genai-0.10.27.pom — aar, Apache 2.0) and
 *   is the exact version Google's own Android quickstart pins.
 *   The metadata lists newer versions (up to 0.10.35), but the docs pin
 *   0.10.27, so the docs version is used — the API shape below is the
 *   one those docs show.
 * - API shape, from Google's official docs
 *   (developers.google.com/edge/mediapipe/solutions/genai/llm_inference/
 *   android): `LlmInference.LlmInferenceOptions.builder()`
 *   `.setModelPath(path).setMaxTopK(64).build()`,
 *   `LlmInference.createFromOptions(context, options)`,
 *   `generateResponse(prompt)` for one whole reply, and
 *   `generateResponseAsync(prompt)` for streaming with
 *   `.setResultListener { partialResult, done -> }` on the options.
 *   Config table: maxTokens is input + output tokens (default 512),
 *   topK default 40, temperature default 0.8.
 * - Model format: a `.task` bundle. Google's docs name Gemma-3 1B in
 *   4-bit quantized format (from Hugging Face) as the quickstart model,
 *   and Gemma-3n E2B / E4B for multimodal use. The app NEVER bundles a
 *   model — the user downloads a .task file and picks it.
 * - Google lists this API as MAINTENANCE-ONLY and recommends migrating
 *   to LiteRT-LM for new projects. This step still uses MediaPipe
 *   because the task asked for it and it is verified; a LiteRT-LM
 *   migration is future work, not done here.
 *
 * NOT verified from Google's docs, so NOT used: the session async shape
 * (`LlmInferenceSession.generateResponseAsync`) appears only in
 * third-party tutorials, so this client uses the top-level
 * `LlmInference.generateResponseAsync` for streaming and builds the
 * multi-turn prompt as plain text (system + "User:" / "Assistant:" /
 * "Tool results:" turns, ending with "Assistant:"). There is no
 * session context caching — each turn re-sends the history as text,
 * exactly like the remote providers do.
 *
 * Partial results: the result listener's `partialResult` is treated as
 * the newly generated text (a delta to append), which is how Google's
 * sample apps and independent write-ups use it. If a first on-device
 * run ever shows replies repeating themselves, the partials are
 * cumulative instead and this must be fixed by diffing against the
 * previous partial — see the README first-run checks.
 *
 * How the model file reaches the engine: `setModelPath` needs a real
 * filesystem path — a SAF URI is not one. So the picked .task file is
 * copied once into app-private storage (getFilesDir()/local-models/)
 * and the cached copy is what loads. The SAF URI + persistable grant
 * stay the source of truth in DataStore; if the cached copy is ever
 * deleted, the next turn re-copies it from the URI. The copy doubles
 * the disk used by the model (file + cached copy) — the UI says so.
 *
 * Context window: the engine is created with maxTokens 4096 (input +
 * output). The prompt is capped at [MAX_PROMPT_CHARS] (~2,500 tokens)
 * by dropping the oldest history turns first, always keeping the
 * latest user message, so roughly 1,500 tokens stay for the reply.
 * Compaction thresholds are measured against the same 4,096 (see
 * AppViewModel.runAgentTurn), not the 128k remote default.
 *
 * Token counts: the engine reports no usage, so no [ChatStreamEvent.Usage]
 * is ever emitted — the runner falls back to the chars÷4 estimate and
 * labels the row estimated, exactly like a remote provider that sends
 * no usage. Local turns are free, so the dashboard shows "cost unknown"
 * for them (no price table entry exists for a local model).
 *
 * Images: the on-device models wired here are text-only — an image
 * attached to a message is refused before the turn starts (see
 * [com.inyogeshwar.codesathi.data.supportsVision]), so this client
 * never sees one. A history message's images are likewise filtered by
 * the runner's vision check, exactly like any other text-only model.
 *
 * One stream at a time: the app only ever runs one turn at a time
 * (guarded by isRunning), and this client assumes that — one cached
 * engine, one active handler. Stopping mid-turn drops the output;
 * if a new turn starts while the old generation is still running in
 * the engine, MediaPipe's behaviour is unverified (the new call may
 * fail with an error, which surfaces plainly) — see README.
 */
class LocalLlmClient(private val appContext: Context) {

    private val prefs = PreferencesStore(appContext)

    /** The loaded engine, or null until the first turn loads it. */
    private var engine: LlmInference? = null

    /** Which model URI [engine] was built from (null when none loaded). */
    private var loadedModelUri: String? = null

    /** Guards [engine] / [loadedModelUri] only — never held across IO. */
    private val engineLock = Any()

    /** The handler for the stream currently running, if any. */
    @Volatile
    private var currentHandler: StreamHandler? = null

    private val generationCounter = AtomicInteger(0)

    /** One active stream: its generation id plus its callbacks. */
    private class StreamHandler(
        val generation: Int,
        val onPartial: (String, Boolean) -> Unit,
        val onError: (Throwable) -> Unit
    )

    companion object {
        /** The catalog provider id for the on-device model (kind "local"). */
        const val LOCAL_PROVIDER_ID = "ondevice"

        /**
         * The engine's whole context window, in tokens (input + output).
         * 4,096 is the balance for a ~1B model: bigger preallocates a
         * bigger KV cache (more RAM), smaller starves the prompt. The
         * prompt cap below keeps ~1,500 tokens free for the reply.
         */
        const val CONTEXT_TOKENS = 4096

        /**
         * Prompt cap in characters (~2,500 tokens at chars÷4). The
         * prompt builder drops the oldest history turns first and
         * always keeps the latest user message.
         */
        const val MAX_PROMPT_CHARS = 10_000

        /** App-private cache dir for the copied .task file. */
        private const val MODEL_DIR = "local-models"
    }

    /** True when the user has picked a .task model file. */
    suspend fun hasModelFile(): Boolean =
        prefs.localModelUri.first()?.isNotBlank() == true

    /** The picked file's display name, or null when none is picked. */
    suspend fun currentModelName(): String? = prefs.localModelName.first()

    /**
     * Drop the cached engine (and forget which model it was) and delete
     * the cached .task copy, so a replaced / removed model frees its
     * hundreds of MB instead of sitting in app-private storage. Called
     * when the user picks a different file or removes the model — the
     * next turn loads the new file from scratch. Safe to call when no
     * engine is loaded. (Not used on app exit: [close] keeps the cached
     * copy, so the next launch does not have to re-copy the file.)
     */
    fun onModelChanged() {
        closeEngine()
        deleteCachedCopies()
    }

    /** Release the engine, keeping the cached .task copy for next launch. */
    fun close() = closeEngine()

    private fun closeEngine() {
        synchronized(engineLock) {
            engine?.close()
            engine = null
            loadedModelUri = null
        }
    }

    /** Delete every cached .task copy; a failure here never breaks the pick flow. */
    private fun deleteCachedCopies() {
        try {
            File(appContext.filesDir, MODEL_DIR).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
            // A leftover cached copy wastes disk but breaks nothing —
            // the next turn re-copies from the URI when it needs the file.
        }
    }

    /**
     * Stream one turn as text chunks, in the same [ChatStreamEvent]
     * shape the remote client uses — so the agent loop, fallback and
     * compaction need no local special case. Throws [LlmException]
     * (plain message, no stack trace in the chat) when no model file
     * is picked, the file cannot be read or copied, or the engine
     * fails to load or generate.
     */
    fun streamEvents(messages: List<LlmMessage>): Flow<ChatStreamEvent> = callbackFlow {
        val engine = try {
            ensureEngine()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }
        val prompt = buildPrompt(messages)
        val myGeneration = generationCounter.incrementAndGet()
        currentHandler = StreamHandler(
            generation = myGeneration,
            onPartial = { partial, done ->
                if (partial.isNotEmpty()) trySend(ChatStreamEvent.Text(partial))
                if (done) close()
            },
            onError = { error -> close(error) }
        )
        try {
            withContext(Dispatchers.IO) { engine.generateResponseAsync(prompt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close(e)
        }
        awaitClose {
            // Never clear a NEWER stream's handler: a Stop followed by
            // an immediate resend must not drop the new turn's output.
            if (currentHandler?.generation == myGeneration) currentHandler = null
        }
    }

    /**
     * The cached engine, loading it first when needed. Loading is slow
     * (copy a hundreds-of-MB file, then the native engine loads it), so
     * it happens once and the engine is reused for later turns until
     * the model changes or the app process dies.
     */
    private suspend fun ensureEngine(): LlmInference {
        val uri = prefs.localModelUri.first()
            ?: throw LlmException(
                "No on-device model file chosen yet. Pick a .task file in Providers → On-device model (.task)."
            )
        synchronized(engineLock) {
            val current = engine
            if (current != null && loadedModelUri == uri) return current
        }
        val file = withContext(Dispatchers.IO) { ensureModelFile(uri) }
        val built = withContext(Dispatchers.IO) { buildEngine(file) }
        synchronized(engineLock) {
            val current = engine
            if (current != null && loadedModelUri == uri) {
                // Another turn loaded the same model meanwhile — drop
                // the duplicate instead of leaking a second engine.
                built.close()
                return current
            }
            engine?.close()
            engine = built
            loadedModelUri = uri
            return built
        }
    }

    /**
     * The cached copy of the picked .task file, copying it from the SAF
     * URI first when it is missing. A zero-length cached file is treated
     * as a failed copy and re-copied. A copy that fails midway leaves no
     * partial file behind.
     */
    private suspend fun ensureModelFile(uriString: String): File {
        val name = prefs.localModelName.first()
            ?.takeIf { it.endsWith(".task", ignoreCase = true) }
            ?: throw LlmException(
                "No on-device model file chosen yet. Pick a .task file in Providers → On-device model (.task)."
            )
        val dir = File(appContext.filesDir, MODEL_DIR).apply { mkdirs() }
        val cached = File(dir, sanitizeFileName(name))
        if (cached.exists() && cached.length() > 0) return cached
        val uri = Uri.parse(uriString)
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            } ?: throw LlmException(
                "Could not read the model file. Re-choose it in Providers → On-device model (.task)."
            )
        } catch (e: LlmException) {
            cached.delete()
            throw e
        } catch (_: Exception) {
            cached.delete()
            throw LlmException(
                "Could not copy the model file into the app. Check the file is still there, then re-choose it in Providers → On-device model (.task)."
            )
        }
        if (cached.length() == 0L) {
            cached.delete()
            throw LlmException("The model file is empty — pick a real .task file.")
        }
        return cached
    }

    /**
     * Build the engine for one model file. The result listener is set
     * once, at creation, and forwards to whatever stream is active —
     * the engine is cached across turns, so the listener cannot belong
     * to one turn. Options are Google's documented ones: model path,
     * max tokens (input + output), max top-K 64 from the quickstart.
     * Temperature is left at the documented default (0.8).
     */
    private fun buildEngine(modelFile: File): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(CONTEXT_TOKENS)
            .setMaxTopK(64)
            .setResultListener { partialResult, done ->
                currentHandler?.onPartial(partialResult, done)
            }
            .setErrorListener { error ->
                currentHandler?.onError(error)
            }
            .build()
        return try {
            LlmInference.createFromOptions(appContext, options)
        } catch (e: Exception) {
            throw LlmException(
                "Could not load the on-device model (${e.message ?: "engine error"}). " +
                    "The file may not be a MediaPipe .task model, or the device may not have enough free memory — try Gemma-3 1B (4-bit)."
            )
        }
    }

    /**
     * One plain-text prompt for the engine: the system prompt verbatim
     * first, then the turns newest-first until [MAX_PROMPT_CHARS],
     * oldest dropped first. The latest user message is always kept
     * (truncated to its tail when it alone exceeds the budget). Ends
     * with "Assistant:" so the model answers instead of continuing the
     * transcript. Tool results travel as plain text — the runner parses
     * fenced tool blocks out of the reply, exactly like a remote
     * provider that sends no native tool_calls.
     */
    private fun buildPrompt(messages: List<LlmMessage>): String {
        val system = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val turns = messages.filter { it.role != "system" }
        val kept = mutableListOf<String>()
        var used = system.length + 2
        for (message in turns.asReversed()) {
            val line = when (message.role) {
                "assistant" -> "Assistant: ${message.content}"
                "tool" -> "Tool results: ${message.content}"
                else -> "User: ${message.content}"
            }
            if (kept.isEmpty()) {
                // The newest turn is always kept — but when it alone
                // exceeds the budget, keep its tail (the request end
                // matters most) instead of blowing past the cap.
                val budget = MAX_PROMPT_CHARS - used - 2
                val fitted = if (line.length > budget && budget > 0) line.takeLast(budget) else line
                kept.add(0, fitted)
                used += fitted.length + 2
            } else {
                if (used + line.length + 2 > MAX_PROMPT_CHARS) break
                kept.add(0, line)
                used += line.length + 2
            }
        }
        val prompt = StringBuilder()
        if (system.isNotBlank()) prompt.append(system).append("\n\n")
        if (kept.isNotEmpty()) prompt.append(kept.joinToString("\n\n")).append("\n\n")
        prompt.append("Assistant:")
        return prompt.toString()
    }

    /**
     * A safe file name for the cached copy: letters, digits, dot,
     * hyphen and underscore only. The picked name is a SAF display
     * name, so it should already be safe — this only guards against a
     * hostile or odd name reaching the filesystem.
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trimStart('.')
        return cleaned.takeIf { it.endsWith(".task", ignoreCase = true) && it.length > 5 }
            ?: "model.task"
    }
}
