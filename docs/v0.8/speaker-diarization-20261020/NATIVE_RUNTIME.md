# Local speaker diarization runtime (2026-10-20)

This component uses official sherpa-onnx v1.13.8 Android aarch64 Termux shared release, CPU execution, two inference threads, pyannote segmentation-3.0 int8 + Chinese/English CAM++ speaker embeddings. It does not replace or alter the existing ASR model/runtime. It performs speaker segmentation/clustering only, not transcription, identity recognition or emotion inference.

## API

`SpeakerDiarizationNative.prepareAssets(context)` validates size + SHA256 and atomically copies packaged model assets to the app's private files. Returns the model directory. Existing valid files are reused.

`SpeakerDiarizationNative.diarize(dir, rawPcm16Path, numSpeakers, threshold)` returns a sorted JSON array with start_ms/end_ms, zero-based `speaker`, real upstream clustering `confidence` and an `uncertain` flag. Input is raw signed 16-bit little-endian mono 16kHz PCM, not a WAV container. File size must be positive, even, and <= 60 minutes. numSpeakers=0 enables automatic clustering. A positive number overrides the threshold. Supported range 0..32. Threshold must be finite and in (0,2).

Call off the UI thread and serialize alongside ASR. This synchronous native operation cannot be interrupted mid-inference: upstream explicitly ignores the progress callback return value. Cancellation must preserve the recording, discard stale results, and only release the model/ASR resource lease after native inference returns. Do not terminate arbitrary processes to cancel.

`uncertain=true` means overlapping different speaker segments, low score (<0.25) or unavailable score. Confidence is not a calibrated accuracy probability. Do not label it as a percentage. Human correction remains necessary.

## Rebuild

The independent script `scripts/asr/build-speaker-native.sh` expects release archive extracted under `build/speaker-runtime/sherpa-onnx-v1.13.8-android-aarch64-termux-shared`, official embedding.onnx under build/speaker-runtime, and segmentation archive extracted alongside. It verifies the two model hashes, compiles JNI + a CLI test entry from the same source, and copies only the C API/ONNX runtime libraries and model assets. It never changes the existing libc++_shared.so or any existing ASR library. Source/runtime download URLs and hashes are in the packaged manifest.

Pinned C API header SHA256: 2a1b95084be8fd1deb3228fcad2fd3f7f0258b64582f7402281ec174c7b7f4ce. The archive header and tagged upstream header are identical. JNI links only sherpa C API plus existing Android/Termux libc++ dependencies.

## Licensing

Official pyannote conversion archive includes the CNRS MIT license, copied into packaged licenses. Its README links to https://huggingface.co/pyannote/segmentation-3.0/tree/main. Embedding model card is fetched from the official ModelScope API for iic/speech_campplus_sv_zh_en_16k-common_advanced and declares Apache License 2.0. The card is preserved alongside 3D-Speaker Apache license, sherpa-onnx Apache license and ONNX Runtime MIT notice. Model binaries are Git ignored; metadata/licenses remain tracked source inputs. This is not a claim that model training data have been independently audited.

## Real native tests

- Official 0-four-speakers-zh.wav: mono 16kHz PCM, duration 56.861 seconds.
- Official CLI known four-speaker mode: 5.353s process time, 10 detected segments, four labels.
- JNI exact core source CLI known four-speaker mode: exit 0, 5.805s total process wall time (includes model construction), maximum RSS 137244 KiB, 10 segments/four labels. Output `build/speaker-runtime/jni-core-test.json`.
- Unknown speaker count threshold 0.9: exit 0, 6.089s, 10 segments, three labels (under-separation vs known four).
- Unknown speaker count threshold 0.5: exit 0, 6.074s, six labels present (over-separation). IDs need not be contiguous after short segments discarded.
- Unknown speaker count threshold 0.75: exit 0, 5.927s, maximum RSS 137308 KiB, four labels/10 segments on this fixture; not a universal accuracy guarantee.
- Invalid odd PCM, empty PCM, speaker count 33 and NaN threshold all rejected with exit 1 before inference.

This proves actual phone Termux native inference, not Android app process integration/UI/device acceptance. Unknown-count accuracy requires recording-specific evaluation; correct known speaker count is recommended if available. Native fixture results cannot establish arbitrary meetings/overlap accuracy.
