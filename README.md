# CodeSathi — v1

On-device AI coding agent for Android. **Idea-only project**: every line of
code, the provider catalog, the prompts and the branding here were written
fresh for CodeSathi. Nothing was copied from Clozed Code, OpenCode or
AntiGem — no code, prompts, skills, catalog data, licences or assets.

- **App name:** CodeSathi
- **Package:** `com.inyogeshwar.codesathi`
- **Stack:** Kotlin + Jetpack Compose (Material 3), Room, DataStore
  Preferences, EncryptedSharedPreferences, kotlinx-serialization.
- **SDKs:** minSdk 24 (Android 7.0), compileSdk 36, targetSdk 36
  (Android 16), version 1.0.

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
   "Edit plan" prefills the goal in the chat box. After a write, light
   verification runs (exists, non-empty, basic HTML sanity) and shows
   "✓ Verified: …".
4. **Providers + model picker** — a bundled `providers.json` written for
   this app with 206 providers (the 12 v1 starters plus direct labs,
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
   Nothing is ever silently dropped.

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
- **Exhaustion resets at midnight** — a key marked exhausted is skipped
  until the start of the next local day. Exhaustion is tracked by key
  id (a SHA-256 prefix, never the key value) in DataStore, and expired
  flags are pruned on read. A fallback entry whose every key is
  exhausted shows a "Limit reached — resets at midnight" badge in
  Providers.
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
  them; user-added skills are Markdown files in the same folder. No
  skill is ever downloaded — no marketplace, no versioning.
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
- **User-facing only** — the agent has NO shell tool; the terminal is
  not wired into AgentRunner at all. Commands run with the app's own
  permissions (stated on screen).
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
  plan?, fallbackNotice?}` — no streaming, chat only.
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
CodeSathi/  (64 files; 13,769 lines of Kotlin)
app/src/main/
  AndroidManifest.xml
  assets/providers.json          # 206-provider catalog (ours, Phase 2 Step 2 + live-check + Phase 4 fixes)
  assets/skills/                 # Built-in skills (Step 4): code-review.md, write-tests.md, explain-code.md
  java/com/inyogeshwar/codesathi/
    MainActivity.kt              # NavHost (165)
    CodeSathiApp.kt              # Room / DataStore / KeyStore singletons + shared AppViewModel (36)
    data/Database.kt             # Room: Session, Message, FavouriteModel, SessionSummary, CachedModel, UsageRecord (290)
    data/SessionBackup.kt        # Versioned session backup format (v1): encode / parse / validate, sessions only, no secrets (212)
    data/ModelPrices.kt          # Bundled price table for cost estimates, 18 entries, price date 2026-11-13 (144)
    data/PreferencesStore.kt     # DataStore: onboarded, default model, last session, tool rules, fallback chain, exhaustion, MCP servers, skill enabled flags, voice output, remote enabled, custom commands, reasoning effort per model, Azure resource name (515)
    data/KeyStore.kt             # EncryptedSharedPreferences, several keys per provider, MCP tokens, remote password, Git credential, AWS Bedrock credentials, masked display (308)
    data/Providers.kt            # Bundled catalog + GET {baseUrl}/models fetch + Room cache, Azure deployments list, Bedrock note (233)
    data/TokenCounter.kt         # chars ÷ 4 estimate (20)
    agent/Tools.kt               # Read/Write/Edit/Glob/Grep over SAF tree, readBytes + listEntries (183)
    agent/Compaction.kt          # 60% prune / 75% summarise, send-list builder (129)
    agent/LlmClient.kt           # HttpURLConnection + SSE, OpenAI + Anthropic, tool_calls events, LlmException, reasoning_effort (OpenAI-kind only), Azure deployments URL + api-key header, Bedrock SigV4 Converse (non-streaming), real usage parsing (632)
    agent/BedrockSigner.kt       # Hand-rolled AWS SigV4 signing for Bedrock Converse, model-id percent-encoding (130)
    agent/Fallback.kt            # Multi-key + single-provider fallback engine, limit kinds, midnight reset, Bedrock single keyless attempt (157)
    agent/McpClient.kt           # MCP JSON-RPC over streamable HTTP / SSE, initialize / tools/list / tools/call (230)
    agent/McpTools.kt            # MCP tools as normal Tool objects, mcp:<server>:<tool> names (50)
    agent/Skills.kt              # Skill Markdown parse/store, SkillStore, skill tool (264)
    agent/Voice.kt               # TextToSpeech speaker, SpeechRecognizer input, Markdown stripper (282)
    agent/Terminal.kt            # SAF→path resolution, ProcessBuilder shell runner, streaming (139)
    agent/Git.kt                 # JGit wrapper: status / stage / commit / push / pull / branches / clone (262)
    remote/RemoteServer.kt       # Hand-rolled HTTP/1.1 server, remote API + web page, port 8080 (474)
    remote/RemoteService.kt      # Foreground service for remote mode, notification + Stop (192)
    agent/AgentRunner.kt         # Plan/Build tool-calling loop, diff approval, verification, fallback retries, per-attempt reasoning effort + Azure resource + AWS credentials, structured Bedrock tool pairing, per-turn usage events (729)
    ui/AppViewModel.kt           # Sessions, chat, plan/proposal, fetch, compaction, tool rules, fallback, MCP, skills, voice, remote turns, custom commands, reasoning effort, editor-save proposals, subfolder sessions, Azure resource name, AWS credentials, usage recording, session backup/restore (1695)
    ui/TerminalViewModel.kt      # Terminal state: folder, streaming output, history (202)
    ui/GitViewModel.kt           # Git state: folder, status, one op at a time (306)
    ui/EditorViewModel.kt        # Editor state: tabs, in-memory edits, binary checks, save proposals, folder tree (576)
    ui/Markdown.kt               # Self-contained Markdown renderer for chat (282)
    ui/SyntaxHighlight.kt        # Hand-rolled syntax highlighter for diffs + code blocks (368)
    ui/Nav.kt                    # Routes (20)
    ui/theme/Theme.kt            # (37)
    ui/screens/OnboardingScreen.kt   # (93)
    ui/screens/HomeScreen.kt         # Home + New Session, top-bar overflow menu, backup/restore pickers (399)
    ui/screens/ChatScreen.kt         # Plan/Build, plan card, diff card, tool rules dialog, fallback notice, mic + voice output, command suggestions, effort label (542)
    ui/screens/DiffCard.kt           # Shared Approve/Reject diff card (Chat + Editor), syntax-highlighted (75)
    ui/screens/FolderTree.kt         # Folder tree browser for the Editor (243)
    ui/screens/ProviderPickerScreen.kt  # Top picks / favourites / fetched / bundled, manual model id, multi-key, fallback order, reasoning-effort chips, Azure resource name, AWS Bedrock credentials (602)
    ui/screens/McpScreen.kt          # MCP servers: add / enable / delete, tools, per-tool rules (273)
    ui/screens/SkillsScreen.kt       # Skills: list / enable / edit / add / delete / reset (283)
    ui/screens/CommandsScreen.kt     # Custom commands: add / enable / edit / delete (279)
    ui/screens/TerminalScreen.kt     # Terminal: folder card, streaming output, history chips (272)
    ui/screens/RemoteScreen.kt       # Remote mode: toggle, password, Wi-Fi URL, session count (238)
    ui/screens/GitScreen.kt          # Git: status, stage/commit/push/pull, branches, clone, credential (465)
    ui/screens/EditorScreen.kt       # Editor: folder tree, tabs, diff card, .html WebView preview (351)
    ui/screens/UsageScreen.kt        # Usage & cost dashboard: totals, per-model and per-session breakdowns (296)
```
