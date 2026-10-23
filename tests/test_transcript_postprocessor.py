import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CPP = ROOT / "android/app/src/main/cpp"


class TranscriptPostprocessorTests(unittest.TestCase):
    def test_token_level_annotation_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = pathlib.Path(directory) / "transcript-postprocessor-test"
            subprocess.run(
                [
                    "clang++",
                    "-std=c++17",
                    "-O2",
                    f"-I{CPP}",
                    str(CPP / "asr_transcript_postprocessor.cpp"),
                    str(ROOT / "tests/transcript_postprocessor_test.cpp"),
                    "-o",
                    str(binary),
                ],
                check=True,
            )
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
