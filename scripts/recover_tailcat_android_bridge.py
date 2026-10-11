#!/usr/bin/env python3
"""Recover matching bindings and unchanged JNI from official v0.6.1, no downloads."""
import argparse, hashlib, json, os, subprocess, tempfile, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
APK_HASH="37907136832855fb4b46c97cd1eadd350ca678870ac4769ddec481871ae7f344"
NATIVE_HASH="0fbeb0a23aeff759f004e6d6b122ebb2ecdb06708c785d93ff5ad91689c42966"
def sha(data): return hashlib.sha256(data).hexdigest()
def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("apk",type=Path)
    ap.add_argument("--tools-dir",type=Path,required=True)
    a=ap.parse_args()
    if sha(a.apk.read_bytes())!=APK_HASH: raise SystemExit("Official APK hash mismatch")
    jars=list(a.tools_dir.rglob("dex-*-2.4.24.jar"))+list(a.tools_dir.rglob("asm*-9.7.1.jar"))
    if not jars: raise SystemExit("Existing dex2jar/ASM jars missing")
    cp=os.pathsep.join(str(p.resolve()) for p in jars)
    build=ROOT/"build/native-deps";build.mkdir(parents=True,exist_ok=True)
    output=ROOT/"android/app/libs/tailcatbridge.aar";output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="recover-",dir=build) as folder:
        tmp=Path(folder)
        subprocess.run(["javac","-cp",cp,"-d",str(tmp),str(ROOT/"scripts/RecoverTailcatBindings.java")],check=True)
        classes={}
        with zipfile.ZipFile(a.apk) as apk:
            native=apk.read("lib/arm64-v8a/libgojni.so")
            if sha(native)!=NATIVE_HASH: raise SystemExit("Native hash mismatch")
            for name in apk.namelist():
                if not (name.startswith("classes") and name.endswith(".dex")): continue
                dex=tmp/name;dex.write_bytes(apk.read(name));jar=dex.with_suffix(".jar")
                subprocess.run(["java","-cp",str(tmp)+os.pathsep+cp,"RecoverTailcatBindings",str(dex),str(jar)],check=True)
                with zipfile.ZipFile(jar) as z:
                    for n in z.namelist():
                        if not n.endswith(".class"): continue
                        if not n.startswith(("go/","io/github/amxooo/voiceinput2pc/tailcatbridge/")): raise SystemExit("Unexpected class")
                        if n in classes: raise SystemExit("Duplicate binding")
                        data=z.read(n)
                        if b"d2j fail translate" in data: raise SystemExit("Translation failed")
                        classes[n]=data
        required={"go/Seq.class","go/Universe.class","io/github/amxooo/voiceinput2pc/tailcatbridge/Tailcatbridge.class","io/github/amxooo/voiceinput2pc/tailcatbridge/InterfaceLister.class"}
        if not required.issubset(classes): raise SystemExit("Incomplete bindings")
        jar=tmp/"classes.jar"
        with zipfile.ZipFile(jar,"w") as z:
            for n,data in sorted(classes.items()): z.writestr(n,data)
        aar=tmp/"bridge.aar"
        with zipfile.ZipFile(aar,"w",zipfile.ZIP_DEFLATED) as z:
            z.writestr("AndroidManifest.xml",'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.amxooo.voiceinput2pc.tailcatbridge"><uses-sdk android:minSdkVersion="26" /></manifest>')
            z.writestr("classes.jar",jar.read_bytes());z.writestr("jni/arm64-v8a/libgojni.so",native)
        os.replace(aar,output)
    ev={"source":"https://github.com/AMXOOO/VoiceInput2PC/releases/tag/v0.6.1","apk_sha256":APK_HASH,"native_sha256":NATIVE_HASH,"aar_sha256":sha(output.read_bytes()),"binding_classes":sorted(classes),"method":"Native bytes unchanged; matching Java bindings recovered by dex2jar","android_runtime_verification":"PENDING"}
    (build/"recovery.json").write_text(json.dumps(ev,indent=2)+"\n")
    print(json.dumps({"aar":str(output),"binding_count":len(classes),"status":"PACKAGED_RUNTIME_PENDING"}))
if __name__=="__main__": main()
