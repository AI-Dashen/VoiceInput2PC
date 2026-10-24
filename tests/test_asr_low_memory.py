from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AsrLowMemoryBuildTest(unittest.TestCase):
    def test_asr_engine_uses_low_memory_backend(self):
        source = (ROOT / "android/app/src/main/cpp/independent_asr_engine.cpp").read_text()
        self.assertGreaterEqual(source.count("Memory_Low"), 2)
        self.assertNotIn("Memory_Normal", source)

    def test_native_build_uses_dedicated_low_memory_mnn(self):
        build = (ROOT / "scripts/asr/build-independent-native.sh").read_text()
        self.assertIn("build-mnn-asr-lowmem.sh", build)
        self.assertIn("mnn-build-3.6.1-asr-lowmem/OFF", build)

    def test_mnn_build_preserves_transformer_fusion(self):
        build = (ROOT / "scripts/asr/build-mnn-asr-lowmem.sh").read_text()
        self.assertIn("-DMNN_LOW_MEMORY=ON", build)
        self.assertIn("-DMNN_SUPPORT_TRANSFORMER_FUSE=ON", build)
        self.assertIn("env -u LD_LIBRARY_PATH cmake", build)


if __name__ == "__main__":
    unittest.main()
