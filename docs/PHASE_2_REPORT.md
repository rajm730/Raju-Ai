# Phase 2 Report — Futuristic Home UI, Arc Reactor, 2D Hologram

## Implemented
- **Animated Arc Reactor** (`ui/home/visuals/ArcReactor.kt`, original design, Canvas): outer glow, rotating
  segmented ring, counter-rotating tick ring, coil housing, pulsing core, orbiting particles.
- **2D animated hologram** (`HologramAvatar.kt`): original stylised female face + upper body drawn with paths;
  translucent gradient fill, outline glow, scanlines, flicker, natural **blinking**, subtle head sway/bob/tilt,
  state-based gaze/brows/smile, and **mouth movement driven by a speech level** (real TTS level is wired in Phase 4).
  No third-party character asset is used. A 3D renderer can replace it once you supply a licensed model file.
- **Per-state visuals** (`ReactorStyle.kt`) for Idle, Listening (ripples), Thinking (violet spinner),
  Speaking (speech-reactive core + lips), Executing (amber progress sweep), Success (green), Error (red flicker),
  Offline (grey, still). Colours/glow animate smoothly between states.
- **Real Offline state**: `ConnectivityObserver` watches the validated internet connection
  (added `ACCESS_NETWORK_STATE`, a normal install-time permission). Status chips: state, Online/Offline,
  Mic (off until Phase 4), AI provider key saved / no key.
- **Visual preview**: "Preview visual states" lets you view each state's look. It is clearly labelled
  "Visual preview only — nothing is running"; Stop ends the preview.
- **Performance / accessibility** (`MotionProfile.kt`): normal = up to 60 fps, 36 particles; Low-power mode =
  30 fps cap, 12 particles, no scanlines; Reduced motion or system "Remove animations" = static frame.
  Animation runs only while the screen is visible, and frames redraw without recomposition.
  Screen-reader description + polite live region for the state label.
- **Responsive layout**: portrait = hologram with reactor overlapping the chest; landscape = side by side.
  Large Voice / Chat / Stop / Settings buttons (Voice disabled until Phase 4).

## Verified in this environment
- JVM unit tests: **17/17 passed** (new: `ReactorStyleTest`, `MotionProfileTest`, `HologramMathTest`).
- Reactor, hologram, animation clock, theme, components and Tools screen **compiled** against Compose Desktop
  1.7.3 (same `androidx.compose` drawing APIs) with Kotlin 2.0.21. No errors.

## Not verified here (blocked)
- Full Android build/APK, and a visual render check: `dl.google.com` (Android SDK + Google Maven) is blocked
  by this environment's network policy. Please build in Android Studio and send any errors or a screenshot.
- Frame rate and battery on a real Galaxy A14: not measured yet (Phase 9).
