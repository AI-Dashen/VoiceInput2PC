#!/usr/bin/env python3
from __future__ import annotations
import json, os, re, subprocess, threading, time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
REPO=ROOT/"voiceinput2pc-v08"
MODEL=REPO/"android/app/src/main/assets/independent-asr"
CORPUS=ROOT/"analysis/doubao-ime-20261016/independent-runtime/upstream/MNN-3.4.1/apps/iOS/MNNLLMChat/MNNLLMiOS/LocalBatchTest/Audios"
PROBE=Path("/data/data/com.termux/files/usr/tmp/asr-memory-probes")
MODES={
 "normal": (PROBE/"asr_engine_cli_normal", ROOT/".runtime/mnn-build-3.6.1-clean/OFF"),
 "low": (PROBE/"asr_engine_cli_low", ROOT/".runtime/mnn-build-3.6.1-asr-lowmem/OFF"),
}
def rss(pid):
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"): return int(line.split()[1])
    except OSError: pass
    return 0
def run(mode,wav):
    binary,lib=MODES[mode]; env=os.environ.copy(); env["LD_LIBRARY_PATH"]=str(lib)
    p=subprocess.Popen([str(binary),str(MODEL),str(wav)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    peak=0
    while p.poll() is None:
        peak=max(peak,rss(p.pid)); time.sleep(.02)
    out,err=p.communicate()
    def val(name):
        m=re.search(rf"^{name} (.*)$",out,re.M); return m.group(1) if m else None
    return {"exit":p.returncode,"text":val("TEXT"),"load_ms":float(val("LOAD_MS") or "nan"),"infer_ms":float(val("INFER_MS") or "nan"),"peak_rss_kb":peak,"stderr_tail":err[-500:]}
def main():
    wavs=sorted(CORPUS.glob("*.wav"))
    rows=[]
    for wav in wavs:
        row={"wav":wav.name}
        for mode in MODES: row[mode]=run(mode,wav)
        row["text_equal"]=row["normal"]["text"]==row["low"]["text"]
        rows.append(row)
        checkpoint=REPO/"docs/v0.8/evidence/asr-low-memory-regression.partial.json"
        checkpoint.parent.mkdir(parents=True,exist_ok=True)
        checkpoint.write_text(json.dumps({"schema_version":1,"completed":len(rows),"rows":rows},ensure_ascii=False,indent=2)+"\n")
        print(wav.name,row["text_equal"],row["normal"]["text"],row["low"]["text"],flush=True)
    summary={
      "samples":len(rows),
      "all_text_equal":all(r["text_equal"] for r in rows),
      "all_exit_zero":all(r[m]["exit"]==0 for r in rows for m in MODES),
      "normal_total_infer_ms":sum(r["normal"]["infer_ms"] for r in rows),
      "low_total_infer_ms":sum(r["low"]["infer_ms"] for r in rows),
      "normal_peak_rss_kb":max(r["normal"]["peak_rss_kb"] for r in rows),
      "low_peak_rss_kb":max(r["low"]["peak_rss_kb"] for r in rows),
    }
    data={"schema_version":1,"summary":summary,"rows":rows}
    out=REPO/"docs/v0.8/evidence/asr-low-memory-regression.json"; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=="__main__": main()
