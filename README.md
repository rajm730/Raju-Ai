# Raju AI Assistant (Android)

Personal AI assistant for Android — Kotlin + Jetpack Compose + Material 3.

| | |
|---|---|
| Package | `com.raju.aiassistant` (debug build: `com.raju.aiassistant.debug`) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Build | Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.0.21, JDK 17+ |
| Current status | **Phase 1 — architecture & navigation shell** (see `docs/PHASE_1_REPORT.md`) |

## Build in Android Studio (सीधे बिल्ड करने के स्टेप्स)

1. Android Studio (Ladybug 2024.2 or newer) install करें।
2. **File → Open** → इस `Raju-Ai` फ़ोल्डर को चुनें (जिसमें `settings.gradle.kts` है)।
3. Studio अपने आप Gradle sync करेगा और Android SDK 35 माँगे तो install कर दें
   (SDK Manager → Android 15 / API 35, Build-Tools 35)।
4. Phone को USB debugging के साथ जोड़ें (या emulator चलाएँ) → **Run ▶**।

Command line:

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug
```

## Project structure

```
app/src/main/java/com/raju/aiassistant/
├── RajuApp.kt, MainActivity.kt
├── di/AppContainer.kt            manual dependency container
├── core/security/                Android Keystore encrypted key storage, key masking
├── data/db/                      Room database (conversations, messages)
├── data/prefs/                   DataStore (non-secret settings)
├── data/repository/              ConversationRepository
├── domain/model, domain/ai       AssistantState, SecretId, AiProvider adapter contract
└── ui/                           theme, navigation (Home/Chat/Tools/Settings), screens, ViewModels
```

## API keys & privacy

- There is **no backend**. Keys you paste into Settings (Gemini, Grok, Tavily) are encrypted with
  AES-256-GCM using a non-exportable Android Keystore key and stored in app-private storage.
- App backup and device transfer are disabled so encrypted keys are never copied off the phone.
- Never commit keys, `local.properties`, keystores or signing passwords — `.gitignore` covers them.
- Risk note: a key stored on-device can still be extracted from a rooted/compromised phone. Use keys
  with spending limits and rotate them if the phone is lost.

Get keys: Gemini — https://aistudio.google.com/apikey · xAI Grok — https://console.x.ai · Tavily — https://app.tavily.com

## Release signing

Create your own keystore in Android Studio (**Build → Generate Signed App Bundle / APK**). Keep the
`.jks` file and passwords outside Git. No signing config or password is included in this repo.

## Roadmap

1. ✅ Architecture & navigation shell
2. Futuristic home UI, animated Arc Reactor, 2D animated hologram fallback (3D only with a licensed model you provide)
3. Gemini / Grok adapters, real chat, model list, connection tests
4. Speech input, female TTS, stop/interrupt
5. Contacts, calling via dialer, app launching, permissions
6. Notification access & WhatsApp notification announcements
7. Tavily web search; music via YouTube Music / Spotify intents
8. Memory, task planning, safe background work
9. Performance, battery, error recovery
10. Full test pass, APKs, docs
