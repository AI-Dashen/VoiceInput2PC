#!/usr/bin/env python3
"""Rebuild T9 spelling prisms using the existing table cache, without full deployment.
Backs up replaced assets. No downloads and no Android runtime dictionary compiler.
"""
import pathlib, subprocess, os, shutil, hashlib, json, re, datetime, time
ROOT=pathlib.Path(__file__).resolve().parents[2]
SOURCE=ROOT/'native-deps/rime-source'
ASSETS=ROOT/'android/app/src/main/assets/rime'
BUILD=ASSETS/'build'
STAMP=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
WORK=ROOT/'native-deps'/('t9-update-'+STAMP)
BACKUP=WORK/'backup'
SHARED=WORK/'shared'; USER=WORK/'user'
def main():
    for name in ('rime_ice.table.bin','rime_ice.reverse.bin'):
        assert (BUILD/name).is_file(), 'Existing dictionary cache required; refusing full deployment'
    SHARED.mkdir(parents=True);USER.mkdir();BACKUP.mkdir()
    for p in SOURCE.iterdir():
        if p.name not in ('build','t9.schema.yaml','t9_private.schema.yaml'):
            (SHARED/p.name).symlink_to(p.resolve(),target_is_directory=p.is_dir())
    shutil.copytree(BUILD,SHARED/'build')
    schemas=[]
    for name in ('t9.schema.yaml','t9_private.schema.yaml'):
        target=SHARED/name;shutil.copyfile(ROOT/'scripts/rime/schemas'/name,target);schemas.append(str(target))
    includes=ROOT/'native-deps/root/data/data/com.termux/files/usr/include'
    program=WORK/'deploy-t9-prism'
    subprocess.run(['clang','-O2','-I',str(includes),str(ROOT/'scripts/rime/deploy-t9-prism.c'),'-ldl','-o',str(program)],check=True)
    env=dict(os.environ,LD_LIBRARY_PATH=str(ROOT/'android/app/src/main/jniLibs/arm64-v8a'))
    start=time.monotonic()
    subprocess.run([str(program),str(SHARED),str(USER),*schemas],env=env,check=True,timeout=120)
    elapsed=time.monotonic()-start
    generated=USER/'build'
    assert not (generated/'rime_ice.table.bin').exists(), 'Unexpected dictionary rebuild; original assets untouched'
    expected=['t9.prism.bin','t9.schema.yaml','t9_private.schema.yaml']
    assert all((generated/name).is_file() for name in expected),'Missing T9 artifacts'
    # Follow only active import_tables; collect actual spelled dictionary codes.
    syllables=set()
    tables=re.findall(r'^\s*-\s+([^\s#]+)',(SOURCE/'rime_ice.dict.yaml').read_text(),re.M)
    for path in [SOURCE/'rime_ice.dict.yaml',*(SOURCE/(table+'.dict.yaml') for table in tables)]:
        assert path.is_file(),str(path)
        for line in path.read_text().splitlines():
            cols=line.split('\t')
            if len(cols)>1 and re.fullmatch(r'[a-z]+(?: [a-z]+)*',cols[1]):syllables.update(cols[1].split())
    assert syllables, 'No dictionary spellings found'
    updates={BUILD/name:generated/name for name in expected}
    index=WORK/'t9-syllables.txt';index.write_text('\n'.join(sorted(syllables))+'\n')
    updates[ASSETS/'t9-syllables.txt']=index
    for target in [*updates,ASSETS/'prebuilt-manifest.json',ASSETS/'version.txt']:
        if target.exists():shutil.copy2(target,BACKUP/target.name)
    for target,src in updates.items():shutil.copy2(src,target)
    manifest=json.loads((ASSETS/'prebuilt-manifest.json').read_text())
    manifest['t9_spelling_version']='0.8.2'
    manifest['files']=[{'path':str(p.relative_to(ASSETS)),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}for p in sorted(BUILD.iterdir())]
    manifest['syllable_index']={'path':'t9-syllables.txt','count':len(syllables),'sha256':hashlib.sha256(index.read_bytes()).hexdigest()}
    (ASSETS/'prebuilt-manifest.json').write_text(json.dumps(manifest,indent=2))
    version=hashlib.sha256(b''.join(str(p.relative_to(ASSETS)).encode()+p.read_bytes()for p in sorted(ASSETS.rglob('*'))if p.is_file()and p.name!='version.txt')).hexdigest()
    (ASSETS/'version.txt').write_text(version)
    print(json.dumps({'status':'T9 prisms updated; cached dictionary reused','deploy_seconds':round(elapsed,3),'syllables':len(syllables),'version':version,'backup':str(BACKUP)},ensure_ascii=False))
if __name__=='__main__':main()
