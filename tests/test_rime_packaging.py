"""Validate real packaged Android dependency boundaries, not fake candidate data."""
import pathlib,re,subprocess,unittest,json
ROOT=pathlib.Path(__file__).resolve().parents[1]
LIBS=ROOT/'android/app/src/main/jniLibs/arm64-v8a'
SYSTEM={'libc.so','libm.so','libdl.so','liblog.so','libz.so','libandroid.so'}
class RimePackagingTest(unittest.TestCase):
    def test_complete_private_dependency_closure(self):
        items=json.loads((ROOT/'native-deps/rime-libraries.json').read_text())
        self.assertEqual(9,len(items))
        for item in items:
            self.assertTrue((LIBS/item['file']).is_file())
            for name in item['needed']:
                self.assertTrue(name in SYSTEM or (LIBS/name).is_file(),name)
                self.assertNotEqual('libc++_shared.so',name)
    def test_no_termux_dynamic_search_path(self):
        for item in json.loads((ROOT/'native-deps/rime-libraries.json').read_text()):
            text=subprocess.check_output(['llvm-readelf','-d',str(LIBS/item['file'])],text=True)
            self.assertNotIn('/data/data/com.termux',text)
    def test_real_rime_aligned_for_android16(self):
        for name in ['librime.so','librimebridge.so','librime_cxx.so']:
            text=subprocess.check_output(['llvm-readelf','-l',str(LIBS/name)],text=True)
            for line in text.splitlines():
                if line.strip().startswith('LOAD'):
                    self.assertGreaterEqual(int(line.split()[-1],16),16384)
    def test_t9_does_not_overwrite_full_pinyin_prism(self):
        schema=(ROOT/'android/app/src/main/assets/rime/build/t9.schema.yaml').read_text()
        self.assertIn('prism: t9',schema)
        self.assertNotIn('lua_',schema)
        for letters,digit in zip(('abc','def','ghi','jkl','mno','pqrs','tuv','wxyz'),'23456789'):
            self.assertIn(f'derive/[{letters}]/{digit}/',schema)
        self.assertIn('abcdefghijklmnopqrstuvwxyz23456789',schema)
        self.assertNotIn('xlit/abcdefghijklmnopqrstuvwxyz/',schema)
        syllables=(ROOT/'android/app/src/main/assets/rime/t9-syllables.txt').read_text().splitlines()
        self.assertEqual(sorted(set(syllables)),syllables)
        self.assertIn('ni',syllables)
        self.assertIn('hao',syllables)
        for value in syllables:
            self.assertRegex(value,r'^[a-z]+$')
        self.assertEqual('0.8.2',json.loads((ROOT/'android/app/src/main/assets/rime/prebuilt-manifest.json').read_text())['t9_spelling_version'])
    def test_privacy_disables_user_dictionary(self):
        for schema in ['rime_ice_private','t9_private']:
            s=(ROOT/'android/app/src/main/assets/rime/build'/f'{schema}.schema.yaml').read_text()
            self.assertIn('enable_user_dict: false',s)
    def test_runtime_cannot_compile_dictionary(self):
        s=(ROOT/'android/app/src/main/cpp/rime/rimebridge.c').read_text()
        self.assertNotIn('start_maintenance(',s)
        self.assertIn('traits.prebuilt_data_dir=prebuilt_path',s)
        self.assertIn('traits.staging_dir=prebuilt_path',s)
        self.assertFalse((ROOT/'android/app/src/main/assets/rime/cn_dicts').exists())
        self.assertTrue((ROOT/'android/app/src/main/assets/rime/build/rime_ice.table.bin').is_file())
    def test_production_does_not_shell_to_termux(self):
        s=(ROOT/'android/app/src/main/java/io/github/amxooo/voiceinput2pc/RimeChineseInputEngine.java').read_text()
        self.assertNotIn('ProcessBuilder',s)
        self.assertNotIn('com.termux',s)
        self.assertIn('context.getFilesDir()',s)
if __name__=='__main__':unittest.main()
