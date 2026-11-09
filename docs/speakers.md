# Speakers (M4): how the models were chosen

Jaco finds who spoke when (diarization) with sherpa-onnx: pyannote segmentation 3.0 finds where each voice
speaks, a voice embedding model tells the voices apart. This note records the measurements behind the
choice, so it can be redone when new models come out.

## Test set

AMI meeting corpus, test meetings ES2004a, IS1009a, TS3003a, EN2002a: the first 15 minutes of each (one hour
in all), far-field microphone (`Array1-01`, like a phone on a table), four people per meeting. Reference
speaker labels from pyannote's AMI-diarization-setup (`only_words`). Score: diarization error rate (DER: missed
speech, false speech and wrong speaker, as a share of the speech), with a 0.25 s collar. PC, 4 threads per run.

Jaco's own recordings were also looked at (a 37 min work call on a loudspeaker), but without labels they
can only be read, not scored.

## Whole file at once (sherpa-onnx as is)

| Voice model | Size | Known count (4) | Best found count | Threshold |
|---|---|---|---|---|
| 3D-Speaker CAM++ zh+en advanced | 28 MB | 31.3% | **28.2%** | 1.1 |
| NeMo TitaNet small | 40 MB | **28.8%** | 31.9% | 1.2 |
| 3D-Speaker ERes2Net en | 26 MB | 32.1% | 43.4% | 0.9 |
| WeSpeaker ResNet34 en | 27 MB | 45.7% | 78% or worse | 0.3 to 0.5 |
| 3D-Speaker CAM++ en | 30 MB | 58.1% | 47.2% (one speaker for all) | 0.9 to 1.3 |

The threshold scale differs per model: each one needs its own value.

## In chunks, as the phone does it

An hour of audio is 230 MB of samples: too much for an app's memory. Jaco diarizes 5 minute chunks, gives
each chunk's speakers one voice (from up to 30 s of their speech), and groups those voices across chunks
(average linkage on cosine distance, threshold below).

| Voice model | Chunk threshold | Match distance | Jaco counts | Told 4 people |
|---|---|---|---|---|
| **NeMo TitaNet small** (chosen) | 1.0 | 0.45 | **23.9%** | **24.3%** |
| 3D-Speaker CAM++ zh+en advanced | 0.9 | 0.45 | 27.4% | 29.9% |
| 3D-Speaker ERes2Net en | 0.9 | 0.45 | 26.5% | 39.6% |

(Best rows of a sweep over match distances 0.15 to 0.55. Only speakers with at least 5 s of speech in a chunk
are grouped; shorter bits join the closest person. Without that step, telling Jaco the count made it worse,
about 38%, because short bits took places meant for real people.)

Chunks do better than the whole file here: within 5 minutes the segmentation's clustering has fewer voices
to separate, and the voice of each chunk speaker is computed from up to 30 s of their speech.

Settings in the app: `SpeakerModels` (`CHUNK_THRESHOLD = 1.0`, `LINK_DISTANCE = 0.45`) and `Diarizer`
(`CHUNK_S = 300`, `VOICE_S = 30`, `MIN_PIECE_S = 5`).

## On the phone

Galaxy Z Flip6, 4 threads: a 37 minute call takes about 5.5 minutes (about 9 minutes per hour of audio),
in the background after the recording. Memory: one 5 minute chunk (19 MB of samples) at a time.

A voice model fed minutes of audio at once crashes (ONNX Runtime broadcast error in TitaNet): Jaco never
gives it more than 30 s.

## Recognising saved voices

Same people in two meetings of the same AMI group (ES2004 a/b, IS1009 a/b, TS3003 a/b), each person's
voice from 60 s of their speech: 12 same-person pairs, 36 different-person pairs.

| Similarity threshold | Same person recognised | Wrong name |
|---|---|---|
| 0.55 | 100% | 11% |
| 0.60 | 92% | 3% |
| **0.65** (chosen) | 83% | 3% |
| 0.70 | 83% | 0% |

A wrong name is worse than "Speaker 3", so the threshold is on the strict side (`MATCH_SIMILARITY`).
A small sample: worth checking again on real recordings once a few voices are saved. A saved voice learns
from every recording it is named in, which should make it more reliable over time.

## Licences

pyannote segmentation 3.0: MIT (CNRS). TitaNet small: CC-BY-4.0 (NVIDIA). Both are credited in the app
(Settings, Open-source licences).

