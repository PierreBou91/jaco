#!/usr/bin/env bash
# Dev shortcut: copy models already on this computer (~/models/stt, see the hub-spikes STT spike) into the
# app on a phone, instead of downloading them in the app. The app picks them up when it comes to the
# foreground: a model counts as installed when every file has the expected size.
#
# Usage: tools/push_models.sh <adb serial> <model id>...   e.g. tools/push_models.sh 192.168.1.40:42681 canary-180m-flash
set -euo pipefail
SERIAL="$1"; shift
SRC="${MODELS_DIR:-$HOME/models/stt}"
DEST=/sdcard/Android/data/dev.pbou.stt/files/models
for id in "$@"; do
  case "$id" in
    parakeet-tdt-0.6b-v3) dir=sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8; files="encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt" ;;
    canary-180m-flash) dir=sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8; files="encoder.int8.onnx decoder.int8.onnx tokens.txt" ;;
    whisper-large-v3-turbo) dir=sherpa-onnx-whisper-turbo; files="turbo-encoder.int8.onnx turbo-decoder.int8.onnx turbo-tokens.txt" ;;
    whisper-small) dir=sherpa-onnx-whisper-small; files="small-encoder.int8.onnx small-decoder.int8.onnx small-tokens.txt" ;;
    qwen3-asr-0.6b) dir=sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25; files="conv_frontend.onnx encoder.int8.onnx decoder.int8.onnx tokenizer" ;;
    omnilingual-asr-300m) dir=sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05; files="model.int8.onnx tokens.txt" ;;
    cohere-transcribe-2b) dir=sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01; files="encoder.int8.onnx encoder.int8.onnx.data decoder.int8.onnx tokens.txt" ;;
    *) echo "unknown model id: $id" >&2; exit 1 ;;
  esac
  adb -s "$SERIAL" shell mkdir -p "$DEST/$id"
  for f in $files; do adb -s "$SERIAL" push "$SRC/$dir/$f" "$DEST/$id/"; done
  echo "$id pushed"
done
