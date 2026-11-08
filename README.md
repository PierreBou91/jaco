# Murmur (working title)

Offline speech-to-text for Android. Speech recognition runs entirely on the phone with open-weight models
([Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by default) through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). The network is only used to download models.

Status: prototype, sideloaded. Grew out of the STT spike in `hub-spikes` (branch `spike/01-ocr-local`,
`spikes/03-stt/HISTORY.md` has the benchmark).

## What it does today (M0)

- **Live dictation**: Silero VAD cuts speech at pauses; the current sentence is re-transcribed about twice a
  second as a preview, then finalised. Copy, share, clear with undo.
- **Model switcher**: seven models (Parakeet, Canary, Whisper turbo and small, Qwen3-ASR, Omnilingual,
  Cohere Transcribe), with accuracy and speed measured on a Honor 90, and a spoken-language setting.
- **Model manager**: download (resumable, checksum-verified, Wi-Fi only by default, keeps running in the
  background with a notification), pause, resume, delete, pick the one used for dictation.
- **Settings**: language, end-of-sentence pause, live preview, CPU threads, keep the model in memory, timings.
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
| `engine/` | No UI. `ModelCatalog` (every model: files, sizes, SHA-256, source, benchmark), `ModelStore` (what is installed), `ModelInstaller` (download, resume, verify, unpack), `EngineHost` (keeps one model loaded, single native thread), `LiveTranscriber` (mic, VAD, previews). Meant to be shared with the future keyboard. |
| `app/` | Compose UI (`ui/dictate`, `ui/models`, `ui/settings`), settings (DataStore), downloads (WorkManager). |

### Adding a model

1. Add a `ModelSpec` to `ModelCatalog` with its files, sizes and SHA-256. Prefer a Hugging Face URL **pinned
   to a commit** (`hf(repo, commit)`): GitHub release assets get replaced under the same URL (Parakeet's was,
   on 2026-11-07). A new kind of model also needs a branch in `SherpaEngine.modelConfig`.
2. Add its description to `res/values/strings.xml` and `res/values-fr/strings.xml`, and map it in
   `ModelInfo.description`.
3. `tools/push_models.sh` if you want the dev shortcut.

## Roadmap

- **M1, the lab**: transcribe a shared audio file or voice note, benchmark screen (your voice, WER, speed).
- **M2, the keyboard**: a dictation keyboard on the same engine.
- **M3, the recorder**: long background recordings, timestamps, playback, history, search, export.
- **M4, speakers**: diarization (who spoke when) on recordings.
- Later: rewriting with a small local LLM, translation, call transcription research.

## Licences

The code of this repository is under the [Zero-Clause BSD licence](LICENSE) (0BSD): do anything with it, no
conditions.

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
