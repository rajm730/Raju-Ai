# Seema AI Assistant (Android)

Personal Hindi/Hinglish/English AI assistant for Android — Kotlin, Jetpack Compose, Material 3.

| | |
|---|---|
| Package | `com.raju.aiassistant` (debug build: `com.raju.aiassistant.debug`) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Build | Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.0.21 — use **JVM 21** in Android Studio |
| Version | 1.2.0 — see `docs/FINAL_REPORT.md` for the feature checklist and test results |

## What it does
- **Home:** large heart-beating Arc Reactor with a colour and rhythm for each state (Ready, Listening, Thinking, Speaking, Executing, Done, Error, Offline). It pulses with the real voice audio.
- **AI chat:** Google Gemini or Groq. Model list comes from your key, with a real connection test and clear errors (invalid key, rate limit, quota, timeout, offline). Requests are retried once on network errors and can be cancelled with Stop.
- **Voice:**
  - Input: tap-to-talk or continuous conversation (Android speech recognition, Hindi or English-India).
  - Output: Sarvam AI Bulbul female voice (cloud) first, Android TTS (offline) as fallback — order configurable in Settings. Devanagari is always spoken as Hindi; the next sentence is prepared while the current one plays (no long gaps); the first sentence is short so speech starts quickly. Stop interrupts immediately; only one voice plays at a time. Details and the neural-TTS evaluation (Kokoro, MediaPipe): `docs/VOICE.md`.
- **Phone tools:**
  - Find contacts and call. Every call is confirmed first; the dialer opens by default, and direct calling is optional.
  - Open installed apps, settings pages and battery status.
  - Action history with the real result of each action.
- **Incoming calls (opt-in):** after you give Seema AI Android's "call screening" role, it says who is calling: the name from Contacts, or "unknown number". It never blocks, answers or records calls.
- **WhatsApp & notifications:** **asks first**: "WhatsApp par Mom ka message aaya hai. Kya main padhkar sunaun?" It reads the preview only after you tap **Read** (in the app or on its notification). opt-in Notification Access. Seema AI announces the sender and preview for the apps you choose, never twice for the same notification. Message content is never logged. WhatsApp drafts open in WhatsApp for *you* to send.
- **Phone extras (v1.1, personal use):** timers and alarms (`set_timer`, `set_alarm`), flashlight, media volume, play/pause/next/previous for the music already playing, and SMS (always confirmed first; asks for SMS permission at first use). Safety Mode blocks SMS too.
- **New in v1.3 — more voices:** choose Seema's voice from **Sarvam (Bulbul v3)**, **ElevenLabs**, **Cartesia Sonic** or **Deepgram Aura** (English only), with the offline Android voice as the last fallback. Each shows its models and voices with ★ suggestions (ElevenLabs: Multilingual v2 for the sweetest Hindi, Flash v2.5 to save credits; Cartesia: Sonic 3 + a Hindi voice + a mood). **Listening** can use **Deepgram Nova-3** (better for Hinglish) instead of the Android recogniser.
- **New in v1.2:** calendar events and reminders for later days (`add_event`, opens your calendar pre-filled; you tap Save), map directions (`navigate`: drive/walk/bike/transit), copy to clipboard, **share any text to Seema** (Share → *Ask Seema AI* → summary, dates, tasks), and long-press-icon shortcuts **Seema se bolo** (starts voice) and **Chat**. In a voice conversation the battery result is spoken too.
- **Web search:** Tavily, summarised with numbered sources and tappable links. Website text is treated as untrusted data.
- **Music:** YouTube Music or Spotify via Android intents, with a website fallback if the app is missing.
- **Memory:** the assistant remembers only facts you approve. You can view, edit and delete them in Tools. Passwords, PINs, OTPs, card numbers and keys are refused.
- **Safety Mode & Pause:** Safety Mode is meant for payment/banking apps. It pauses announcements and blocks assistant-started calls, WhatsApp, app launches and music. Pause stops announcements and continuous listening.

## Build in Android Studio (सीधे बिल्ड करने के स्टेप्स)
1. **File → Open** → इस फ़ोल्डर को चुनें (जिसमें `settings.gradle.kts` है)। "Trust Project" दबाएँ।
2. Gradle JVM पूछे तो **Use JVM 21** चुनें। "Upgrade AGP" / "Daemon toolchain" पॉपअप को अभी Ignore करें।
3. Sync पूरा होने दें → device **samsung SM-A146B** चुनें → **Run ▶**।

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` · `./gradlew testDebugUnitTest`

### Ready-made APK from GitHub (no Android Studio needed)
Every push and pull request runs **GitHub Actions → Android CI** (`.github/workflows/android.yml`) on GitHub's servers: `assembleDebug`, `testDebugUnitTest` (with a test-count summary in the log) and `lintDebug` (fails the run on lint errors). No secrets are used or printed by CI.
1. Open https://github.com/rajm730/Raju-Ai/actions → the latest green **Android CI** run.
2. Under **Artifacts**, download **raju-ai-debug-apk** (zip) → extract `app-debug.apk`.
3. Copy it to the phone and open it (allow "Install unknown apps" for your file manager once).

Release APK: **Build → Generate Signed App Bundle / APK** with your own keystore. Never commit the `.jks` file or its passwords; `.gitignore` excludes them.

## API keys (app → Settings)
| Service | Needed for | Get a key | Cost |
|---|---|---|---|
| Google Gemini | AI chat (default) | https://aistudio.google.com/apikey | Free tier with limits; paid beyond |
| Groq | AI chat (optional, very fast) | https://console.groq.com/keys | Free tier with rate limits |
| Tavily | Web search | https://app.tavily.com | Free monthly credits; paid beyond |
| Sarvam AI | Natural female Hindi voice | https://dashboard.sarvam.ai | Free credits; paid beyond |
| ElevenLabs | Most natural voice (Hindi + English) | https://elevenlabs.io/app/settings/api-keys | Free 10,000 credits/month |
| Cartesia | Natural voice with moods (Hindi + English) | https://play.cartesia.ai | Free 20,000 credits/month |
| Deepgram | Listening (Nova-3, Hinglish) + English voice | https://console.deepgram.com | Free $200 credit |

After pasting a key, tap **Save**, then **Test**. A provider shows "connected" only after a real successful request.

There is **no backend**. Keys are encrypted with an Android Keystore key (AES-256-GCM), stored only on the phone, and sent only to their own provider over HTTPS. Backup is disabled so the keys never leave the device. Risk: a rooted or compromised phone could still extract them, so use keys with spending limits and rotate them if the phone is lost.

## Permissions on Android 15 (asked only when a feature is used)
| Permission | When | If denied |
|---|---|---|
| Microphone | First tap on Voice | Typing still works |
| Contacts | First "call X" / WhatsApp to X | Assistant says it couldn't look up the contact |
| Phone (CALL_PHONE) | Only if you enable *Direct calling* | Dialer opens instead |
| Notification access | Settings → Notifications → Enable (system screen) | No announcements |
| App notifications (POST_NOTIFICATIONS) | When you turn on announcements (Android 13+) | Read/Skip shown only inside the app |
| SMS (SEND_SMS) | Only when you ask Seema to send an SMS and confirm | Nothing is sent |
| Call-screening role | When you turn on "Announce incoming calls" (Android 10+) | No call announcements |

Manage these anytime: Settings → **Manage app permissions**. On Samsung phones, also set **Settings → Apps → Seema AI → Battery → Unrestricted** if announcements stop in the background.

## Troubleshooting
- **Notification access switch is greyed out / won't turn on:** this happens to apps installed from an APK file (Android 13+ "restricted settings"). Fix: open App info (Seema AI Settings → Status & permissions → **Open App info**), tap **⋮** (top-right), choose **Allow restricted settings**, confirm with your PIN, then switch Notification access on again. On some phones the ⋮ option appears only after you first try the greyed-out switch once.
- **Strange, repeated or Arabic-looking replies:** you are probably using Groq `allam-2-7b` (an Arabic–English model). In Settings, open Groq → Model → **Change** and pick a model marked ★ recommended (for example `llama-3.3-70b-versatile`).
- **"API key was rejected"**: re-copy the key, and check that the Gemini API is enabled for that key.
- **"Too many requests" / "Quota exceeded"**: wait a moment, or check your provider's plan.
- **Voice does nothing**: install or enable *Speech Recognition & Synthesis by Google* (Play Store), and allow the microphone.
- **Android voice sounds English**: install Hindi voice data in *Settings → General management → Text-to-speech*, then pick a voice in Seema AI Settings → Voice.
- **Gradle JVM error**: use JVM 21 (File → Settings → Build Tools → Gradle → Gradle JDK).

## Phone test checklist
1. Settings → Status & permissions: every row should show the real state. Allow the microphone, contacts and app notifications.
2. Notification access → enable Seema AI (use the restricted-settings tip if needed). The row should say "on and connected".
3. Turn on "Announce notifications" and WhatsApp. Send yourself a WhatsApp message: you should hear only *who* wrote. Tap **Read** to hear it, and **Skip** to check that it is not read.
4. Hide previews in WhatsApp and test again: you should hear "Preview chhupa hua hai".
5. Turn on "Announce incoming calls" → grant the call-screening role. Call from another phone, first a saved contact, then an unknown number.
6. Settings → Voice → **Test voice**. Check the "Android voice (Hindi)" status row.
7. Tavily key → Test. In chat ask: "आज की ताज़ा खबरें खोजो और स्रोत लिंक दो". Real links should open.
8. Ask it to remember a safe preference, restart the app, ask again, then delete the memory in Tools.
9. Turn on airplane mode: the app must say offline/failed, never "searched" or "connected".

## Known Android restrictions
- No hidden or always-on background listening. The microphone works only while you're using voice in the app, with the system mic indicator visible.
- Seema AI can't read WhatsApp's chats or send messages invisibly. It only sees what a notification shows, and you send replies yourself.
- No screen reading or control of other apps (no AccessibilityService). Payment and banking apps are never touched.
- Android requires you to grant or revoke special access (notification access, permissions) on its own system screens. No in-app button can do it for you.
- Music playback depends on the music app and your subscription.

## Project structure
```
app/src/main/java/com/raju/aiassistant/
├── domain/        assistant engine, action parser, prompts, models (plain Kotlin, unit-tested)
├── data/ai        Gemini + OpenAI-compatible (Groq) adapters, HTTP/error mapping
├── data/search    Tavily · data/tts Sarvam, Android TTS, TtsManager · data/voice SpeechRecognizer
├── data/tools     contacts, dialer, apps, settings, battery, music, WhatsApp intents
├── data/db, data/repository, data/prefs   Room (v2, with migration) and DataStore
├── notifications/ NotificationListenerService
├── core/          Keystore storage, connectivity, WAV/AudioTrack playback
└── ui/            Home (reactor), Chat, Tools (quick actions, memory, history), Settings
```

Latest changes (Hindi): `docs/CHANGES_v1.2_HINDI.md`.
