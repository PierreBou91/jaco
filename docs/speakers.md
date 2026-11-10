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

These first settings (`CHUNK_THRESHOLD = 1.0`, `LINK_DISTANCE = 0.45`) were replaced on 2026-11-10, see
below.

## Many speakers (2026-11-10)

Pierre's meetings have up to 12 people, and AMI has only 4. Second test set: 9 VoxConverse test recordings
(TV debates and news, YouTube, CC-BY-4.0, labels v0.3, taken from the `diarizers-community/voxconverse` copy)
with 2, 2, 8, 10, 11, 12, 15, 15 and 21 speakers, 124 minutes, plus the same 4 AMI meetings: 3 hours in all.
Same scoring (collar 0.25 s). Scripts: `bench.py` (PC) and the `SpeakersBenchmark` instrumented test (phone).

Bigger voice models, same pipeline, each at its best match distance:

| Voice model | Size | AMI | VoxConverse | Flip, minutes per hour of audio |
|---|---|---|---|---|
| TitaNet small (in use) | 40 MB | 23.9% | 15.9% | 20 |
| TitaNet large | 101 MB | 26.5% | 13.4% | 54 |
| 3D-Speaker ERes2NetV2 (zh-cn) | 71 MB | 39.8% | 27.0% | not measured |
| WeSpeaker ResNet293 | 114 MB | stopped: over an hour of PC time for 3 hours of audio, far too slow for a phone | | |

(Flip: 20 minute show with 12 speakers, 4 threads. Dense speech costs more than a call: about 9 minutes
per hour on Pierre's 37 minute call.)

The voice model was not the problem: all of them found far too few people on the big shows (2 found for 15).
The grouping was. Lower thresholds inside a chunk (sherpa-onnx's clustering keeps more voices apart),
TitaNet small:

| Chunk threshold, match distance | AMI | VoxConverse | VoxConverse, told the count |
|---|---|---|---|
| 1.0, 0.45 (before) | 23.9% | 15.9% | 33.3% |
| 0.8, 0.5 | 24.7% | 10.5% | |
| **0.7, 0.5 (in use)** | 24.8% | **9.6%** | 13.7% |
| 0.6, 0.5 | 28.1% | 9.0% | 11.4% |
| 0.5, 0.5 | 28.2% | 8.9% | 10.5% |

Below 0.7, meetings get split into too many people (7 to 11 found for 4). Speakers found on the big shows,
real/found: before 12/8, 10/7, 15/7, 15/1, 11/7, 21/18; now 12/8, 10/7, 15/8, 15/6, 11/10, 21/20. The
5 s minimum per chunk (`MIN_PIECE_S`) still does best: 0 and 2 s were tried. Same speed: only thresholds.

Settings in the app: `SpeakerModels` (`CHUNK_THRESHOLD = 0.7`, `LINK_DISTANCE = 0.5`) and `Diarizer`
(`CHUNK_S = 300`, `VOICE_S = 30`, `MIN_PIECE_S = 5`).

## Sentences shared by several people (2026-11-11)

A sentence ends after 0.8 s of silence. On calls with quick replies that pause rarely comes: in Pierre's five
calls the typical sentence lasted 6 to 17 s, and 9% to 36% of the speech was in sentences where a second
person spoke at least a fifth of the time, all of it given to one person.

Now, once the speakers are found, each such sentence is cut in the middle of the gap between two voices
(`SpeakerText.cuts`: pieces of at least 1 s, so a quick "yes" stays inside), and each piece is transcribed
again with its version's model and language (`SentenceSplit`). On the 68 minute call (Parakeet): 258
sentences became 365, mixed speech went from 36% to 2%, in about 3 minutes on the Flip. Versions made later
(transcribe again, improve while charging) are split the same way. A faster way, for later: docs/backlog.md.

## Other systems

- pyannote Community-1 (CC-BY-4.0, no speaker limit, VBx clustering with PLDA): the candidate left; not
  measured yet (its files are gated on Hugging Face). Bringing it to the phone means writing VBx in Kotlin.
- DiariZen (BUT): weights CC-BY-NC-4.0, Reverb (Rev): non-production licence. Not allowed in a free app.
- NVIDIA Sortformer: 4 speakers at most.

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

