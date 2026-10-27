"""Build the same FP32 FSMN model/frontend for host JNI and standalone parity.
Uses existing local NCNN and JNI headers. Replaces completed binaries atomically
so an ongoing replay can retain its already-loaded library safely.
"""
import argparse
import os
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--ncnn-source", type=Path, required=True)
p.add_argument("--ncnn-build", type=Path, required=True)
p.add_argument("--jni-include", type=Path, required=True)
p.add_argument("--output", type=Path, required=True)
p.add_argument("--cxx", default="g++")
a = p.parse_args()
platform = (a.ncnn_build / "src/platform.h").read_text()
cache = (a.ncnn_build / "CMakeCache.txt").read_text()
if not re.search(r"^NCNN_OPENMP:BOOL=OFF$", cache, re.M) or not re.search(r"^#define NCNN_SIMPLEOMP 0$", platform, re.M):
    raise SystemExit("FSMN host JNI requires a real serial NCNN build")
symbols = subprocess.check_output(["nm", "-u", str(a.ncnn_build / "src/libncnn.a")], text=True)
if re.search(r"\b(?:__kmp\w*|GOMP_\w*|omp_(?:get|set|in|init|destroy|test)\w*)\b", symbols):
    raise SystemExit("OpenMP runtime reference remains in the host NCNN archive")
module = Path(__file__).resolve().parents[2]
source = module / "android/src/main/cpp/fsmn"
firered = source.parent / "firered"
a.output.mkdir(parents=True, exist_ok=True)
common = [a.cxx, "-O2", "-std=c++17", "-ffp-contract=off", "-pthread", "-DNCNN_OPENMP=0",
          "-I" + str(source), "-I" + str(firered), "-I" + str(a.jni_include),
          "-I" + str(a.ncnn_source / "src"), "-I" + str(a.ncnn_build / "src")]
link = [str(firered / "frontend/fft.cc"), str(a.ncnn_build / "src/libncnn.a")]
for name, inputs, options in [
    ("libguardian_fsmn_vad.so", [str(source / "jni.cpp")],
     ["-shared", "-fPIC", "-fvisibility=hidden", "-Wl,--exclude-libs,ALL",
      "-Wl,--version-script=" + str(source / "exports.map")]),
    ("fsmn_host", [str(module / "tests/native/fsmn_host.cpp")], []),
]:
    temporary = a.output / (name + ".building-" + str(os.getpid()))
    try:
        subprocess.run(common + options + inputs + link + ["-o", str(temporary)], check=True)
        temporary.replace(a.output / name)
    finally:
        temporary.unlink(missing_ok=True)
    print(a.output / name, flush=True)
