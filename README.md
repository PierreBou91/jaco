# Jaco

Offline speech-to-text for Android. Speech recognition runs entirely on the phone with open-weight models
([Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by default) through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). The network is only used to download models.

Status: prototype, sideloaded. Grew out of the STT spike in `hub-spikes` (branch `spike/01-ocr-local`,
`spikes/03-stt/HISTORY.md` has the benchmark).

## What it does today (M0, M1)

- **Live dictation**: Silero VAD cuts speech at pauses; the current sentence is re-transcribed about twice a
  second as a preview, then finalised. Copy, share, clear with undo.
- **Model switcher**: seven models (Parakeet, Canary, Whisper turbo and small, Qwen3-ASR, Omnilingual,
  Cohere Transcribe), with accuracy and speed measured on a Honor 90, and a spoken-language setting (models like Whisper can also detect it: "Auto").
- **Model manager**: download (resumable, checksum-verified, Wi-Fi only by default, keeps running in the
  background with a notification), pause, resume, delete, pick the one used for dictation.
- **Transcribe a file** (M1): share a voice note, recording or video to the app ("Transcribe" in the share
  sheet, or "Open with"), or pick one. Decoded with Android's codecs (Opus/OGG, M4A/AAC, MP3, WAV, video
  sound...), resampled to 16 kHz, cut at pauses and transcribed sentence by sentence with timestamps.
  Streams, so long files are not held in memory; another model can be picked to transcribe it again. Files
  can use their own model (Settings > Model for files), e.g. one told the language for casual voice notes.
- **Benchmark** (M1): read six sentences (French or English) once; every installed model then transcribes
  them on the phone. Shows word errors, the wait after each sentence, speed and load time, with the
  transcripts. Model cards show your measured result, or an estimated wait from the reference benchmark.
- **Keyboard** (M2): a voice-only keyboard for any app. Turn it on in Settings > Keyboard, then switch to it
  from any text field. It listens as soon as it opens; the sentence being spoken shows underlined in the
  field and is fixed at each pause. Keys: switch back, delete, microphone, space, and the field's action
  (send, search...), which waits for the last sentence before sending. Also a "voice" keyboard for
  keyboards whose microphone key opens one (AOSP, HeliBoard). Frees the model 5 minutes after last use.
  A Quick Settings tile opens the keyboard chooser from any app: SwiftKey has no key to switch keyboards
  and its voice typing only accepts Google's recognisers, and gesture navigation hides the system's button.
  Jaco then opens in the field by itself. With a permission given once from a computer
  (`adb shell pm grant dev.pbou.stt android.permission.WRITE_SECURE_SETTINGS`), the tile switches in one
  tap, to Jaco and back.
- **Recorder and Library** (M3): a Library tab with every recording and transcribed file, newest first, with
  search (accents and case ignored). Record a meeting or a lecture: each sentence is transcribed at its pause
  with the dictation model and saved at once, in the background with a notification (pause, resume, stop),
  screen off or in another app. No word-by-word preview while recording, to save battery over hours.
  "Keep the audio" (off by default) saves it as M4A (AAC, 48 kbps, about 21 MB per hour), written so that a
  crash or a dead battery loses nothing already recorded. With the audio: playback (tap a sentence to hear
  it), share the audio, transcribe again with another model, and "Improve while charging": a bigger model
  (e.g. Cohere) transcribes it again when the phone is plugged in; the live version stays one tap away.
  Export as plain text or text with times. Rename, delete.
- **Settings**: keyboard, language, model for files, end-of-sentence pause, live preview, CPU cores (defaults
  to the count measured fastest, usually 4: more cores is slower on phones), keep the model in memory, timings.
- Dark theme with an orange accent, Inter font, English and French UI.

## Build

Requirements: JDK 21 (Gradle/AGP do not support 25 yet), Android SDK platform 37.

```bash
tools/fetch_deps.sh                      # once: sherpa-onnx AAR into third_party/maven (not on Maven Central)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:testDebugUnitTest      # catalog and installer tests (resume, checksums, archives)
adb -s <ip:port> install -r app/build/outputs/apk/debug/app-debug.apk
```

Dev shortcut, skipping the in-app download: `tools/push_models.sh <ip:port> parakeet-tdt-0.6b-v3` copies
models from `~/models/stt` into the app.

## Layout

| Module | Role |
|---|---|
| `engine/` | No UI. `ModelCatalog` (every model: files, sizes, SHA-256, source, benchmark), `ModelStore` (what is installed), `ModelInstaller` (download, resume, verify, unpack), `EngineHost` (keeps one model loaded, single native thread), `LiveTranscriber` (mic, VAD, previews), `FileTranscriber`, `Benchmark`. Shared by the app and the keyboard. |
| `app/` | Compose UI (`ui/dictate`, `ui/file`, `ui/bench`, `ui/models`, `ui/settings`), the keyboard (`keyboard/`, an `InputMethodService` with a Compose panel), settings (DataStore), downloads (WorkManager). |

### Adding a model

1. Add a `ModelSpec` to `ModelCatalog` with its files, sizes and SHA-256. Prefer a Hugging Face URL **pinned
   to a commit** (`hf(repo, commit)`): GitHub release assets get replaced under the same URL (Parakeet's was,
   on 2026-11-07). A new kind of model also needs a branch in `SherpaEngine.modelConfig`.
2. Add its description to `res/values/strings.xml` and `res/values-fr/strings.xml`, and map it in
   `ModelInfo.description`.
3. `tools/push_models.sh` if you want the dev shortcut.

## Roadmap

- **M1, the lab**: done (benchmark, transcribe a file).
- **M2, the keyboard**: first version done (voice-only keyboard); next: polish from daily use.
- **M3, the recorder**: first version done (recorder, Library, playback, search, export, improve while charging).
- **M4, speakers**: diarization (who spoke when) on recordings.
- Later: rewriting with a small local LLM, translation, call transcription research.

Ideas parked for later and loose ends: [docs/backlog.md](docs/backlog.md).

## Licences

Copyright 2026 Pierre Bouriat. The code of this repository is under the [Apache License 2.0](LICENSE): use,
change and redistribute it freely, keeping the licence and the [NOTICE](NOTICE) file. It also grants the patent
rights needed to use the code.

Third-party files committed here keep their own licences (texts in `third_party/licenses/`):

| Files | Origin | Licence |
|---|---|---|
| `engine/src/main/assets/vad/silero_vad.onnx` | Silero VAD, Silero Team | MIT (`Silero-VAD-MIT.txt`) |
| `app/src/main/res/font/inter_*.ttf` | Inter, Rasmus Andersson | SIL Open Font License 1.1 (`Inter-OFL.txt`) |
| `app/src/main/res/drawable/ic_*.xml` (except `ic_launcher_*`) | Material Symbols, Google | Apache 2.0 (`Apache-2.0.txt`) |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle | Apache 2.0 |

Dependencies fetched at build time (sherpa-onnx, AndroidX, Kotlin coroutines, Apache Commons Compress) are under
Apache 2.0, ONNX Runtime (inside sherpa-onnx) under MIT. Models are not in this repository; each keeps its own
licence (CC-BY-4.0, MIT or Apache 2.0), listed in the app under Settings, Open-source licences.
