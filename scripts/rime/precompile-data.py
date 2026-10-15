#!/usr/bin/env python3
"""Compile real dictionaries before packaging; never from inside the Android IME."""
import pathlib,subprocess,os,shutil,hashlib,json
ROOT=pathlib.Path(__file__).resolve().parents[2]
SOURCE=ROOT/'native-deps/rime-source'; USER=ROOT/'native-deps/precompile-user'
ASSETS=ROOT/'android/app/src/main/assets/rime'; BUILD=ASSETS/'build'
assert (SOURCE/'cn_dicts/base.dict.yaml').is_file(),'Restore approved official dictionary source first'
for schema in (ROOT/'scripts/rime/schemas').glob('*.yaml'):shutil.copyfile(schema,SOURCE/schema.name)
USER.mkdir(parents=True,exist_ok=True)
includes=ROOT/'native-deps/root/data/data/com.termux/files/usr/include'
subprocess.run(['clang','-O2','-I',str(includes),str(ROOT/'scripts/rime/rime-probe.c'),'-ldl','-o',str(ROOT/'native-deps/rime-probe')],check=True)
env=dict(os.environ,LD_LIBRARY_PATH=str(ROOT/'android/app/src/main/jniLibs/arm64-v8a'))
subprocess.run([str(ROOT/'native-deps/rime-probe'),str(SOURCE),str(USER)],env=env,check=True)
BUILD.mkdir(parents=True,exist_ok=True)
for artifact in (USER/'build').iterdir():shutil.copyfile(artifact,BUILD/artifact.name)
manifest={'format':'librime-prebuilt','engine':'1.17.0','source_commit':'da1fbe602e38f26db846fa10120ee64c2b0324c0','files':[{'path':str(p.relative_to(ASSETS)),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}for p in sorted(BUILD.iterdir())]}
(ASSETS/'prebuilt-manifest.json').write_text(json.dumps(manifest,indent=2))
version=hashlib.sha256(b''.join(str(p.relative_to(ASSETS)).encode()+p.read_bytes()for p in sorted(ASSETS.rglob('*'))if p.is_file()and p.name!='version.txt')).hexdigest()
(ASSETS/'version.txt').write_text(version)
print('Packaged prebuilt dictionary files, no Android runtime compiler needed')
