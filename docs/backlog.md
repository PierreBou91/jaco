# Backlog

Ideas parked for later and loose ends, so they are not lost between milestones. Not commitments: each
one gets decided when its time comes.

## Keyboard ideas to test later

- **Floating mic button.** A small button drawn over any keyboard (SwiftKey included) that switches to
  Jaco in one tap. Needs an accessibility service: Android warns strongly when it is turned on, and app
  stores look hard at apps that use one for something else than accessibility. Only worth it if the
  Quick Settings tile turns out too slow in daily use.
- **Jaco as the phone's speech service.** Implement `RecognitionService` (and an activity for
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`), so apps' own mic buttons and some keyboards use Jaco.
  The user picks it in the system settings ("Voice input"); some phones hide that screen, then only adb
  can set it. Does not help with SwiftKey (hard-wired to Google). To decide.
- **A full keyboard** (letters, suggestions, dictation in one): Pierre wants it "one day, from scratch", as a
  fully offline replacement for SwiftKey. Big job, see the discussion of 2026-11-08: the hard part is typing
  quality (autocorrect, prediction, swipe), not the plumbing.

## Fixing transcription mistakes (with the full keyboard)

Correcting a wrong word today means switching keyboards. To design together with the full keyboard:
- **Personal vocabulary** (Pierre likes this one): words Jaco often gets wrong and what they should be (names,
  jargon, "Jako" to "Jaco"), applied everywhere: keyboard, home, recordings. Maybe also as hints to models
  that accept them (hotwords).
- **Edit a sentence** in a recording (long-press to correct it).
- **Say it again**: a keyboard key that deletes the last sentence and listens again.

## Loose ends

- **Whisper "Auto"** has only been checked by unit tests: no Whisper model has been installed on a phone.
- **Very long files** (hours): no decision on limits, splitting or warnings yet.
- **Parakeet answers in English** for some non-English audio (a known Parakeet v3 problem). Done: a
  separate model for files. Not done: re-check a sentence with a model told the language when Parakeet
  answers in English while the spoken language is not English.
- **Sending during a sentence** (WhatsApp's send button tapped before the pause) sends the preview, which
  can be a little less accurate than the final text. The final text is no longer typed again, but it is
  not swapped in either.
- **Thread benchmark**: one pass on the Galaxy Z Flip6, so heat may have slowed the high thread counts a
  little. Run `ThreadsBenchmark` with `-e passes 2` on new phones before trusting a single run.
- **Recorder, not yet checked on a phone**: waiting for the charger before improving (the test phone was
  always plugged in); the notification's own Pause and Stop buttons (the screen's buttons are checked); the
  live words on the home screen with real speech (the test room was silent); a file transcribed with "Keep
  the audio" on; dictating with the keyboard while a session records (both share the microphone).
- **Speakers, first version** (M4, 2026-11-10). Not done yet:
  - a sentence spoken by two people is now cut where the voice changes and the pieces transcribed again
    (2026-11-11, see "Splitting sentences by word times" below for the faster way);
  - the saved-voice threshold rests on a small sample (12 people pairs): check it on real recordings;
  - the eight speaker colors repeat from the ninth speaker on;
  - the home screen's Copy stays plain text, without speaker names;
  - not checked on a phone: the download waiting for Wi-Fi, and a file transcribed with its audio.

## Splitting sentences by word times (speed-up of the speaker split)

Today a sentence that several people speak in is cut at the change of voice and each piece is transcribed
again (`SentenceSplit`): it works with every model, for existing recordings too, but costs a new
transcription of those pieces (about 3 minutes for a 68 minute call with Parakeet on the Galaxy Z Flip6,
more with Cohere). Faster, for new recordings: keep each word's time while transcribing (sherpa-onnx gives
token timestamps for transducer models such as Parakeet; to check for Cohere, Canary and Whisper), store them
with the segments, and split the text at the word nearest the change of voice, with no new transcription.
Costs: a bigger `item.json` (a time per word), a fallback to the current way for models without times, and
care at the cut (a word straddling the change). Worth it if the split ever feels slow, or to split live
versions as they are recorded.
