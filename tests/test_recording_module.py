import subprocess
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'android/app/src/main/java/io/github/amxooo/voiceinput2pc'

class RecordingModuleTests(unittest.TestCase):
    def test_exclusive_microphone_lease_and_concurrent_start(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac','-d',out,str(JAVA/'MicrophoneLease.java'),str(ROOT/'tests/MicrophoneLeaseTest.java')],check=True)
            result=subprocess.run(['java','-cp',out,'io.github.amxooo.voiceinput2pc.MicrophoneLeaseTest'],check=True,text=True,capture_output=True)
            self.assertIn('microphone lease checks passed',result.stdout)
    def test_service_declares_narrow_microphone_scope(self):
        a='{http://schemas.android.com/apk/res/android}'
        manifest=ET.parse(ROOT/'android/app/src/main/AndroidManifest.xml').getroot()
        service=next(s for s in manifest.find('application').findall('service') if s.get(a+'name')=='.VoiceRecordingService')
        self.assertEqual(service.get(a+'exported'),'false')
        self.assertEqual(service.get(a+'foregroundServiceType'),'microphone')
        self.assertIsNone(service.find('intent-filter'))
        permissions={p.get(a+'name') for p in manifest.findall('uses-permission')}
        self.assertIn('android.permission.FOREGROUND_SERVICE_MICROPHONE',permissions)
        self.assertNotIn('android.permission.MANAGE_EXTERNAL_STORAGE',permissions)
        self.assertNotIn('android.permission.RECEIVE_BOOT_COMPLETED',permissions)

if __name__=='__main__':unittest.main()
