"""Validate official runtime packaging and reject an untrusted source APK."""
import hashlib
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class TailcatPackagingTest(unittest.TestCase):
 def test_untrusted_apk_rejected_before_any_conversion(self):
  with tempfile.TemporaryDirectory() as d:
   bad=Path(d)/"wrong.apk";bad.write_bytes(b"not the pinned release")
   r=subprocess.run(["python",str(ROOT/"scripts/recover_tailcat_android_bridge.py"),str(bad),"--tools-dir",d],capture_output=True,text=True)
   self.assertNotEqual(0,r.returncode)
   self.assertIn("Official APK hash mismatch",r.stderr)
 def test_packaged_native_is_original_and_bindings_are_present(self):
  aar=ROOT/"android/app/libs/tailcatbridge.aar"
  apk=ROOT/"android/app/build/outputs/apk/debug/app-debug.apk"
  if not aar.exists() or not apk.exists(): self.skipTest("Build artifacts absent")
  with zipfile.ZipFile(aar) as z:
   native=z.read("jni/arm64-v8a/libgojni.so")
   self.assertEqual("0fbeb0a23aeff759f004e6d6b122ebb2ecdb06708c785d93ff5ad91689c42966",hashlib.sha256(native).hexdigest())
   import io
   with zipfile.ZipFile(io.BytesIO(z.read("classes.jar"))) as classes:
    self.assertEqual(15,len(classes.namelist()))
    self.assertIn("go/Seq.class",classes.namelist())
    self.assertIn("io/github/amxooo/voiceinput2pc/tailcatbridge/Tailcatbridge.class",classes.namelist())
    self.assertTrue(all(n.startswith(("go/","io/github/amxooo/voiceinput2pc/tailcatbridge/")) for n in classes.namelist()))
  with zipfile.ZipFile(apk) as z:
   self.assertEqual(native,z.read("lib/arm64-v8a/libgojni.so"))
