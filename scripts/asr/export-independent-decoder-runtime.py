#!/usr/bin/env python3
"""Export the recovered decoder into a bounded native-APK runtime asset."""
from __future__ import annotations
import argparse, hashlib, json, pathlib, struct, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = ROOT.parent
SOURCE = PROJECT / "analysis/doubao-ime-20261016/independent-runtime"
sys.path.insert(0, str(SOURCE))
from decoder_structure import decoder_structure, GRAPH  # noqa: E402
from gelu_native_reference import TABLE  # noqa: E402
from model_storage import Resolver  # noqa: E402
from native_model_projection import NativeModelProjection  # noqa: E402

MAGIC = b"IASRDEC1"
VERSION = 1
WIDTH = 768
LAYERS = 12
MAX_POSITIONS = 8192

def projection_names(structure):
    for layer in structure["layers"]:
        yield layer["q"]
        yield layer["k"]
        yield layer["v"]
        yield layer["attn_projection"]
        yield layer["ffn_in"]
        yield layer["ffn_out"]

def norm_names(structure):
    for layer in structure["layers"]:
        yield layer["norm1"]
        yield layer["norm2"]
    yield structure["final_norm"]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=pathlib.Path, required=True)
    ap.add_argument("--manifest", type=pathlib.Path, required=True)
    args = ap.parse_args()
    resolver = Resolver(max_read_bytes=8 << 20, max_rows=8, max_output_elements=65536)
    structure = decoder_structure(resolver)
    projector = NativeModelProjection(resolver, contracts=structure["projections"], numeric_mode="native_fp32", clamp=(-3.4028234663852886e38, 3.4028234663852886e38))
    projections = list(projection_names(structure))
    norms = list(norm_names(structure))
    if len(projections) != 72 or len(norms) != 25:
        raise SystemExit("DECODER_STRUCTURE_COUNT")
    data = bytearray()
    data += struct.pack("<8s7I", MAGIC, VERSION, WIDTH, LAYERS, MAX_POSITIONS, len(projections), len(norms), len(TABLE))
    projection_manifest = []
    for name in projections:
        body, alpha, bias, width, rows, groups = projector.payload(name)
        data += struct.pack("<6I", rows, width, groups, len(body), len(alpha), len(bias))
        data += body
        data += struct.pack(f"<{len(alpha)}f", *alpha)
        data += struct.pack(f"<{len(bias)}f", *bias)
        projection_manifest.append({"name": name, "rows": rows, "width": width, "groups": groups, "packed_bytes": len(body)})
    norm_manifest = []
    for name in norms:
        gamma, beta = resolver.layer_norm_parameters(GRAPH, name)
        contract = resolver.layer_norm_contract(GRAPH, name)
        if len(gamma) != WIDTH or len(beta) != WIDTH:
            raise SystemExit("NORM_SHAPE")
        data += struct.pack("<fI", contract["epsilon"], WIDTH)
        data += struct.pack(f"<{WIDTH}f", *gamma)
        data += struct.pack(f"<{WIDTH}f", *beta)
        norm_manifest.append({"name": name, "epsilon": contract["epsilon"]})
    data += struct.pack("<I", MAX_POSITIONS)
    for start in range(0, MAX_POSITIONS, 8):
        cos, _ = resolver.rope_coefficients(GRAPH, range(start, start + 8))
        data += struct.pack("<256f", *(x for row in cos for x in row))
    for start in range(0, MAX_POSITIONS, 8):
        _, sin = resolver.rope_coefficients(GRAPH, range(start, start + 8))
        data += struct.pack("<256f", *(x for row in sin for x in row))
    data += struct.pack(f"<{len(TABLE)}f", *TABLE)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tmp = args.output.with_suffix(args.output.suffix + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(args.output)
    digest = hashlib.sha256(data).hexdigest()
    manifest = {
        "format": "independent-asr-decoder-runtime",
        "version": VERSION,
        "bytes": len(data),
        "sha256": digest,
        "width": WIDTH,
        "layers": LAYERS,
        "max_positions": MAX_POSITIONS,
        "projection_count": len(projections),
        "norm_count": len(norms),
        "projections": projection_manifest,
        "norms": norm_manifest,
        "original_runtime_required": False,
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"output": str(args.output), "bytes": len(data), "sha256": digest}, ensure_ascii=False))

if __name__ == "__main__":
    main()
