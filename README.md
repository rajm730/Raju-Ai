# Raju AI Assistant (Android)

Personal Hindi/Hinglish/English AI assistant for Android — Kotlin, Jetpack Compose, Material 3.

| | |
|---|---|
| Package | `com.raju.aiassistant` (debug build: `com.raju.aiassistant.debug`) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Build | Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.0.21 — use **JVM 21** in Android Studio |
| Version | 1.0.0 — see `docs/FINAL_REPORT.md` for the feature checklist and test results |

## What it does
- **Home:** large heart-beating Arc Reactor with a colour and rhythm for each state (Ready, Listening, Thinking, Speaking, Executing, Done, Error, Offline). It pulses with the real voice audio.
- **AI chat:** Google Gemini or Groq. Model list comes from your key, with a real connection test and clear errors (invalid key, rate limit, quota, timeout, offline). Requests are retried once on network errors and can be cancelled with Stop.
- **Voice:**
  - Input: tap-to-talk or continuous conversation (Android speech recognition, Hindi or English-India).
  - Output: female voice from Sarvam AI Bulbul (cloud), falling back to Android TTS (offline). The reactor shows which engine is active. Stop interrupts immediately, and only one voice plays at a time.
- **Phone tools:**
  - Find contacts and call. Every call is confirmed first; the dialer opens by default, and direct calling is optional.
  - Open installed apps, settings pages and battery status.
  - Action history with the real result of each action.
- **WhatsApp & notifications:** opt-in Notification Access. Raju AI announces the sender and preview for the apps you choose, never twice for the same notification. Message content is never logged. WhatsApp drafts open in WhatsApp for *you* to send.
- **Web search:** Tavily, summarised with numbered sources and tappable links. Website text is treated as untrusted data.
- **Music:** YouTube Music or Spotify via Android intents, with a website fallback if the app is missing.
- **Memory:** the assistant remembers only facts you approve. You can view, edit and delete them in Tools. Passwords, PINs, OTPs, card numbers and keys are refused.
- **Safety Mode & Pause:** Safety Mode is meant for payment/banking apps. It pauses announcements and blocks assistant-started calls, WhatsApp, app launches and music. Pause stops announcements and continuous listening.

## Build in Android Studio (सीधे बिल्ड करने के स्टेप्स)
1. **File → Open** → इस फ़ोल्डर को चुनें (जिसमें `settings.gradle.kts` है)। "Trust Project" दबाएँ।
2. Gradle JVM पूछे तो **Use JVM 21** चुनें। "Upgrade AGP" / "Daemon toolchain" पॉपअप को अभी Ignore करें।
3. Sync पूरा होने दें → device **samsung SM-A146B** चुनें → **Run ▶**।

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` · `./gradlew testDebugUnitTest`

Release APK: **Build → Generate Signed App Bundle / APK** with your own keystore. Never commit the `.jks` file or its passwords; `.gitignore` excludes them.

## API keys (app → Settings)
| Service | Needed for | Get a key | Cost |
|---|---|---|---|
| Google Gemini | AI chat (default) | https://aistudio.google.com/apikey | Free tier with limits; paid beyond |
| Groq | AI chat (optional, very fast) | https://console.groq.com/keys | Free tier with rate limits |
| Tavily | Web search | https://app.tavily.com | Free monthly credits; paid beyond |
| Sarvam AI | Natural female Hindi voice | https://dashboard.sarvam.ai | Free credits; paid beyond |

After pasting a key, tap **Save**, then **Test**. A provider shows "connected" only after a real successful request.

There is **no backend**. Keys are encrypted with an Android Keystore key (AES-256-GCM), stored only on the phone, and sent only to their own provider over HTTPS. Backup is disabled so the keys never leave the device. Risk: a rooted or compromised phone could still extract them, so use keys with spending limits and rotate them if the phone is lost.

## Permissions on Android 15 (asked only when a feature is used)
| Permission | When | If denied |
|---|---|---|
| Microphone | First tap on Voice | Typing still works |
| Contacts | First "call X" / WhatsApp to X | Assistant says it couldn't look up the contact |
| Phone (CALL_PHONE) | Only if you enable *Direct calling* | Dialer opens instead |
| Notification access | Settings → Notifications → Enable (system screen) | No announcements |

Manage these anytime: Settings → **Manage app permissions**. On Samsung phones, also set **Settings → Apps → Raju AI → Battery → Unrestricted** if announcements stop in the background.

## Troubleshooting
- **"API key was rejected"**: re-copy the key, and check that the Gemini API is enabled for that key.
- **"Too many requests" / "Quota exceeded"**: wait a moment, or check your provider's plan.
- **Voice does nothing**: install or enable *Speech Recognition & Synthesis by Google* (Play Store), and allow the microphone.
- **Android voice sounds English**: install Hindi voice data in *Settings → General management → Text-to-speech*, then pick a voice in Raju AI Settings → Voice.
- **Gradle JVM error**: use JVM 21 (File → Settings → Build Tools → Gradle → Gradle JDK).

## Known Android restrictions
- No hidden or always-on background listening. The microphone works only while you're using voice in the app, with the system mic indicator visible.
- Raju AI can't read WhatsApp's chats or send messages invisibly. It only sees what a notification shows, and you send replies yourself.
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
