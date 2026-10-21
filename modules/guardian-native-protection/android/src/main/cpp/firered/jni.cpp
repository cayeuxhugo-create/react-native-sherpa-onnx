#include <jni.h>
#include <memory>
#include "confirmation.h"
namespace {
void fail(JNIEnv* e,const char* message) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),message); }
std::vector<unsigned char> bytes(JNIEnv* e,jbyteArray a) {
 const auto size=e->GetArrayLength(a);std::vector<unsigned char> v(size);
 e->GetByteArrayRegion(a,0,size,reinterpret_cast<jbyte*>(v.data()));return v;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFireRedNative_create(JNIEnv* e,jobject,jbyteArray param,jbyteArray model,jbyteArray cmvn) {
 try {
  auto p=bytes(e,param),m=bytes(e,model),c=bytes(e,cmvn);
  if(e->ExceptionCheck()) return 0;
  if(c.size()!=640) throw std::runtime_error("FireRed CMVN size invalid");
  std::vector<float> stats(160);std::memcpy(stats.data(),c.data(),c.size());
  return reinterpret_cast<jlong>(new guardian::FireRedConfirmation(std::string(p.begin(),p.end()),m,stats));
 } catch(const std::exception& x) { fail(e,x.what());return 0; }
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFireRedNative_infer(JNIEnv* e,jobject,jlong handle,jfloatArray pcm) {
 try {
  if(!handle) throw std::runtime_error("FireRed is closed");
  const int n=e->GetArrayLength(pcm);
  if(n<400 || n>128000) throw std::runtime_error("FireRed PCM length invalid");
  std::vector<float> wave(n);e->GetFloatArrayRegion(pcm,0,n,wave.data());if(e->ExceptionCheck())return nullptr;
  auto result=reinterpret_cast<guardian::FireRedConfirmation*>(handle)->infer(wave);
  auto out=e->NewFloatArray(result.size());if(out)e->SetFloatArrayRegion(out,0,result.size(),result.data());return out;
 } catch(const std::exception& x) { fail(e,x.what());return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_expo_modules_guardiannativeprotection_GuardianFireRedNative_destroy(JNIEnv*,jobject,jlong handle) {
 delete reinterpret_cast<guardian::FireRedConfirmation*>(handle);
}
