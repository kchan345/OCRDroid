# OCRDroid

Offline Android 13 / API 33+ OCR using the Apache-2.0
[ATH-MaaS/OvisOCR2](https://huggingface.co/ATH-MaaS/OvisOCR2) 0.8B model.

## Run the application

1. Install the preview APK from a successful `Build and exercise OCR app` run
   (or the repository's preview release). It supports arm64-v8a phones and
   x86_64 emulators on Android 13 or later. The preview is debug-signed, not a
   Play Store production release.
2. Download and extract `ovisocr2-q4` on the phone. Select **Import model folder**
   and choose the directory containing `manifest.json` and both GGUF files.
   The app verifies the pinned revision, sizes, GGUF headers, and SHA-256 hashes,
   then copies the weights into private storage. Allow space for both the
   downloaded folder and the private copy.
3. Leave **Q4_K_M** selected. Choose **Camera** for a full-resolution capture or
   **Photos** for the system photo picker, then tap **Read with OvisOCR2**.
   Photo orientation is normalized and the longest edge is limited to 2048 pixels.
4. Edit the result in the plain-text field. Select text to highlight matching
   image regions. Choose the appropriate **Highlight language** before running
   OCR. **Save text** exports UTF-8 text to a user-selected destination.

There is no INTERNET permission in the installed app, no server inference, no
model download at runtime, and no broad photo-library permission. Captures use an
external camera app and a narrowly scoped FileProvider URI. Model folders use
Android's Storage Access Framework. The app retains edits through rotation;
save text before closing it because drafts are not persisted across process death.
The photo and imported weights remain in private app storage until replaced or
the app is uninstalled.

## Verified Android inference

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

The first passing gate is
[run 37381343053](https://github.com/kchan345/OCRDroid/actions/runs/37381343053).
Its API 33 x86_64 emulator used 4096 MiB RAM, and produced:

| Image | Recognized content | Elapsed seconds | Peak native RSS (KiB) |
| --- | --- | --- | --- |
| receipt | LOCAL OCR / Invoice 4729 / Total 38.50 | 74.4059 | 935280 |
| note | OFFLINE NOTES / Blue river / Code 8264 | 72.8528 | 935168 |

These are two synthetic smoke fixtures, not a document accuracy benchmark.
Elapsed time includes loading the model and excludes app localization.
The Q4 language GGUF is 529296960 bytes and its F16 projector is 204986560 bytes.
Emulator evidence does **not**
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
included in the default smaller artifact. BF16 GGUF support is the optional
higher-precision path; raw Hugging Face safetensors cannot be loaded directly by
llama.cpp and must be converted in CI, not on the phone.

Use the `Export optional BF16 model folder` workflow to export the BF16 language
model with the matching F16 vision projector and a checksum manifest. This
higher-memory profile is optional and separate from Q4 hardware acceptance.
Its native Android execution passed in
[run 37383883636](https://github.com/kchan345/OCRDroid/actions/runs/37383883636):
the receipt took 171.523 seconds at 1899336 KiB peak RSS, and the note took
168.741 seconds at 1899748 KiB. Both produced the same expected text as Q4.
These are emulator measurements, not phone performance estimates.
Import that exported folder using the same button; the precision selector changes
to **BF16**. Both precision variants can remain installed, and the selector switches
between them. Arbitrary HF folders or other models are intentionally rejected.

The initial mobile profile uses CPU inference, four or fewer threads, 4096
context tokens, and at most 1024 image tokens. This is deliberately smaller than
the publisher's server profile (up to 2880x2880 pixels and 16384 output tokens).
The app allows 2048 output tokens and explicitly reports truncation. Dense pages
may need cropping in a photo editor before import. Inference runs on a worker,
keeps the screen awake while visible, and can be cancelled. Cancellation during
vision encoding may wait until that native operation finishes.

OvisOCR2 emits Markdown, formulas, HTML tables, and **visual-region** boxes.
Its documented format does not provide text-word bounding boxes. Those visual
boxes must not be misrepresented as text-selection coordinates. The application
uses **bundled ML Kit** for offline line localization only. All displayed OCR text
comes from OvisOCR2. ML Kit is a separate Google-licensed component, not part of
the open-weight OvisOCR2 model. Latin, Chinese, Japanese, Korean, and Devanagari
localizers are bundled; other Ovis-supported scripts can still transcribe but
may not highlight reliably.

Alignment matches unique complete line token sequences, then unambiguous individual
words. Duplicate/ambiguous or unmatched text gets no guessed box, and the UI says
when a selection cannot be located. Edits invalidate affected word associations
and shift unaffected UTF-16 ranges; changing image starts a new result.

## CI and development

All builds/tests run on GitHub Actions, not on the local workstation:

| Workflow | Purpose |
| --- | --- |
| Android inference gate | Convert/quantize pinned weights; build ARM64/x86_64 probes; execute Android OCR and enforce memory/output checks |
| Build and exercise OCR app | Require a matching successful core gate; unit tests, Android lint, APK build, then actual offline JNI inference, SAF import, localization, selection, edits and rotation on API 33 |
| Export optional BF16 model folder | Export higher-precision language GGUF with matching F16 projector and manifest |
| Physical ARM64 acceptance | Repeat the native gate on a supplied real phone; currently awaiting a device runner |
| Publish verified preview | Publish only successful app/model artifacts as a prerelease, with evidence and SHA-256 checksums |

Changes to the native core or model lock require a new successful inference gate
before running app CI. For app-only changes, CI reuses the latest compatible
successful gate. Its APK artifact is uploaded only after instrumentation passes.
The debug-only fixture provider is protected by the platform's signature-level
MANAGE_DOCUMENTS permission and exercises SAF imports in CI.
The NDK build uses 16 KiB-compatible JNI library alignment and optimized CPU code,
including in the development APK. SDK, NDK, Gradle and Python dependencies are
provisioned only inside workflows.

Model license: [Apache-2.0](https://huggingface.co/ATH-MaaS/OvisOCR2/blob/main/LICENSE).
Runtime: [llama.cpp / MIT](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE).
Auxiliary localization: [ML Kit terms](https://developers.google.com/ml-kit/terms).
OvisOCR2 can hallucinate or omit content; manually verify important documents.
