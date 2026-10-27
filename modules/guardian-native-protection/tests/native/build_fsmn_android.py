"""Build isolated serial FP32 FSMN JNI for four ABIs, NDK r27c / NCNN 20260113.
First build each NCNN archive with build_fsmn_ncnn.py (OPENMP/SIMPLEOMP OFF).
No download, OpenMP runtime, or modification of the existing FireRed library.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--ndk", type=Path, required=True)
p.add_argument("--ncnn", type=Path, required=True)
p.add_argument("--abis", default="arm64-v8a,armeabi-v7a,x86,x86_64")
a = p.parse_args()
module = Path(__file__).resolve().parents[2]
source = module / "android/src/main/cpp/fsmn"
firered = source.parent / "firered"
bins = a.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi, triple in [("arm64-v8a", "aarch64-linux-android"), ("armeabi-v7a", "armv7a-linux-androideabi"),
                    ("x86", "i686-linux-android"), ("x86_64", "x86_64-linux-android")]:
    if abi not in a.abis.split(","):
        continue
    platform = (a.ncnn / abi / "include/ncnn/platform.h").read_text()
    metadata = json.loads((a.ncnn / abi / "fsmn-serial-build.json").read_text())
    archive = a.ncnn / abi / "lib/libncnn.a"
    if metadata.get("openmp") is not False or metadata.get("simpleomp") is not False or \
            metadata.get("archiveSha256") != hashlib.sha256(archive.read_bytes()).hexdigest() or \
            not re.search(r"^#define NCNN_SIMPLEOMP 0$", platform, re.M):
        raise SystemExit("FSMN must link a real serial NCNN build; OpenMP and SIMPLEOMP must be OFF")
    output = module / "android/src/main/jniLibs" / abi / "libguardian_fsmn_vad.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(bins / (triple + "24-clang++")), "-shared", "-O2", "-std=c++17", "-fPIC",
               "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-ffp-contract=off",
               "-DNCNN_OPENMP=0", "-static-libstdc++", "-Wl,--exclude-libs,ALL",
               "-Wl,--version-script=" + str(source / "exports.map"),
               "-Wl,--gc-sections", "-Wl,-soname,libguardian_fsmn_vad.so", "-Wl,-z,max-page-size=16384",
               "-Wl,-z,defs", "-I" + str(source), "-I" + str(firered), "-I" + str(a.ncnn / abi / "include/ncnn"),
               str(source / "jni.cpp"), str(firered / "frontend/fft.cc"), str(a.ncnn / abi / "lib/libncnn.a"),
               "-llog", "-landroid", "-latomic", "-ldl", "-lm", "-o", str(output)]
    subprocess.run(command, check=True)
    subprocess.run([str(bins / "llvm-strip"), "--strip-unneeded", str(output)], check=True)
    print(abi, output.stat().st_size, flush=True)
