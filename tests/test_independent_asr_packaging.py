import hashlib,json,pathlib,re,unittest,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
ASSETS=ROOT/'android/app/src/main/assets/independent-asr'
APK=ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
class IndependentAsrPackagingTests(unittest.TestCase):
 def test_manifest_matches_native_assets(self):
  manifest=json.loads((ASSETS/'manifest.json').read_text())
  self.assertFalse(manifest['original_runtime_required'])
  self.assertEqual(6,len(manifest['files']))
  for item in manifest['files']:
   path=ASSETS/item['name'];self.assertEqual(item['bytes'],path.stat().st_size);self.assertEqual(item['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
 def test_service_uses_apk_owned_engine(self):
  source=(ROOT/'android/app/src/main/java/io/github/amxooo/voiceinput2pc/LocalAsrService.java').read_text()
  self.assertIn('IndependentAsrNative.prepare',source);self.assertIn('IndependentAsrNative.beginStream',source);self.assertIn('IndependentAsrNative.acceptStream',source);self.assertIn('IndependentAsrNative.finishStream',source);self.assertNotIn('IndependentAsrNative.recognize(captured)',source)
  self.assertNotIn('LocalAsrNative.prepare',source);self.assertNotIn('Runtime.getRuntime()',source)
 def test_native_engine_has_no_vendor_runtime_dependency(self):
  source=(ROOT/'android/app/src/main/cpp/independent_asr_engine.cpp').read_text()
  self.assertIn('IndependentDecoderRuntime',source);self.assertNotIn('audioeffect',source)
 def test_built_apk_contains_new_runtime_only(self):
  if not APK.exists():self.skipTest('assembleDebug not run')
  with zipfile.ZipFile(APK) as archive:names=set(archive.namelist())
  self.assertIn('lib/arm64-v8a/libindependentasr.so',names);self.assertIn('lib/arm64-v8a/libMNN.so',names)
  for item in json.loads((ASSETS/'manifest.json').read_text())['files']:self.assertIn('assets/independent-asr/'+item['name'],names)
  self.assertNotIn('assets/local-asr/model.flute',names);self.assertNotIn('lib/arm64-v8a/libaudioeffect.so',names);self.assertNotIn('lib/arm64-v8a/libofflineasr.so',names)
if __name__=='__main__':unittest.main()
