#include <fstream>
#include "streaming.h"
template<class T> std::vector<T> read(const char* p) {
 std::ifstream f(p,std::ios::binary|std::ios::ate);if(!f)throw std::runtime_error("input missing");
 auto n=f.tellg();if(n<0 || n%sizeof(T))throw std::runtime_error("input shape");
 std::vector<T> v(static_cast<size_t>(n)/sizeof(T));f.seekg(0);f.read(reinterpret_cast<char*>(v.data()),n);return v;
}
int main(int argc,char** argv) {
 try {
  if(argc!=6)throw std::runtime_error("param model cmvn conditioned-pcm output");
  auto p=read<char>(argv[1]);guardian::FireRedStreaming s(std::string(p.begin(),p.end()),read<unsigned char>(argv[2]),read<float>(argv[3]));
  auto x=read<float>(argv[4]);std::ofstream f(argv[5],std::ios::binary);
  for(size_t i=0;i<x.size();i+=800) {
   auto y=s.accept(std::vector<float>(x.begin()+i,x.begin()+std::min(i+800,x.size())));
   f.write(reinterpret_cast<const char*>(y.data()),y.size()*4);
  }
 }catch(const std::exception& e){fprintf(stderr,"%s\n",e.what());return 1;}
}
