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

    def test_dock_keeps_platform_and_recovery_boundaries(self):
        source = (ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc/UniversalInputMethodService.java').read_text()
        recognizer = (ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc/OfflineSpeechRecognizer.java').read_text()
        # Platform escape hatches and recognizer watchdog remain production requirements.
        for required in ('deleteSurroundingTextInCodePoints(1,0)', 'deleteSurroundingText(1,0)',
                         'KEYCODE_DEL', 'showInputMethodPicker', '跨设备传输', '输入记录', '输入法设置'):
            self.assertIn(required, source, required)
        self.assertIn('RESULT_TIMEOUT_MS=60000', recognizer)
        self.assertIn('hardReset(g,"本地识别超时，语音子进程已恢复，请重新说")', recognizer)
        # A floating IME must not gain global overlay or root control to implement the dock.
        manifest = (ROOT / 'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn('SYSTEM_ALERT_WINDOW', manifest)
        self.assertNotIn('TYPE_APPLICATION_OVERLAY', source)
        self.assertIn('TOUCHABLE_INSETS_REGION', source)
        # Old preview-specific strings are replaced by runtime editor assertions.
        runtime = (ROOT / 'android/app/src/androidTest/java/io/github/amxooo/voiceinput2pc/VoiceDockIntegrationTest.java').read_text()
        for scenario in ('testFinalDirectlyWritesHostOnceAndNeverSends',
                         'testRejectedHostCommitRetainsRecoverableText',
                         'testRemoteFinalDoesNotTouchHostOrAutoSend',
                         'testMovingCursorAfterPartialPreservesHostTextAndRejectsLateFinal'):
            self.assertIn(scenario, runtime)

    def test_voice_dock_policy_behavior(self):
        java = ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc'
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac', '-d', out, str(java / 'VoiceDockPolicy.java'),
                            str(ROOT / 'tests/VoiceDockPolicyTest.java')], check=True)
            result = subprocess.run(['java', '-cp', out, 'VoiceDockPolicyTest'],
                                    check=True, capture_output=True, text=True)
            self.assertIn('15 PASS', result.stdout)

    def test_controller_and_preview_contract(self):
        java = ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc'
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac', '-d', out, *(str(java / x) for x in ['ChineseInputEngine.java', 'KeyboardController.java', 'EditorCompatibility.java', 'InputSession.java', 'ImePanelController.java', 'OutputTargetController.java', 'ImeLayoutSpec.java', 'SpeechEndpointDetector.java', 'VoiceDraft.java', 'AudioCaptureReadPolicy.java', 'StartupPcmBuffer.java']), str(ROOT / 'tests/KeyboardControllerTest.java'), str(ROOT / 'tests/ImePanelControllerTest.java'), str(ROOT / 'tests/OutputTargetControllerTest.java'), str(ROOT / 'tests/ImeLayoutSpecTest.java'), str(ROOT / 'tests/SpeechEndpointDetectorTest.java'), str(ROOT / 'tests/VoiceDraftTest.java'), str(ROOT / 'tests/AudioCaptureLifecycleTest.java')], check=True)
            keyboard = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.KeyboardControllerTest'], check=True, text=True, capture_output=True)
            self.assertIn('keyboard checks passed', keyboard.stdout)
            panels = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.ImePanelControllerTest'], check=True, text=True, capture_output=True)
            self.assertIn('IME panel checks passed', panels.stdout)
            targets = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.OutputTargetControllerTest'], check=True, text=True, capture_output=True)
            self.assertIn('output target checks passed', targets.stdout)
            layout = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.ImeLayoutSpecTest'], check=True, text=True, capture_output=True)
            self.assertIn('IME layout + keycap hint checks passed', layout.stdout)
            endpoint = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.SpeechEndpointDetectorTest'], check=True, text=True, capture_output=True)
            self.assertIn('speech endpoint checks passed', endpoint.stdout)
            draft = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.VoiceDraftTest'], check=True, text=True, capture_output=True)
            self.assertIn('voice draft checks passed', draft.stdout)
            audio = subprocess.run(['java', '-cp', out, 'io.github.amxooo.voiceinput2pc.AudioCaptureLifecycleTest'], check=True, text=True, capture_output=True)
            self.assertIn('audio capture lifecycle checks passed', audio.stdout)
if __name__ == '__main__': unittest.main()
