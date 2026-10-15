#!/data/data/com.termux/files/usr/bin/sh
# Run only after user approval for the listed official packages/resources.
set -eu
cd "$(dirname "$0")/../.."
mkdir -p native-deps/packages native-deps/root
cd native-deps/packages
apt download librime=1.17.0-1 gflags=2.3.1 google-glog=0.7.1-2 libsnappy=1.3.1 leveldb=1.23-4 marisa=0.3.1-1 libopencc=1.4.2 libyaml-cpp=0.9.0
for package in ./*.deb; do dpkg-deb -x "$package" ../root; done
