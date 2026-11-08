# stt_app (Jaco)

Offline Android speech-to-text, Kotlin + Compose. Read README.md first.

- English for code, comments, docs, commits. UI copy lives in `res/values` (English) and `res/values-fr`
  (French): every string goes in both.
- Never put em or en dashes in anything a user reads (UI strings, notifications, store text).
- UI: dark theme only, orange accent, Inter. Reuse `ui/theme` and `ui/components`; keep flows practical
  over clever.
- Model files are pinned by size and SHA-256 in `ModelCatalog`; never point a model at a mutable URL
  without hashes.
- Build with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`. Test on the Honor 90 over wireless adb
  (pairing recipe in the auto-memory); Honor logcat is gagged, read crashes with `adb shell dumpsys dropbox --print`.
- Sideload only until Pierre says the app is production-ready.
