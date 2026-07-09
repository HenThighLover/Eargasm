# Digits-in-noise (DIN) check — corpus & procedure

The listening-in-noise check plays triplets of spoken digits in masking noise
and adapts the speech-to-noise ratio (SNR). Because the result is a *ratio*,
it is meaningful on uncalibrated consumer hardware — absolute playback level
cancels out. This is the app's one screening where "set a comfortable volume"
is scientifically sufficient.

**Status: engine + UI shipped, corpus NOT yet recorded.** The check stays off
the home screen until the audio files below exist in `app/src/main/res/raw/`
(`DigitCorpus.isAvailable()` gates it). Everything else is built and
unit-tested (`DigitsInNoiseTest`, `SpeechNoiseMixerTest`, `WavCodecTest`).

## What to record (maintainer)

One clean utterance per digit, spoken calmly at a steady level:

- Digits: **0, 1, 2, 3, 4, 5, 6, 8, 9** ("7" is excluded — the only
  two-syllable English digit, which would make triplets uneven in difficulty).
- Format: **48 kHz, mono, 16-bit PCM WAV** (`WavCodec` accepts nothing else).
- File names: `din_digit_0.wav` … `din_digit_9.wav` (skip 7), `din_noise.wav`.
- A quiet room and any decent phone/USB mic is fine for a screening-grade
  corpus. Keep a fist-width from the mic; avoid plosive blasts.

## Normalization pipeline (offline, before committing)

1. Trim leading/trailing silence (leave ~20 ms).
2. RMS-normalize every digit to the same level (e.g. `sox in.wav out.wav norm -3`
   then match RMS via `sox --norm` or a small script; the mixer re-measures RMS
   at runtime, so consistency matters more than the absolute value).
3. Generate **speech-shaped noise** from the corpus itself: concatenate all
   digits, take the long-term average spectrum, and shape white noise with it
   (classic overlap-add / FFT filtering; a ~5 s loop is enough — the mixer
   starts reads at random offsets). Save as `din_noise.wav`.
4. Listen once: no clicks, no clipping, consistent loudness across digits.

## License

Record your own voice and release the files **CC0** in this repo (state it in
the commit message). This keeps the corpus redistributable by F-Droid and
forks with zero questions. Community contributions of other languages follow
the same pipeline: the engine is language-agnostic — only the recordings and
the digit alphabet change.

## Honest caveats (also reflected in the UI copy)

- This is a self-recorded, **unvalidated** corpus: per-digit difficulty will
  not be perfectly homogeneous like a clinically calibrated DIN test. Fine for
  an "estimate" framing; per-digit level corrections can be added later inside
  `DigitCorpus` without touching the engine.
- Results are displayed only (bands of "how you did in this check"), never fed
  into the amplification profile — an SNR has no principled mapping to
  frequency-specific gain.
- The staircase reports range-pinned sessions ("beyond the range") instead of
  fabricating a number (`DinResult.pinnedAtEdge`).

## Procedure summary (what the engine does)

Smits-style adaptive DIN: start at +4 dB SNR, fixed 2 dB steps, one-down on a
fully correct triplet / one-up otherwise, 24 triplets total, SRT = mean
presented SNR of triplets 5–24. Noise is the level anchor (constant RMS at
−25 dBFS); speech scales around it. Ramps ≥ `MIN_TONE_RAMP_MS`, peak-clamped,
and played through `TonePlayer`'s limiter with instant mute — the same safety
shape as the pure-tone check.
