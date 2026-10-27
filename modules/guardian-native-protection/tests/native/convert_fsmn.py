"""Lossless FP32 layout conversion of the pinned offline FSMN ONNX to NCNN.

This is an architecture-specific converter, not a general ONNX importer. The
source SHA-256 is mandatory: an unfamiliar graph is rejected. Dense matrices
are transposed into NCNN InnerProduct storage; the four 20-tap left-context
depthwise convolutions and residuals are represented with standard NCNN layers.
No inference, quantization, FP16 conversion, or model retraining occurs here.
Requires NumPy; no ONNX/protobuf package or compiler is needed.
"""
import argparse
import collections
import hashlib
import re
import struct
from pathlib import Path

import numpy as np

ONNX_SHA256 = "4bbd68b11519e916b6871ff6f8df15e2100936b256be9cb104cd63fb7c859965"
CMVN_SHA256 = "df189fd5f4352df84a0fd464eeab4e450a5e645665d6b38f13c832492261a739"


def varint(data, offset):
    value = shift = 0
    while True:
        if offset >= len(data) or shift > 63:
            raise ValueError("Invalid protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7


def fields(data):
    """Read only standard protobuf wire fields; schema field numbers follow ONNX."""
    result = collections.defaultdict(list)
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = data[offset:offset + size]
            offset += size
        elif wire == 2:
            size, offset = varint(data, offset)
            value = data[offset:offset + size]
            offset += size
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire}")
        if offset > len(data):
            raise ValueError("Truncated protobuf field")
        result[number].append(value)
    return result


def dimensions(values):
    result = []
    for value in values:
        if isinstance(value, int):
            result.append(value)
        else:
            offset = 0
            while offset < len(value):
                item, offset = varint(value, offset)
                result.append(item)
    return result


def convert(onnx_path, cmvn_path, destination):
    model = onnx_path.read_bytes()
    cmvn = cmvn_path.read_bytes()
    if hashlib.sha256(model).hexdigest() != ONNX_SHA256:
        raise ValueError("Unsupported FSMN ONNX SHA-256; inspect the new graph explicitly")
    if hashlib.sha256(cmvn).hexdigest() != CMVN_SHA256:
        raise ValueError("Unsupported FSMN CMVN SHA-256")
    graph = fields(fields(model)[7][0])
    if len(graph[1]) != 114 or len(graph[5]) != 32:
        raise ValueError("Unexpected FSMN graph structure")
    tensors = {}
    for initializer in graph[5]:
        tensor = fields(initializer)
        if tensor[2] != [1]:  # Only FP32 learned parameters; skip int64 shape constants.
            continue
        shape = dimensions(tensor[1])
        values = np.frombuffer(tensor[9][0], dtype="<f4")
        tensors[tensor[8][0].decode()] = values.reshape(shape)
    destination.mkdir(parents=True, exist_ok=True)
    layers, blobs, weights = [], set(), bytearray()

    def layer(kind, name, inputs, outputs, parameters=""):
        blobs.update(outputs)
        layers.append(" ".join(map(str, [kind, name, len(inputs), len(outputs), *inputs, *outputs])) +
                      (" " + parameters if parameters else ""))

    def dense(name, source, matrix_name, bias_name=None):
        matrix = tensors[matrix_name]
        if matrix.ndim != 2:
            raise ValueError("Expected a dense matrix")
        inputs, outputs = matrix.shape
        weights.extend(struct.pack("<I", 0))  # NCNN FP32 storage marker.
        weights.extend(matrix.T.astype("<f4", copy=False).tobytes())
        if bias_name:
            bias = tensors[bias_name]
            if bias.shape != (outputs,):
                raise ValueError("Unexpected dense bias shape")
            weights.extend(bias.astype("<f4", copy=False).tobytes())
        layer("InnerProduct", name, [source], [name],
              f"0={outputs} 1={int(bias_name is not None)} 2={inputs * outputs}")
        return name

    layer("Input", "speech", [], ["speech"])
    current = dense("input_affine1", "speech", "onnx::MatMul_175", "encoder.in_linear1.linear.bias")
    current = dense("input_affine2", current, "onnx::MatMul_176", "encoder.in_linear2.linear.bias")
    layer("ReLU", "input_relu", [current], ["input_relu"])
    current = "input_relu"
    for index, (projection, affine) in enumerate(((177, 180), (181, 184), (185, 188), (189, 192))):
        prefix = f"fsmn{index}"
        current = dense(prefix + "_projection", current, f"onnx::MatMul_{projection}")
        layer("Permute", prefix + "_transpose", [current], [prefix + "_transpose"], "0=1")
        layer("Split", prefix + "_split", [prefix + "_transpose"], [prefix + "_memory", prefix + "_residual"])
        kernel = tensors[f"encoder.fsmn.{index}.fsmn_block.conv_left.weight"]
        if kernel.shape != (128, 1, 20, 1):
            raise ValueError("Unexpected temporal convolution shape")
        weights.extend(struct.pack("<I", 0))
        weights.extend(kernel.astype("<f4", copy=False).tobytes())
        layer("ConvolutionDepthWise1D", prefix + "_left", [prefix + "_memory"], [prefix + "_left"],
              "0=128 1=20 2=1 3=1 4=19 15=0 5=0 6=2560 7=128")
        layer("BinaryOp", prefix + "_add", [prefix + "_residual", prefix + "_left"], [prefix + "_add"], "0=0")
        layer("Permute", prefix + "_restore", [prefix + "_add"], [prefix + "_restore"], "0=1")
        current = dense(prefix + "_affine", prefix + "_restore", f"onnx::MatMul_{affine}",
                        f"encoder.fsmn.{index}.affine.linear.bias")
        layer("ReLU", prefix + "_relu", [current], [prefix + "_relu"])
        current = prefix + "_relu"
    current = dense("output_affine1", current, "onnx::MatMul_193", "encoder.out_linear1.linear.bias")
    current = dense("output_affine2", current, "onnx::MatMul_194", "encoder.out_linear2.linear.bias")
    layer("Softmax", "logits", [current], ["logits"], "0=1 1=1")
    (destination / "fsmn_vad.ncnn.param").write_text("7767517\n" + f"{len(layers)} {len(blobs)}\n" + "\n".join(layers) + "\n")
    (destination / "fsmn_vad.ncnn.bin").write_bytes(weights)
    vectors = re.findall(r"<LearnRateCoef>\s+0\s+\[([^]]+)\]", cmvn.decode())
    if len(vectors) != 2:
        raise ValueError("Expected AddShift and Rescale CMVN vectors")
    stats = [np.fromstring(vector, sep=" ", dtype="<f8") for vector in vectors]
    if any(stat.shape != (400,) for stat in stats) or not np.isfinite(stats).all() or np.any(stats[1] <= 0):
        raise ValueError("Invalid FSMN CMVN vectors")
    (destination / "cmvn-f64.bin").write_bytes(np.concatenate(stats).astype("<f8", copy=False).tobytes())
    for path in sorted(destination.glob("*")):
        if path.is_file():
            print(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--cmvn", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    convert(args.onnx, args.cmvn, args.output)
