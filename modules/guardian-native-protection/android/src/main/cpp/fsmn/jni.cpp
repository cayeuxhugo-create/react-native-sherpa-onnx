#include <jni.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include "model.h"

namespace {
void fail(JNIEnv* env, const char* message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::vector<unsigned char> bytes(JNIEnv* env, jbyteArray value) {
  if (!value) throw std::invalid_argument("FSMN null asset");
  const auto size = env->GetArrayLength(value);
  std::vector<unsigned char> result(size);
  env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
  return result;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFsmnNative_executionBackend(JNIEnv* env, jobject) {
#if !defined(NCNN_OPENMP) || NCNN_OPENMP != 0 || defined(_OPENMP) || NCNN_SIMPLEOMP
  fail(env, "FSMN requires NCNN_OPENMP=0; refusing an affinity-managed backend");
  return nullptr;
#else
  return env->NewStringUTF("NCNN_SERIAL_CPU");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFsmnNative_create(
    JNIEnv* env, jobject, jbyteArray param, jbyteArray weights, jbyteArray cmvn) {
  try {
    auto p = bytes(env, param), w = bytes(env, weights), c = bytes(env, cmvn);
    if (env->ExceptionCheck()) return 0;
    if (c.size() != 800 * sizeof(double)) throw std::invalid_argument("FSMN CMVN byte count invalid");
    // Android's four supported ABIs and the host replay are little-endian.
    std::vector<double> stats(800);
    std::memcpy(stats.data(), c.data(), c.size());
    return reinterpret_cast<jlong>(new guardian_fsmn::Model(std::string(p.begin(), p.end()), w, stats));
  } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFsmnNative_infer(
    JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
  try {
    if (!handle || !pcm) throw std::invalid_argument("FSMN closed or null PCM");
    const int count = env->GetArrayLength(pcm);
    if (count < 400 || count > 128000) throw std::invalid_argument("FSMN PCM length invalid");
    std::vector<float> source(count);
    env->GetFloatArrayRegion(pcm, 0, count, source.data());
    if (env->ExceptionCheck()) return nullptr;
    const auto probabilities = reinterpret_cast<guardian_fsmn::Model*>(handle)->infer(source);
    auto result = env->NewFloatArray(probabilities.size());
    if (result) env->SetFloatArrayRegion(result, 0, probabilities.size(), probabilities.data());
    return result;
  } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFsmnNative_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<guardian_fsmn::Model*>(handle);
}
