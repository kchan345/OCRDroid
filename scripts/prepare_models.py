"""Executed only in GitHub Actions; no local Python packages are required."""
import hashlib
import json
import pathlib
import subprocess

from huggingface_hub import snapshot_download

ROOT = pathlib.Path(__file__).resolve().parents[1]
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
subprocess.run(converter + ["--outfile", str(out / "model-bf16.gguf"), "--outtype", "bf16"], check=True)
subprocess.run(converter + ["--mmproj", "--outfile", str(out / "mmproj-f16.gguf"), "--outtype", "f16"], check=True)
subprocess.run(["build-host/bin/llama-quantize", str(out / "model-bf16.gguf"),
                str(out / "model-q4_k_m.gguf"), "Q4_K_M"], check=True)
files = {}
for name in ("model-q4_k_m.gguf", "mmproj-f16.gguf"):
    path = out / name
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    files[name] = {"sha256": digest, "bytes": path.stat().st_size}
(out / "manifest.json").write_text(json.dumps({
    "format_version": 1, **lock, "files": files,
    "language_model": "model-q4_k_m.gguf", "vision_model": "mmproj-f16.gguf",
}, indent=2) + "\n")
(out / "LICENSE").write_bytes((pathlib.Path(source) / "LICENSE").read_bytes())
