# OCRDroid

Offline Android 13 / API 33+ OCR using the Apache-2.0
[ATH-MaaS/OvisOCR2](https://huggingface.co/ATH-MaaS/OvisOCR2) 0.8B model.

## Inference gate (first milestone)

The `Android inference gate` GitHub Actions workflow converts the pinned original
weights to GGUF, quantizes the language model to **Q4_K_M**, retains the vision
encoder/projector in F16, and cross-compiles a CPU-only runtime for arm64-v8a and
x86_64. Q4_K_M is mixed 4-bit quantization, not every tensor being exactly 4 bits.
All model preparation, SDK/NDK installation, builds, and tests occur on GitHub
runners. No SDK or additional local tools are required.

The gate runs actual image-conditioned generation in an API 33 Android emulator
with networking disabled. Two different images must produce their expected text,
reach end-of-generation, and remain below 3.5 GiB peak native RSS. Artifacts include
the model folder, both native probes, OCR text, runtime/memory measurements, and
device properties. A native build alone is **not** inference evidence.

The app milestone is gated on this runtime check. Emulator evidence does **not**
establish ARM phone speed, thermals, or memory behavior. A physical arm64 device
with more than 6 GB RAM is still required for hardware acceptance.

`Physical ARM64 acceptance` is a manually dispatched workflow for an existing,
dedicated Linux runner labeled `android-device`, with `adb`, Python, and exactly
one authorized phone connected. Put that phone in airplane mode with Wi-Fi off
before dispatching; the workflow does not change the phone's radio settings.
Supply a successful inference run ID to reuse its exact model/probe artifacts.
The workflow rejects emulators, older APIs, non-ARM64 devices, and devices with
6,000,000 KiB or less reported RAM, then repeats the OCR and memory gate. It
records elapsed times without inventing a latency target. No physical runner or
phone is provisioned by this repository.

## Model formats and limits

`runtime.lock.json` pins both model and llama.cpp revisions. The model folder is
`model-q4_k_m.gguf`, `mmproj-f16.gguf`, `manifest.json`, and `LICENSE`.
Conversion uses `--no-mtp`: OvisOCR2's inherited configuration advertises an MTP
draft layer that is absent from the published weights. Including that layer's
metadata makes llama.cpp reject the model with a missing `blk.24` tensor.
Conversion also produces `model-bf16.gguf` in CI before quantization; it is not
included in the default smaller artifact. BF16 GGUF support is the intended
higher-precision path; raw Hugging Face safetensors cannot be loaded directly by
llama.cpp and must be converted in CI, not on the phone.

Use the `Export optional BF16 model folder` workflow to export the BF16 language
model with the matching F16 vision projector and a checksum manifest. This
higher-memory profile is optional and separate from Q4 hardware acceptance.

The initial mobile profile uses CPU inference, four or fewer threads, 4096
context tokens, and at most 1024 image tokens. This is deliberately smaller than
the publisher's server profile (up to 2880x2880 pixels and 16384 output tokens).
Dense pages may need crops, and truncated output must be reported as incomplete.

OvisOCR2 emits Markdown, formulas, HTML tables, and **visual-region** boxes.
Its documented format does not provide text-word bounding boxes. Those visual
boxes must not be misrepresented as text-selection coordinates. The application
will need local text localization/alignment for grounded highlighting.
