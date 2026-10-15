import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
class KeyboardTests(unittest.TestCase):
    def test_ime_scopes_preview_to_exact_editor(self):
        source = (ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc/UniversalInputMethodService.java').read_text()
        self.assertIn('if (!fieldKey(info).equals(session.identity().targetId)) beginEditor(info,restarting);', source)
        self.assertIn('session.begin(editorKey,', source)
        self.assertNotIn('session.begin("android-current-field"', source)

    def test_ime_bugfix_and_secondary_surface_contract(self):
        source = (ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc/UniversalInputMethodService.java').read_text()
        recognizer = (ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc/OfflineSpeechRecognizer.java').read_text()
        self.assertIn('commitPreviewBeforeModeSwitch()', source)
        self.assertIn('deleteSurroundingTextInCodePoints(1,0)', source)
        self.assertIn('deleteSurroundingText(1,0)', source)
        self.assertIn('KEYCODE_DEL', source)
        self.assertIn('跨设备传输', source)
        self.assertIn('输入记录', source)
        self.assertIn('输入法设置', source)
        self.assertIn('RESULT_TIMEOUT_MS=15000', recognizer)
        self.assertIn('本地识别超时，已恢复，可重新说', recognizer)
        self.assertIn('if (busy()) interruptRecognitionForModeSwitch();', source)
        self.assertIn('String retained=visibleVoiceDraft();', source)
        self.assertIn('识别异常，当前文字已保留，可继续说或切换键盘', source)

    def test_controller_and_preview_contract(self):
        java = ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc'
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac', '-d', out, *(str(java / x) for x in ['ChineseInputEngine.java', 'KeyboardController.java', 'InputSession.java', 'ImePanelController.java', 'OutputTargetController.java', 'ImeLayoutSpec.java', 'SpeechEndpointDetector.java', 'VoiceDraft.java']), str(ROOT / 'tests/KeyboardControllerTest.java'), str(ROOT / 'tests/ImePanelControllerTest.java'), str(ROOT / 'tests/OutputTargetControllerTest.java'), str(ROOT / 'tests/ImeLayoutSpecTest.java'), str(ROOT / 'tests/SpeechEndpointDetectorTest.java'), str(ROOT / 'tests/VoiceDraftTest.java')], check=True)
            keyboard = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.KeyboardControllerTest'], check=True, text=True, capture_output=True)
            self.assertIn('keyboard checks passed', keyboard.stdout)
            panels = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.ImePanelControllerTest'], check=True, text=True, capture_output=True)
            self.assertIn('IME panel checks passed', panels.stdout)
            targets = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.OutputTargetControllerTest'], check=True, text=True, capture_output=True)
            self.assertIn('output target checks passed', targets.stdout)
            layout = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.ImeLayoutSpecTest'], check=True, text=True, capture_output=True)
            self.assertIn('IME layout checks passed', layout.stdout)
            endpoint = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.SpeechEndpointDetectorTest'], check=True, text=True, capture_output=True)
            self.assertIn('speech endpoint checks passed', endpoint.stdout)
            draft = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.VoiceDraftTest'], check=True, text=True, capture_output=True)
            self.assertIn('voice draft checks passed', draft.stdout)
if __name__ == '__main__': unittest.main()
