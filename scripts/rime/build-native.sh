#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")/../.."
RIME_INCLUDE="$PWD/native-deps/root/data/data/com.termux/files/usr/include"
JAVA_INCLUDE="${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}/include"
clang -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -I "$RIME_INCLUDE" -I "$JAVA_INCLUDE" -I "$JAVA_INCLUDE/linux" \
  android/app/src/main/cpp/rime/rimebridge.c -ldl -o native-deps/librimebridge.so
python scripts/rime/prepare-rime.py
