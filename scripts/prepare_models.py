"""Executed only in GitHub Actions; no local Python packages are required."""
import argparse
import hashlib
import json
import pathlib
import subprocess

from huggingface_hub import snapshot_download

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--bf16", action="store_true", help="Export BF16 instead of the default Q4 language model")
args = parser.parse_args()
lock = json.loads((ROOT / "runtime.lock.json").read_text())
source = snapshot_download(
    lock["model_repository"],
    revision=lock["model_revision"],
    allow_patterns=["*.json", "*.safetensors", "*.txt", "*.jinja", "LICENSE"],
    local_dir="hf-model",
)
out = ROOT / "models"
out.mkdir(exist_ok=True)
converter = ["python", "vendor/llama.cpp/convert_hf_to_gguf.py", source]
subprocess.run(converter + ["--no-mtp", "--outfile", str(out / "model-bf16.gguf"), "--outtype", "bf16"], check=True)
subprocess.run(converter + ["--mmproj", "--outfile", str(out / "mmproj-f16.gguf"), "--outtype", "f16"], check=True)
language_model = "model-bf16.gguf" if args.bf16 else "model-q4_k_m.gguf"
if not args.bf16:
    subprocess.run(["build-host/bin/llama-quantize", str(out / "model-bf16.gguf"),
                    str(out / language_model), "Q4_K_M"], check=True)
files = {}
for name in (language_model, "mmproj-f16.gguf"):
    path = out / name
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    files[name] = {"sha256": digest, "bytes": path.stat().st_size}
(out / "manifest.json").write_text(json.dumps({
    "format_version": 1, **lock, "files": files,
    "quantization": "BF16" if args.bf16 else lock["quantization"],
    "language_model": language_model, "vision_model": "mmproj-f16.gguf",
}, indent=2) + "\n")
(out / "LICENSE").write_bytes((pathlib.Path(source) / "LICENSE").read_bytes())
