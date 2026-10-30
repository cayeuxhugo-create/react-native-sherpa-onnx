"""Offline real-model host test bridge. No microphone, ASR, or network access.

Protocol: big-endian sample count, little-endian float32 payload. A reset (-1)
acknowledges zero; stop (-2) exits without flushing artificial audio.
"""
import hashlib
import struct
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx


def read_exact(count):
    data = bytearray()
    while len(data) < count:
        block = sys.stdin.buffer.read(count - len(data))
        if not block:
            raise EOFError("Incomplete test input")
        data.extend(block)
    return bytes(data)


model = Path(sys.argv[1])
assert hashlib.sha256(model.read_bytes()).hexdigest() == "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534"
config = sherpa_onnx.OnlineSpeechDenoiserConfig()
config.model.gtcrn.model = str(model)
config.model.num_threads = 1
config.model.provider = "cpu"
denoiser = sherpa_onnx.OnlineSpeechDenoiser(config)
sys.stdout.buffer.write(struct.pack(">i", 256))
sys.stdout.buffer.flush()
while True:
    count = struct.unpack(">i", read_exact(4))[0]
    if count == -2:
        break
    if count == -1:
        denoiser.reset()
        sys.stdout.buffer.write(struct.pack(">i", 0))
    else:
        assert count == 256
        pcm = np.frombuffer(read_exact(count * 4), dtype="<f4").copy()
        result = np.asarray(denoiser.run(pcm, 16000).samples, dtype="<f4")
        sys.stdout.buffer.write(struct.pack(">i", result.size))
        sys.stdout.buffer.write(result.tobytes())
    sys.stdout.buffer.flush()
