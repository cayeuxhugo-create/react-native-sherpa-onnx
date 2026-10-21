"""Native Sherpa/Silero/GTCRN checks; no Qwen inference or device-range claim.
Usage: python validate_models.py --speech-wav /path/to/16khz-mono-pcm16.wav
Requires numpy, sherpa-onnx==1.13.8 (and sherpa-onnx-core).
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import wave

import numpy as np
import sherpa_onnx as sherpa

ASSETS = Path(__file__).resolve().parents[1] / 'android/src/main/assets'
DIGESTS = {
    'silero_vad.onnx': '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6',
    'gtcrn_simple.onnx': 'e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534',
}

def detector():
    config = sherpa.VadModelConfig()
    config.silero_vad.model = str(ASSETS / 'silero_vad.onnx')
    config.silero_vad.threshold = 0.5
    config.silero_vad.min_speech_duration = 0.128
    config.silero_vad.min_silence_duration = 0.128
    config.silero_vad.max_speech_duration = 30
    config.silero_vad.window_size = 512
    config.sample_rate = 16000
    config.num_threads = 1
    return sherpa.VoiceActivityDetector(config, buffer_size_in_seconds=40)

def vad_frames(pcm):
    vad = detector()
    flags = []
    for offset in range(0, len(pcm), 800):
        vad.accept_waveform(pcm[offset:offset + 800])
        flags.append(vad.is_speech_detected())
        while not vad.empty():
            vad.pop()
    return flags

def denoise(pcm):
    config = sherpa.OnlineSpeechDenoiserConfig()
    config.model.gtcrn.model = str(ASSETS / 'gtcrn_simple.onnx')
    config.model.num_threads = 1
    denoiser = sherpa.OnlineSpeechDenoiser(config)
    outputs = []
    for offset in range(0, len(pcm), 800):
        outputs.extend(denoiser.run(pcm[offset:offset + 800], 16000).samples)
    return np.asarray(outputs, dtype=np.float32)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--speech-wav', required=True)
    parser.add_argument('--report')
    args = parser.parse_args()
    for name, digest in DIGESTS.items():
        assert hashlib.sha256((ASSETS / name).read_bytes()).hexdigest() == digest, name
    with wave.open(args.speech_wav) as wav:
        assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, 16000)
        voice = np.frombuffer(wav.readframes(wav.getnframes()), '<i2').astype(np.float32) / 32768
    assert len(voice) >= 16000, 'Speech fixture must contain at least a second of actual speech'
    rng = np.random.default_rng(2026)
    t = np.arange(16000 * 10, dtype=np.float32) / 16000
    scenarios = {
        'silence_10s': np.zeros(len(t), dtype=np.float32),
        'white_noise_rms_0.003_10s': rng.normal(0, 0.003, len(t)).astype(np.float32),
        'white_noise_rms_0.03_10s': rng.normal(0, 0.03, len(t)).astype(np.float32),
        'hum_100hz_10s': (0.03 * np.sin(2 * np.pi * 100 * t)).astype(np.float32),
        'speech_reference': voice,
        'speech_attenuated_x0.1': voice * 0.1,
        'speech_plus_noise_rms_0.003': voice + rng.normal(0, 0.003, len(voice)).astype(np.float32),
        'attenuated_speech_plus_noise_rms_0.003': voice * 0.1 + rng.normal(0, 0.003, len(voice)).astype(np.float32),
    }
    report = {'sherpa_version': sherpa.__version__, 'scenarios': {}}
    for name, pcm in scenarios.items():
        started = time.monotonic()
        flags = vad_frames(pcm)
        row = {'frames': len(flags), 'speech_frames': sum(flags),
               'runtime_seconds': round(time.monotonic() - started, 3)}
        report['scenarios'][name] = row
        print(name, row)
        if name.startswith(('silence_', 'white_noise_', 'hum_')):
            assert not any(flags), f'Neural VAD admitted non-speech fixture: {name}'
        else:
            assert sum(flags) > 0, f'Neural VAD rejected all speech: {name}'
    noisy = scenarios['attenuated_speech_plus_noise_rms_0.003']
    enhanced = denoise(noisy)
    assert len(enhanced) > 0 and len(enhanced) <= len(noisy)
    assert np.isfinite(enhanced).all()
    assert any(vad_frames(enhanced)), 'GTCRN path removed all speech'
    report['gtcrn'] = {'input_samples': len(noisy), 'output_samples': len(enhanced),
                       'speech_frames': sum(vad_frames(enhanced))}
    # No speech after denoising synthetic background noise either.
    noise_output = denoise(scenarios['white_noise_rms_0.003_10s'])
    assert not any(vad_frames(noise_output)), 'GTCRN noise became speech'
    print('GTCRN', report['gtcrn'])
    print('Native model checks passed (synthetic noise and reference speech only)')
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + '\n')

if __name__ == '__main__':
    main()
