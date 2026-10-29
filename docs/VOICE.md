# Voice (TTS) — design, Hindi support and engine evaluation

## How speech output works
`TtsManager` → `SpeechPipeline` (`domain/voice/SpeechPipeline.kt`) → one `TtsProvider` per engine → `AudioPlayer` (AudioTrack).

1. **Cleanup:** the reply is cleaned for speaking (`speakableText`). Markdown, links, `[1]` citations and emoji/app markers (✅ ⚠️) are removed.
2. **Chunks:** it is split at sentence ends, including the Hindi danda "।". The **first chunk is short (≤160 chars)**, so speech starts quickly; later chunks are up to 400 chars.
3. **Language per chunk:** Devanagari text is always spoken as **hi-IN**. Roman-script Hindi (Hinglish) and English use the language chosen in Settings (default hi-IN).
4. **Prefetch:** while one chunk plays, the next is already being synthesised, so there are no long silences between sentences.
5. **Single voice:** only one utterance plays at a time. A new reply, the Stop button, or Safety Mode/Pause cancels synthesis and playback at once, so nothing is spoken twice or overlaps.
6. **Background work:** all synthesis and network work runs off the main thread. `TtsManager` lives in the app scope, so it survives screen rotation.

## Engine priority and fallback
The engine selected in Settings → Voice is tried first, then the others (`providerOrder`):

| Order (default) | Engine | Needs | Hindi | Notes |
|---|---|---|---|---|
| 1 | **Sarvam AI Bulbul v3** (cloud; v2 was retired by Sarvam) | Sarvam key + internet | Yes; also handles code-mixed Hinglish | Female voices: priya (default), ritu, kavya, neha, simran, ishita, shreya, pooja, roopa. A saved v2 voice (anushka etc.) falls back to priya. No pitch/loudness in v3 |
| 2 | **Android TextToSpeech** (on-device) | Hindi voice data installed | Depends on the phone's TTS engine (Google/Samsung) | Always available as the last fallback |

- **No key or no internet:** Sarvam is skipped silently.
- **Sarvam request fails:** the same chunk is retried with Android TTS, and the reason is shown in chat ("Voice: Sarvam AI (cloud) failed (...) — using Android TTS").
- **Adding an engine later:** a new engine only needs a `TtsProvider` implementation, a `TtsEngine` entry, and one line in `AppContainer`.

## Offline neural Hindi TTS — evaluated, not integrated (yet)
| Option | Finding | Decision |
|---|---|---|
| **Google AI Edge / MediaPipe** | No public on-device text-to-speech task. Google's on-device audio features are speech-to-text and translation through Gemma 3n ([Google Developers Blog](https://developers.googleblog.com/google-ai-edge-gallery-now-with-audio-and-on-google-play/)) | Not possible |
| **Kokoro-82M** via sherpa-onnx | Apache-2.0, runs on Android through sherpa-onnx (official builds via GitHub releases/JitPack — [sherpa-onnx jitpack.yml](https://github.com/k2-fsa/sherpa-onnx/blob/master/jitpack.yml)). See below | Not integrated now |

Why Kokoro is not integrated:
- **Hindi voice quality is graded only C:** hf_alpha, hf_beta, hm_omega and hm_psi are all C on Kokoro's A–F scale ([Replicate listing of VOICES.md grades](https://replicate.com/codingayam/kokoro-82m-complete/readme)). That is likely below Sarvam Bulbul.
- **Not Hinglish-friendly:** it phonemises Hindi via espeak-ng, which expects Devanagari. Roman-script Hinglish, the most common chat style here, would be read poorly.
- **Heavy:** a multi-language model download of a few hundred MB, plus native libraries (tens of MB per CPU architecture) in the APK.
- **Slow on target hardware:** CPU-only inference on a Galaxy A14-class phone would likely be slower than real time, which feels worse than a short cloud round-trip.

Kokoro can still be added later as an *optional, downloaded* provider, with progress, cancel, storage check and SHA-256 verification, if those trade-offs become acceptable. The provider interface is already in place for that.

## Local LLM (LiteRT-LM / Gemma) — evaluated, not integrated
On-device chat models that are useful in Hindi (Gemma 3n / Gemma 3 1B+) need roughly 0.5–3 GB downloads and several GB of RAM. They would be slow on a 4 GB Galaxy A14. The cloud providers (Gemini, Groq) stay the AI path. No heavyweight dependency was added.

## Still to verify on a real phone
Naturalness, pronunciation of Hinglish, gaps between chunks and the Sarvam→Android fallback can only be judged by listening on the device. Nothing here claims the voice is "perfect".
