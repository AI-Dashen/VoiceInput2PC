#!/usr/bin/env python3
"""Package a bounded, reproducible Termux Rime dependency closure for Android."""
import pathlib,re,subprocess,shutil,json,hashlib,struct
ROOT=pathlib.Path(__file__).resolve().parents[2]
EXTRACT=ROOT/'native-deps/root/data/data/com.termux/files/usr/lib'
PREFIX=pathlib.Path('/data/data/com.termux/files/usr/lib')
OUT=ROOT/'android/app/src/main/jniLibs/arm64-v8a'
SYSTEM={'libc.so','libm.so','libdl.so','liblog.so','libz.so','libandroid.so'}
def needed(path):
 s=subprocess.check_output(['llvm-readelf','-d',str(path)],text=True)
 return re.findall(r'\(NEEDED\).*\[([^]]+)\]',s)
def replace_dynamic_string(path,old,new):
 # Modify only validated ELF64 dynamic-table string references, never arbitrary binary matches.
 b=bytearray(path.read_bytes()); assert b[:6]==b'\x7fELF\x02\x01' and len(new)<=len(old)
 phoff=struct.unpack_from('<Q',b,32)[0]; entsize,count=struct.unpack_from('<HH',b,54)
 segments=[struct.unpack_from('<IIQQQQQQ',b,phoff+i*entsize) for i in range(count)]
 dynamic=next(s for s in segments if s[0]==2)
 entries=[struct.unpack_from('<qQ',b,o)for o in range(dynamic[2],dynamic[2]+dynamic[5],16)]
 straddr=next(v for t,v in entries if t==5)
 load=next(s for s in segments if s[0]==1 and s[3]<=straddr<s[3]+s[5]); start=load[2]+straddr-load[3]
 offsets=[v for t,v in entries if t in (1,14,29)]
 for offset in offsets:
  p=start+offset;end=b.index(0,p);value=bytes(b[p:end]).decode()
  if value==old:b[p:end+1]=new.encode()+b'\x00'*(end-p+1-len(new))
 path.write_bytes(b)
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 queue=['librime.so'];seen=set();manifest=[]
 while queue:
  name=queue.pop(0)
  if name in SYSTEM or name in seen:continue
  seen.add(name);src=EXTRACT/name
  if not src.exists():src=PREFIX/name
  if not src.is_file():raise RuntimeError('Missing native dependency: '+name)
  queue.extend(needed(src));target=OUT/('librime_cxx.so' if name=='libc++_shared.so' else name)
  shutil.copyfile(src,target);replace_dynamic_string(target,'libc++_shared.so','librime_cxx.so');replace_dynamic_string(target,'/data/data/com.termux/files/usr/lib','$ORIGIN')
  manifest.append({'source':str(src),'file':target.name,'original_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'needed':needed(target)})
 shutil.copyfile(ROOT/'native-deps/librimebridge.so',OUT/'librimebridge.so')
 (ROOT/'native-deps/rime-libraries.json').write_text(json.dumps(manifest,indent=2))
 print('Packaged',len(manifest)+1,'native libraries')
if __name__=='__main__':main()
