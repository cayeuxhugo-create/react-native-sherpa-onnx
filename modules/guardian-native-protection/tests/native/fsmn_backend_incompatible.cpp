// Host compatibility-test fixture only. Deliberately has no create/infer/destroy
// exports: reaching model creation instead of rejecting this backend must fail.
#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFsmnNative_executionBackend(
    JNIEnv* env, jobject) {
  return env->NewStringUTF("NCNN_OPENMP_CPU");
}
