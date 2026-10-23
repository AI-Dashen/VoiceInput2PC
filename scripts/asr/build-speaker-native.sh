#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
BUILD="$ROOT/build/speaker-runtime"
RUNTIME="$BUILD/sherpa-onnx-v1.13.8-android-aarch64-termux-shared"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/android/app/src/main/assets/speaker-diarization"
# Downloads are prepared explicitly; never silently swap runtime/version/models.
test -f "$RUNTIME/include/sherpa-onnx/c-api/c-api.h"
test -f "$BUILD/embedding.onnx"
printf '%s  %s\n' ad7bac42adc6295293fe6d36475936afb227cdc757cf293c02a706f6aca4e0a2 "$BUILD/runtime.tar.bz2" 2a1b95084be8fd1deb3228fcad2fd3f7f0258b64582f7402281ec174c7b7f4ce "$RUNTIME/include/sherpa-onnx/c-api/c-api.h" | sha256sum -c -
mkdir -p "$OUT" "$ASSETS"
printf '%s  %s\n' aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2 "$BUILD/embedding.onnx" d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d "$BUILD/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" | sha256sum -c -
clang++ -O2 -fPIC -std=c++17 -I"$RUNTIME/include" -I"$PREFIX/include" -I"$PREFIX/lib/jvm/java-21-openjdk/include" -I"$PREFIX/lib/jvm/java-21-openjdk/include/linux" -shared -Wl,--no-undefined -Wl,-soname,libspeakerdiarization.so -Wl,-rpath,'$ORIGIN' "$ROOT/android/app/src/main/cpp/speaker_diarization_jni.cpp" -L"$RUNTIME/lib" -lsherpa-onnx-c-api -o "$OUT/libspeakerdiarization.so"
cp "$RUNTIME/lib/libsherpa-onnx-c-api.so" "$RUNTIME/lib/libonnxruntime.so" "$OUT/"
# Existing ASR uses Termux libc++; NEVER overwrite it from another archive.
cp "$BUILD/embedding.onnx" "$ASSETS/embedding.onnx"
cp "$BUILD/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" "$ASSETS/model.int8.onnx"
clang++ -O2 -std=c++17 -DSPEAKER_DIARIZATION_CLI -I"$RUNTIME/include" -I"$PREFIX/include" -I"$PREFIX/lib/jvm/java-21-openjdk/include" -I"$PREFIX/lib/jvm/java-21-openjdk/include/linux" "$ROOT/android/app/src/main/cpp/speaker_diarization_jni.cpp" -L"$RUNTIME/lib" -lsherpa-onnx-c-api -Wl,-rpath,"$RUNTIME/lib" -o "$BUILD/diarize-pcm"
readelf -d "$OUT/libspeakerdiarization.so" | rg NEEDED
