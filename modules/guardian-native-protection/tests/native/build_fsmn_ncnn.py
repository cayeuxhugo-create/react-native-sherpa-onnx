"""Build pinned NCNN for FSMN with no OpenMP runtime or affinity management.

CPU dispatch and the existing FP32 operators remain enabled. Only operators
used by the fixed FSMN graph and their matrix helper layers are compiled.
No OpenMP shim/stub is substituted. Run once per ABI (or --abi host).
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PIN = "e956fbf9bd97fe945df35d736747dc816f8b5f42"
LAYERS = {"input", "innerproduct", "relu", "permute", "split", "convolutiondepthwise1d",
          "binaryop", "softmax", "flatten", "padding", "packing", "cast", "reshape"}
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--source", type=Path, required=True)
p.add_argument("--build-root", type=Path, required=True)
p.add_argument("--install-root", type=Path, required=True)
p.add_argument("--abi", choices=["host", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"], required=True)
p.add_argument("--ndk", type=Path)
p.add_argument("--cmake", default="cmake")
p.add_argument("--parallel", type=int, default=2)
a = p.parse_args()
revision = subprocess.check_output(["git", "-C", str(a.source), "rev-parse", "HEAD"], text=True).strip()
if revision != PIN:
    raise SystemExit("Unexpected NCNN source commit")
subprocess.run(["git", "-C", str(a.source), "diff", "--quiet", "HEAD", "--", "CMakeLists.txt", "src", "cmake"], check=True)
available = set(re.findall(r"ncnn_add_layer\((\w+)", (a.source / "src/CMakeLists.txt").read_text()))
if not LAYERS.issubset({layer.lower() for layer in available}):
    raise SystemExit("Required FSMN operators missing from pinned NCNN")
build = a.build_root / a.abi
install = a.install_root / a.abi
command = [a.cmake, "-S", str(a.source), "-B", str(build), "-DCMAKE_BUILD_TYPE=Release",
           "-DCMAKE_INSTALL_PREFIX=" + str(install), "-DNCNN_OPENMP=OFF", "-DNCNN_SIMPLEOMP=OFF",
           "-DNCNN_SHARED_LIB=OFF", "-DNCNN_VULKAN=OFF", "-DNCNN_BUILD_TOOLS=OFF",
           "-DNCNN_BUILD_EXAMPLES=OFF", "-DNCNN_BUILD_BENCHMARK=OFF", "-DNCNN_BUILD_TESTS=OFF",
           "-DNCNN_INSTALL_SDK=ON", "-DNCNN_ENABLE_LTO=OFF", "-DNCNN_RUNTIME_CPU=ON"]
command += ["-DWITH_LAYER_" + layer.lower() + "=" + ("ON" if layer.lower() in LAYERS else "OFF")
            for layer in sorted(available)]
if a.abi != "host":
    if not a.ndk:
        raise SystemExit("--ndk is required for Android")
    command += ["-DCMAKE_TOOLCHAIN_FILE=" + str(a.ndk / "build/cmake/android.toolchain.cmake"),
                "-DANDROID_ABI=" + a.abi, "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static"]
build.mkdir(parents=True, exist_ok=True)
subprocess.run(command, check=True)
subprocess.run([a.cmake, "--build", str(build), "--parallel", str(a.parallel)], check=True)
subprocess.run([a.cmake, "--install", str(build)], check=True)
platform = (install / "include/ncnn/platform.h").read_text()
cache = (build / "CMakeCache.txt").read_text()
if not re.search(r"^NCNN_OPENMP:BOOL=OFF$", cache, re.M) or not re.search(r"^#define NCNN_SIMPLEOMP 0$", platform, re.M):
    raise SystemExit("NCNN is not a real serial build")
archive = install / "lib/libncnn.a"
nm = str(a.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm") if a.ndk else "nm"
symbols = subprocess.check_output([nm, "-u", str(archive)], text=True)
if re.search(r"\b(?:__kmp\w*|GOMP_\w*|omp_(?:get|set|in|init|destroy|test)\w*)\b", symbols):
    raise SystemExit("OpenMP runtime reference remains in the NCNN archive")
metadata = dict(sourceCommit=revision, abi=a.abi, openmp=False, simpleomp=False,
                cpuDispatch=True, layers=sorted(LAYERS), configureCommand=command,
                archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest())
(install / "fsmn-serial-build.json").write_text(json.dumps(metadata, indent=2) + "\n")
print("SERIAL NCNN READY", a.abi, archive, flush=True)
