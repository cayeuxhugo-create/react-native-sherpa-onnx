"""Compare real streaming Kotlin output with the fixed Python DD reference.

Only aggregate numeric errors are printed. Input/output audio stays in a
temporary directory; an optional corpus manifest must stay outside the repo.
Build WienerReplay, WienerEnhancerTest and GuardianWienerEnhancer into --jar.
"""

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

import numpy as np

from filters import apply, configs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--stdlib", type=Path, required=True)
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--atol", type=float, default=3e-7)
    args = parser.parse_args()
    config = next(c for c in configs() if c["name"] == "adaptive-dd-a0.98-f0.5")
    rng = np.random.default_rng(98)
    random = (rng.normal(size=200_017) * .025).astype(np.float32)
    time = np.arange(len(random) - 48_000) / 16_000
    random[48_000:] += (.06 * np.sin(2 * np.pi * 211 * time)).astype(np.float32)
    cases = [("seeded", random), ("silence", np.zeros(64_000, dtype=np.float32))]
    # Inputs too short to supply finalized enhanced samples exercise startup
    # and stop semantics, including one sample before/after calibration.
    for length in (0, 1, 256, 257, 511, 512, 47_999, 48_000, 48_001, 49_601):
        cases.append((f"prefix-{length}", random[:length]))
    if args.corpus:
        manifest = json.loads(args.corpus.read_text())
        cases.extend((f"corpus-{index}", np.fromfile(row["path"], dtype="<f4"))
                     for index, row in enumerate(
                         (row for row in manifest if row["scope"] == "recent"), 1))
    rows = []
    for name, source in cases:
        expected = apply(source, config)
        previous = None
        for chunks in ("400", "800", "1,257,17,901,63,800,511"):
            with tempfile.TemporaryDirectory(prefix="guardian-wiener-parity-") as temporary:
                input_path = Path(temporary) / "input.f32"
                output_path = Path(temporary) / "output.f32"
                source.tofile(input_path)
                result = subprocess.run(
                    ["java", "-cp", f"{args.jar}:{args.stdlib}",
                     "expo.modules.guardiannativeprotection.WienerReplay",
                     str(input_path), str(output_path), chunks],
                    check=True, capture_output=True, text=True)
                actual = np.fromfile(output_path, dtype="<f4")
            count = max(0, len(source) // 256 - 1) * 256
            if len(actual) != count:
                raise AssertionError(f"{name}: output length {len(actual)} != {count}")
            if not np.isfinite(actual).all():
                raise AssertionError(f"{name}: non-finite output")
            if previous is not None and not np.array_equal(actual, previous):
                raise AssertionError(f"{name}: chunk boundaries alter output")
            previous = actual
            startup = min(len(actual), 48_000)
            if not np.array_equal(actual[:startup], source[:startup]):
                raise AssertionError(f"{name}: startup differs from original")
            error = actual.astype(np.float64) - expected[:len(actual)].astype(np.float64)
            maximum = float(np.max(np.abs(error), initial=0.))
            rms = float(np.sqrt(np.mean(error ** 2))) if len(error) else 0.
            row = dict(case=name, chunks=chunks, input_samples=len(source),
                       output_samples=len(actual), pending_samples=len(source) - len(actual),
                       max_abs_error=maximum, rms_error=rms)
            # Diagnostics contain timings/counts only, never PCM or transcripts.
            row["runtime"] = result.stderr.strip()
            rows.append(row)
            if maximum > args.atol:
                raise AssertionError(f"{name}: max error {maximum} > {args.atol}")
    report = dict(config=config["name"], cases=len(cases), runs=len(rows),
                  max_abs_error=max(r["max_abs_error"] for r in rows),
                  max_rms_error=max(r["rms_error"] for r in rows),
                  chunk_invariant=True, exact_startup_passthrough=True, rows=rows)
    encoded = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
