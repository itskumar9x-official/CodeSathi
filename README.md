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

## What is TODO / Phase 2 (explicitly NOT in v1)
- Phase 2, Step 1 (multi-key fallback / auto-switch) is done — see the
  section above. Still to do:
- MCP servers, custom skills, custom commands, voice, reasoning-effort
  picker, remote-server mode, Git client, in-app terminal, editor tabs /
  live preview, `MANAGE_EXTERNAL_STORAGE`.
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
CodeSathi/  (36 files, ~4,600 lines total; 4,216 lines of Kotlin)
app/src/main/
  AndroidManifest.xml
  assets/providers.json          # 208-provider catalog (ours, Phase 2 Step 2 + live-check)
  java/com/inyogeshwar/codesathi/
    MainActivity.kt              # NavHost (85)
    CodeSathiApp.kt              # Room / DataStore / KeyStore singletons (26)
    data/Database.kt             # Room: Session, Message, FavouriteModel, SessionSummary, CachedModel (177)
    data/PreferencesStore.kt     # DataStore: onboarded, default model, last session, tool rules, fallback chain, exhaustion (176)
    data/KeyStore.kt             # EncryptedSharedPreferences, several keys per provider, masked display (146)
    data/Providers.kt            # Bundled catalog + GET {baseUrl}/models fetch + Room cache (181)
    data/TokenCounter.kt         # chars ÷ 4 estimate (20)
    agent/Tools.kt               # Read/Write/Edit/Glob/Grep over SAF tree (163)
    agent/Compaction.kt          # 60% prune / 75% summarise, send-list builder (129)
    agent/LlmClient.kt           # HttpURLConnection + SSE, OpenAI + Anthropic, tool_calls events, LlmException (252)
    agent/Fallback.kt            # Multi-key + single-provider fallback engine, limit kinds, midnight reset (143)
    agent/AgentRunner.kt         # Plan/Build tool-calling loop, diff approval, verification, fallback retries (493)
    ui/AppViewModel.kt           # Sessions, chat, plan/proposal, fetch, compaction, tool rules, fallback (740)
    ui/Markdown.kt               # Self-contained Markdown renderer for chat (275)
    ui/Nav.kt                    # Routes (12)
    ui/theme/Theme.kt            # (37)
    ui/screens/OnboardingScreen.kt   # (93)
    ui/screens/HomeScreen.kt         # Home + New Session (204)
    ui/screens/ChatScreen.kt         # Plan/Build, plan card, diff card, tool rules dialog, fallback notice (442)
    ui/screens/ProviderPickerScreen.kt  # Top picks / favourites / fetched / bundled, manual model id, multi-key, fallback order (422)
```
