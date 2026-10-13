import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class InputSessionContractTests(unittest.TestCase):
    def test_plain_java_contract(self):
        with tempfile.TemporaryDirectory() as build:
            source = ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc/InputSession.java'
            test = ROOT / 'tests/InputSessionTest.java'
            subprocess.run(['javac', '-d', build, str(source), str(test)], check=True)
            result = subprocess.run(
                ['java', '-cp', build, 'io.github.amxooo.voiceinput2pc.InputSessionTest'],
                check=True, capture_output=True, text=True)
            self.assertIn('input session checks passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
