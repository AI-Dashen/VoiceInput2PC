#!/usr/bin/env python3
"""Measure APK ASR MNN components without Android UI or microphone data."""
from __future__ import annotations
import argparse, hashlib, json, os, select, subprocess, threading, time
from pathlib import Path

KB_FIELDS = ("Rss", "Pss", "SwapPss", "Private_Clean", "Private_Dirty")

def read_kb_file(path: Path) -> dict[str, int]:
    result = {}
    try:
        for line in path.read_text().splitlines():
            key, _, value = line.partition(":")
            if key in KB_FIELDS or key in {"VmRSS", "VmHWM", "VmSwap"}:
                result[key] = int(value.strip().split()[0])
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        pass
    return result

def sample(pid: int) -> dict[str, int | float]:
    values = {"t_monotonic": time.monotonic()}
    values.update(read_kb_file(Path(f"/proc/{pid}/status")))
    values.update(read_kb_file(Path(f"/proc/{pid}/smaps_rollup")))
    return values

def wait_readable(stream, timeout: float) -> bool:
    ready, _, _ = select.select([stream], [], [], timeout)
    return bool(ready)

def run_probe(args: argparse.Namespace) -> dict:
    repo = Path(args.repo).resolve()
    fixtures = repo.parent / "analysis/doubao-ime-20261016/independent-runtime/engine-probe-20261018"
    assets = repo / "android/app/src/main/assets/independent-asr"
    libs = Path(args.lib_root).resolve() if args.lib_root else repo / "android/app/src/main/jniLibs/arm64-v8a"
    if args.component == "encoder":
        binary = fixtures / "mnn_stream_encoder_server"
        command = [str(binary), str(assets / "encoder_stream.mnn"), str(assets / "encoder.mnn.weight")]
        inference_input = (fixtures / "stream-fbank/00.f32").read_bytes()
        expected_output = 1536 * 4
    else:
        binary = fixtures / "mnn_logit_server"
        command = [str(binary), str(assets / "logit.mnn"), str(assets / "logit.mnn.weight")]
        inference_input = (fixtures / "hidden-last.f32").read_bytes()
        expected_output = None
    if args.binary:
        binary = Path(args.binary).resolve()
        command[0] = str(binary)
    env = os.environ.copy()
    env["LD_LIBRARY_PATH"] = str(libs) + (":" + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    started = time.monotonic()
    proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    samples = []
    stop_sampler = threading.Event()
    def sampler() -> None:
        while not stop_sampler.is_set() and proc.poll() is None:
            point = sample(proc.pid)
            if len(point) > 1:
                samples.append(point)
            stop_sampler.wait(0.01)
    thread = threading.Thread(target=sampler, daemon=True)
    thread.start()
    try:
        if not wait_readable(proc.stdout, args.timeout):
            raise TimeoutError("server did not become ready")
        ready = proc.stdout.readline()
        if ready != b"READY\n":
            stderr = proc.stderr.read(4096).decode("utf-8", "replace")
            raise RuntimeError(f"unexpected readiness output {ready!r}; stderr={stderr}")
        ready_at = time.monotonic()
        time.sleep(args.settle)
        ready_sample = sample(proc.pid)
        proc.stdin.write(inference_input)
        proc.stdin.flush()
        if not wait_readable(proc.stdout, args.timeout):
            raise TimeoutError("inference did not return")
        if expected_output is None:
            output = proc.stdout.readline()
        else:
            output = proc.stdout.read(expected_output)
            if len(output) != expected_output:
                raise RuntimeError(f"short encoder output: {len(output)} != {expected_output}")
        inference_at = time.monotonic()
        time.sleep(args.settle)
        inference_sample = sample(proc.pid)
    finally:
        stop_sampler.set()
        if proc.stdin:
            proc.stdin.close()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.terminate()
            proc.wait(timeout=3)
        thread.join(timeout=1)
    peak = {}
    for point in samples:
        for key, value in point.items():
            if key != "t_monotonic":
                peak[key] = max(peak.get(key, 0), int(value))
    stderr_text = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""
    return {
        "schema_version": 1, "component": args.component, "command": command,
        "pid": proc.pid, "exit_code": proc.returncode,
        "timing_ms": {"ready": round((ready_at-started)*1000, 3), "first_inference": round((inference_at-ready_at)*1000, 3)},
        "ready_kb": ready_sample, "after_first_inference_kb": inference_sample,
        "peak_kb": peak, "sample_count": len(samples),
        "input_bytes": len(inference_input), "output_bytes": len(output),
        "output_sha256": hashlib.sha256(output).hexdigest(),
        "output_preview": output[:160].decode("utf-8", "replace") if expected_output is None else None,
        "stderr": stderr_text[-2000:]
    }

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("component", choices=("encoder", "logit"))
    parser.add_argument("--binary")
    parser.add_argument("--lib-root")
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--output", required=True)
    parser.add_argument("--settle", type=float, default=0.5)
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    result = run_probe(args)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
