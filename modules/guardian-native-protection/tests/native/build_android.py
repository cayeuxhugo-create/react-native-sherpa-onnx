"""Rebuild the four isolated NCNN JNI libraries using NDK r27c and NCNN 20260113.
No download occurs in this script; supply verified upstream archives/checkouts.
"""
import argparse,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--ndk',type=Path,required=True);p.add_argument('--ncnn',type=Path,required=True);a=p.parse_args()
module=Path(__file__).resolve().parents[2];source=module/'android/src/main/cpp/firered';bins=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';destination=module/'android/src/main/jniLibs'
for abi,triple in [('arm64-v8a','aarch64-linux-android'),('armeabi-v7a','armv7a-linux-androideabi'),('x86','i686-linux-android'),('x86_64','x86_64-linux-android')]:
 out=destination/abi/'libguardian_firered_vad.so';out.parent.mkdir(parents=True,exist_ok=True)
 args=[str(bins/(triple+'24-clang++')),'-shared','-O2','-std=c++17','-fPIC','-fvisibility=hidden','-ffunction-sections','-fdata-sections','-fopenmp','-static-openmp','-static-libstdc++','-Wl,--exclude-libs,ALL','-Wl,--gc-sections','-Wl,-soname,libguardian_firered_vad.so','-Wl,-z,max-page-size=16384','-Wl,-z,defs','-I'+str(source),'-I'+str(a.ncnn/abi/'include/ncnn'),str(source/'jni.cpp'),str(source/'frontend/fft.cc'),str(a.ncnn/abi/'lib/libncnn.a'),'-llog','-landroid','-latomic','-ldl','-lm','-o',str(out)]
 subprocess.run(args,check=True);subprocess.run([str(bins/'llvm-strip'),'--strip-unneeded',str(out)],check=True);print(abi,out.stat().st_size,flush=True)
