"""Build isolated FP32 FSMN JNI for four ABIs from local NDK r27c / NCNN 20260113.
No download and no modification of the existing FireRed JNI library.
"""
import argparse
import subprocess
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--ndk", type=Path, required=True)
p.add_argument("--ncnn", type=Path, required=True)
a = p.parse_args()
module = Path(__file__).resolve().parents[2]
source = module / "android/src/main/cpp/fsmn"
firered = source.parent / "firered"
bins = a.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi, triple in [("arm64-v8a", "aarch64-linux-android"), ("armeabi-v7a", "armv7a-linux-androideabi"),
                    ("x86", "i686-linux-android"), ("x86_64", "x86_64-linux-android")]:
    output = module / "android/src/main/jniLibs" / abi / "libguardian_fsmn_vad.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(bins / (triple + "24-clang++")), "-shared", "-O2", "-std=c++17", "-fPIC",
               "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-ffp-contract=off",
               "-fopenmp", "-static-openmp", "-static-libstdc++", "-Wl,--exclude-libs,ALL",
               "-Wl,--version-script=" + str(source / "exports.map"),
               "-Wl,--gc-sections", "-Wl,-soname,libguardian_fsmn_vad.so", "-Wl,-z,max-page-size=16384",
               "-Wl,-z,defs", "-I" + str(source), "-I" + str(firered), "-I" + str(a.ncnn / abi / "include/ncnn"),
               str(source / "jni.cpp"), str(firered / "frontend/fft.cc"), str(a.ncnn / abi / "lib/libncnn.a"),
               "-llog", "-landroid", "-latomic", "-ldl", "-lm", "-o", str(output)]
    subprocess.run(command, check=True)
    subprocess.run([str(bins / "llvm-strip"), "--strip-unneeded", str(output)], check=True)
    print(abi, output.stat().st_size, flush=True)
