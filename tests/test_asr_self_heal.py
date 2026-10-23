import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android/app/src/main/java/io/github/amxooo/voiceinput2pc"


class AsrSelfHealContractTests(unittest.TestCase):
    def test_hard_reset_is_scoped_to_dedicated_asr_process(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text()
        service = (JAVA / "LocalAsrService.java").read_text()
        client = (JAVA / "OfflineSpeechRecognizer.java").read_text()
        self.assertIn('android:name=".LocalAsrService" android:process=":local_asr"', manifest)
        self.assertIn('Binder.getCallingUid()!=getApplicationInfo().uid', service)
        self.assertIn('RESET_PROCESS=6', service)
        self.assertIn('killProcess(android.os.Process.myPid())', service)
        self.assertIn('resultTimeout=()->{if(valid&&generation==g&&finishSent)hardReset(', client)
        self.assertNotIn('startTimeout=()->{if(valid&&generation==g)hardReset(', client)

    def test_native_failures_invalidate_resident_engine(self):
        service = (JAVA / "LocalAsrService.java").read_text()
        self.assertGreaterEqual(service.count('invalidateEngine();'), 3)
        self.assertIn('IndependentAsrNative.release();', service)
        self.assertIn('prepared=false;', service)

    def test_real_error_is_visible_in_ime(self):
        ime = (JAVA / "UniversalInputMethodService.java").read_text()
        self.assertIn('String detail=error==null?"":error.trim();', ime)
        self.assertIn('message=detail.isEmpty()?"识别停止 · 点语音重试":detail;', ime)


if __name__ == "__main__":
    unittest.main()
