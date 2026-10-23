#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PROJECT=$(CDPATH= cd -- "$ROOT/.." && pwd)
RUNTIME="$PROJECT/.runtime"
MNN="$RUNTIME/MNN-3.6.1"
MNNLIB="$RUNTIME/mnn-build-3.6.1-clean/OFF"
CPP="$ROOT/android/app/src/main/cpp"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
BUILD="$ROOT/build/independent-asr-native"
mkdir -p "$BUILD" "$OUT"
CC=${CC:-clang}; CXX=${CXX:-clang++}
COMMON="-O3 -fPIC -ffp-contract=off --target=aarch64-linux-android26 -DANDROID"
$CC $COMMON -std=c11 -I"$CPP" -c "$CPP/frontend_log_reference.c" -o "$BUILD/frontend_log_reference.o"
$CC $COMMON -std=c11 -I"$CPP" -c "$CPP/fft512_radix4_reference.c" -o "$BUILD/fft512_radix4_reference.o"
$CXX $COMMON -std=c++17 -I"$CPP" -I"$PREFIX/include" -I"$MNN/include" -I"$MNN/source" -I"$MNN/schema/current" -c "$CPP/independent_asr_engine.cpp" -o "$BUILD/independent_asr_engine.o"
$CXX $COMMON -std=c++17 -I"$CPP" -c "$CPP/asr_transcript_postprocessor.cpp" -o "$BUILD/asr_transcript_postprocessor.o"
$CXX $COMMON -std=c++17 -I"$CPP" -c "$CPP/independent_decoder_runtime.cpp" -o "$BUILD/independent_decoder_runtime.o"
for source in projection_lut_kernel decoder_norm_kernel decoder_rope_kernel incremental_attention_kernel decoder_vector_kernel gelu_native_kernel; do
  $CC $COMMON -std=c11 -I"$CPP" -c "$CPP/$source.c" -o "$BUILD/$source.o"
done
$CXX $COMMON -std=c++17 -I"$CPP" -I"$PREFIX/include" -I"$PREFIX/lib/jvm/java-21-openjdk/include" -I"$PREFIX/lib/jvm/java-21-openjdk/include/linux" -c "$CPP/independent_asr_jni.cpp" -o "$BUILD/independent_asr_jni.o"
$CXX -shared -Wl,--no-undefined -Wl,-soname,libindependentasr.so -Wl,-rpath,'$ORIGIN' \
  "$BUILD/independent_asr_engine.o" "$BUILD/asr_transcript_postprocessor.o" "$BUILD/independent_decoder_runtime.o" "$BUILD/independent_asr_jni.o" "$BUILD/frontend_log_reference.o" "$BUILD/fft512_radix4_reference.o" \
  "$BUILD/projection_lut_kernel.o" "$BUILD/decoder_norm_kernel.o" "$BUILD/decoder_rope_kernel.o" "$BUILD/incremental_attention_kernel.o" "$BUILD/decoder_vector_kernel.o" "$BUILD/gelu_native_kernel.o" \
  -L"$MNNLIB" -lMNN -llog -landroid -lm -ldl -o "$OUT/libindependentasr.so"
cp "$MNNLIB/libMNN.so" "$OUT/libMNN.so"
# MNN is built with Termux's current Android libc++ ABI. Packaging a
# different libc++_shared.so (for example the older copy contributed by an
# AAR) makes Android's linker reject libMNN before the ASR engine can start.
# Keep the runtime and the engine on the exact same C++ ABI.
cp "$PREFIX/lib/libc++_shared.so" "$OUT/libc++_shared.so"
readelf -d "$OUT/libindependentasr.so" | grep NEEDED
sha256sum "$OUT/libindependentasr.so" "$OUT/libMNN.so" "$OUT/libc++_shared.so"
