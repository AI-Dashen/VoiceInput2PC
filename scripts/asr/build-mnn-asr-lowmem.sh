#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PROJECT=$(CDPATH= cd -- "$ROOT/.." && pwd)
SOURCE="$PROJECT/.runtime/MNN-3.6.1"
BUILD="$PROJECT/.runtime/mnn-build-3.6.1-asr-lowmem"

if [ ! -f "$SOURCE/CMakeLists.txt" ]; then
  echo "MNN 3.6.1 source missing: $SOURCE" >&2
  exit 2
fi

env -u LD_LIBRARY_PATH cmake -S "$SOURCE" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DMNN_BUILD_SHARED_LIBS=ON \
  -DMNN_ARM82=ON \
  -DMNN_USE_LOGCAT=ON \
  -DMNN_KLEIDIAI=ON \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_SUPPORT_QUANT_EXTEND=ON \
  -DMNN_LOW_MEMORY=ON \
  -DMNN_BUILD_TEST=OFF \
  -DMNN_BUILD_TOOLS=OFF \
  -DMNN_BUILD_CONVERTER=OFF \
  -DMNN_BUILD_DEMO=OFF \
  -DMNN_OPENCL=OFF \
  -DMNN_VULKAN=OFF \
  -DMNN_SEP_BUILD=OFF

env -u LD_LIBRARY_PATH cmake --build "$BUILD" --target MNN -j "${MNN_BUILD_JOBS:-4}"

grep -q '^MNN_LOW_MEMORY:BOOL=ON$' "$BUILD/CMakeCache.txt"
grep -q '^MNN_SUPPORT_TRANSFORMER_FUSE:BOOL=ON$' "$BUILD/CMakeCache.txt"
test -s "$BUILD/OFF/libMNN.so"
sha256sum "$BUILD/OFF/libMNN.so"
