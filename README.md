# CodeSathi — v1.2

On-device AI coding agent for Android. **Idea-only project**: every line of
code, the provider catalog, the prompts and the branding here were written
fresh for CodeSathi. Nothing was copied from Clozed Code, OpenCode or
AntiGem — no code, prompts, skills, catalog data, licences or assets.

- **App name:** CodeSathi
- **Package:** `com.inyogeshwar.codesathi`
- **Stack:** Kotlin + Jetpack Compose (Material 3), Room, DataStore
  Preferences, EncryptedSharedPreferences, kotlinx-serialization.
- **SDKs:** minSdk 24 (Android 7.0), compileSdk 36, targetSdk 36
  (Android 16), version 1.2.

## Latest setup (Nov 2026 migration)

Verified against the official AGP 9.5 release notes
(developer.android.com/build/releases/agp-9-5-0-release-notes) and Maven
Central: **AGP 9.5.1** (9.5.0 was 28 Oct 2026, 9.5.1 is 5 Nov 2026),
**Gradle 9.6.0**, **JDK 17**, **Build Tools 36.0.0**, max API level 37.
AGP 9.5 bundles Kotlin Gradle plugin **2.2.10** and has Kotlin built in,
so `org.jetbrains.kotlin.android` is no longer applied (AGP 9 rejects it);
Compose uses the `org.jetbrains.kotlin.plugin.compose` plugin instead of
`kotlinCompilerExtensionVersion`. Play Store requires new apps and updates
to target API 36 from 31 Aug 2026. Previous setup (for rollback):
AGP 8.2.0, Gradle 8.2, Kotlin 1.9.22, compileSdk/targetSdk 34,
Compose BOM 2024.01.00, Room 2.6.1.

> First Gradle sync in Android Studio is the verification step for this
> migration — **no build has been run here** (no Android SDK on this
> machine). The KSP version was corrected to `2.2.10-2.0.2` on 2026-11-12
> after the first real build failed: `2.2.10-1.0.2` does not exist on
> Maven Central. If sync still complains, the Room version (`2.7.0`) is
> the next line to check. The Gradle files are named `.gradle.kts`
> (Kotlin DSL) — a plain `.gradle` name will not compile them.

## Open in Android Studio

1. Open this folder (`CodeSathi/`) in Android Studio (Rabbit 2 /
   2026.2.2 or newer — the version that ships AGP 9.5 support).
2. Let Gradle sync (Android Gradle Plugin 9.5.1, Kotlin 2.2.10,
   Gradle 9.6.0, JDK 17). The first sync downloads the Android SDK
   pieces if they are missing (platforms;android-36,
   build-tools;36.0.0), and accept the SDK licences
   (`sdkmanager --licenses`) if the build asks for them.
3. Run on a device or emulator (API 24+): `./gradlew installDebug` or the
   Run button. **There is no Android SDK in the machine this project was
   written on, so no Gradle build has been run yet** — the first build in
   Android Studio is the real verification step.

## What works in v1 (checklist Steps 1–5)

1. **Permission-first onboarding + Home** — one onboarding screen, one
   "Allow file access" button opening the system folder picker
   (`OpenDocumentTree`). The persistable SAF URI grant is taken and stored
   in DataStore. **There is no path text field anywhere in the app.**
   Home lists sessions (title, folder name, model label, last activity,
   status dot). "+ New session": name prefilled from the folder name →
   Choose folder → default model → Create → chat. A folder whose grant was
   revoked (or that was deleted) shows "Folder unavailable — tap to
   re-choose" and never crashes.
2. **Plan screen** — Plan | Build toggle, Plan is the default. Plan mode is
   read-only in code (`ToolContext.readOnly`, plus Write/Edit refuse in
   Plan). A plan ends in a summary card (files / steps / risks) with
   **Approve & switch to Build / Edit plan / Discard**. Nothing is written
   before Approve.
3. **Build / chat screen** — chat bubbles (assistant replies render as
   Markdown: headings, bold/italic, code blocks with copy, lists, links),
   tool-call history, a diff card (old → new, or new file) with
   **Approve / Reject before any write**, Stop button, model id +
   approximate token counter (chars ÷ 4). The agent runs a real
   tool-calling loop: up to 10 model turns per message, native
   OpenAI-style `tool_calls` plus fenced ```tool blocks, read/glob/grep
   run at once and feed results back, a write/edit call pauses for the
   diff-card Approve and the loop resumes after approval. Per-tool
   permission rules (allow / ask / deny) are in the chat menu under
   "Tool permissions" — writes always need Approve, even on "allow".
   (Phase 7, Step B2 adds approval modes + glob rules to the same
   dialog — see below.)
   "Edit plan" prefills the goal in the chat box. After a write, light
   verification runs (exists, non-empty, basic HTML sanity) and shows
   "✓ Verified: …".
4. **Providers + model picker** — a bundled `providers.json` written for
   this app with 207 providers (the 12 v1 starters plus direct labs,
   inference hosts, gateways and 30 local servers — Anthropic, OpenAI,
   OpenRouter, Google Gemini, Groq, DeepSeek, xAI, Mistral, Together,
   Ollama local, LM Studio local, TokenHarbor, and 196 more), each with
   base URL and example models (261 bundled model ids in total; 46
   providers ship with an empty model list because their model ids
   could not be verified — the app fetches them live or the user types
   one in by hand). Live-checked 2026-11-12: every remote base URL was
   probed with `GET {baseUrl}/models` (no auth; 200/401/403 = reachable),
   6 dead/duplicate entries were removed, 10 base URLs were corrected
   and 62 providers were added — the 200+ target is reached. Picker order:
   Top picks → Favourites (starred, in Room) → fetched models → bundled
   models, with search. **Refresh** loads
   `GET {baseUrl}/models` (Bearer, or `x-api-key` for Anthropic, no key
   for local providers), caches the list in Room, and on failure shows
   the cached list with the error; a model id can also be typed in by
   hand. Picking a model sets it for the open session (if any) and as
   the default for new sessions. API keys are entered per provider and
   stored **only in EncryptedSharedPreferences** — never plain text, never
   in the JSON, never shipped. Chat uses a small OpenAI-compatible client
   over `HttpURLConnection` with SSE streaming; Anthropic uses
   base + `/messages` with the `anthropic-version` header. With no key /
   model set, the chat shows "Add a provider API key first" instead of
   pretending to work.
5. **Compaction (basic)** — all messages stay in Room. `estimateTokens`
   = chars ÷ 4; over 60% of context, old tool outputs are pruned before
   sending; over 75% the session's model is asked for a structured
   Markdown summary (offline local summary is the fallback if that call
   fails), kept beside the last ~15k tokens, with a "Chat summarised…"
   banner (tap to read) and a manual "Summarise chat" menu item.
   Nothing is ever silently dropped. (Phase 7, Step B2 caps the window
   at the smallest known window among the session model + fallback
   chain, never splits a tool call/result pair at the cut, and retries
   a "context too long" step once after harder compaction — see below.)

## Phase 2, Step 1 — multi-key fallback / auto-switch

- **Several API keys per provider** — add, list and delete keys in
  Providers. Keys live only in EncryptedSharedPreferences (one encrypted
  JSON list per provider); they are shown masked (first 3 + last 4
  characters) and are never logged or stored in Room. A key saved in v1
  still works: it is read as a one-key list.
- **Fallback order** — an ordered list of (provider, model) steps, 1st →
  2nd → 3rd …, edited in Providers: add any model with the + button,
  move steps up/down, remove them. The chain is stored in DataStore
  (no Room change, no migration).
- **Auto-switch in the chat** — when a model turn fails with a limit
  error, the turn retries automatically: first every other
  non-exhausted key of the same provider + model, then the fallback
  chain in order. Because a chain step may name another model of the
  *same* provider, this is also single-provider fallback (e.g. a paid
  model hits its limit → the free model of the same provider takes
  over). A turn is only retried when nothing was shown yet, so a reply
  is never duplicated.
- **Limit kinds** — quota / long limit is HTTP 402 or a response body
  that mentions quota; a plain HTTP 429 is a short rate limit. The kind
  is shown in the switch notice; both mark the key exhausted. Bedrock
  throttling is a 429 too, so it maps to the same RATE kind — no new
  failure kind was invented. Bedrock has one credential set, not a key
  list, so it gets a single keyless attempt per turn and the synthetic
  key id "aws" lets a throttled Bedrock model be marked exhausted until
  midnight like any other provider's key.
- **Exhaustion resets by cooldown, not midnight (Phase 7, Step
  B1)** — a key marked exhausted is skipped until its failure's
  cooldown ends: rate limit 60 s, daily quota 1 h, out of credit
  6 h, server error 20 s, network failure 15 s. A key the provider
  rejected (401/403) is marked invalid and stays skipped until the
  user resets it. Exhaustion is tracked by key id (a SHA-256 prefix,
  never the key value) in DataStore, and expired flags are pruned on
  read. A fallback entry whose every key is blocked shows its reason
  and retry time in Providers ("rate limited — retries in ~1 min").
- **Switch notice** — when a turn switches, the chat shows a small
  banner ("Switched to … — quota reached on …") and keeps the same text
  as a tool message in the history. The session's own model is not
  changed: the fallback applies per turn, and the next message starts
  from the session model again.

## Phase 2, Step 3 — MCP servers

- **Add MCP servers** — name + URL + optional auth token, in the MCP
  screen (puzzle-piece button on Home). Servers and their enabled flags
  live in DataStore; auth tokens live **only in EncryptedSharedPreferences**
  (one entry per server, shown masked, never in DataStore/Room/logs).
- **MCP client** — JSON-RPC over streamable HTTP / SSE on
  `HttpURLConnection` (no new dependencies): `initialize` handshake,
  `tools/list`, `tools/call`. Accepts plain-JSON and SSE (`data:`)
  replies, keeps an `Mcp-Session-Id` when the server hands one out,
  sends `Authorization: Bearer` when a token exists. Short timeouts
  (10 s connect / 30 s read); failures are user-facing messages, not
  stack traces.
- **MCP tools in the agent** — a server's tools appear as normal tools
  named `mcp:<server>:<tool>`, under the same allow / ask / deny rules
  as the built-ins (default "ask"). They are listed in the Build system
  prompt when their server is enabled, dispatched through the same
  `AgentRunner` path, and a denied MCP tool is refused exactly like a
  denied built-in. A down server never blocks a chat turn — its error
  is recorded and the turn runs without it.
- **Exa web search** — `https://mcp.exa.ai/mcp` is pre-filled and
  **disabled by default**. No API key is bundled: enable it and paste
  your own Exa key as the server's token.
- **Known limits** — MCP tool arguments are strings only (a value that
  is itself valid JSON is sent as that JSON value); there is no
  "change token" UI for an existing server — delete and re-add it.
  No live MCP call has been tested yet (no server was reachable from
  the machine this was written on); first check is enabling Exa with a
  real key and tapping Refresh on the MCP screen.

## Phase 2, Step 4 — Skills

- **Skills are Markdown files** — one file per skill: a `# Name` line,
  a `Description:` line, then the instructions. Built-in skills ship
  under `assets/skills/` and are copied into the app-private
  `getFilesDir()/skills/` folder on first use, so the user can edit
  them; user-added skills are Markdown files in the same folder.
  Skills can also be installed from a GitHub URL or skills.sh link
  (Phase 7, Step B4 — one SKILL.md becomes one file here), and plugins
  contribute skills read from their own folder (same section). There
  is no marketplace and no versioning.
- **Built-in skills (written fresh)** — `code-review` (review a file
  for bugs, security and style, findings with file + line), `write-tests`
  (small focused tests per behaviour, happy path then edge cases),
  `explain-code` (plain-language explanation, short summary first).
- **The skill tool** — the agent calls
  `{"tool":"skill","args":{"name":"code-review"}}` and the skill's
  instructions come back as the tool result, which the model then
  follows. Enabled skills are listed in the Build system prompt. The
  tool is a normal tool: rule "ask" by default, same gating as the
  built-ins, and it refuses unknown / disabled / empty skills with a
  clear message. A skill never writes by itself — anything it asks for
  still goes through write / edit and the diff-card Approve.
- **Skills screen** — list with enable/disable switch (DataStore, no
  entry = enabled), Edit (plain-text Markdown editor), Add new (name
  becomes the id, duplicates refused), Delete (user skills only),
  Reset (built-ins only, restores the shipped asset).
- **Not yet verified live** — first-launch seeding, editing/saving a
  skill, and a real turn where the model calls the skill tool are all
  untested here (no Gradle build on this machine).

## Phase 2, Step 5 — Voice

- **Voice input** — mic button in the chat input row. Tapping it
  asks for the microphone permission first (RECORD_AUDIO, in the
  manifest and requested at runtime); once granted, the next tap
  records with Android SpeechRecognizer and the recognised text is
  appended to the input field. While listening the icon turns red and
  "Listening… speak now." shows; tapping again stops. Failures show a
  short message ("No speech heard — try again.", network variants).
  One recognizer is created per listen and destroyed after each use,
  so it never leaks.
- **Voice output** — Android TextToSpeech reads the assistant's final
  reply, with a "Voice output" toggle in the chat menu (persisted in
  DataStore, off by default). Markdown is stripped first (code blocks
  dropped, links keep their text, `#` / `**` / bullets removed) and
  replies are cut to the first 500 characters. Turning the toggle off
  stops any speech in progress. One TTS instance, owned by the
  ViewModel, device default locale with English fallback, shut down in
  `onCleared()`; if TTS fails to start, speaking is silently skipped
  and the chat never crashes.
- **"Task completed" announcement** — after a Build-mode turn finishes
  cleanly, the app speaks "Task completed." (own wording), queued
  after the reply. It fires only when the toggle is ON and the turn
  ended with Done and no error and no file-proposal pause; Plan-mode
  turns and failed turns stay silent.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). Riskiest lines: the Switch inside the dropdown
  menu item, and the TextToSpeech constructor lambda. Also untested:
  devices without a recognition service, and TTS voice availability
  per locale.

## Phase 2, Step 6 — Terminal

- **In-app terminal** — runs shell commands in the last session's
  project folder (`sh -c <command>` via ProcessBuilder), streams
  stdout + stderr (merged) live into a scrollable monospace history,
  shows the exit code when a command finishes. One command at a time;
  Stop kills the running process. Past commands are kept as chips —
  tap one to re-run it. Output is capped at the last 2,000 lines,
  history at 20 commands.
- **Folder resolution** — the project folder is a SAF tree URI, not a
  filesystem path, so it is resolved best-effort to a raw path for
  primary external storage ("primary:AgentMT" →
  /storage/emulated/0/AgentMT). The screen always shows the folder
  name and the exact path commands run in; when it cannot be resolved
  (revoked grant, deleted folder, non-primary volume) the screen says
  why and runs in the app's private files dir instead — never silently
  in the wrong folder. With no session yet, the screen explains and
  offers a button back to Home.
- **User-facing only, until Phase 6 Step 1** — the terminal was not
  wired into AgentRunner at first; Phase 6, Step 1 adds the agent's
  `run` tool on top of the same `TerminalRunner` (see below), so the
  screen and the agent now share one shell executor. Commands run with
  the app's own permissions (stated on screen).
- **Honest limit** — the app holds no storage permission (folders come
  only from the SAF picker grant), and a SAF grant does not give a
  child shell process raw-filesystem access. So commands that touch
  files in the resolved project folder may print "Permission denied" —
  the terminal shows that stderr plainly instead of hiding it, and the
  app-private fallback folder always works. Reliably reading/writing
  the project folder from the shell would need a storage-permission
  decision, which is out of scope for this step.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). Watch at compile time: `Icons.Filled.Terminal`
  and `LocalTextStyle`.

## Phase 2, Step 7 — Remote mode

- **Local-network HTTP server, off by default** — a foreground service
  with a persistent notification (with a Stop action) serves a small
  REST API + web page on port 8080, so the user can chat with the
  agent from another device on the same Wi-Fi. Hand-rolled minimal
  HTTP/1.1 server on `java.net.ServerSocket` (no new dependency;
  `com.sun.net.httpserver` is a JDK API, not in the Android runtime).
- **Own small API** — `GET /` (plain HTML page, no frameworks),
  `GET /api/sessions`, `GET /api/session/{id}`,
  `POST /api/prompt {"sessionId","message"}`. The prompt call runs one
  agent turn and returns JSON `{reply, tools[], error?, proposal?,
  plan?, fallbackNotice?}` — no streaming, chat only. Phase 6,
  Step 4 adds two sync endpoints on the same server, same password:
  `GET /api/sync/export` (every session as backup-format JSON) and
  `POST /api/sync/import` (merge a backup, newest `updatedAt` wins,
  answering `{added, updated, skipped, reasons}`) — see the Step 4
  section below.
- **Password auth** — set on the Remote screen (min. 4 characters),
  stored only in EncryptedSharedPreferences. Every `/api/*` call needs
  `Authorization: Bearer <password>` (constant-time check); wrong /
  missing → 401, no password set → 503 and the server refuses to
  start. The password is never logged and never in a URL. The toggle
  only shows "on" once the server is actually listening (a failed
  start, e.g. port busy, leaves it off).
- **Same agent loop** — a remote turn reuses `runAgentTurn` (same
  provider / model / project, tool rules, MCP tools, skills,
  compaction, fallback). It refuses to start while another turn runs
  or a file change awaits approval on the phone. A turn that proposes
  a file change does NOT apply it — the proposal stays on the phone's
  diff card and is returned in the response. No remote approve, no
  remote file browsing, no remote terminal. LAN only, plain HTTP.
- **Remote screen** — on/off toggle, masked password set/change,
  the local URL (Wi-Fi IP + port, with Refresh), session count, and a
  "same Wi-Fi" note when no Wi-Fi address is found.
- **Known quirks** — if voice output is ON, a remote turn that
  finishes cleanly also speaks on the phone (it reuses the same turn
  code); a remote turn's reply is read back from Room as the last
  assistant message, so a turn that only emits a plan/proposal with no
  assistant text returns an empty `reply` plus the `plan`/`proposal`
  fields.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). Most likely friction: the `dataSync`
  foreground-service type/permission on API 34+, and the
  notification-permission flow on Android 13+.

## Phase 2, Step 8 — Git

- **JGit, one new dependency** —
  `org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r` (latest
  stable on Maven Central, released 5 Nov 2026). Pure Java, no native
  code, no `git` binary needed. Its only transitive dependencies are
  JavaEWAH and slf4j-api — no BouncyCastle in core (that is the
  separate `gpg.bc` artifact, for commit signing, which we do not
  use), no SSH transport (separate artifact, deliberately not added),
  so remotes are **HTTPS only**. JGit 7.x is compiled for Java 11,
  which D8 dexes. Only packaging change: `META-INF/*.SF`, `*.DSA`,
  `*.RSA`, `LICENSE*`, `NOTICE*` added to the existing
  `packaging.resources.excludes` (the JGit jar is signed and its jars
  ship duplicate license files). No library/AGP/Gradle version
  changed.
- **Status / stage / commit / push / pull** — the Git screen works on
  the last session's project folder, resolved the same way as the
  terminal (SAF tree URI → raw path, primary storage only). Status
  shows the current branch, the redacted origin URL, and Staged /
  Changed (not staged) / Untracked / Conflicts lists, each file with a
  Stage/Unstage button; deletions stage via `rm --cached`. Commit
  needs a message and something staged, and returns the short commit
  id. The author is set explicitly (saved Git username, else
  "CodeSathi"; email `codesathi@localhost` unless the username is an
  email), so nothing depends on a `~/.gitconfig`. Pull throws a
  plain-language error naming the conflicted files on a merge
  conflict.
- **Branches + clone** — local branch list with the current branch
  marked, Checkout per branch. Clone takes a repo URL and clones into
  a subfolder named after the repo (last URL segment minus `.git`,
  sanitised); it refuses when a folder with that name already exists.
  The clone is not the session's folder — open it as a new session
  from Home to work in it.
- **Credential** — username + token/password, stored only in
  EncryptedSharedPreferences, shown masked only, passed via
  `UsernamePasswordCredentialsProvider` (never in a URL, never
  logged). Needed for push/pull and private clones (GitHub: username
  + a personal access token). Remote URLs are shown with any
  `user:token@` userinfo stripped.
- **No fallback folder** — unlike the terminal, Git has no app-private
  fallback: JGit needs a real filesystem path, so on an unresolved
  folder every Git action is refused with a plain message ("go back
  to Home and open the session to re-choose its folder").
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). One first-build risk is already fixed: JGit 7.x uses
  `java.nio.file` APIs that only exist on Android API 26+, so NIO
  desugaring is enabled (`isCoreLibraryDesugaringEnabled = true` +
  `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")`
  in `app/build.gradle.kts`) to backport `java.nio.file` for API 24/25
  (minSdk 24 stays). slf4j ships with no binding, so expect a one-time
  "No SLF4J providers were found" warning in logcat (harmless). If the
  first sync complains about duplicate META-INF entries beyond the five
  patterns added, extend the excludes block in
  `app/build.gradle.kts`.

## Phase 3, Step 1 — Custom commands

- **Slash commands in chat** — a command is a name, a short
  description, and a prompt template, stored as JSON in DataStore
  (`commands_json`, same pattern as the MCP servers and the fallback
  chain). Typing `/name` in chat expands to the template before the
  message reaches the model, in Plan and Build alike — and also on
  remote turns, which share the same expansion. The message stored in
  the chat history stays exactly as the user typed it; only the text
  sent to the model is expanded.
- **Expansion rule** — "/name some text": if the template contains
  `{input}`, the text after the name replaces it; otherwise the text
  is appended after the template on a new line. "/name" alone sends
  just the template. An unknown "/name" and a disabled command are
  sent as plain text — the chat never breaks on a slash.
- **Built-in examples, seeded once** — `review-code` (review the
  project code for bugs, security issues and readability, focused on
  the file or area named after it) and `write-tests` (write tests for
  the code named, in the project's own testing style, or pick the most
  important untested part). Seeded once, never overwriting a list the
  user already has — same pattern as the Exa MCP entry. Both can be
  edited or deleted like any other command. Templates are written
  fresh, in the app's own words.
- **Commands screen** — reachable from Home (top-bar `</>` icon) via
  the `commands` route. Add / edit / enable / disable / delete.
  Names use lowercase letters, digits and hyphens only
  ("review-code"); empty names and duplicates are refused with a
  plain message. While typing "/" in the chat input, matching enabled
  commands appear as tappable chips above the input; tapping one
  completes "/name " in the field.
- **Limits** — in Plan mode the expanded prompt is still answered as
  a plan (read-only, JSON plan only), so a command there plans the
  work rather than doing it. In Build mode the history still contains
  the raw "/name …" line alongside the expanded message sent last, so
  the model sees both. Commands hold no secrets.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: the `prefix` slot on the name
  fields (`OutlinedTextField` prefix, Material3 1.2+) and the
  `Icons.Filled.Code` top-bar icon (material-icons-extended is
  already a dependency).

## Phase 3, Step 2 — Reasoning-effort picker

- **Per-model effort** — every model row in Providers has Default / Low /
  Medium / High chips (FilterChip), for OpenAI-kind and Anthropic-kind providers alike. The choice is stored per provider +
  model in DataStore as a JSON map (`reasoning_effort_json`, keyed
  `"providerId/modelId"` — same pattern as the tool rules and skill
  flags). No entry means "Default": the app sends no effort field at
  all for that model. Only "low" | "medium" | "high" are ever stored;
  anything else is dropped on read and on write.
- **Sent only when set** — `LlmClient` adds the effort only when
  the user set one for that model: `"reasoning_effort":"<low|medium|high>"`
  in the `/chat/completions` body for OpenAI-kind providers (including
  local OpenAI-compatible ones), and `"output_config":{"effort":"<low|
  medium|high>"}` in the `/messages` body for Anthropic-kind providers.
  The value is validated again at send time, so a corrupt stored value
  can never reach a provider. A model that does not support the field
  answers 400, which surfaces through the normal `LlmException` path
  (the chat shows the provider's error) — no new error handling.
- **Anthropic effort verified from Anthropic's own docs** — the builder
  first left Anthropic untouched because the field could not be verified
  against Anthropic's docs; the coordinator then verified it directly
  at platform.claude.com/docs/en/build-with-claude/effort (Nov 2026):
  the field is `output_config.effort`, levels low/medium/high/xhigh/max,
  on the Messages API. Only low/medium/high are ever sent (the picker
  offers those). Anthropic lists effort for Claude 4.5+ / 5.x models, so
  an older Claude model may answer 400 (normal error path). Two caveats
  from those docs, recorded not fixed: Anthropic recommends a large
  max_tokens at high effort, but this client still sends max_tokens 4096;
  and changing the top-level effort between requests can invalidate the
  prompt cache, so the effort is best left steady within one session.
- **Flows through fallback per model** — the effort is not cached from
  the first attempt. `AgentRunner` takes an `effortFor` lookup (wired
  to DataStore by the ViewModel) and calls it fresh for every attempt,
  so a fallback switch to another model sends that model's effort —
  or nothing, when that model is at "Default". The approve-only
  runner (no LLM call) passes no lookup. The compaction summary call
  also sends no effort (it uses the session model at default).
- **Shown in chat** — the chat header's model line reads
  "Model: provider / model • Effort: medium • ~tokens" when an effort
  is set for the session's model, and omits it at Default.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: `FilterChip` (Material3,
  needs the existing `ExperimentalMaterial3Api` opt-in — applied on
  `ModelRow` and `EffortChip`) and the suspend lambda type
  `(suspend (String, String) -> String?)?` on the `AgentRunner`
  constructor. Untested live: that a real provider accepts
  `reasoning_effort` on a reasoning model and rejects it cleanly (400)
  on one that does not, and that the fallback path really re-looks-up
  the effort after a switch.

## Phase 3, Step 3 — Editor tabs / live preview

- **Editor screen** — reachable from Home (top-bar pencil icon) via
  the `editor` route. It works on the last-opened session's project
  folder (same session choice as the terminal and Git). The folder is
  resolved with the same `resolveTerminalFolder` as the terminal and
  Git (SAF tree URI → raw path, primary storage only); like Git there
  is no fallback folder — when it cannot be resolved the screen
  refuses with a plain message telling the user to re-choose the
  folder from Home.
- **Tabs** — the top-level files of the folder were shown as chips
  (the same SAF listing the agent's glob tool uses, via
  `SafProject.listEntries()`); Step 5 replaced the chips with the
  folder tree browser (see below). Tap a file
  to open it in a tab; tap a tab to switch. Edits live in memory per
  tab with a "•" dirty marker — switching tabs never loses an unsaved
  edit. Closing a clean tab closes at once; closing a dirty tab asks
  "Discard changes?" in an AlertDialog (Discard / Keep editing). A
  tab whose save is waiting for approval cannot be closed until that
  proposal is approved or rejected.
- **Save reuses the diff flow — no second write path** — Save never
  writes a file directly. `AppViewModel.proposeEditorSave()` turns
  the edited text into the same `PlannedFile` proposal the agent's
  writes use, and the file is only written when the user taps
  Approve on the shared diff card (`DiffCard`, moved out of
  ChatScreen into its own file so both screens use the identical
  card). The proposal is an "edit" carrying the whole old file as
  `oldSnippet` and the whole new text as `newSnippet`, so
  `AgentRunner.applyFileProposal` replaces the file only when the
  disk still holds that old text — a change the agent made meanwhile
  fails the write instead of being silently overwritten. An empty or
  missing file on disk becomes a "create" (full overwrite), because
  an "edit" with an empty old snippet would corrupt the file
  (`String.replace("", …)` inserts at every position). Plan mode
  refuses to save at all (Save disabled, with a note to switch to
  Build in Chat), and a proposal already waiting refuses a second
  one. `approveProposal()` gained two optional parameters with
  defaults, so Chat is unchanged: `resumeAgent = false` for editor
  saves (there is no agent turn to resume after an editor save) and
  an `onApplied(ok)` callback so the editor clears its dirty marker
  only when the write really succeeded. If the disk already holds
  the tab's exact text (e.g. the same save was approved from the
  Chat screen), Save just clears the dirty marker. An agent proposal
  can also be approved from the editor's diff card — it resumes the
  agent loop exactly as approving it in Chat would.
- **Live preview** — for .html files, an Editor / Preview toggle
  under the tabs (a phone screen is too narrow for editor + preview
  side by side, so the toggle — not a split — is the layout). The
  preview is a plain `android.webkit.WebView` (no new dependency)
  fed with the file's SAVED text read through SAF via
  `loadDataWithBaseURL(null, …)` — no SAF URI or file:// URL is ever
  loaded, so the WebView gets no filesystem access
  (`allowFileAccess = false`, JavaScript on, WebView destroyed on
  release). It reloads when a file is opened and when a save is
  approved; typing alone does not reload it.
- **Text files only** — binary files are refused with a plain
  message, detected by extension (images, archives, apk/jar, pdf,
  media, fonts, databases…) and by a NUL byte in the first 512
  bytes; non-UTF-8 files are refused too (strict decode, so a later
  save can never silently corrupt the file with replacement
  characters); files over 1 MB are refused. Reading uses a new
  `SafProject.readBytes()`; nothing here invents a second
  folder-listing implementation.
- **Limits** — Step 5's tree browser now lists subfolder files too
  (5 levels deep, 200 entries per folder, 500 nodes total); the preview shows the saved file only, and
  local relative assets (a linked .css / .js / image next to the
  .html) do NOT load in it, because the content is in-memory data,
  not a file URL. If a proposal is approved from the Chat screen
  while the editor tab is open, the tab's dirty marker clears the
  next time Save is tapped (the disk already holds the text).
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: `AndroidView` with
  `onRelease = { it.destroy() }` (Compose 1.2+ API, present in the
  BOM's Compose version) and `androidx.compose.runtime.key` with two
  keys; the `resumeAgent` / `onApplied` parameters on
  `approveProposal` (defaults keep the Chat call site unchanged);
  and the WebView behaviour on a real device (JavaScript on for
  local content, `loadDataWithBaseURL` with a null base URL).

## Phase 3, Step 4 — MANAGE_EXTERNAL_STORAGE

- **Manifest** — `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />`
  added to `app/src/main/AndroidManifest.xml` (declared only). It is
  NEVER requested at runtime anywhere: this is a special permission
  that can only be granted from system Settings, and the app never
  opens that screen unprompted or at startup.
- **"Storage access" card on Home** — under the "Sessions" heading
  (no dedicated Settings screen exists yet, and Providers / Remote
  are feature screens, so Home is the natural home; no new screen,
  no navigation change). The card explains in plain words what
  all-files access does (the app can read/write files in the project
  folder directly, so the terminal, Git and editor work on real files
  instead of only through the folder picker grant) and what it does
  NOT do (it does not send files anywhere; API keys stay encrypted).
  It shows the current state — granted / not granted — read live via
  `Environment.isExternalStorageManager()`, re-read on every resume
  so coming back from Settings shows the new state at once. Tapping
  the card opens this app's page in system Settings with
  `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` and the
  `"package:<applicationId>"` URI (falling back to the general
  `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` list if the app page
  cannot be opened).
- **API gating** — the permission only exists on Android 11+ (API
  30+). On older phones the card is shown disabled with a one-line
  explanation ("All-files access needs Android 11 or newer") instead
  of being hidden.
- **Helper** — `agent/Terminal.kt` gained `hasAllFilesAccess()`
  (`isExternalStorageManager()` on API 30+, false below),
  `isAllFilesAccessSupported()`, and `openAllFilesAccessSettings(context)`
  (tap-only). The granted state is wired in honestly and minimally:
  the terminal's folder card says whether commands have raw
  filesystem access (and that "Permission denied" from file-touching
  commands can be fixed from Home → Storage access), and
  `resolveTerminalFolder`'s "Folder not found" reason gains a hedged
  hint about the grant — the one unresolved case where it can
  actually help, since without it the app may be unable to SEE a
  folder that really exists. That reason is shared by the terminal,
  Git and editor, so all three show it. The SAF layer (`SafProject`)
  is untouched, and no file operation depends on the permission:
  when it is not granted, everything keeps working exactly as today
  via the folder picker.
- **Play Store declaration** — `MANAGE_EXTERNAL_STORAGE` is a
  restricted / special permission: the Play Store listing needs a
  declaration with a justification for it before the app can be
  published (Google asks why broad file access is needed and may
  reject apps whose core use does not require it — CodeSathi's case
  is that the terminal and Git work on real files in the user's own
  project folder). The permission is optional in the app itself
  (everything works via SAF without it), so if the declaration is
  refused the fallback is to remove the permission and keep the
  folder-picker-only behaviour.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: `LifecycleEventObserver` /
  `LifecycleOwner` on Home (lifecycle-runtime, a transitive
  dependency of the ViewModel libraries — should resolve, but if
  sync complains, re-read the state in `LaunchedEffect` on the card
  instead); `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
  and `Environment.isExternalStorageManager()` are API 30+ and are
  both guarded by `isAllFilesAccessSupported()`; and the system
  Settings screen flow itself (tap → app page → grant → back →
  card shows "Granted") has not been exercised on a device.

## Phase 3, Step 5 — UI polish

- **Syntax-highlighted diffs** — a small hand-rolled highlighter,
  `ui/SyntaxHighlight.kt` (no new dependency): a single-pass scanner
  turns code text into an `AnnotatedString` — keywords (bold purple),
  strings (green), comments (grey italic), numbers (blue), plus tag
  names for HTML/XML, keys for JSON, and headings / inline code /
  bold for Markdown. The language comes from the file's extension
  (`languageForPath`: Kotlin/Java, Python, JS/TS, HTML/XML, JSON,
  Markdown); an unknown extension is shown plain, never guessed.
  The diff card (`DiffCard`) highlights the old text, the new
  snippet, and new-file content in monospace, with the 600-char
  display cap applied BEFORE highlighting so the spans always line
  up, and each highlight computed once per display (`remember`),
  never on every recomposition. Approve / Reject behaviour is
  completely unchanged — only the text rendering. The same
  highlighter also colours fenced code blocks in the chat's Markdown
  renderer, keyed off the fence's language tag. An unterminated
  string or comment is capped at the end of the line, so a snippet
  cut off at 600 chars can never paint the rest of the text one
  colour. Colours are fixed mid-tones chosen to read on both the
  light and the dark theme.
- **Folder tree browser** — the Editor screen's top-level file chips
  are replaced by `ui/screens/FolderTree.kt`, an expandable tree of
  the whole project folder. The root level reuses the editor's
  existing top-level listing; every subfolder loads its children
  lazily the first time it is expanded, through the same
  `SafProject.listEntries(relativeDir)` the agent's glob tool uses —
  no second listing implementation. Tap a file to open it in a tab:
  nested paths work (`SafProject.find` walks "sub/file.kt" segment
  by segment, and the same path is used for reading, saving and the
  tab key — verified by reading `SafProject`, not yet by an
  on-device run). Tap a folder's chevron to expand / collapse it;
  collapsing drops that folder's cached children (and everything
  below it), so memory stays proportional to what is on screen.
  Caps: folders expand at most 5 levels deep, at most 200 entries
  are shown per folder, and at most 500 tree nodes are loaded in
  total — a huge folder cannot freeze the UI (the cap is enforced in
  `EditorViewModel.toggleTreeDir` as well as in the UI, and the tree
  says so on screen). Refresh clears all cached subfolder listings.
- **New session from a subfolder** — each folder row has a small
  "+" button (a visible button, not a hidden long-press — a
  long-press would be undiscoverable). Tapping it asks first ("New
  session from this folder?"), then creates the session through the
  normal new-session path: the subfolder's DocumentFile URI is
  turned into a proper SAF tree URI rooted at that folder
  (`DocumentsContract.buildTreeDocumentUri` — a plain document URI
  would not work as a session's projectUri, because `SafProject`
  opens its root with `DocumentFile.fromTreeUri`), and
  `AppViewModel.createSessionFromFolderUri` runs it through the
  usual persistable-grant + `createSession` flow, so the session
  gets the default model, Plan mode, and becomes the last session
  exactly like a picked folder. The result stays on screen with an
  "Open session" button (or "Stay here"). Honest limit: taking a
  persistable grant on a subfolder tree URI can fail (the grant
  lives on the parent tree) — when it does, the session is still
  created from the URI, which works right now under the parent
  folder's grant, and the note says plainly that the permission may
  not survive a restart, so the user can re-choose the folder from
  Home if it ever stops working.
- **Home top-bar overflow (known issue, fixed)** — the top bar held
  8 icons and the last ones clipped on a narrow phone, with
  Providers (Settings) last so it would clip first. The four
  most-used destinations stay visible as icons (Editor, Git,
  Terminal, Providers — Providers is always one tap away on any
  screen width); Remote, Skills, Commands and MCP live behind a
  "⋮" menu (same `DropdownMenu` pattern as the Chat screen's menu).
  No destination was removed — all eight stay reachable.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: `Icons.Filled.ChevronRight` /
  `ExpandMore` / `Folder` / `InsertDriveFile` (material-icons-extended
  is already a dependency, and the other icons used here were already
  in the codebase); `DocumentsContract.buildTreeDocumentUri` /
  `getDocumentId` (framework APIs, API 21+ — fine on minSdk 24);
  the subfolder persistable-grant behaviour on a real device (the
  grant may or may not stick — the note covers both outcomes, but
  only a device run shows which one happens); and the tree on a
  real large folder (the caps are untested under load).

## Phase 5, Step 2 — Usage & cost dashboard

- **Real usage when the provider sends it** — the LLM client now
  parses the provider's own token counts and reports them as a
  `ChatStreamEvent.Usage` at the end of each turn: the OpenAI SSE
  `usage` object in the final chunk (the request now asks for it with
  `stream_options: {"include_usage": true}` — without that flag
  `/chat/completions` never reports usage), Anthropic's `message_start`
  (input_tokens) and `message_delta` (output_tokens) events, the
  `usage` object of a plain-JSON (non-streaming) reply, and Bedrock
  Converse `usage` (`inputTokens` / `outputTokens` — Converse does
  report usage, so Bedrock turns are real, not estimated, when the
  field is present). When a provider sends nothing, the turn falls
  back to the existing chars÷4 estimate over the prompt actually sent
  and the text actually received, and the row is flagged estimated.
- **One row per turn in Room** — `usage_records` (session + provider +
  model + prompt/completion tokens + estimated flag + time), one row
  per successful model turn (Plan: one row; Build: one row per tool
  step, tagged with the provider + model that actually answered after
  any fallback switch). Failed turns write no row. Every total the
  dashboard shows is a SUM over stored rows — nothing lives only in
  memory. Room version is now **3**, with a real `Migration(2, 3)`
  that creates the table and its two indexes; no existing table is
  touched, so no user data moves or is lost.
- **Small bundled price table** — `data/ModelPrices.kt`, 18 entries:
  the top-pick flagships (Anthropic Sonnet 4.5 $3/$15, Haiku 4.5
  $1/$5, Opus 4.1 $15/$75; OpenAI GPT-5 $1.25/$10, GPT-5 mini
  $0.25/$2, GPT-4o $2.50/$10; Google Gemini 2.5 Flash $0.30/$2.50,
  2.5 Pro $1.25/$10; DeepSeek deepseek-chat $0.28/$0.42), the same
  models via OpenRouter at the direct list prices (OpenRouter passes
  them through with no per-token markup), and seven free routes at
  $0 (openrouter/free, TokenHarbor deepseek-v4-flash:free, three
  unorouter `:free` models, orcarouter/free, clinepass
  ernie-5.5-flash-preview:free). Every price was verified against a
  public pricing page on **2026-11-13** (sources in the file's KDoc);
  models whose price could not be verified — TokenHarbor's paid
  models, Groq, xAI, Mistral, Together, DeepSeek Reasoner — are left
  out and show "cost unknown" instead of an invented price. Rupees
  use a fixed approximate rate of ~₹94 per $1 (the 13 Nov 2026
  close). Every cost figure is shown as approximate ("~₹"), never as
  an exact bill.
- **Dashboard screen** — Home → "⋮" menu → "Usage & cost"
  (`usage` route): grand totals (prompt / completion / total tokens,
  turns, how many estimated), approx. total cost with the tokens
  whose cost is unknown called out separately (never silently counted
  as free), a per-model breakdown and a per-session breakdown (tokens
  + approx. cost or "cost unknown", "estimated" / "partly estimated"
  labels). Empty state until the first turn is recorded. The screen
  states the price date, the conversion rate, and that costs are
  estimates.
- **Verification fix found by the balance check (pre-existing,
  compile-blocking)** — Kotlin block comments nest, so a `/*`
  inside a KDoc comment opens a nested comment that its closing `*/`
  never closes: `AppViewModel.kt` (`getFilesDir()/skills/*.md`),
  `Skills.kt` (`assets/skills/*.md`, `getFilesDir()/skills/*.md`)
  and `RemoteServer.kt` (`/api/*`) each had one, leaving the rest of
  the file inside an unterminated comment — the project could not
  have compiled with those lines. All four spots are reworded to
  drop the `/*` sequence (wording only, no code changed). This is
  worth knowing for the first build: the nesting-aware balance check
  now passes all 44 Kotlin files.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: the Room 2→3 migration (first
  launch on an existing v2 database is the real test); the
  `stream_options` field (a provider that 400s on unknown fields
  would fail the turn through the normal error path instead of
  silently losing usage — if a provider does that, the flag needs a
  per-provider opt-out); `Icons.Filled.BarChart`
  (material-icons-extended, already a dependency); the aggregate
  `Flow<UsageTotals>` query. Honest limits: compaction summary calls
  are model calls too but are not recorded (only user turns are);
  DeepSeek's peak/off-peak rates mean its estimate uses the higher
  peak rate; OpenRouter's 5.5% credit-purchase fee is not in the
  per-token numbers, so real OpenRouter spend runs ~5.5% higher;
  Google reroutes gemini-2.5-pro/flash to Gemini 3.8/3.5 Flash-Lite
  from 1 Dec 2026 at those models' rates, so the 2.5 prices go stale
  then; a fallback switch mid-turn records usage under the model
  that answered, not the session's model.

## Phase 5, Step 3 — Session backup & export

- **Sessions only, in a versioned JSON file** — the backup holds Room
  sessions only: each session, its messages (role / text / tool JSON /
  time) and its compaction summary. It never holds API keys, provider
  credentials, MCP tokens, the remote password, the Git credential, the
  AWS credential or usage / cost records — the export screen's result
  message says so, and usage is re-derived from new turns after a
  restore. The file is versioned: top-level `formatVersion` (1) and
  `"app": "CodeSathi"`, so future format changes stay importable;
  unknown fields in a version-1 file are ignored.
- **Home → "⋮" menu → "Backup sessions" / "Restore sessions"** — both
  use the system file picker (SAF) only, no new permission, no path
  text field: `CreateDocument("application/json")` lets the user
  choose where the backup goes (suggested name
  `codesathi-backup-YYYYMMDD-HHmm.json`); `OpenDocument()` lets them
  pick a backup file to restore. Tap → picker → a plain result
  message on Home. No new navigation route was needed.
- **Import rules** — all-or-nothing per FILE for structural problems:
  malformed JSON, a missing / wrong `app` marker or a wrong
  `formatVersion` imports nothing, with a plain message. Per SESSION
  after that: a session whose id already exists is SKIPPED, never
  overwritten (the insert itself is also `OnConflictStrategy.ABORT`,
  a second layer); an invalid session (missing id / title / folder /
  provider / model, or an unknown mode) is skipped with its reason;
  the rest are imported with fresh message ids, and a write that
  still fails mid-session is cleaned up (messages + session deleted),
  so no half-imported session is left behind. The result is a plain
  summary, e.g. "3 imported, 1 skipped — “My project” — already
  exists." (first 5 reasons shown, then "and N more").
- **Folder grants do not travel with the file** — the backup stores
  the session's project-folder URI string, so an imported session
  still points at its folder, but a SAF persistable grant is per
  device and per install and is NOT in the file. On the same device
  and install the grant usually still holds and the session just
  works; on a new device, after reinstalling, or after the grant was
  revoked, the imported session shows "Folder unavailable" and the
  user re-chooses the folder from Home, exactly like any session
  whose folder was lost. The summary covers up to the last imported
  message (old message ids are gone — messages get fresh ids on
  import). Files over 10 MB are refused. Room is untouched: version
  stays 3, no migration, no schema change — only two new DAO methods
  (`SessionDao.insert` with ABORT, `deleteById`;
  `MessageDao.deleteForSession` for import cleanup). No new
  dependency (kotlinx-serialization was already in the project).
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: `Icons.Filled.Download` /
  `Upload` (material-icons-extended, already a dependency);
  `ActivityResultContracts.CreateDocument` / `OpenDocument` launch
  signatures; the `entry.summaryMarkdown` smart cast in the import;
  and the real SAF grant behaviour for an imported session on a
  second device (only a device run shows it — the message covers
  both outcomes).

## Phase 5, Step 4 — Session organisation

- **Search sessions by title** — a "Search sessions" field on Home,
  with a × to clear it. Typing filters the list live through a Room
  query (`title LIKE '%query%'`, case-insensitive for ASCII — SQLite
  LIKE); `%` and `_` typed in the box are escaped (`ESCAPE '\'`,
  escaped in the ViewModel) so they match those literal characters
  in a title instead of acting as wildcards. Clearing the field
  restores the full list. A search with no matches says
  "No sessions match “query”." instead of a blank screen with no
  explanation.
- **Pin / favourite sessions** — a pin button on every session row
  (tinted when pinned, grey when not). Pinned sessions sort first on
  Home (`pinned DESC, updatedAt DESC` — the unpinned order is exactly
  as before), stored in a new `pinned` column on `sessions`. Room
  version is now **4**, with a real `Migration(3, 4)`:
  `ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0`
  — existing sessions get 0 (not pinned), so no session moves or is
  lost and no other table is touched. Pinning survives a restart
  because it lives in Room, not in memory. Pinning does not touch
  `updatedAt`, so it never reorders by activity. The terminal, Git
  and editor screens keep their own "most recent session" fallback
  through a separate recency-only query (`observeSessionsByRecency`),
  so pinning an old session never hijacks which folder they open.
- **Rename** — a pencil button on every row opens a dialog with the
  current title pre-filled. An empty title is refused with a plain
  message ("Title cannot be empty — the old title stays.") and
  nothing is written. Renaming does not touch `updatedAt` ("Last
  activity" stays about the chat, not the rename).
- **Delete** — a trash button on every row asks "Delete this session?"
  first ("…and its messages will be deleted. This cannot be
  undone."). Confirming deletes the session and — in one Room
  transaction — its messages, its compaction summary and its usage
  rows, so the dashboard never shows orphan rows for a session that
  no longer exists. Deleting the open or last session never crashes:
  the active-session pointer and the last-session preference are
  cleared, the terminal / Git / editor fall back to the most recent
  remaining session (or "no session yet" when none is left), and a
  chat screen left open on the deleted session already shows
  "Session not found." with a Back button.
- **Backup** — the pinned state travels in the backup file (format
  version still 1; `pinned` defaults to false, so backups written
  before pinning existed still import) and a restored pinned session
  stays pinned.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine). First-build risks: the Room 3→4 migration (first
  launch on an existing v3 database is the real test);
  `Icons.Filled.PushPin` (material-icons-extended, already a
  dependency); the `flatMapLatest` search flow in the ViewModel; the
  `@Transaction` delete (same pattern as the existing
  `replaceForProvider` in `CachedModelDao`).

## Phase 6, Step 1 — Agent Run + auto-fix

- **The agent can now run commands** — a new `run` tool
  (`{"tool":"run","args":{"command":"npm test"}}`), listed in the Build
  system prompt next to read / glob / grep / write / edit / skill.
  It runs `sh -c <command>` in the session's project folder and returns
  the exit code plus the merged stdout + stderr as the tool result, so
  the model can see a failure and fix the code. No second shell
  executor was written: the tool calls the same `TerminalRunner.run`
  the terminal screen uses (same `sh -c`, same ProcessBuilder, same
  merged stream). The output cap is the same 2,000 lines as the
  terminal screen, now one shared constant (`TERMINAL_MAX_LINES` in
  `agent/Terminal.kt`, kept as the last lines — the error of a failing
  command is usually at the end).
- **Same allow / ask / deny rules** — `run` is gated by the exact
  pattern as every other tool: `AgentRunner` consults
  `toolRule("run")` before the call, "deny" refuses it before anything
  runs, and the rule is editable in the chat's Tool permissions dialog
  (Chat → ⋮ → "Tool permissions"), where `run` now appears alongside
  read / glob / grep / write / edit. Default rule is **"ask"**, so
  nothing runs until the user has approved it at least once. The rule
  is an explicit entry in `DEFAULT_TOOL_RULES` (not just the
  `?: "ask"` fallback in `toolRule()`).
- **Auto-fix loop, bounded at 3** — after a Build turn, a `run` that
  exits non-zero consumes one fix attempt for that turn
  (`AgentRunner.MAX_FIX_ATTEMPTS = 3`). The tool result fed back to
  the model says which attempt it was ("Fix attempt 1 of 3 …"), and
  after the third non-zero run, further `run` calls in the same turn
  are refused WITHOUT executing, with a message telling the model to
  summarise the failure instead of running again. The bound is
  enforced in code, not just in the prompt, so the loop can never run
  unbounded. A successful run (exit 0) consumes nothing, and a run
  that never executed (denied, or refused for an unresolved folder)
  consumes nothing either. The budget resets on the next user message
  (a post-approval resume is a new `runBuild` call, so it gets a fresh
  budget of 3). The count uses a typed exit code
  (`ToolResult.Ok.exitCode`, set only by the `run` tool) — never
  string-parsing of the result text.
- **Plan mode** — the `run` tool stays available in Plan mode (running
  a command only reads its output), but write-like commands are refused
  in Plan mode, exactly like the write / edit tools refuse:
  `planModeRunRefusal` blocks a write command (`rm`, `mv`, `cp`,
  `touch`, `mkdir`, `tee`, …), `sed -i`, and any unquoted `>`
  redirection, splitting the command into segments outside quotes so
  `ls; rm -rf out` is caught and `grep "a>b" file` is not falsely
  refused. Honest limit: that screen is a heuristic, not a sandbox —
  a command can still write through an interpreter (`python3 -c …`,
  `node -e …`) or a tool the list does not name, so Plan mode's hard
  guarantee stays what it always was (write / edit refuse, and no file
  proposal is ever applied without Approve). Plan turns never call
  tools today, so this is a guard for any future plan-tool path.
- **Unresolved folder refuses, never falls back** — the folder is
  resolved with the same `resolveTerminalFolder` as the terminal
  screen, but unlike the terminal screen the agent NEVER runs in the
  app-private fallback folder: the model must never believe it ran a
  command in the project folder when it actually ran somewhere else.
  An unresolved folder returns the plain reason instead.
- **Stop kills the command** — the chat Stop button now also kills the
  running shell process (`AppViewModel.stopRun` → `RunTool.stop()` →
  `TerminalRunner.stop()`), because cancelling the job alone would
  leave a quiet command blocked in its output read. `TerminalRunner`
  also watches its own job now (throws `CancellationException` when a
  line arrives after cancellation, and polls instead of blocking in
  `waitFor()`), so a stopped command is killed in `finally` either way.
- **Honest limit — "Permission denied"** — a SAF folder-picker grant
  gives the child shell NO raw-filesystem access, so file-touching
  commands in the project folder may print "Permission denied". That
  stderr is returned plainly in the tool result (never hidden),
  exactly like the terminal screen shows it, and when it appears the
  result adds a plain note explaining why (SAF grant ≠ raw access)
  and what fixes it (Home → Storage access, or the read / write tools,
  which work through the folder grant).
- **Remote turns** — a remote turn reuses the same `runAgentTurn`, so
  the `run` tool is available remotely too, under the same rule. That
  means a remote caller can run shell commands on the phone whenever
  the `run` rule is not "deny" — the same exposure the read / write
  tools already had, but a shell is more powerful, so set `run` to
  "deny" if remote mode is ever on with a password shared beyond
  trust. No new dependency, no version changes, no Room change.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine), no live agent run has called `run` yet. First-build
  risks: the `v1Tools(project, context)` signature change (both call
  sites updated); `currentCoroutineContext()[Job]` + `delay` in
  `TerminalRunner.run`; the `ToolResult.Ok` second (defaulted)
  parameter; the `filterIsInstance<RunTool>()` lookup in
  `runAgentTurn`. Untested live: a real failing command triggering
  the fix loop, the 3-attempt refusal, Stop mid-command, and a
  "Permission denied" result from a real project folder.

## Phase 6, Step 2 — Image input (vision)

- **Attach up to 3 images to a message** — an image button in the chat
  input row opens the system picker (SAF `GetMultipleContents`,
  `image/*`). Picked images show as thumbnails above the input (× to
  remove one) and, once sent, as thumbnails in the user message bubble
  in the history.
- **Downscaled + JPEG before anything is sent** — a raw phone photo is
  several MB, far too large to base64 into a request. `ImageStore`
  (new `agent/ImageAttachments.kt`, no new dependency — `BitmapFactory`
  / `Bitmap` + JPEG compression only) decodes each pick subsampled,
  scales it so its longest side is at most **1568 px**, and re-encodes
  it as JPEG quality **80** before storing or sending it. The stored
  file is what the model gets, so the thumbnail shows what the model
  sees. Any format the decoder understands (JPEG, PNG, WebP, GIF, HEIC
  on newer devices) becomes JPEG on disk. An unreadable image, and any
  pick past the 3-per-message cap, is skipped with a plain note.
- **App-private storage, never a blob, never exported** — images live
  as files in `getFilesDir()/images/img_<uuid>.jpg`; Room stores only
  the file names (`Message.imagesJson`, a JSON array). Room version is
  now **5**, with a real `Migration(4, 5)` (`ALTER TABLE messages ADD
  COLUMN imagesJson TEXT` — existing messages simply have no images).
  Session backup / export never carries images (`BackupMessage` has no
  images field): a restored message keeps its text but not its images.
  Deleting a session deletes its image files too; removing a staged
  image deletes its file. A staged image the user never sends stays
  app-private until it is sent or removed.
- **Each provider's own vision shape** — `LlmClient` sends the images
  in the shape that provider documents: OpenAI `image_url` with a
  base64 data URL (also Azure, Google's OpenAI-compatible endpoint and
  other OpenAI-kind providers), Anthropic image content blocks
  (`{"type":"image","source":{"type":"base64","media_type":"image/jpeg",
  "data":"…"}}`, images before the text), Bedrock Converse image
  blocks (`{"image":{"format":"jpeg","source":{"bytes":"…"}}}` — the
  format fits the existing Converse body, no new request shape). A
  message with no images sends exactly the plain-string content it
  always did, so text-only turns are byte-for-byte unchanged.
- **Verified vision list — everything else refuses** — only
  (provider, model) pairs verified against the provider's own docs
  (checked 2026-11-13, see `data/VisionSupport.kt`) may receive
  images: OpenAI GPT-5 / GPT-4o / GPT-4.1 / GPT-4.5 / GPT-4 Turbo /
  o-series; Anthropic Claude (all `claude-*`); Google Gemini
  (`gemini-*`, via the OpenAI-compatible endpoint — Google's docs show
  `image_url` with a base64 data URL for image understanding); Azure
  OpenAI GPT-4o / GPT-5 (same families, deployment name = model id);
  Bedrock `anthropic.claude*` (Claude 3.x/4.x support Converse images;
  Titan / Llama / Mistral do not). Of the catalog's 260 bundled models,
  11 are verified vision models. Every other provider — OpenRouter,
  Groq, DeepSeek, xAI, Mistral, Together, Ollama, LM Studio,
  TokenHarbor and the rest — is treated as unsupported, even when it
  routes to a model that can see images: vision was not verified
  against that provider's docs, so the app refuses plainly instead of
  guessing. The picker warns before sending ("This model cannot see
  images…"), `sendMessage` refuses before anything is stored or sent
  (the text stays in the input field), and the agent runner filters
  fallback attempts the same way — a fallback switch to a text-only
  model is refused instead of sending a broken request.
- **History images are re-sent** — an old message's images are resolved
  fresh each turn (`imagesFor`), so the model re-sees a screenshot when
  the history is re-sent, exactly like it re-reads the old text. A
  missing image file (deleted, or a message restored from a backup) is
  skipped silently — the text still goes out. Plan mode sees the
  images too (the plan turn carries them). Remote turns have no image
  input and are unaffected.
- **Cost** — images count as tokens: when the provider reports usage,
  its counts already include them; when it does not, the estimate adds
  ~1,500 tokens per image (a 1568 px image is roughly 1,300–2,000
  vision tokens depending on the provider). Re-sending history images
  each turn costs tokens each turn, like any chat API.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine), no live vision call with a real key. First-build
  risks: `ActivityResultContracts.GetMultipleContents` (activity 1.8.2
  — present since 1.2, but untested here); `Icons.Filled.AddPhotoAlternate`
  / `Close` (material-icons-extended, already a dependency);
  `produceState` + `asImageBitmap` for thumbnails; the Room 4→5
  migration on first launch after update; `BitmapFactory` decode of a
  real phone photo (HEIC on some devices, very large panoramas).
  Verification fix found by the balance check (real, compile-blocking):
  the KDoc in `ImageAttachments.kt` and in `AppViewModel.kt` each
  contained the text `` image/* ``, which Kotlin reads as a nested
  block comment — the rest of each file was inside an unterminated
  comment (same bug class as Phase 5, Step 2). Both are reworded.

## Phase 6, Step 3 — Project templates

- **Four templates on the New Session screen** — Blank (empty folder,
  exactly as before, and the default), Portfolio website (a single
  `index.html`), Python script (a small `main.py` starter), Markdown
  notes (a `NOTES.md` starter). The picker sits on the New Session
  screen **after the folder button and before Create**: the folder
  comes first (the template needs a folder to land in), and the
  choice is recorded in `AppViewModel.selectedTemplateId` until Create
  consumes it once and resets it — a template picked and then
  abandoned never leaks into a later session, and a session created
  another way (the editor's "new session from a subfolder") always
  gets Blank, because that path passes no template.
- **Templates are small text files in assets** — new
  `agent/Templates.kt` (registry + asset loader, 107 lines) lists the
  four templates with their file lists, and the content ships under
  `assets/templates/<id>/` (`portfolio/index.html`,
  `python-script/main.py`, `notes/NOTES.md`), all written fresh for
  this app. The portfolio is a genuine single-page site, not a stub:
  sticky nav, hero, about, skills chips, three project cards, contact,
  self-contained CSS in `:root` variables, one tiny script for the
  footer year — no external files, so it also works in the editor's
  live preview. No new dependency, no Room change, no new route.
- **Nothing is written before Approve — the normal file-creation
  path** — choosing a template only queues its files as `PlannedFile`
  proposals, one at a time, through the same diff card (`DiffCard`)
  and the same `AgentRunner.applyFileProposal` the agent's writes and
  the editor's saves use. `createSession` reads the template from
  assets off the main thread and queues the first file; approving it
  queues the next file (still a proposal, never a direct write) until
  the template is done. Template approvals never resume the agent
  loop — there is no turn to resume — and the session gets a tool
  message saying what was chosen and that nothing is written until
  Approve is tapped. Rejecting a template file ends the template
  (the rest is skipped, never proposed on its own); a failed write
  clears the rest of the queue too.
- **Plan mode is not bypassed** — a new session starts in Plan mode,
  and the agent still cannot write there. A template proposal is
  user-initiated (the user chose the template, then taps Approve on
  the diff card), like an editor save — the Approve tap is the
  approval, and the file only reaches the disk through it. The
  template never writes straight to disk, and no template file is
  ever created by the agent on its own.
- **Scoped to its session** — a template proposal belongs to the
  session it was created for (`pendingTemplateSessionId`); the Chat
  and Editor screens only show the diff card when it belongs to the
  session on screen (`shouldShowProposalFor`). A template chosen for
  session A never offers its file in session B's chat. Agent and
  editor proposals carry no session id and show wherever the card is,
  as before.
- **An existing file is never silently overwritten** — when the
  template's file already exists in the folder, it is proposed as an
  "edit" carrying the current text as `oldSnippet` (same pattern as
  `proposeEditorSave`), so the diff card shows old vs new and the
  write only replaces when the disk still holds that old text. An
  empty file counts as missing, because an "edit" with an empty old
  snippet would corrupt the file. The session's tool message says
  when a file already exists.
- **Edge cases, all with a plain message in the session** — a template
  file that cannot be read from assets prepares no proposal; a
  template chosen while another change already waits for approval is
  not prepared at all (one approval at a time); if the agent proposes
  a change while a template file is waiting, the agent's proposal
  takes the single slot and the template file is set aside, never
  written; deleting the template's session drops its proposal and
  the rest of its queue.
- **How to add a new template** — three touches, nothing else:
  1. Put its files under `app/src/main/assets/templates/<new-id>/`
     (one subfolder per template, named after the id).
  2. Add one `ProjectTemplate(id = "<new-id>", name = …, description
     = …, files = listOf(TemplateFile("<path>", "templates/<new-id>/<file>")))`
     entry to `BUILT_IN_TEMPLATES` in `agent/Templates.kt`.
  3. Nothing else: the picker reads the list, `createSession`
     loads it, and the diff flow proposes it. No Room migration, no
     new route, no ViewModel change. Keep template files small plain
     text, and never put a secret in one — there is nothing to put a
     key in here.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine), no live template session created yet.
  First-build risks: `RadioButton` (Material3, already a dependency)
  on the picker cards; the `takeIf { shouldShowProposalFor(…) }`
  guard on both diff cards; the template proposal appearing in the
  chat right after Create (it is queued in the same coroutine as the
  session insert, so a very fast tap on the chat could briefly show
  no card — it appears as soon as the insert finishes).

## Phase 6, Step 4 — Multi-device sync

- **Sync screen** — Home → "⋮" menu → "Sync devices" (`sync` route).
  The user types the other device's IP address (shown on that device's
  Remote screen) and port (blank = 8080), and the other device's Remote
  password. Two checkboxes — Push my sessions / Pull the other device's
  sessions, both on by default — and one "Sync now" button. A pasted
  Remote-screen URL (`http://192.168.1.5:8080`) is cleaned up
  automatically (scheme, path and `:port` stripped); IPv6 is refused
  with a plain message. The password field is prefilled with this
  device's Remote password (most users set the same one on both
  devices) and is masked; change it when the other device uses a
  different one.
- **Built on Remote mode + the backup format — no second format, no
  second password** — sync talks to the other device's remote server
  (same port 8080, same Bearer password auth, same constant-time
  check). Two new server endpoints: `GET /api/sync/export` returns
  every session as the exact backup JSON the file export writes
  (`formatVersion` 1, via the shared `buildBackupText()`), and
  `POST /api/sync/import` takes that JSON and merges it, answering
  `{added, updated, skipped, reasons}`. The client is a plain
  `HttpURLConnection` caller (`remote/SyncClient.kt`, no new
  dependency): 15 s connect / 60 s read timeouts, the password only
  in an Authorization header, never in a URL or a log, and a 10 MB
  reply cap (same cap as a file restore). The other device must have
  Remote mode ON; this device does not need its own server on to
  sync, only to be synced FROM.
- **Merge rule: newest `updatedAt` wins** — per session. A session the
  device does not have is added; an incoming copy newer than the local
  one replaces it wholesale (session row + messages + summary, in one
  Room transaction, so a failed write rolls back instead of leaving a
  half-replaced session); an equal or older copy is skipped as
  "already up to date". Invalid sessions and in-file duplicates are
  skipped with their reason; the rest still merge. Pull runs first,
  then Push, so one "both ways" round converges both devices on the
  newest copy of each session, and a repeat sync right after is a
  no-op (everything skipped). The result is a plain summary: "Pulled
  3 sessions (2 new, 1 updated), 1 skipped. Pushed 5 sessions
  (5 new, 0 updated)." with the first 3 non-routine skip reasons.
- **Sessions only, exactly like a backup** — no API keys, provider
  credentials, MCP tokens, remote/Git/AWS credentials or usage/cost
  records ever leave the device, in either direction. The screen and
  the result say so. The export endpoint serves the same
  `encodeBackup` output as a file backup, so nothing new can leak
  through sync that backup does not already exclude.
- **Honest limits** — no conflict screen in v1: when the same session
  changed on both devices, the older change is replaced by the newer
  one, silently (the screen says so before syncing). Both devices must
  be on the same Wi-Fi, and both must run the same app version's
  format (formatVersion 1 — a wrong version is refused whole, like a
  bad backup file). An imported session keeps its folder URI string,
  but the SAF persistable grant does NOT travel: on the other device
  the session shows "Folder unavailable" until the folder is re-chosen
  from Home (same note as a file restore). A sync refuses to start
  while a turn is running or a file change waits for approval, so a
  merge can never land mid-turn. A backup over 10 MB is refused on
  both ends (the server also refuses request bodies over 10 MB before
  reading them). Sync failures are plain messages: wrong password,
  no password set on the other device, unreachable device, or a
  mid-sync break each get their own wording.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine), no live two-device sync tested. First-build risks:
  `Icons.Filled.Sync` (material-icons-extended, already a dependency);
  `Checkbox` in the Sync screen; `androidx.room.withTransaction`
  (room-ktx 2.7.0, already a dependency); the `readCapped` loop and
  the `errorStream` read in `SyncClient`; and the real two-device
  behaviour (same-Wi-Fi reachability, the other device's server
  staying up during a big push).

## Phase 6, Step 5 — On-device local models (.task)

- **Verified first, then built** — MediaPipe LLM Inference was checked
  against official sources BEFORE any code was written (2026-11-13):
  the artifact `com.google.mediapipe:tasks-genai:0.10.27` exists on
  Google Maven (its POM is there — aar, Apache 2.0) and is the exact
  version Google's own Android quickstart pins
  (developers.google.com/edge/mediapipe/solutions/genai/llm_inference/
  android). The metadata lists newer versions (up to 0.10.35), but the
  docs pin 0.10.27, so the docs version is used. The API shape in code
  is the one those docs show: `LlmInference.LlmInferenceOptions`
  (`.setModelPath(path).setMaxTopK(64)`), `LlmInference.createFromOptions
  (context, options)`, `generateResponse(prompt)` for one whole reply,
  and `generateResponseAsync(prompt)` for streaming with
  `.setResultListener { partialResult, done -> }`; maxTokens is input +
  output tokens (default 512). The `.task` model format and the
  Gemma-3 1B 4-bit quickstart model come from the same docs. The
  session async shape (`LlmInferenceSession.generateResponseAsync`)
  appears only in third-party tutorials, so it is NOT used — streaming
  goes through the top-level `generateResponseAsync`, and multi-turn
  context is a plain-text prompt, not a session.
- **The user picks a `.task` file — the app never bundles a model** —
  Providers → "On-device model (.task)" (new catalog provider
  `ondevice`, kind `local`, no server URL, no API key). The system file
  picker (SAF `OpenDocument`) returns the file; only `.task` files are
  accepted (a GGUF or safetensors file is refused with a plain
  message). The URI + a persistable READ grant are stored in DataStore
  (same pattern as the folder picker), and the file's display name
  becomes the model id — picking a file makes it the default model
  (and the open session's model). The chosen file shows as a model row
  (star / fallback / Use) like any other model; the key UI is hidden
  for it, exactly like the other local providers, and Refresh is
  replaced by a plain message (there is no server list to fetch).
- **The engine needs a real file, so the file is copied once** —
  `setModelPath` takes a filesystem path, and a SAF URI is not one.
  On first use the picked file is copied into app-private storage
  (`getFilesDir()/local-models/`, sanitised file name) and the cached
  copy is what loads; the URI stays the source of truth, so a deleted
  cache is re-copied from the URI. The copy doubles the disk the model
  uses (file + cached copy) — the screen says so, and removing the
  model (or picking a different one) deletes the cached copy again.
  Loading is slow (copy a hundreds-of-MB file, then the native engine
  loads it) and happens once — the engine is cached across turns until
  the model changes or the app process dies. First load is the slow
  one; later turns reuse the loaded model.
- **Known-compatible models, download size warning** — Google's docs
  name Gemma-3 1B in 4-bit quantized format (from Hugging Face) as the
  quickstart model and Gemma-3n E2B / E4B for multimodal use. Gemma-3
  1B 4-bit (`gemma-3-1b-it-int4.task`) is about **550 MB** — check the
  download size before starting, and have free space about twice the
  file size (the file plus the cached copy). The screen says both.
  Other MediaPipe `.task` bundles may work; anything that is not a
  `.task` file will not load. Google lists this API as
  **maintenance-only** and recommends LiteRT-LM for new projects — a
  LiteRT-LM migration is future work, not done here.
- **Streaming is real; token counts are estimated** — the engine
  streams through `generateResponseAsync` + the result listener, so
  replies appear token by token like a remote provider. The engine
  reports no token usage, so no Usage event is emitted and the runner
  falls back to the chars÷4 estimate, flagged estimated — the
  dashboard shows local turns as estimated, and "cost unknown" (local
  turns are free; no price-table entry exists for a local model). The
  reasoning-effort picker does nothing for local models (the engine
  takes no effort field), so no effort chips or label are shown for
  them. Images are refused for local models (text-only; Gemma-3n
  multimodal is not wired) — the picker warns and `sendMessage`
  refuses, exactly like any other text-only model.
- **Small model, small context** — the engine is created with maxTokens
  **4,096** (input + output tokens). The prompt is capped at 10,000
  chars (~2,500 tokens) by dropping the oldest history turns first and
  always keeping the latest user message, so ~1,500 tokens stay for the
  reply. Compaction is measured against the same 4,096 (not the 128k
  remote default), so a long local session compacts early instead of
  overflowing the engine; the model summary call runs on the local
  engine too, or falls back to the offline summary when no file is
  picked. Tool calls come back as fenced ```tool blocks in the text
  (the engine sends no native `tool_calls`), which the runner already
  parses — a small 1B model will be much less reliable at exact tool
  JSON than a frontier model, so expect more failed / malformed tool
  calls. Fallback treats it like any other provider: it is a keyless
  attempt (like the other local providers), and a fallback chain can
  include it or a remote model after it.
- **Not yet verified live** — no on-device run here (no Gradle build
  on this machine), no live local-model run (no .task file exists in
  this VM). First-build risks, in order: the MediaPipe AAR's minSdk
  (if it is above 24, manifest merge fails — untested); the
  `setResultListener` / `setErrorListener` SAM lambdas on
  `LlmInferenceOptions` (the documented shapes, but uncompiled here);
  `callbackFlow` + `awaitClose` around the async listener; the native
  `.so` libraries the AAR brings (packaging); the result listener's
  partials being deltas, not cumulative text (two independent
  write-ups append them, and the code assumes deltas — if a first
  on-device reply repeats itself, the partials are cumulative and the
  listener must diff against the previous partial); a second
  `generateResponseAsync` while one is still running after Stop +
  immediate resend (MediaPipe's behaviour there is unverified — the
  new call may fail with an error, which surfaces plainly); first load
  time and RAM on a real mid-range phone (Gemma-3 1B 4-bit needs
  roughly 1–2 GB free memory; Google says the API is optimised for
  high-end devices such as Pixel 8 / S23 or later and does not
  reliably support emulators — so the first real test should be on a
  real phone, not an emulator).

## Phase 7, Step A1 — v1.2 build fixes (ported 2026-11-14)

Ported from the user's own CodeSathi v1.2 (`b5d6003`, his
`inyogeshwar/CodeSathi` repo) into this repo — build fixes only, no
UX changes, no version bump, no workflow file, no catalog change.

- **LocalLlm listener bug (the real bug)** — `agent/LocalLlm.kt`
  put `.setResultListener` / `.setErrorListener` on the
  `LlmInferenceOptions` builder, but in tasks-genai 0.10.27 the
  builder has no such methods, so local-model streaming could not
  compile / work as written. The progress listener now goes on
  `generateResponseAsync(prompt) { partialResult, done -> }` as a
  parameter in `streamEvents`, with errors surfaced through the
  returned `ListenableFuture` (`future.get()` →
  `ExecutionException` → `onError`); the builder now sets only
  model path, max tokens and max top-K. Both KDoc passages that
  described the old (wrong) API shape are corrected. Prompt-capping,
  engine caching and the .task file-copy logic are untouched.
- **Missing kotlinx-serialization imports** — added only the
  missing imports, no logic changed: `contentOrNull` in
  `agent/LlmClient.kt` and `agent/McpClient.kt`;
  `encodeToString` + `decodeFromString` in `data/KeyStore.kt`,
  `data/PreferencesStore.kt` and `remote/RemoteServer.kt`;
  `contentOrNull` + `decodeFromString` in `data/Providers.kt`.
  Each was genuinely used by the file already (verified by grep).
- **ServiceInfo import** — `remote/RemoteService.kt` imported
  `android.app.ServiceInfo`, which does not exist; now imports
  `android.content.pm.ServiceInfo` (used for
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC`).
- **@JvmName clash** — `ui/AppViewModel.kt`: the `sessionSearchQuery`
  property's generated setter clashed with the
  `setSessionSearchQuery(query)` function; the property setter is
  now `@JvmName("setSessionSearchQueryState")`.
- **KSP 2.3.13** — root `build.gradle.kts`:
  `com.google.devtools.ksp` `2.2.10-2.0.2` → `2.3.13`. The artifact
  EXISTS on Maven Central (verified 2026-11-14:
  `https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing/2.3.13/symbol-processing-2.3.13.pom`
  returns HTTP 200 with version 2.3.13, published 2026-10-31).
  **UNTESTED against this project's bundled Kotlin 2.2.10** — KSP
  2.3.x normally pairs with Kotlin 2.3.x, not 2.2.10, so the first
  Gradle sync is the verification step; if sync complains about the
  KSP/Kotlin pairing, revert to `2.2.10-2.0.2` (recorded in the
  build file's comment). AGP, Gradle, Kotlin, minSdk, targetSdk and
  the package name are unchanged.
- **Not ported from v1.2 here** — `AppViewModel`'s `onboardingChecked`
  flag and the `isRunning`/`runStartedAt` rework are later v1.2 UX
  changes (the `d132f8f` commit), not build fixes, so they stay out
  of this step.
- **Not yet verified live** — no Gradle build has been run here (no
  Android SDK on this machine). First-build risks: the KSP 2.3.13 /
  Kotlin 2.2.10 pairing above; the `generateResponseAsync` listener
  + `ListenableFuture` shape in `LocalLlm` (only verifiable by a
  real local-model run on a phone).

## Phase 7, Step A2 — Gradle wrapper 9.6.0 + .gitignore (ported 2026-11-14)

Ported from the user's own CodeSathi v1.2 (`53edc7c`, his
`inyogeshwar/CodeSathi` repo) — wrapper + .gitignore only, no source,
no version bump, no workflow file.

- **What was added** — the standard Gradle wrapper files, copied
  byte-for-byte from v1.2: `gradle/wrapper/gradle-wrapper.jar`
  (48,462 bytes, the standard Gradle distribution file),
  `gradle/wrapper/gradle-wrapper.properties`,
  `gradlew` (8,656 bytes, the standard Gradle-generated POSIX
  script, executable), `gradlew.bat` (2,848 bytes), and a new
  root `.gitignore` (87 bytes).
- **Wrapper version** — `gradle-wrapper.properties` points at
  `https://services.gradle.org/distributions/gradle-9.6.0-bin.zip`
  (Gradle 9.6.0), matching the version already recorded in the
  "Latest setup" section above. No AGP / Kotlin / SDK version
  changed.
- **What the target already had** — this repo was NOT wrapper-less:
  it already held a 272-byte stub `gradlew` (it only ran
  `exec gradle "$@"`, i.e. it needed a system Gradle on PATH, and
  its comment still mentioned Gradle 8.2) and a
  `gradle-wrapper.properties` identical to v1.2's except for two
  missing lines (`retries=0`, `retryBackOffMs=500`); there was no
  `gradle-wrapper.jar`, no `gradlew.bat` and no `.gitignore`.
  The stub `gradlew` and the properties file were replaced with
  v1.2's standard files; the jar, bat and .gitignore are new.
- **`.gitignore`** — covers `build/`, `.gradle/`, `.kotlin/`,
  `local.properties`, `*.apk`, `*.aab`, `.idea/`, `*.iml`,
  `.DS_Store`, `captures/`. It ignores no source files.
- **Not yet verified live** — Gradle was NOT run here (no Android
  SDK on this machine), so `./gradlew` has never executed in this
  project. First build on a real machine is the verification step:
  it downloads Gradle 9.6.0 on first run (needs network), then the
  Android SDK pieces if missing. First-build risks: the KSP 2.3.13
  / Kotlin 2.2.10 pairing from Step A1 (first sync decides it);
  `gradlew` line endings / executable bit after a Git checkout on
  Windows (the `.bat` covers Windows, but `git` must preserve the
  `+x` bit on `gradlew`).

## Phase 7, Step A3 — release signing config (ported 2026-11-14)

Ported from the user's own CodeSathi v1.2 (`687b655`, his
`inyogeshwar/CodeSathi` repo) — the `signingConfigs` block in
`app/build.gradle.kts` only. No source file, no version bump
(versionCode 1 / versionName "1.0" untouched — bumped to 3 / "1.2" in
Step A6), no KSP / AGP / Gradle / Kotlin / SDK change, no workflow file.

- **What was added** — in `app/build.gradle.kts`, after
  `defaultConfig`: a `signingConfigs` block that creates a
  `release` signing config ONLY when the env var `KEYSTORE_FILE`
  points at an existing file; plus one line in the `release`
  build type, `signingConfig = signingConfigs.findByName("release")
  ?: signingConfigs.getByName("debug")`. Every other line of the
  file is unchanged (tasks-genai dependency, NIO desugaring, Room /
  KSP / JGit setup, packaging excludes).
- **Env vars (actual names from v1.2 — note there is NO
  `KEYSTORE_BASE64`)** — `KEYSTORE_FILE` (path to the `.jks`
  file), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. All
  four are read with `System.getenv(...)` only.
- **Debug-key fallback** — when `KEYSTORE_FILE` is unset or the
  file does not exist, no `release` signing config is created and
  the release build is signed with the debug key, so a local build
  without the secrets still succeeds instead of failing.
- **No secret anywhere** — no keystore file (`.jks` / `.keystore` /
  `.pem`), no password and no secret value was added to the repo;
  the config reads env vars only. The `.jks` file itself is never
  in this repo; whoever runs a signed release build supplies its
  path via `KEYSTORE_FILE`.
- **Workflow EXCLUDED** — the `.github/workflows/` Actions file
  from v1.2 was deliberately NOT ported, under any name or in any
  location, and no CI / workflow file was created. The user copies
  that one file himself if he wants it.
- **Not yet verified live** — Gradle was NOT run here (no Android
  SDK on this machine), so the signing config is only proven by a
  real release build with the env vars set. First-build risks:
  `KEYSTORE_FILE` pointing at a missing file silently falls back
  to the debug key (a release APK signed with the debug key looks
  built but is not releasable — check the signing identity before
  publishing); a wrong password / alias fails the build at signing
  time; the KSP 2.3.13 / Kotlin 2.2.10 pairing from Step A1 (first
  sync decides it).

## Phase 7, Step A6 — unit tests + version bump to vc3 / 1.2 (ported 2026-11-14)

Ported from the user's own CodeSathi v1.2 (`d132f8f`, his
`inyogeshwar/CodeSathi` repo) — the test + version pieces only. This
completes Part A (the v1.2 port).

- **Version bump** — `app/build.gradle.kts`: `versionCode = 3`,
  `versionName = "1.2"`. The header and the SDK line of this README
  now say v1.2 / version 1.2 (both still said 1.0 — v1.2's own README
  left them stale). No KSP / AGP / Gradle / Kotlin / SDK change, no
  workflow file.
- **Unit tests** — two plain JVM tests copied byte-for-byte from
  v1.2 (no Android framework in them, so they run with `./gradlew
  test`, no device needed): `ui/MarkdownParseTest.kt` (fenced code
  block keeps its language and code; paragraphs around it) and
  `ui/screens/UiFormatTest.kt` (`formatElapsed` seconds / padded
  minutes, `relativeTime` buckets incl. a future timestamp showing
  "just now"). Both test helpers already existed in this project
  with the same names and signatures (`parseMarkdown` /
  `MdBlock.Code` in `ui/Markdown.kt`, byte-identical to v1.2's;
  `formatElapsed` in `ui/screens/ChatScreen.kt`,
  `relativeTime` in `ui/screens/SessionComponents.kt` — same
  package as the tests, `internal` so the same-module tests can
  reach them). `testImplementation("junit:junit:4.13.2")` added
  to `app/build.gradle.kts` (verified to exist on Maven Central:
  POM, HTTP 200, and the latest/release in the metadata).
- **Not yet verified live** — Gradle was NOT run here (no Android
  SDK on this machine), so the tests have never executed. First
  `./gradlew test` on a real machine is the verification step.
  First-build risks: the KSP 2.3.13 / Kotlin 2.2.10 pairing from
  Step A1 (first sync decides it).

## Phase 7, Step B1 — AgentMT routing port (RouteRunner + ModelRouter, adapted 2026-11-14)

AgentMT's routing logic, reworked to fit CodeSathi — our package,
our HttpURLConnection + SSE client (not AgentMT's OkHttp types), and
our existing fallback design. Nothing of AgentMT's providers.json,
skills, GPL binaries, Termux JNI or Linux orchestration was touched.

- **FailureClassifier (new `agent/Routing.kt`)** — every failed
  attempt is classified from OUR `LlmException` (`httpCode` / `body` /
  `retryAfterMs`) or from the thrown `IOException`: 429 rate limit vs
  daily quota (a 429 whose body says quota / per day /
  free-models-per-day / exceeded your current quota) vs 402 / out of
  credit vs 401/403 key rejected vs 413 / context-too-long vs 408 /
  timeout vs 5xx server vs 404 / capability mismatch (a 400 about
  images, tools, or "does not support") vs other 4xx vs network.
  Keyword lists are AgentMT's, checked against the error bodies our
  providers actually return (OpenAI/Anthropic-style `{"error":
  {"message": …}}`, Bedrock throttling as plain 429).
- **Per-kind cooldowns REPLACE midnight** — a rate-limited key
  retries after 60 s, a daily-quota key after 1 h, an out-of-credit
  key after 6 h, a server error after 20 s, a network failure after
  15 s (never more than 24 h). The old rule ("exhausted until next
  local midnight") is gone: the legacy `markExhausted(attempt, kind)`
  overload is kept only for the router-less path. The user's 1st /
  2nd / 3rd fallback-chain order and multi-key behaviour are
  unchanged — cooldowns only change WHEN a key becomes eligible
  again, never the order. A 401/403 key carries no cooldown: it is
  marked invalid in the health ledger and stays skipped until reset
  (a rejected key will not start working on its own).
- **Retry-After headers** — `LlmClient` now reads `Retry-After` /
  `X-RateLimit-Reset` off the `HttpURLConnection` on error replies
  (main path and Bedrock) and carries the wait on `LlmException`
  (`retryAfterMs`); a rate limit then cools down for exactly the
  asked-for wait (seconds, HTTP date, or epoch s/ms) instead of the
  default 60 s.
- **Bounded attempts + deadline** — at most **4 attempts per model
  turn** and a **180 s pre-first-token deadline** (AgentMT's defaults;
  `RouteBounds.MAX_ROUTE_ATTEMPTS` / `PRE_FIRST_TOKEN_DEADLINE_MS`),
  in Plan and in every Build step. Server/timeout failures get a
  short backoff before the next candidate (400 ms, doubling to 4 s).
  A turn that fails with a retryable failure (limit, auth, server,
  network, context, capability, model-missing 404) now tries the
  next candidate — previously only limit errors retried. A
  non-retryable failure (other 4xx, anything else) still fails the
  turn at once.
- **Mid-stream failure** — if a stream fails after text or tool
  calls were already emitted, the turn NEVER retries on another
  model: the partial reply stays exactly as shown and the chat says
  "The reply stopped halfway (reason) — the part above is kept. Send
  a message to continue." (`MidStreamException`; the user continues
  with a new message, which re-sends the history including the
  partial reply).
- **Routing profiles (new `agent/ModelRouter.kt`)** — PINNED (stay on
  the session's model, rotate only its keys) vs FALLBACK (session
  model, then the fallback chain — the Phase 2, Step 1 order). The
  mode is a DataStore setting, switched by two chips on the
  Providers screen, read fresh per turn. Key rotation is the next
  healthy key of the same provider + model (`nextHealthyKey`).
- **Health ledger — DataStore JSON, NOT Room** — one `KeyHealth`
  row per "providerId|modelId|keyId" (failures, status, cooldownUntil,
  token counts, latency), in `key_health_json` next to the
  exhaustion flags it is read/written with. Chosen over a Room
  entity + v5 → v6 migration because the ledger is a small map keyed
  by the exact string the exhaustion flags already use, needs no
  query/index/migration, and Room stays at version 5. No secret is
  stored in it (the key is a hash prefix, never the key value).
  Success clears the key's cooldown at once and records tokens +
  latency; failure records the status (`rate_limited` / `no_credit`
  / `invalid` / `error`) and the error label.
- **Providers screen** — the fallback section explains the cooldowns
  (no more "resets at midnight"), shows each blocked entry's reason
  + when it retries ("rate limited — retries in ~1 min"), shows each
  key's worst health state under its masked value, and has a
  one-tap **Reset limit flags** button (plain confirm dialog) that
  lifts every cooldown and invalid flag. Per-key reset exists too
  (`ModelRouter.resetCredential`, `AppViewModel.resetKeyLimit`).
- **Chat notice** — the existing "Switched to …" banner now carries
  the failure reason ("Switched to X / Y — rate limited (429) on
  A / B."), kept as a tool message in the history exactly as before.
- **Not yet verified live** — no Gradle build here (no SDK), and no
  live rate-limit / quota failure has been observed with a real key:
  the classifier is ported logic, not proven by execution. First
  checks on a real device: force a 429 (e.g. a free-tier key at its
  daily cap) and watch the switch notice name the reason and the
  key retry after ~60 s instead of waiting until midnight; force a
  401 and check the key stays skipped until "Reset limit flags".
  First-build risks: the `delay` import in `AgentRunner`, the
  `AlertDialog` / `FilterChip` use in the Providers screen, and the
  new `LlmException.retryAfterMs` parameter (defaults null, so all
  existing throw sites still compile).

## Phase 7, Step B2 — AgentMT permissions + compaction + resume (adapted 2026-11-14)

AgentMT's `PermissionEngine`, compaction cap / retry and `resume` /
`CONTINUE_TEXT`, reworked to fit CodeSathi — our package, our
DataStore JSON storage (not AgentMT's Room rules table), our
HttpURLConnection + SSE client, and our existing per-tool rules and
compaction. Nothing of AgentMT's providers.json, skills, GPL
binaries, Termux JNI or Linux orchestration was touched.

- **Permissions (new `agent/Permissions.kt`)** — ordered glob rules
  with AgentMT's glob dialect (`**`, `*`, `?`, `{a,b}` — adapted from
  AgentMT's `Workspace.globToRegex`; CodeSathi had no workspace glob
  of its own, so this is the project's only glob dialect, nothing
  invents a second one) and 4 approval modes (AskAll / AskWrites /
  AutoAll / AutoEdits). **Precedence: explicit per-tool rule >
  glob rule > mode.** Only a rule the user actually stored counts
  as "explicit" (new `storedToolRules()` reads the stored map
  without the v1 defaults merged in) — a stored "deny" always wins
  and no mode or glob rule can override it. The mode is a DataStore
  setting (`approval_mode`, default `ask_all`); the glob rules are
  an ordered DataStore JSON list (`permission_rules_json`, first
  match wins, add / delete / move up-down). No Room change, no
  migration, no secrets (tool names and path/command patterns only).
  A rule names a tool (`*` for all, or a glob such as `mcp:*` for
  every MCP tool) and a pattern over its target: the file path for
  read / write / edit, the directory for glob, the query for grep,
  the command for run, the skill name for skill. MCP tools have no
  target, so only a `*` pattern matches them — an MCP rule is
  written against the tool name instead.
- **What "ask" means here (deliberate difference from AgentMT)** —
  AgentMT's Ask blocks the turn on a user approval dialog. CodeSathi
  has no blocking ask dialog: "ask" means what it has always meant
  in v1 — the tool runs and is always shown as a tool card in the
  chat. Adding a blocking dialog would change the default behaviour
  for every existing user, so the mode and glob rules decide
  allow / ask / deny and the runner enforces them exactly the way
  it enforces the per-tool rules today: only "deny" refuses a call.
  The default mode is AskAll, so existing users see no change at
  all unless they pick another mode or add a glob rule. Plan mode
  still hard-refuses writes before any of this is consulted, and a
  file write is never applied without the diff-card Approve — not
  even under AutoAll or AutoEdits. The runner now gets a
  target-aware decision function (`toolDecision`, wired in
  `runAgentTurn` from the stored rules + glob rules + mode); the
  plain per-tool `toolRule` is kept as the fallback for callers
  that pass none (e.g. the approve-only runner).
- **Permissions UI** — the mode + glob rules live in the existing
  **Tool permissions dialog** (Chat ⋮ menu → "Tool permissions"),
  above the unchanged per-tool list: four mode rows (label +
  description, "Use this" / "Selected"), then the glob rules in
  order (↑ / ↓ / Delete) with an add form (tool, pattern, action).
  The dialog scrolls now, so the longer content fits a phone.
- **Compaction cap (new `data/ModelContext.kt`)** — AgentMT caps the
  threshold at 75% of the smallest known context window among the
  candidate models. **Our catalog carries no context sizes** (its
  models are plain id strings — the premise that "the catalog's
  models carry a context size" was checked and does not hold for
  our catalog), so the known windows live in a small hand-written
  table instead: GPT-5 400k, GPT-4.1 1M, GPT-4o/4-turbo 128k,
  Claude 4.x / 3.5 200k standard (the 1M tier is opt-in beta, so the
  conservative 200k is used), Gemini 2.5 Pro/Flash 1M, Gemini 1.5
  Pro 2M, DeepSeek V3-era chat/reasoner 64k, Grok-4 256k, Grok-3
  131k, Mistral Large/Medium/Small 128k, Codestral 256k, Llama 3.x
  128k, Qwen 2.5 128k, Qwen 3 256k (vendors' standard published
  windows, Nov 2026). Matching is longest-prefix on the normalised
  id (provider prefix stripped, so `openai/gpt-4o` and dated ids
  match). `Compaction.effectiveContextTokens(sessionModel,
  fallbackChain)` is the smallest known window among the session
  model + every fallback-chain model; unknown models are skipped
  (never treated as zero), and when none is known the fixed 128k
  default is used, exactly as before. The local engine keeps its own
  4,096. Because the 60% prune / 75% summarise thresholds are
  fractions of this window, capping the window caps the thresholds:
  with a 64k model in the chain, tool outputs prune above ~38k
  tokens and the summary triggers above 48k instead of 96k.
- **"Context too long" → harder compaction + one retry** — when a
  Build step fails with a CONTEXT failure and nothing was emitted
  yet, the runner calls back into the ViewModel
  (`onContextTooLong` → `compactHarder`), which re-summarises with
  only the newest 5k tokens kept verbatim (`HARD_KEEP_TOKENS`,
  model summary first, offline local summary as fallback), and the
  step is retried once with the new summary instead of failing the
  turn (new `AgentEvent.Notice`, shown as a tool message: "Context
  too long — summarised the earlier chat more tightly and retrying
  this step."). The retry happens once per step, on the SAME
  attempt — the next candidate would hit the same oversized prompt.
  If nothing more can be compacted (nothing outside the 5k tail, or
  the saved summary already covers it) the callback returns null
  and the normal next-candidate / error path runs. Build only: Plan
  has no summary mechanism, so a plan turn keeps the existing
  next-candidate retry.
- **Pair boundary** — `Compaction.splitForSummary` (now with a
  `keepTokens` parameter) never splits a tool call/result pair at
  the cut. In Room a turn is stored as its tool messages FIRST and
  its one assistant message AFTER them (the assistant text is
  inserted when the turn ends), so the split can only happen one
  way: the tail keeps an assistant message while that turn's tool
  messages fell outside the budget. When that happens the assistant
  message is dropped from the tail too (it joins the covered part,
  so the summary still describes the turn). A tail starting with a
  tool message is fine here — its assistant message is newer, so it
  is already in the tail. The 60% prune / 75% summarise / ≤80%-to-
  start behaviour is unchanged.
- **Resume / Continue (new `AgentRunner.CONTINUE_TEXT`)** — after an
  error, a limit, or a Stop, the chat shows a **Continue** button
  next to the error text (only while no turn runs; sending a new
  message or tapping Continue clears it). Continue sends
  `CONTINUE_TEXT` ("Continue from where you stopped. Earlier steps
  and tool results above are done; do not repeat them. Check the
  last results, then carry on with the remaining work.") as the
  next user message — the history already holds every finished
  step, tool result and the partial reply, so the turn resumes from
  the last finished step instead of resending the last message from
  scratch. The CONTINUE_TEXT itself is NOT stored as a user
  message (same pattern as the post-approval resume, whose
  continuation is likewise only in the text sent to the model), so
  the chat shows no extra bubble. The mid-stream partial text stays
  as shown and is never replayed (Step B1's rule). Plan mode is the
  one exception: `runPlan` ignores the history and only sends the
  goal, so a plan Continue appends the original goal (the last user
  message) to the continue text — a plan has no tool steps to
  resume, so this is a re-ask of the same goal with the "continue"
  instruction. Remote turns have no Continue button (no chat UI
  there) — a remote client sends a new message.
- **Not yet verified live** — no Gradle build here (no SDK); no
  live long-context turn has been observed, so the compaction cap,
  the harder-compaction retry and Continue are ported logic, not
  proven by execution. First checks on a real device: a long chat
  with a 64k model in the fallback chain should summarise at ~48k
  tokens, not 96k; force a context-too-long error and watch the
  "summarised … retrying" notice followed by a resumed step; stop
  a turn mid-stream and tap Continue to see it pick up where it
  stopped. First-build risks: the `while`-loop attempt retry in
  `AgentRunner.runBuild` (the `for` → `while` conversion — the
  index must only advance when moving to the next candidate), the
  `verticalScroll` + `OutlinedTextField` use in the permissions
  dialog, and the `UUID` import in `ChatScreen`.
- **Verification fix found by the balance check (real,
  compile-blocking)** — Kotlin block comments nest, so a `/*`
  inside a KDoc comment opens a nested comment that its closing
  `*/` never closes (same bug class as Phase 5, Step 2): the new
  `docs/**` examples in the `Permissions.kt` and `ChatScreen.kt`
  KDocs did exactly that and would have commented out the rest of
  each file. All three spots are reworded to `docs/` + `**`
  (wording only, no code changed). The `docs/**` strings inside
  string literals in the dialog body are fine and were left alone.

## Phase 7, Step B3 — AgentMT workspace + background + backup + attachments + runs + crash reporter (adapted 2026-11-14)

- **Workspace sandbox** — `agent/Workspace.kt`: AgentMT's
  canonical-path sandbox, adapted — CodeSathi has no `java.io.File`
  project root (the folder is a SAF tree URI, and `SafProject`
  already refuses `..` segments), so the rule is expressed for
  relative SAF paths and checked by the tools before they touch a
  path: absolute paths, any `..` segment, and paths under an ignored
  first folder (`.git`, `node_modules`, `build`, `.gradle`, `.idea`,
  `.venv`, `__pycache__`, `dist`, `.next`, `target` — AgentMT's set)
  are refused with a plain "Path is outside the project" message.
  Wired into Read / Write / Edit / Glob (Grep is top-level only and
  takes no path); what the tools do is unchanged, only the check is
  added. Glob also gains an optional `pattern` argument, matched
  with Step B2's `globToRegex` — still the project's only glob
  dialect, no second one was created.
- **Background processes** — `agent/BackgroundProcs.kt`: the agent's
  `run` tool stays foreground-only; `background_process` starts a
  long-running command (dev server, watcher) in the background
  through the same `sh -c` shell and work-dir resolution, and lists
  / reads output / stops it. Output goes to one log file per process
  under `getFilesDir()/bg/` (AgentMT's pattern), read back from the
  tail, so output survives the process exiting. At most **3**
  processes run at once (AgentMT had no cap — 3 is CodeSathi's
  choice, so a phone cannot accumulate forgotten servers); a fourth
  start is refused with a plain message. Start is refused in Plan
  mode (a server is a side effect) and on an unresolvable folder,
  exactly like `run`. The tool is kind EXECUTE under the same
  allow / ask / deny rules as `run` (default "ask"); its target for
  a glob rule is its command (start) or its action. The chat Stop
  button kills all background processes too, and they never outlive
  the app process. Surface: the agent tool calls, plus a
  "Background processes" card on the Terminal screen (snapshot with
  Refresh, Stop per running process).
- **Encrypted backup option** — the plain sessions-only backup
  (Phase 5, Step 3) is unchanged and stays the default. Opt-in, from
  Chats → "⋮" → "Backup with encrypted keys": the export is a ZIP
  with two entries — `codesathi-backup.json` (byte-for-byte the same
  sessions JSON the plain export writes) and `secrets.enc` (the
  device's secrets, encrypted with `BackupCrypto`: PBKDF2-HMAC-SHA256,
  210,000 iterations → AES-256-GCM, 16-byte salt, 12-byte IV, magic
  "CSS1" as additional authenticated data — AgentMT's exact
  parameters, CodeSathi's magic). The secrets JSON carries every
  provider's key list, the AWS credential, the Remote password, the
  Git credential and the MCP tokens that exist (`KeyStore`
  export/import, same internal key names). The passphrase needs at
  least 8 characters and is **never stored** — typed at export and
  again at import, held only in memory for that operation. Import is
  two-phase, like AgentMT's preview: picking an encrypted backup
  shows the counts first (sessions, messages, whether it carries
  secrets — the count of secrets cannot be shown until the
  passphrase is entered), and nothing is applied until Import is
  tapped. With secrets, they are decrypted FIRST, so a wrong
  passphrase stops the whole import with nothing changed; restoring
  secrets never overwrites a credential already on the device
  (per-provider / per-credential, absent-only). The plain JSON
  restore path is untouched. Restore accepts both file types
  (the picker offers JSON and ZIP).
- **Attachments — PDF + text** — `agent/Attachments.kt`: images stay
  in `ImageStore`; files go through `AttachmentStore` (same
  app-private pattern, `getFilesDir()/attachments/`, Room stores
  only records in the new `Message.attachmentsJson` column — Room
  v5 → v6, see below). SAF pick, 25 MB cap (AgentMT's), at most 3
  files per message (same cap as images). Text files are inlined
  into the prompt (at most 120,000 chars, AgentMT's cap, in an
  attached-file block). **What PDF support actually does: a PDF is
  stored as-is and sent as raw PDF bytes, base64-encoded — a `file`
  part for OpenAI-kind providers and a `document` block for
  Anthropic-kind providers (AgentMT's two shapes, in
  `LlmClient`). There is NO on-device PDF text extraction: no PDF
  library is in the app, so the model reads the PDF itself. A PDF
  for a local on-device model or Bedrock is refused before sending
  with a plain message (neither takes a PDF part here) — never
  silently dropped. A binary file that is neither text nor PDF is
  stored and shown, but the model only gets a note naming it. An
  image picked with the file button is skipped with a note pointing
  at the image button (images must go through `ImageStore` so they
  are downscaled). Only the **2 most recent user messages resend
  binaries** on each turn (AgentMT's rule — adopted here for images
  too: before this step every history image was re-sent on every
  turn). Older messages keep their text attachments inlined but
  their images / PDFs become a short "[shared earlier]" note.
  Chat shows staged files as chips above the input, a PDF warning
  like the vision warning, and the files as chips on stored user
  messages. Session delete deletes the attachment files too.
  Known quirk, pre-existing for images and left as-is: in Build the
  newest user message is sent twice (once as the last history
  message, once as the new user message), so its binaries go out
  twice in one turn — the raw + expanded text both reaching the
  model is the established slash-command behaviour, and changing it
  here would change every Build prompt. PDF bytes are not counted
  in the chars÷4 usage estimate (only their surrounding text is).
- **Runs history** — a new Room table `runs` (Room v5 → v6, real
  `Migration(5, 6)` creating the table + its indexes and adding
  `attachmentsJson` to messages; registered in `CodeSathiApp`).
  A new table, not a derivation from the messages table: messages
  hold no per-turn token counts (usage rows have no turn grouping),
  no step count, no changed-file list and no route log, and a failed
  turn leaves no message row at all — deriving would have meant
  re-reading tool-message text with heuristics. One run per
  `runAgentTurn` invocation (a post-approval resume is a second
  run): title, status, steps (model turns), tool calls, changed
  files, tokens in / out, route log, error, model. Status
  "waiting" means the turn paused for a file approval (AgentMT's
  meaning). A run left "running" / "waiting" by a killed process is
  marked "cancelled" on the next start (AgentMT's orphan rule, run
  from `AppViewModel` init); the chat Stop button marks the live run
  cancelled too. Screen: Tools tab → Runs (list with All / Active /
  Done / Failed filters, clear-finished with confirm — finished runs
  only, chats and files are never touched), tap for the detail
  (stats, changed files, route log, the messages written during the
  run, "Open chat"). Deleting a session deletes its runs.
- **Crash reporter** — `core/CrashReporter.kt` (AgentMT's, adapted):
  uncaught exceptions go to a local file
  (`getFilesDir()/last_crash.txt`), shown on next launch as a card
  at the top of Home with a Copy button and a Dismiss button (which
  deletes the file). NO upload and NO analytics anywhere — the file
  never leaves the device unless the user copies it out. Installed
  in `CodeSathiApp.onCreate` via the default uncaught-exception
  handler, keeping the previous handler chained so the process still
  dies normally. Secrets never appear in stack traces because they
  are never put in exception messages.
- **Not yet verified live** — no Gradle build here (no SDK); no
  live background process, encrypted backup, PDF attach or crash has
  been observed — all ported logic, not proven by execution. The
  Room 5→6 migration is the first-build risk (first launch on an
  existing v5 database is the real test); next are the
  `GetMultipleContents` file picker with `*/*`, the ZIP
  import-preview dialog flow, `Icons.Filled.AttachFile` /
  `Lock` / `History`, and the `PasswordVisualTransformation`
  fields. First checks on a real device: start a dev server with
  `background_process`, read its output, stop it from the Terminal
  screen, tap Stop in chat and confirm it dies; export an encrypted
  backup, restore it on the same device with the right passphrase
  (keys return) and with a wrong one (nothing changes); attach a PDF
  to an OpenAI-kind model and to a local model (refused); kill the
  app mid-turn and confirm the run shows "Stopped" on next launch.

## Phase 7, Step B4 — AgentMT plugins / skills / Termux / browser / MCP stdio / tests (adapted 2026-11-14)

The LAST Phase 7 step, so Phase 7 is now complete.

- **Plugins** — `agent/Plugins.kt` (PluginManager + PluginRecord,
  DataStore registry `plugins_json`, folders under
  `getFilesDir()/plugins/`): install a plugin from a GitHub repo URL
  or a zip file (Claude Code layout: `.claude-plugin/plugin.json`,
  `skills/`, `commands/`, `.mcp.json`; marketplace.json is listed, never
  installed whole — a marketplace URL is refused with its plugin names
  so the user picks one plugin's repo). A plugin is installed
  **disabled** and **never executes code** — hooks, scripts, sub-agents
  and local-command MCP servers are recorded as unsupported and
  ignored. It only contributes skills (read from its folder by
  SkillStore, enable / disable only), slash commands (CustomCommand
  records, name collisions skipped never overwritten, `$ARGUMENTS`
  becomes `{input}` and positional `$1`…`$9` are dropped) and http MCP
  servers (McpServer records, disabled, delete blocked — uninstall the
  plugin). Enabling a plugin enables its commands and servers with it;
  uninstall removes its folder, commands, servers and record. Surface:
  the Plugins section on the Skills screen. Caps: GitHub tree 80 files
  / 4 MB, zip 20 MB, writes guarded to the plugin folder.
- **Skill install** — `agent/GitHubFetcher.kt` (RepoRef parser +
  read-only GitHub contents API over HttpURLConnection, unauthenticated
  60 requests/hour, no token) + `SkillStore.installFromGitHub`:
  install skills from a GitHub URL, skills.sh link or owner/repo
  shorthand. Each folder with a SKILL.md becomes one CodeSathi skill
  file (front matter becomes the `# Name` / `Description:` lines);
  supporting files beside SKILL.md are NOT installed, and an id that
  already exists is skipped, never overwritten. Surface: "Install a
  skill from GitHub" on the Skills screen. The built-in skills are
  untouched, and `Skills.kt` was extended, not replaced — the skill
  tool, the enable flags and the screen's edit / add / delete / reset
  all work exactly as before.
- **Termux bridge** — `agent/Termux.kt` (TermuxBridge, public
  RUN_COMMAND intent only, no new dependency, no terminal-view AAR, no
  JNI): the agent's `run` tool gains a Termux backend, off by default,
  switched by "Agent run tool uses Termux" on the Terminal screen
  (DataStore `termux_enabled`). Same tool, same allow / ask / deny
  rule, same Plan-mode screen. Requirements, stated on the screen and
  here: Termux installed, `allow-external-apps=true` in Termux, and the
  `com.termux.permission.RUN_COMMAND` permission granted (declared in
  the manifest with a `<queries>` entry so the app can see Termux).
  Without Termux the tool refuses with a plain message instead of
  silently running in-app. Termux only sees shared storage or its own
  home, so the project folder must resolve to a path Termux can see.
  A Termux command cannot be stopped from here (Stop does nothing to
  it) and returns its whole output at once (60 s wait) instead of
  streaming. The Terminal screen's own commands still run in the
  in-app shell — only the agent's `run` tool switches.
- **MCP stdio via Termux** — `agent/McpClient.kt` gains a
  `termux:<command>` server URL: TermuxMcp starts the command inside
  Termux behind supergateway (npx, fetched on first use) serving
  streamable HTTP on 127.0.0.1:18100–18899, then the normal HTTP MCP
  client talks to it — tools/list, tools/call, same allow / ask / deny
  rules. Needs Termux + nodejs in Termux (`pkg install nodejs`); first
  start can take up to 2 minutes and is reused while it answers. No
  Linux transport: CodeSathi has no Linux env, so there is no
  `linux:` prefix. The MCP screen accepts `termux:` URLs and notes
  the requirement. HTTP MCP is unchanged.
- **Agent-driven browser — NOT DONE.** AgentMT has a built-in WebView
  browser the agent drives (`browser_read` / `browser` tools with
  numbered elements), sharing one WebView with the user so it keeps
  their cookies and logins. It does not fit CodeSathi cleanly: our
  only WebView is the editor's preview, which deliberately loads saved
  text with no filesystem access, no cookies and no navigation, and is
  destroyed when the screen closes — while an agent browser needs a
  long-lived shared WebView that stays alive off-screen, a browser
  screen with address bar / navigation / downloads / file chooser,
  a userscript store, and a new tool kind with its own approval
  questions (the agent would act with the user's logged-in sessions).
  That is a new feature surface, not a port, so it was left out rather
  than forced in. WebFetch / WebSearch stay as-is.
- **Tests** — two new plain-JUnit files, no new dependency (no
  Robolectric): `agent/RoutingTest.kt` (classifier, Retry-After
  parser, route bounds, mid-stream marker) and
  `agent/PluginInstallTest.kt` (RepoRef parsing, plugin manifest /
  marketplace parsing, command conversion, skill front matter,
  Termux MCP port). The eight RouteRunnerTest cases that drive
  `RouteRunner.run` were skipped — CodeSathi has no RouteRunner; its
  retry loop lives in AgentRunner + FallbackEngine and needs Room /
  DataStore / a live LLM client. AppLaunchTest was not ported at all:
  it is a Robolectric launch test (compose rules, ActivityScenario,
  Room migration from a v1 schema file) and adding Robolectric would
  be a heavy new test dependency for a launch test of another app's
  screens.

**Not yet verified live** — no Gradle build here (no Android SDK on
this machine). First checks on a real device: install a small plugin
from GitHub and from a zip, enable it, confirm its skill appears and
its command expands and its MCP server lists tools, then uninstall it
and confirm all three are gone; install one skill from a skills.sh
link; turn the Termux backend on with Termux installed and run `ls`
through the agent, then with Termux missing and confirm the plain
refusal; add a `termux:` MCP server and confirm its tools list;
run `./gradlew test` for the two new test files.

## What is TODO / Phase 2 (explicitly NOT in v1)
- Phase 2 is complete — Steps 1–8 are all done, see the sections
  above. Phase 3, Steps 1–5 (custom commands, reasoning-effort picker,
  editor tabs / live preview, MANAGE_EXTERNAL_STORAGE, UI polish) are done — Phase 3 is complete.
- The provider catalog grew from 12 (v1) to 208 providers in Phase 2,
  Step 2 plus the live-check pass (see `assets/providers.json`). The 200+
  target is reached: every remote base URL was probed live with
  `GET {baseUrl}/models` (no auth; 200/401/403 = reachable), 6 dead /
  duplicate entries were removed, 10 base URLs were corrected and 62
  providers were added. Phase 4 (2026-11-13) fixed the four remaining
  entries: LangDB was removed (its API host returned 503 on every path,
  probed twice — and the correct form is project-scoped, which a flat
  catalog cannot express) and Galadriel was removed (its API host
  completes TLS then sends an empty reply, and an independent sweep
  lists its inference API as discontinued); Azure OpenAI now works via
  a resource-name field in the provider card (kind `azure`, deployment
  URL with api-version 2024-10-21, `api-key` header) and AWS Bedrock
  now works via SigV4-signed Converse API calls with an access key id,
  secret access key and region from the provider card (kind `bedrock`).
  The catalog is now 206 providers. Details in the catalog's `note` field.
  Bedrock known limits (Phase 4, honest): Converse is non-streaming —
  the reply arrives whole and is shown as one chunk, never faked as a
  stream; Bedrock has no `/models` list, so the bundled model or a
  hand-typed model id is the way in; the reasoning-effort picker does
  nothing for Bedrock (Converse has no effort field); tool results go
  back paired by toolUse id only when every call of the turn carried
  an id — a mixed turn (native + fenced calls) falls back to the plain
  combined text, exactly as other providers get.

## Acceptance tests (from the checklist)

- Fresh install → onboarding → picker → session in list → kill app →
  reopen → same session resumes with history; no path field appears.
- "Build a single-file portfolio website for Yogeshwar Kumar" in an empty
  folder → plan names `index.html` → zero files created before approval.
- Plan → Approve → Build creates `index.html` → "✓ Verified" shows.

## Layout

```
CodeSathi/  (117 files; 23,214 lines of Kotlin)
app/src/main/
  AndroidManifest.xml
  assets/providers.json          # 207-provider catalog (ours, Phase 2 Step 2 + live-check + Phase 4 fixes + Phase 6 Step 5 on-device provider)
  assets/skills/                 # Built-in skills (Step 4): code-review.md, write-tests.md, explain-code.md
  assets/templates/              # Project templates (Phase 6, Step 3): portfolio/index.html, python-script/main.py, notes/NOTES.md
  java/com/inyogeshwar/codesathi/
    MainActivity.kt              # NavHost (261)
    CodeSathiApp.kt              # Room / DataStore / KeyStore singletons + shared AppViewModel, TermuxBridge init (Phase 7, Step B4) (53)
    core/CrashReporter.kt        # Uncaught-exception handler: local last_crash.txt, no upload (Phase 7, Step B3) (64)
    data/Database.kt             # Room: Session, Message, FavouriteModel, SessionSummary, CachedModel, UsageRecord, RunEntity (534)
    data/BackupCrypto.kt         # PBKDF2-SHA256 210k + AES-256-GCM for encrypted backups (Phase 7, Step B3) (68)
    data/EncryptedBackup.kt      # Encrypted backup ZIP: codesathi-backup.json + secrets.enc, import preview (Phase 7, Step B3) (133)
    data/SessionBackup.kt        # Versioned session backup format (v1): encode / parse / validate, sessions only, no secrets, no images; SyncMergeResult for multi-device sync (239)
    data/ModelPrices.kt          # Bundled price table for cost estimates, 18 entries, price date 2026-11-13 (144)
    data/PreferencesStore.kt     # DataStore: onboarded, default model, last session, tool rules, fallback chain, exhaustion, MCP servers, skill enabled flags, voice output, remote enabled, custom commands, reasoning effort per model, Azure resource name, local .task model file, routing mode + per-key health ledger, approval mode + glob permission rules, plugins registry, Termux backend flag (890)
    data/ModelContext.kt         # Known context windows for the compaction cap (Phase 7, Step B2): longest-prefix match, unknown models fall back to 128k (95)
    data/KeyStore.kt             # EncryptedSharedPreferences, several keys per provider, MCP tokens, remote password, Git credential, AWS Bedrock credentials, masked display, secrets export/import for encrypted backups (442)
    data/Providers.kt            # Bundled catalog + GET {baseUrl}/models fetch + Room cache, Azure deployments list, Bedrock note, local kind guard (240)
    data/VisionSupport.kt        # Which (provider, model) pairs can see images — verified list only, everything else refuses; local models are text-only (90)
    data/TokenCounter.kt         # chars ÷ 4 estimate (20)
    agent/Tools.kt               # Read/Write/Edit/Glob/Grep over SAF tree, readBytes + listEntries, `run` shell tool + Plan-mode write screen, workspace sandbox, background_process tool + Termux backend (Phase 7, Step B4) (465)
    agent/Workspace.kt           # Project sandbox for agent file tools: refuse escapes + ignored dirs (Phase 7, Step B3) (63)
    agent/BackgroundProcs.kt     # Background processes (max 3): start / list / tail / stop, log files under getFilesDir()/bg (Phase 7, Step B3) (216)
    agent/Attachments.kt         # PDF + text attachments: AttachmentStore, 25 MB cap, 120k-char text inline, raw-PDF base64 sending (Phase 7, Step B3) (246)
    agent/Compaction.kt          # 60% prune / 75% summarise, send-list builder, pair-boundary tail cut, effective context window = smallest known window among session model + fallback chain (193)
    agent/Permissions.kt         # Approval modes (AskAll/AskWrites/AutoAll/AutoEdits), glob rules + globToRegex, PermissionEngine decide: explicit per-tool rule > glob rule > mode (226)
    agent/LlmClient.kt           # HttpURLConnection + SSE, OpenAI + Anthropic, tool_calls events, LlmException, reasoning_effort (OpenAI-kind only), Azure deployments URL + api-key header, Bedrock SigV4 Converse (non-streaming), real usage parsing, per-provider vision shapes, on-device .task branch, Retry-After / X-RateLimit-Reset headers, PDF parts for OpenAI/Anthropic kinds (803)
    agent/Routing.kt             # FailureClassifier (429 vs daily quota vs 402 vs auth vs context vs 5xx vs capability vs network), per-kind cooldowns, Retry-After parsing, MidStreamException, RouteBounds 4 attempts / 180 s deadline (208)
    agent/ModelRouter.kt         # Routing profiles (pinned vs fallback), per-key health ledger, key rotation, resetCredential / resetAllLimits (134)
    agent/LocalLlm.kt            # On-device local models (Phase 6, Step 5): MediaPipe LLM Inference (tasks-genai 0.10.27), .task file copy + cached engine, streaming via generateResponseAsync, 4,096-token context, plain-text prompt (384)
    agent/BedrockSigner.kt       # Hand-rolled AWS SigV4 signing for Bedrock Converse, model-id percent-encoding (130)
    agent/Fallback.kt            # Multi-key + single-provider fallback engine, limit kinds, per-kind cooldowns, Bedrock single keyless attempt (274)
    agent/ImageAttachments.kt    # Image store: downscale to 1568 px + JPEG q80, app-private files, file-name JSON for Room (216)
    agent/McpClient.kt           # MCP JSON-RPC over streamable HTTP / SSE, initialize / tools/list / tools/call, termux: stdio servers via supergateway (Phase 7, Step B4) (329)
    agent/GitHubFetcher.kt       # Read-only GitHub contents API (RepoRef parse, list/download/tree/findSkills), HttpURLConnection (Phase 7, Step B4) (166)
    agent/Plugins.kt             # Plugin install from GitHub/zip (Claude Code layout), disabled by default, never executes code (Phase 7, Step B4) (291)
    agent/Termux.kt              # TermuxBridge: run commands in the user's Termux via the public RUN_COMMAND intent (Phase 7, Step B4) (135)
    agent/McpTools.kt            # MCP tools as normal Tool objects, mcp:<server>:<tool> names (50)
    agent/Skills.kt              # Skill Markdown parse/store, SkillStore, skill tool, plugin skills + GitHub skill install (Phase 7, Step B4) (445)
    agent/Templates.kt           # Project templates (Phase 6, Step 3): registry + asset loader, BUILT_IN_TEMPLATES (107)
    agent/Voice.kt               # TextToSpeech speaker, SpeechRecognizer input, Markdown stripper (282)
    agent/Terminal.kt            # SAF→path resolution, ProcessBuilder shell runner, streaming, shared 2,000-line cap, cancellation-aware wait (232)
    agent/Git.kt                 # JGit wrapper: status / stage / commit / push / pull / branches / clone (262)
    remote/RemoteServer.kt       # Hand-rolled HTTP/1.1 server, remote API + web page, port 8080, sync export/import endpoints (548)
    remote/RemoteService.kt      # Foreground service for remote mode, notification + Stop (200)
    remote/SyncClient.kt         # Multi-device sync client (Phase 6, Step 4): HttpURLConnection pull/push, host + port parsing, plain errors (215)
    agent/AgentRunner.kt         # Plan/Build tool-calling loop, diff approval, verification, fallback retries with per-kind cooldowns + bounded attempts (4) + 180 s pre-first-token deadline + mid-stream stop, per-attempt reasoning effort + Azure resource + AWS credentials, structured Bedrock tool pairing, per-turn usage events, `run` tool + max-3 auto-fix bound, vision-attempt filter, target-aware tool decisions, one harder-compaction retry on context-too-long, CONTINUE_TEXT resume, PDF + text attachments with the 2-most-recent-binaries rule (1172)
    ui/AppViewModel.kt           # Sessions, chat, plan/proposal, fetch, compaction, tool rules, fallback, MCP, skills, voice, remote turns, custom commands, reasoning effort, editor-save proposals, subfolder sessions, Azure resource name, AWS credentials, usage recording, session backup/restore, `run` tool wiring + Stop kills shell, staged images, project-template picker + proposal queue, multi-device sync merge + syncWithDevice, local .task model file + 4,096-token local context, routing mode + key health + reset limit flags, approval mode + glob rules, harder compaction + Continue, runs history, encrypted backup, PDF + text attachments, crash report state, plugins + skill install + Termux flag (Phase 7, Step B4) (3414)
    ui/TerminalViewModel.kt      # Terminal state: folder, streaming output, history, background processes, Termux status + flag (Phase 7, Step B4) (249)
    ui/GitViewModel.kt           # Git state: folder, status, one op at a time (306)
    ui/EditorViewModel.kt        # Editor state: tabs, in-memory edits, binary checks, save proposals, folder tree (576)
    ui/Markdown.kt               # Self-contained Markdown renderer for chat (282)
    ui/SyntaxHighlight.kt        # Hand-rolled syntax highlighter for diffs + code blocks (368)
    ui/Nav.kt                    # Routes (93)
    ui/theme/Theme.kt            # (37)
    ui/screens/OnboardingScreen.kt   # (93)
    ui/screens/HomeScreen.kt         # Home + New Session, top-bar overflow menu, backup/restore pickers, template picker, sync menu entry, crash-report card (443)
    ui/screens/ChatScreen.kt         # Plan/Build, plan card, diff card, tool rules dialog (incl. `run`, approval mode + glob rules), fallback notice, Continue button after an interrupted turn, mic + voice output, command suggestions, effort label (hidden for local models), image attach + thumbnails + vision warning, PDF + text attachments (1126)
    ui/screens/DiffCard.kt           # Shared Approve/Reject diff card (Chat + Editor + templates), syntax-highlighted (76)
    ui/screens/FolderTree.kt         # Folder tree browser for the Editor (243)
    ui/screens/ProviderPickerScreen.kt  # Top picks / favourites / fetched / bundled, manual model id, multi-key, fallback order, routing-mode chips + reset limit flags + per-key health, reasoning-effort chips, Azure resource name, AWS Bedrock credentials, on-device .task file picker (771)
    ui/screens/McpScreen.kt          # MCP servers: add / enable / delete, tools, per-tool rules, termux: URLs, plugin servers (Phase 7, Step B4) (288)
    ui/screens/SkillsScreen.kt       # Skills: list / enable / edit / add / delete / reset, GitHub skill install + Plugins section (Phase 7, Step B4) (455)
    ui/screens/CommandsScreen.kt     # Custom commands: add / enable / edit / delete (279)
    ui/screens/TerminalScreen.kt     # Terminal: folder card, streaming output, history chips, background-processes card, Termux card (Phase 7, Step B4) (404)
    ui/screens/RemoteScreen.kt       # Remote mode: toggle, password, Wi-Fi URL, session count (238)
    ui/screens/SyncScreen.kt         # Multi-device sync (Phase 6, Step 4): other device IP + port + Remote password, push/pull checkboxes, plain result (183)
    ui/screens/GitScreen.kt          # Git: status, stage/commit/push/pull, branches, clone, credential (465)
    ui/screens/EditorScreen.kt       # Editor: folder tree, tabs, diff card, .html WebView preview (353)
    ui/screens/RunsScreen.kt         # Runs history: list with filters + clear-finished, detail with stats / changed files / route log / timeline (Phase 7, Step B3) (377)
    ui/screens/UsageScreen.kt        # Usage & cost dashboard: totals, per-model and per-session breakdowns (296)
```
