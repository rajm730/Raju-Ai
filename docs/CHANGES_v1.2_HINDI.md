# Seema AI v1.2.0 — क्या जाँचा, क्या सुधारा, क्या जोड़ा

यह बदलाव आपके `seema-ai-update.zip` (v1.1.0) को repo पर लगाकर, उसकी गलतियाँ सुधारकर और नए फ़ीचर जोड़कर बना है।
अब zip हाथ से copy करने की ज़रूरत नहीं — सब कुछ इसी branch में है।

## Android Studio में कैसे चलाएँ
1. Branch `claude/android-app-review-build-8jwk5q` checkout करें (या PR merge होने के बाद `main`)।
2. **File → Open** → प्रोजेक्ट फ़ोल्डर → Trust Project → Gradle JDK **21** (या 17)।
3. **Sync** → फ़ोन चुनें → **Run ▶**।
   बिना Android Studio: GitHub → Actions → इस branch का हरा **Android CI** run → Artifacts → `raju-ai-debug-apk`।

## zip में मिली गलतियाँ और सुधार
| गलती | असर | सुधार |
|---|---|---|
| Read बटन में `goAsync()` बिना time-limit | संदेश लंबा हो तो 10 सेकंड बाद receiver timeout / ANR का ख़तरा | अधिकतम 9 सेकंड में receiver छोड़ दिया जाता है; बोलना जारी रहता है |
| Timer/alarm/volume के नंबर सिर्फ़ पूरे अंक (`5`) पर चलते थे | AI `1.5` मिनट या `5.0` भेजे तो action चुपचाप गायब | `5`, `5.0`, `"5"` सब मान्य; `"hours"` भी |
| Torch: `"state":"false"` को **on** समझा जाता | उल्टा काम | `off/false/0/band` = बंद |
| ProGuard rules अलग फ़ाइल में थे, लगे नहीं थे | Release APK में action history के नाम बिगड़ते | `app/proguard-rules.pro` में जोड़ दिए; CI अब release APK भी बनाता है (R8 जाँच) |
| Gemini "सोचने" में पूरी token limit खा ले तो सिर्फ़ "Empty response (MAX_TOKENS)" | समझ नहीं आता क्या करें | साफ़ संदेश: छोटा सवाल पूछें या flash model चुनें |
| Safety Mode का text SMS नहीं बताता था | भ्रम | text ठीक किया |
| zip का कोड compile/test नहीं हुआ था | — | GitHub CI पर build + सभी unit tests + lint + release build चलाए गए |
| CI इस branch पर नहीं चलता था | — | `claude/**` branches पर भी CI |

## नए फ़ीचर (v1.2)
- **Calendar / reminder:** "Kal 5 baje doctor ka appointment add karo" → Calendar ऐप पहले से भरा हुआ खुलता है, आप **Save** दबाएँ। बीता हुआ समय हो तो मना कर देता है।
- **रास्ता (Maps):** "India Gate ka rasta dikhao", "station tak paidal" → Google Maps navigation (Maps न हो तो browser)।
- **Copy:** "yeh number copy kar do" → clipboard।
- **Share to Seema:** किसी भी ऐप में text/link → Share → **Ask Seema AI** → छोटा summary, ज़रूरी तारीख़/काम/नंबर।
- **App shortcuts:** आइकन पर long-press → **Seema se bolo** (सीधा सुनना शुरू) या **Chat**।
- **बोलकर बैटरी:** voice बातचीत में बैटरी का जवाब अब बोला भी जाता है।
- AI को अब तारीख़ ISO रूप में भी मिलती है, ताकि "kal/parso/Monday" सही गिने।

## फ़ोन पर ज़रूर जाँचें (ये सिर्फ़ असली फ़ोन पर परखे जा सकते हैं)
1. "5 minute ka timer", "subah 6:30 ka alarm" — Samsung Clock में बने?
2. "torch on karo" / "off karo", "volume 40 karo", "agla gaana"।
3. "Mom ko SMS karo: late aaunga" → confirm → permission → SMS गया?
4. "Kal shaam 5 baje meeting add karo" → Calendar खुला, सही दिन/समय?
5. Chrome में कोई लेख → Share → Ask Seema AI → summary आया?
6. आइकन long-press → "Seema se bolo" → mic शुरू?
7. WhatsApp announcement → notification पर **Read** → पूरा संदेश बोला?

## CI नतीजा (GitHub Actions run 40850717184, commit 7fb5bad)
`assembleDebug` ✅ · `testDebugUnitTest` ✅ **112 tests, 0 failed** · `lintDebug` ✅ · `assembleRelease` (R8 के साथ) ✅
