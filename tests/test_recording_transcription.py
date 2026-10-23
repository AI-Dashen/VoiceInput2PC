import subprocess,tempfile,unittest
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class RecordingTranscriptionTests(unittest.TestCase):
 def test_duration_boundaries_cancel_completion_and_recovery(self):
  with tempfile.TemporaryDirectory() as out:
   subprocess.run(['javac','-d',out,str(ROOT/'android/app/src/main/java/io/github/amxooo/voiceinput2pc/RecordingWorkPolicy.java'),str(ROOT/'tests/RecordingWorkPolicyTest.java')],check=True)
   result=subprocess.run(['java','-cp',out,'io.github.amxooo.voiceinput2pc.RecordingWorkPolicyTest'],text=True,capture_output=True,check=True)
   self.assertIn('transcription policy checks passed',result.stdout)
 def test_execution_lease_excludes_duplicate_worker_and_can_stop_waiting(self):
  with tempfile.TemporaryDirectory() as out:
   subprocess.run(['javac','-d',out,str(ROOT/'android/app/src/main/java/io/github/amxooo/voiceinput2pc/RecordingExecutionLock.java'),str(ROOT/'tests/RecordingExecutionLockTest.java')],check=True)
   result=subprocess.run(['java','-cp',out,'io.github.amxooo.voiceinput2pc.RecordingExecutionLockTest'],text=True,capture_output=True,check=True)
   self.assertIn('execution lease checks passed',result.stdout)
 def test_scheduler_worker_is_system_bound_and_isolated(self):
  a='{http://schemas.android.com/apk/res/android}'
  m=ET.parse(ROOT/'android/app/src/main/AndroidManifest.xml').getroot()
  service=next(s for s in m.find('application').findall('service') if s.get(a+'name')=='.RecordingTranscriptionService')
  self.assertEqual(service.get(a+'permission'),'android.permission.BIND_JOB_SERVICE')
  self.assertEqual(service.get(a+'process'),':recording_worker')
  self.assertIsNone(service.find('intent-filter'))
if __name__=='__main__':unittest.main()
