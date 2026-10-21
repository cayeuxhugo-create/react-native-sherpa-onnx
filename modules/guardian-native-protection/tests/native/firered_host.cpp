#include <fstream>
#include <iterator>
#include "confirmation.h"
template<class T> std::vector<T> read(const char* path) {
 std::ifstream f(path,std::ios::binary|std::ios::ate);if(!f)throw std::runtime_error("Cannot read input");
 auto size=f.tellg();if(size<0 || size%sizeof(T))throw std::runtime_error("Invalid input size");
 std::vector<T> v(static_cast<size_t>(size)/sizeof(T));f.seekg(0);f.read(reinterpret_cast<char*>(v.data()),size);return v;
}
int main(int argc,char** argv) {
 try {
  if(argc!=7)throw std::runtime_error("Expected param model cmvn pcm output features-output");
  auto p=read<char>(argv[1]);guardian::FireRedConfirmation model(std::string(p.begin(),p.end()),read<unsigned char>(argv[2]),read<float>(argv[3]));
  auto pcm=read<float>(argv[4]);auto feat=model.features(pcm);auto probs=model.inferFeatures(feat);
  std::ofstream o(argv[5],std::ios::binary);o.write(reinterpret_cast<const char*>(probs.data()),probs.size()*4);
  std::ofstream q(argv[6],std::ios::binary);q.write(reinterpret_cast<const char*>(feat.data()),feat.size()*4);
 }catch(const std::exception& e){fprintf(stderr,"%s\n",e.what());return 1;}
}
