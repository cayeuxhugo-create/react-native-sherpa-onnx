#include <chrono>
#include <ctime>
#include <fstream>
#include <iostream>
#include <iterator>
#include "confirmation.h"
#include "streaming.h"
#include "model.h"
template<class T>std::vector<T> read(const std::string&p){std::ifstream f(p,std::ios::binary|std::ios::ate);if(!f)throw std::runtime_error("input unavailable");auto n=f.tellg();if(n<0||n%sizeof(T))throw std::runtime_error("shape");std::vector<T>x(static_cast<size_t>(n)/sizeof(T));f.seekg(0);f.read(reinterpret_cast<char*>(x.data()),n);return x;}
template<class F>void timed(const char*stage,int trial,double seconds,F run){auto c=std::clock();auto t=std::chrono::steady_clock::now();const auto count=run();if(trial>0)std::cout<<"{\"stage\":\""<<stage<<"\",\"trial\":"<<trial<<",\"audioSeconds\":"<<seconds<<",\"cpuMs\":"<<1000.*(std::clock()-c)/CLOCKS_PER_SEC<<",\"wallMs\":"<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t).count()<<",\"outputValues\":"<<count<<"}\n";}
int main(int argc,char**argv){try{
 if(argc<4)throw std::runtime_error("Usage: vad_cpu assets continuous.f32 window1.f32 [window2.f32 ...]");
 const std::string root=argv[1];auto p=read<char>(root+"/firered/firered_vad_packed_cache_stream.ncnn.param");
 guardian::FireRedStreaming stream(std::string(p.begin(),p.end()),read<unsigned char>(root+"/firered/firered_vad_packed_cache_stream.ncnn.bin"),read<float>(root+"/firered/cmvn.bin"));
 auto q=read<char>(root+"/firered/firered_vad_non_stream.ncnn.param");guardian::FireRedConfirmation confirm(std::string(q.begin(),q.end()),read<unsigned char>(root+"/firered/firered_vad_non_stream.ncnn.bin"),read<float>(root+"/firered/cmvn.bin"));
 auto r=read<char>(root+"/fsmn/fsmn_vad.ncnn.param");guardian_fsmn::Model fsmn(std::string(r.begin(),r.end()),read<unsigned char>(root+"/fsmn/fsmn_vad.ncnn.bin"),read<double>(root+"/fsmn/cmvn-f64.bin"));
 const auto continuous=read<float>(argv[2]);std::vector<std::vector<float>>windows;double windowSeconds=0;
 for(int i=3;i<argc;i++){windows.push_back(read<float>(argv[i]));windowSeconds+=windows.back().size()/16000.;}
 for(int trial=0;trial<4;trial++){
 stream.reset();timed("FIRERED_STREAM",trial,continuous.size()/16000.,[&](){size_t count=0;for(size_t i=0;i<continuous.size();i+=800)count+=stream.accept(std::vector<float>(continuous.begin()+i,continuous.begin()+std::min(i+800,continuous.size()))).size();return count;});
 timed("FIRERED_CONFIRM",trial,windowSeconds,[&](){size_t count=0;for(const auto&w:windows)count+=confirm.inferFeatures(confirm.features(w)).size();return count;});
 timed("FSMN_CONFIRM",trial,windowSeconds,[&](){size_t count=0;for(const auto&w:windows)count+=fsmn.infer(w).size();return count;});
 }
}catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}}
