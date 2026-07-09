# Project log

A chronological record of how OpenHearing was built, the decisions made, and what
is verified vs. not — so the full context survives across sessions. High-level
orientation is in [CLAUDE.md](../CLAUDE.md).

## Origin & mission

Apple ships a hearing screening + hearing-aid/transparency mode on AirPods Pro 2/3,
locked to its own platforms. OpenHearing brings equivalent **hearing assistance**
to Android, working with any earbuds, with best-effort AirPods support. It is a
sound-amplification tool, **not a medical device** (no "diagnose/treat/medical").

## Decisions (confirmed with the maintainer)

- **License** GPLv3 (matches the LibrePods/CAPod ecosystem; F-Droid friendly).
- **Stack** Kotlin · Compose/Material 3 · MVVM + Hilt · coroutines/Flow ·
  Gradle KTS + version catalog · JUnit5/Turbine/Robolectric · ktlint + detekt.
  minSdk 26, compile/target SDK 35, JDK 17.
- **DSP = pure Kotlin** behind I/O interfaces, so safety/signal logic is JVM-tested.
- **Namespace** `app.openhearing`.
- **Earbud-agnostic first**; AirPods is an `UNVERIFIED` enhancement, never required.
- **Fitting = half-gain rule** for v1 (NAL-NL2 deferred until calibration + WDRC);
  see [FITTING.md](FITTING.md).
- **Assist is mono** in v1.

## Toolchain (local only; not committed)

Empty machine at start: installed Temurin JDK 17, Android cmdline-tools (+ platform
35, build-tools 35.0.0) at `/usr/local/share/android-commandlinetools`, and the
Gradle wrapper pinned to **8.11.1** (system Gradle 9.6.1 is too new for AGP 8.7.3).
Hilt later bumped 2.52 → **2.56.2** (2.52 couldn't read DataStore Kotlin metadata).
Maintainer's machine disk is near-full; freed regenerable `~/.gradle/caches`.

## Phase-by-phase

### Phase 0 — Scaffold ✅
Six-module Gradle project (`:app`, `:core-common`, `:core-audiogram`, `:core-audio`,
`:airpods-protocol`, `:data`), version catalog, wrapper, ktlint/detekt, GitHub
Actions CI (build + test + lint), full docs set, GPLv3 LICENSE, issue/PR templates.
Safety seeded day one: `SafetyConstants` + tested `HardCeilingLimiter`. Disclaimer
UI. Pushed; repo configured (description + topics).

### Phase 1 — Audiogram engine ✅
`HughsonWestlakeStaircase` (down-10/up-5, 2-of-3 ascending criterion),
`PureToneScreening` (both ears × 6 frequencies), `Audiogram` model, half-gain
`FittingStrategy`/`GainCurve`. `ToneGenerator` (raised-cosine ramps, amplitude
clamp), `ToneLevel` (uncalibrated mapping), `TonePlayer` (AudioTrack + limiter).
Debug screen (`HearingTestViewModel`/`HearingTestScreen`) runs on phone speaker /
any headset. Simulated-listener tests prove staircase convergence. docs/FITTING.md
+ docs/DEVICE_TESTING.md.

### Phase 2 — Real-time assist ✅
Pure-Kotlin `dsp/`: `Biquad` peaking EQ → `GainEqualizer`, `Wdrc` broadband
compression, `FeedbackGuard` (autocorrelation-based howl detect + duck),
`LookaheadLimiter` (smooth limiting + hard brick-wall backstop), composed in
`HearingAssistChain`. **Limiter safety suite** (steady overload, transients,
sustained full-scale, runaway ramp, garbage, NaN/∞) = the release gate.
`AndroidAudioEngine` (AudioRecord→process→AudioTrack, urgent-audio thread),
`AssistController` + foreground-microphone `AssistService`, RECORD_AUDIO/foreground
permissions. **Not yet run on a device.**

### Phase 4 — Usable app ✅
DataStore `SettingsRepository` (consent, high-contrast, comfort ceiling) +
`ProfileRepository` (single active profile); `AudiogramCodec` (compact, tested).
Consent/onboarding gate; the screening saves its result as the active profile.
`AssistViewModel`/`AssistScreen` (mic-permission flow, amplification slider,
instant stop) builds a mono gain curve from the saved profile. Settings + high-
contrast theme + accessibility (large targets, semantics, scalable type).

### Calibration ✅ (proxy)
"Comfort calibration": preview a 1 kHz tone, set the maximum comfortable loudness;
that value becomes the assist limiter ceiling. Subjective but safe; true dB SPL
needs a sound-level meter. docs/CALIBRATION.md.

### Phase 5 — Release readiness ✅
R8 release build (minify + resource shrink); signing from a gitignored
`keystore.properties` (unsigned if absent). docs/PRIVACY.md (no data collected,
on-device only), docs/RELEASE.md (signed-build steps, distribution, release gates),
F-Droid fastlane metadata. Verified `assembleRelease` produces a shrunk APK.

### Phase A — Consumer polish ✅ (2026-07-02)
Market research first (three parallel studies: user demand, FOSS go-to-market,
repo audit — findings summarized in the session, key regulatory point below).
Then: adaptive launcher icon + monochrome layer and a proper notification icon
(sound-waves motif); audiogram-style results **chart** (log-spaced pitch axis,
inverted dB HL, red-O right / blue-X left per audiology convention, CVD-validated
colors, marker shape carries identity); system back handling + `rememberSaveable`
nav state; About card (version, GPLv3, source/privacy links); **all UI strings
moved to strings.xml** (translation now possible) with a regulatory copy pass —
"hearing check"/"sound profile" wording, "(debug)" title dropped, full
"does not diagnose, treat, cure, or prevent" formula; fastlane changelog + real
emulator screenshots. Whole flow smoke-tested on the API 35 emulator.

**Regulatory note (Gate 2 input):** FDA's 2022 hearing-device guidance lists
"audiogram + fitting formula programming output to the user's hearing profile"
as device-defining *design* evidence — disclaimers alone don't neutralize it
(21 CFR 801.4; Apple's De Novo created 21 CFR 874.3335 for exactly this software
category). No enforcement found against free/OSS apps 2023–2026; actions target
commercial efficacy claims. Copy now avoids "hearing loss"/severity/hearing-aid
comparisons everywhere user-facing. Maintainer legal review still required
before consumer release.

### Phase B — User-demanded features ✅ (2026-07-03)
Driven by the demand research (top asks: live control, professional-audiogram
import, profiles, latency trust):
- **Live master gain**: volatile per-block parameter in `HearingAssistChain`,
  adjustable while running; clamped to `SafetyConstants`, limiter downstream;
  +2 chain safety tests.
- **Manual audiogram entry** (`ManualEntryScreen`/`ViewModel`): thresholds from
  a professional test (per ear/pitch, 5 dB steps) saved as a profile — bypasses
  the uncalibrated on-device check.
- **Multi-profile persistence**: encoded profile list + active id in DataStore
  (`ProfileListCodec`, internal, tested); every check/manual entry saves a new
  dated profile (= history); legacy single-profile keys migrate on read;
  switcher + delete UI on the assist screen; +5 repository tests.
- **Safety stops**: `ACTION_AUDIO_BECOMING_NOISY` receiver stops assist on
  headset disconnect (never falls back to the speaker); starting with no
  headphones shows a feedback warning and requires "Start anyway".
- **Quick-settings tile** (`AssistTileService`): toggle assist from the shade;
  opens the app if permission/profile is missing.
All flows verified on the emulator (including tile toggle and live slider while
running). 76 JVM tests green.

### Phase C — Differentiators ✅ (2026-07-03)
- **Per-ear stereo assist** (`StereoAssistChain`): mono mic duplicated to
  interleaved stereo, one full `HearingAssistChain` per channel with its own
  fitted curve and limiter — asymmetric hearing gets per-side correction (the
  old mono path averaged both ears). `AndroidAudioEngine` gained a
  mono-capture/stereo-output mode. Needs real-earbud confirmation (gate 1).
- **Environment presets** (`AssistPreset`: standard/conversation/outdoors):
  conversation adds +4 dB in the 1–4 kHz speech band on top of the fitted
  curves; outdoors adds a 150 Hz low-cut (new `Biquad.highPass`). Persisted;
  segmented-button selector on the assist screen; applies on next start.
- **Experimental media EQ** (`MediaEqController` + pure `MediaEqPlanner`):
  per-ear curves applied to other apps' audio via `DynamicsProcessing` on the
  global mix (API 28+, deprecated platform behavior → explicitly experimental,
  graceful "not supported" fallback, toggle snaps back off on failure). Boosts
  capped at `SafetyConstants.MEDIA_EQ_MAX_BAND_GAIN_DB` (12 dB), never cuts,
  effect-limiter always on. New normal permission: MODIFY_AUDIO_SETTINGS.
  Settings-screen toggle; effect lives only while the process does.
All emulator-verified (stereo session ran with conversation preset; media EQ
toggle attach succeeded). 88 JVM tests green.

### Phase D — Shareable results card ✅ (2026-07-09)
First of the "viral" phases (D–H planned: share card, A/B profile demo, remote
mic, loudness meter, digits-in-noise). A "Share results" button on the completed
check screen opens a preview-then-share dialog: the visible card (app name, date,
`AudiogramChart`, "not a diagnosis" disclaimer, repo footer) is recorded via
Compose 1.7 `rememberGraphicsLayer()`/`toImageBitmap()`, written as PNG to
`cacheDir/shared/results.png`, and handed to the system share sheet through a new
`FileProvider` (one-time URI grant; still no INTERNET permission). The card is
pinned to the light theme inside a `Surface` so exports look identical from
dark-mode/high-contrast sessions (`AudiogramChart` gained an optional `darkTheme`
param, default unchanged). Emulator-verified in dark mode: preview correct,
chooser opens, no crashes.

### Phases E–H — Viral features ✅ code-complete (2026-07-09)
- **E · "Hear the difference" A/B demo**: synthesized speech-like clip (pure
  `DemoClipGenerator`, license-clean, deterministic, HF "consonant" bursts where
  profiles boost most) rendered offline through the real `StereoAssistChain`
  (`DemoRenderer`), streamed by a new stereo `AbPlayer` with a **mid-playback
  raw/processed crossfade toggle** (pure `AbBufferSource`, JVM-tested). Card on
  the results + assist screens; disabled while the mic loop runs.
- **F · Remote microphone mode** (Live Listen for any earbuds): new home
  destination reusing the assist pipeline with an `AssistMode` — raw mic tuning
  (`UNPROCESSED`→`VOICE_RECOGNITION` fallback via new `InputTuning`), 20 ms
  blocks, wake lock for screen-off, per-mode notification. **Headphones are a
  hard requirement (no "Start anyway")** because the feedback guard is bypassed
  in this mode (documented in SAFETY.md; limiter untouched). Works without a
  profile via a flat-curve fallback. **Found & fixed en route:** the guard's
  activation RMS (0.1) sat above what the WDRC can ever emit (≈0.05), so the
  guard had been inert inside the live chain — activation is now calibrated to
  post-WDRC levels (0.03) with a chain-level regression test.
- **G · Listening meter** (Headphone-Safety-inspired): lock-free post-limiter
  level tap (`OutputLevelMeter` + `MeteredAudioProcessor` wrapper — chain
  untouched), pure `ExposureTracker` (energy-based relative units, 3 dB exchange
  rate, explicitly NOT dB SPL), 1 s sampling loop in `AssistService`, daily
  rollover persisted in DataStore, card on the assist screen with a gentle
  ≥80% note.
- **H · Digits-in-noise check**: complete Smits-style engine (`DinStaircase` +
  `DigitsInNoiseScreening`, 9-digit alphabet excluding "7", simulated-listener
  convergence test ±1.5 dB), `SpeechNoiseMixer` (noise-anchored, exact SNR,
  ramped, clamped) and a pure `WavCodec`, plus the full keypad UI
  (`DinTestScreen`). **Gated off the home screen until the maintainer records
  the CC0 digit corpus** — procedure, naming, and caveats in docs/DIN.md. TTS
  was rejected (device-dependent voices make results non-comparable).
Full check green after each phase; release build verified on the emulator
(demo play/toggle/stop, live meter, remote-mic headphone gate, DIN hidden).
Remote-mic hardware items added to DEVICE_TESTING.md.

### Phase 3 — AirPods ❌ NOT STARTED
The reverse-engineered (LibrePods/CAPod) protocol is `UNVERIFIED`. `:airpods-protocol`
ships interfaces/models only. Needs real BLE/HCI capture on hardware and the
item-by-item verification checklist in [PROTOCOL.md](PROTOCOL.md). It may turn out
some AirPods features can't be driven from Android without firmware access — which
is exactly why the app is earbud-agnostic first.

## Verified vs. NOT verified

- **Verified (local):** all pure logic — staircase, fitting, codecs, DSP, the
  limiter invariant, live-gain safety, and profile persistence/migration — via
  76 JVM unit tests; debug + release APKs compile; lint clean. UI flows (check →
  chart, manual entry, profiles, speaker warning, QS tile) exercised on an API 35
  emulator.
- **NOT verified:** anything requiring hardware — actual audio playback/capture,
  assist latency, feedback behaviour with real earbuds, stereo routing, true loudness
  in dB SPL, and the entire AirPods protocol.

## Distribution posture

Alpha: sideload for testers. Public path is F-Droid first (GPLv3 FOSS fit), then
maybe Play with careful non-medical framing — only after the two release gates
(on-device validation; calibration + legal) are cleared. See RELEASE.md.

## Commit history (this work)

`be5e7c0` issue-template links → `5a46572` Phase 0 scaffold → `76eb3c9` Phase 1 →
`96e3da8` Phase 2 → `79f1e39` Phase 4 + calibration → `6aa69b8` Phase 5 →
`a3b7eab`/`d552efc`/`400fe8c` Phase A (icons, chart, strings/copy, metadata) →
`6d0f7c0`/`ac59420`/`dcda17b` Phase B (live gain, profiles, manual entry, tile).
All authored solely by the maintainer (no co-author), Conventional Commits.
