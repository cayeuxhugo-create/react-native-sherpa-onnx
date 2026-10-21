FireRed confirmation assets — Guardian Voice v3.3

Model: FireRed VAD non-streaming (not Stream-VAD).
Original source/model and Apache-2.0 license:
https://github.com/FireRedTeam/FireRedVAD/tree/c30ec49e8cc69642b0ee65362eba11b9d11c6e54

NCNN conversion, FP16 stored weights / FP32 CPU inference:
https://github.com/lhwcv/FireRedVAD-NCNN-streaming/tree/8e9d2694e7e55abeda4bfdb14f31e56dc0d4c95e/convert/out
Files: firered_vad_non_stream.ncnn.param and .bin, copied without modification.
CMVN: original cmvn.ark converted to 80 means then 80 inverse standard deviations,
160 little-endian float32 values, no quantization except conversion to float32.

NCNN 20260113 (BSD-3-Clause):
https://github.com/Tencent/ncnn/tree/e956fbf9bd97fe945df35d736747dc816f8b5f42
The adjacent FIRERED_LICENSE.txt and NCNN_LICENSE.txt apply to these components.

Frontend adapted from FireRed's runtime/common/frontend at the source commit above.
Changes: frame-local Kaldi pre-emphasis and float epsilon mel-energy floor, matching
the kaldi-native-fbank configuration used for the reference evaluation. This is a
non-streaming confirmation frontend. Each completed window has fresh feature state.

Native JNI: static NCNN/OpenMP/C++ runtime, no ONNX Runtime dependency, 1 CPU thread,
no Vulkan/GPU, 16 KB ELF segment alignment, Android API 24 minimum, four ABIs.

This integration does not assert NCNN probabilities are bit-identical to ONNX.
Measured: 647 window/perturbation decisions identical on host CPU; maximum absolute
probability difference 0.009052. Android runtime/device validation remains required.
