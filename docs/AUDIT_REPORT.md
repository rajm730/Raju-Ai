# Audit report — 29 Oct 2026

This audit follows the "Full Audit and Claude Master Instructions" brief. Every item lists evidence (file/line or test), what was changed, and what still needs a real phone.

API facts were checked on 29 Oct 2026 through web search, because this build environment cannot open docs.tavily.com, console.groq.com or docs.sarvam.ai directly:
- [Groq allam-2-7b model card](https://console.groq.com/docs/model/allam-2-7b)
- [Sarvam TTS convert](https://docs.sarvam.ai/api-reference-docs/text-to-speech/convert.md)
- [Sarvam text preprocessing](https://docs.sarvam.ai/api-reference-docs/api-guides-tutorials/text-to-speech/how-to/enable-text-preprocessing)
- [Tavily SDK reference](https://docs.tavily.com/sdk/reference/python)
- [Android 13 restricted settings](https://bayton.org/android/android-13-restricted-permissions/)

## A. Confirmed defects (with evidence)
| # | Defect | Evidence | Fix |
|---|---|---|---|
| A1 | **Nonsense/repeated Hindi replies.** The user's screenshot shows Groq model `allam-2-7b`, an Arabic–English model by SDAIA with a ~4K context (Groq model card). The app sent up to 24K characters of history, and the model list let the user pick it with no warning; it is first alphabetically. | `ModelPicker` showed a raw list. `toTurns` budget was a fixed 24,000 chars (`PromptBuilder.kt`). | `ModelAdvice` (`domain/ai/ModelAdvice.kt`) ranks models, marks recommended ones (★) and warns on allam (⚠ in Settings and Chat), never picks it by default, and uses a 6K history budget for it. `collapseRepetition` (`domain/assistant/ResponseGuards.kt`) removes stuck loops and adds a note. Empty replies become an error with **Retry**. Tests: `ResponseGuardsTest`. |
| A2 | **Notification-access switch won't turn on** for an APK installed from a file. Android 13+ "restricted settings" blocks Notification access for sideloaded apps (bayton.org, F-Droid issue). | Behaviour matches the user's screenshot. The manifest declaration was already correct (`BIND_NOTIFICATION_LISTENER_SERVICE` + intent filter). | Settings → **Status & permissions** explains *App info → ⋮ → Allow restricted settings* and has an **Open App info** button. Status now separates "enabled in Android" from "listener connected" (`onListenerConnected` / `onListenerDisconnected`). |
| A3 | **No consent before reading messages.** The listener spoke the sender *and* the preview immediately. | Old `RajuNotificationListener.onNotificationPosted` called `speak(announcementText(...))` directly. | `AnnouncementCoordinator` (`domain/notify`) says only who wrote and reads only after **Read**. Skip, timeout (30 s), Stop, Pause/Safety Mode or dismissing the notification mean it is never read. Tests: `NotificationFlowTest`. |
| A4 | **Group name read as sender; "2 new messages" read as content.** | The old parser used only `EXTRA_TITLE` / `EXTRA_TEXT`. | `parseNotification` uses the MessagingStyle latest sender, handles groups (including the old "Group: Sender" title form), detects hidden-preview placeholders and strips invisible characters. |
| A5 | **App notifications "Blocked" in App info.** The app had no channel and did not request `POST_NOTIFICATIONS` (needed on Android 13+ for app-owned notifications). | Manifest had no `POST_NOTIFICATIONS`; no `NotificationChannel`. | Added the permission, a "Message announcements" channel, a status row and an Allow button. This is separate from Notification access. |
| A6 | **No incoming-call announcement.** | Only the outgoing `Call` action existed. | `RajuCallScreeningService` (Android 10+, call-screening role): it says the Contacts name or "Unknown number", and the number only if enabled. It never blocks, answers or records. This is better than `TelephonyCallback` (which gives no number) and avoids `READ_CALL_LOG`. Best-effort WhatsApp/Telegram call announcements come from their notifications. |
| A7 | **Double submission possible.** Send stayed enabled while a reply was in progress. | `ChatScreen` Send was enabled whenever text was present. | Send is disabled while busy, and `SubmitGuard` ignores an identical message within 1.5 s. |
| A8 | **Memory had no size, duplicate or count limits.** | `remember()` checked only `looksSensitive`. | `validateMemory`: max 300 chars, no duplicates (case-insensitive), max 200 items, plus the sensitive filter. |
| A9 | **"Preview visual states" visible in production.** | `HomeScreen` showed it in every build. | Shown only when `BuildConfig.DEBUG`. |
| A10 | **Android TTS Hindi support was never checked.** | `AndroidTts` used `setLanguage` only. | `diagnostics()` uses `isLanguageAvailable(hi-IN / en-IN)` plus the engine package. Settings shows it with **Install voice data** and **TTS settings** buttons. |

## B. Verified as already correct (code trace + tests)
- **Tavily path:** AI `<action>{"type":"search_web"}` → `parseReply` → `TavilyClient` (POST `https://api.tavily.com/search`, Bearer key) → untrusted-data follow-up prompt → answer + numbered sources. Unsafe URLs are dropped. A failed search is reported as failed (`AssistantEngineTest.webSearchSummaryIncludesSources`, `ProvidersTest`). Added: `topic: "news"` with `published_date`, and source domains.
- **Keystore storage:** AES-256-GCM, a non-exportable Android Keystore key, a random IV per write, and ciphertext only in prefs. Backup and device transfer are excluded. Undecryptable entries are removed so the UI shows "Not set".
- **Secrets:** no API keys, tokens, keystores or `local.properties` in any tracked file or in the git history (pattern scan of `git log --all -p`, results redacted).
- **Outgoing calls** are confirmed with name and number first. WhatsApp only opens a draft; the user sends it.

## C. Tests actually run
- **Local JVM** (Kotlin 2.0.21, JUnit 4, coroutines-test, MockWebServer): **98 tests, 0 failed**.
- **GitHub Actions** (real Android SDK), run [40838486995](https://github.com/rajm730/Raju-Ai/actions/runs/40838486995) on `662aa41`:
  - `assembleDebug`: BUILD SUCCESSFUL
  - `testDebugUnitTest`: `UNIT TESTS: total=98 failed=0 skipped=0`
  - `lintDebug`: BUILD SUCCESSFUL
- The first audit run on `85f768f` failed lint with one error: NewApi on the call-screening service manifest entry. It was fixed in `662aa41` and re-run green.

## D. Not tested / blocked
- **No phone or emulator here:** notification access on Samsung/Android 15, the Read/Skip notification, call screening on a real SIM, TTS naturalness, mic and Bluetooth have **not** been run on a device.
- **Voice yes/no consent** is not implemented. Starting the microphone from the background is restricted on Android 14+ (while-in-use rule), so consent is by tap only.
- **Room migration instrumented test** is not added. It needs the exported schema JSON from a device/CI build and an emulator.

## E. What the user must do on the phone
See "Phone test checklist" in the README and `docs/FINAL_REPORT.md`.

## F. Costs, permissions and limits
- **Costs:** Groq, Gemini, Tavily and Sarvam each have free tiers with limits; heavier use costs money.
- **Permissions:** each one is asked only when its feature is used (table in the README).
- **Keys:** stored in the app, so they can be extracted from a rooted phone. For a public release, put the keys behind your own server (not done here).
