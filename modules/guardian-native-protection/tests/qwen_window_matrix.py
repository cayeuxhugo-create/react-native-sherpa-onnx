#!/usr/bin/env python3
"""Opt-in real Qwen CPU evaluation. Inputs/results must stay outside the checkout.

JSON jobs: [{id, original (float32LE path), filtered (optional path), alpha,
maximumGain, admitted, expected (optional reference)}]. Sources are aligned slices
of the same clock, never WAV int16 approximations. No network or model downloads.
An empty expected string explicitly labels a non-speech control.
"""
import argparse
import hashlib
import json
import re
import time
import unicodedata
from pathlib import Path


def words(text):
    text = unicodedata.normalize("NFKC", text).casefold().replace("’", "'")
    return re.findall(r"[^\W_]+", text, re.UNICODE)


def errors(reference, hypothesis):
    a, b = words(reference), words(hypothesis)
    previous = list(range(len(b) + 1))
    for i, token in enumerate(a, 1):
        current = [i]
        for j, other in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[-1] + 1,
                               previous[j - 1] + (token != other)))
        previous = current
    return {"wordErrors": previous[-1], "referenceWords": len(a),
            "exactNormalized": a == b, "unexpectedText": not a and bool(b)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--jobs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    import numpy as np
    import sherpa_onnx
    jobs = json.loads(args.jobs.read_text())
    model = args.model
    def file_sha(path):
        with path.open("rb") as source:
            return hashlib.file_digest(source, "sha256").hexdigest()
    model_hashes = {str(p.relative_to(model)): file_sha(p)
                    for p in sorted(model.rglob("*")) if p.is_file()}
    config = dict(num_threads=1, sample_rate=16000, feature_dim=128,
                  max_total_len=512, max_new_tokens=256, temperature=1e-6,
                  top_p=.8, seed=42, provider="cpu")
    recognizer = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=str(model / "conv_frontend.onnx"),
        encoder=str(model / "encoder.int8.onnx"), decoder=str(model / "decoder.int8.onnx"),
        tokenizer=str(model / "tokenizer"), **config)
    identity = hashlib.sha256(json.dumps(dict(model=model_hashes, config=config,
                                              runtime=sherpa_onnx.__version__), sort_keys=True).encode()).hexdigest()
    prior = [json.loads(line) for line in args.output.read_text().splitlines()] if args.output.exists() else []
    cache = {(row["evaluationIdentity"], row["pcmSha256"]): row for row in prior}
    completed = {(r["id"], r["pcmSha256"], r["evaluationIdentity"], r.get("jobIdentity")) for r in prior}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("a", buffering=1) as out:
        for job in jobs:
            job_identity = hashlib.sha256(json.dumps(job, sort_keys=True).encode()).hexdigest()
            x = np.fromfile(job["original"], dtype="<f4")
            alpha = float(job.get("alpha", 0))
            assert 0 <= alpha <= 1 and x.size and np.isfinite(x).all()
            if alpha:
                y = np.fromfile(job["filtered"], dtype="<f4")
                assert y.size == x.size and np.isfinite(y).all()
                x = (x + np.float32(alpha) * (y - x)).astype("<f4")
            maximum = float(job.get("maximumGain", 1))
            assert 1 <= maximum <= 4
            rms = float(np.sqrt(np.mean(x.astype(np.float64) ** 2)))
            peak = float(np.max(np.abs(x)))
            gain = max(1., min(maximum, .015 / rms, .8 / peak)) if job.get("admitted", False) and rms and peak else 1.
            x = (x * np.float32(gain)).astype("<f4")
            sha = hashlib.sha256(x.tobytes()).hexdigest()
            if (job["id"], sha, identity, job_identity) in completed:
                continue
            cached = cache.get((identity, sha))
            if cached:
                text, elapsed = cached["text"], cached["decodeSeconds"]
            else:
                stream = recognizer.create_stream()
                stream.set_option("language", "French")
                stream.accept_waveform(16000, x)
                start = time.monotonic()
                recognizer.decode_stream(stream)
                elapsed = time.monotonic() - start
                text = stream.result.text
            row = dict(job, text=text, decodeSeconds=elapsed, gain=gain, pcmSha256=sha,
                       jobIdentity=job_identity,
                       evaluationIdentity=identity, reusedDecode=bool(cached),
                       samples=int(x.size), sampleRate=16000, runtime=sherpa_onnx.__version__)
            if "expected" in job:
                row.update(errors(job["expected"], text))
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            cache[identity, sha] = row
            print(f"Completed {job['id']} ({elapsed:.2f}s, cached={bool(cached)})", flush=True)
    args.output.with_suffix(".provenance.json").write_text(json.dumps(
        dict(modelHashes=model_hashes, configuration=config, forcedLanguage="French",
             runtime=sherpa_onnx.__version__, evaluationIdentity=identity), indent=2))


if __name__ == "__main__":
    main()
