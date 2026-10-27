# FSMN offline confirmation — FP32 NCNN

The source weights and CMVN are tracked files from
https://github.com/lovemefan/fsmn-vad/tree/7bb4811a628e29f4645fae15fc9106b2932d089e
at `fsmnvad/runtime/onnx/vad/onnx/`:

| Source | SHA-256 |
|---|---|
| `fsmnvad-offline.onnx` | `4bbd68b11519e916b6871ff6f8df15e2100936b256be9cb104cd63fb7c859965` |
| `am.mvn` | `df189fd5f4352df84a0fd464eeab4e450a5e645665d6b38f13c832492261a739` |

That repository distributes these tracked files under its top-level MIT
license, reproduced in `FSMN_SOURCE_LICENSE.txt`. Its README attributes the
model to ModelScope's `damo/speech_fsmn_vad_zh-cn-16k-common-pytorch` and FunASR.
The current official FunASR MIT notice, retrieved from
https://raw.githubusercontent.com/modelscope/FunASR/main/LICENSE on 2026-10-27,
is reproduced in `FunASR_LICENSE.txt` (SHA-256
`f382b62dcc61fc566b9215b1e3b604b539bfd96ed52ae1eeff5090f6dc176057`).
No separate model-weight license is asserted beyond the tracked source
repository's distribution and notices. This is not an independent license
claim based on a model-card mirror.

## Conversion

`tests/native/convert_fsmn.py` accepts only the two pinned source hashes above.
It reads the fixed ONNX graph and copies every learned FP32 value unchanged:
12 dense projections (transposed to NCNN storage), four causal 20-tap
depthwise-memory convolutions, residual additions, ReLUs, and 248-way softmax.
Dynamic shape scaffolding becomes standard NCNN Permute/Split layers.
There is no FP16 storage, quantization, retraining, or ONNX Runtime dependency.

`cmvn-f64.bin` contains 800 little-endian doubles: 400 AddShift values followed
by 400 Rescale values parsed directly from the upstream decimal CMVN file.
Double precision preserves upstream CMVN operation ordering; this is not the
Float32 CMVN format used by the independent FireRed model.

| Packaged asset | Bytes | SHA-256 |
|---|---:|---|
| `fsmn_vad.ncnn.param` | 2655 | `8391d5366869edfbce1a9fe915a8d5b02a09f746ef0e64d11f9f946dd65d9a47` |
| `fsmn_vad.ncnn.bin` | 1715016 | `3e2261cba5f55ade2c1aa3a8fe1fea6bbcc1f3f625b07ff540ef5ae737df71fa` |
| `cmvn-f64.bin` | 6400 | `ad23bece553b8c8536b34ec630af644888dd3ef2a84776e528e6b4de2b1669fb` |

## Frontend and native isolation

16 kHz float PCM, Hamming 400-sample window / 160-sample hop, 80 mel bins,
LFR 5/1 with boundary replication, and the upstream CMVN. Each confirmation
window has fresh frontend and memory state. The native result is the silence
posterior at class index zero, one value per 10 ms frame; it is not inverted.
Input gain belongs to the Kotlin caller and never changes primary ASR PCM.

The frontend shares the already-vendored Apache-2.0 FFT implementation whose
notices remain in `cpp/firered/frontend/fft.*`; the existing Apache-2.0 license
is reproduced as `FFT_APACHE_LICENSE.txt`.

NCNN 20260113, commit `e956fbf9bd97fe945df35d736747dc816f8b5f42`, is
BSD-3-Clause (`NCNN_LICENSE.txt`). The Android source archive is
`ncnn-20260113-android.zip`, SHA-256
`c05263ca7f1f25bde9ac5172b696a40c55eb931b863b8820be98276ee2eaf7e5`.
`tests/native/build_fsmn_android.py` builds the isolated
`libguardian_fsmn_vad.so` with NDK r27c, API 24, one FP32 CPU thread, no Vulkan,
static NCNN/OpenMP/C++ runtime, 16 KB ELF alignment, and four ABIs. An explicit
export map exposes only the three FSMN JNI functions. Existing FireRed native
code and libraries are not modified.

The model-only synthetic comparison against the pinned ONNX model includes
1/3/20/71/799 frames: maximum absolute error 0.000001133 across all 248
posteriors, 0.000001103 for silence class zero. Full audio/JNI decision parity
is validated separately. The 61-case frontend comparison has 58 nonempty
inputs: maximum normalized feature error 0.00025332 and silence-posterior
error 0.0000152587890625, with no speech-frame classification or upstream
segment changes. Real JNI replay over 132 views, repeated in reverse order,
has no admission or completed-segment-count differences from upstream.
These measurements do not assert bit identity with ONNX or validation of
this new library on a physical Android device.
