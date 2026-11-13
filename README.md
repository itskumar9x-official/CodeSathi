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
   this app with 208 providers (the 12 v1 starters plus direct labs,
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
  is shown in the switch notice; both mark the key exhausted.
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

## What is TODO / Phase 2 (explicitly NOT in v1)
- Phase 2 is complete — Steps 1–8 are all done, see the sections
  above. Still to do:
- custom commands, reasoning-effort
  picker, editor tabs / live preview,
  `MANAGE_EXTERNAL_STORAGE`.
- UI polish: syntax-highlighted diffs, folder tree browser.
- The provider catalog grew from 12 (v1) to 208 providers in Phase 2,
  Step 2 plus the live-check pass (see `assets/providers.json`). The 200+
  target is reached: every remote base URL was probed live with
  `GET {baseUrl}/models` (no auth; 200/401/403 = reachable), 6 dead /
  duplicate entries were removed, 10 base URLs were corrected and 62
  providers were added. Two entries still need a fix before first use —
  Azure OpenAI (replace `YOUR-RESOURCE` with your resource name) and AWS
  Bedrock (needs SigV4 signing, not a plain API key) — and two are
  uncertain (LangDB returned 503 when probed; Galadriel's probe was
  blocked by this machine's egress proxy, URL corrected from official
  docs). Details in the catalog's `note` field.

## Acceptance tests (from the checklist)

- Fresh install → onboarding → picker → session in list → kill app →
  reopen → same session resumes with history; no path field appears.
- "Build a single-file portfolio website for Yogeshwar Kumar" in an empty
  folder → plan names `index.html` → zero files created before approval.
- Plan → Approve → Build creates `index.html` → "✓ Verified" shows.

## Layout

```
CodeSathi/  (54 files, ~11,600 lines total; 9,006 lines of Kotlin)
app/src/main/
  AndroidManifest.xml
  assets/providers.json          # 208-provider catalog (ours, Phase 2 Step 2 + live-check)
  assets/skills/                 # Built-in skills (Step 4): code-review.md, write-tests.md, explain-code.md
  java/com/inyogeshwar/codesathi/
    MainActivity.kt              # NavHost (135)
    CodeSathiApp.kt              # Room / DataStore / KeyStore singletons + shared AppViewModel (35)
    data/Database.kt             # Room: Session, Message, FavouriteModel, SessionSummary, CachedModel (177)
    data/PreferencesStore.kt     # DataStore: onboarded, default model, last session, tool rules, fallback chain, exhaustion, MCP servers, skill enabled flags, voice output, remote enabled (313)
    data/KeyStore.kt             # EncryptedSharedPreferences, several keys per provider, MCP tokens, remote password, Git credential, masked display (237)
    data/Providers.kt            # Bundled catalog + GET {baseUrl}/models fetch + Room cache (181)
    data/TokenCounter.kt         # chars ÷ 4 estimate (20)
    agent/Tools.kt               # Read/Write/Edit/Glob/Grep over SAF tree (163)
    agent/Compaction.kt          # 60% prune / 75% summarise, send-list builder (129)
    agent/LlmClient.kt           # HttpURLConnection + SSE, OpenAI + Anthropic, tool_calls events, LlmException (252)
    agent/Fallback.kt            # Multi-key + single-provider fallback engine, limit kinds, midnight reset (143)
    agent/McpClient.kt           # MCP JSON-RPC over streamable HTTP / SSE, initialize / tools/list / tools/call (230)
    agent/McpTools.kt            # MCP tools as normal Tool objects, mcp:<server>:<tool> names (50)
    agent/Skills.kt              # Skill Markdown parse/store, SkillStore, skill tool (264)
    agent/Voice.kt               # TextToSpeech speaker, SpeechRecognizer input, Markdown stripper (282)
    agent/Terminal.kt            # SAF→path resolution, ProcessBuilder shell runner, streaming (139)
    agent/Git.kt                 # JGit wrapper: status / stage / commit / push / pull / branches / clone (262)
    remote/RemoteServer.kt       # Hand-rolled HTTP/1.1 server, remote API + web page, port 8080 (474)
    remote/RemoteService.kt      # Foreground service for remote mode, notification + Stop (192)
    agent/AgentRunner.kt         # Plan/Build tool-calling loop, diff approval, verification, fallback retries (535)
    ui/AppViewModel.kt           # Sessions, chat, plan/proposal, fetch, compaction, tool rules, fallback, MCP, skills, voice, remote turns (1135)
    ui/TerminalViewModel.kt      # Terminal state: folder, streaming output, history (202)
    ui/GitViewModel.kt           # Git state: folder, status, one op at a time (306)
    ui/Markdown.kt               # Self-contained Markdown renderer for chat (275)
    ui/Nav.kt                    # Routes (17)
    ui/theme/Theme.kt            # (37)
    ui/screens/OnboardingScreen.kt   # (93)
    ui/screens/HomeScreen.kt         # Home + New Session (229)
    ui/screens/ChatScreen.kt         # Plan/Build, plan card, diff card, tool rules dialog, fallback notice, mic + voice output (546)
    ui/screens/ProviderPickerScreen.kt  # Top picks / favourites / fetched / bundled, manual model id, multi-key, fallback order (422)
    ui/screens/McpScreen.kt          # MCP servers: add / enable / delete, tools, per-tool rules (273)
    ui/screens/SkillsScreen.kt       # Skills: list / enable / edit / add / delete / reset (283)
    ui/screens/TerminalScreen.kt     # Terminal: folder card, streaming output, history chips (272)
    ui/screens/RemoteScreen.kt       # Remote mode: toggle, password, Wi-Fi URL, session count (238)
    ui/screens/GitScreen.kt          # Git: status, stage/commit/push/pull, branches, clone, credential (465)
```
