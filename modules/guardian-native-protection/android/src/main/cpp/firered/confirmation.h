#pragma once
#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include "net.h"
#include "datareader.h"
#include "frontend/fbank.h"

namespace guardian {
// One worker owns a confirmation instance. No recurrent state between windows.
class FireRedConfirmation {
 public:
  FireRedConfirmation(const std::string& param, const std::vector<unsigned char>& model,
                      const std::vector<float>& cmvn) : model_(model), cmvn_(cmvn) {
    if (cmvn.size() != 160 || model.empty()) throw std::runtime_error("FireRed assets invalid");
    for (size_t i=0;i<cmvn.size();++i)
      if (!std::isfinite(cmvn[i]) || (i>=80 && cmvn[i]<=0)) throw std::runtime_error("FireRed CMVN invalid");
    net_.opt.num_threads=1;
    net_.opt.use_vulkan_compute=false;
    net_.opt.use_fp16_packed=false;
    net_.opt.use_fp16_storage=false;
    net_.opt.use_fp16_arithmetic=false;
    const unsigned char* memory=model_.data();
    ncnn::DataReaderFromMemory reader(memory);
    if (net_.load_param_mem(param.c_str()) || net_.load_model(reader) ||
        memory != model_.data()+model_.size())
      throw std::runtime_error("FireRed NCNN model load failed");
  }
  std::vector<float> features(const std::vector<float>& pcm) const {
    if (pcm.size() < 400 || pcm.size() > 128000) throw std::runtime_error("FireRed expects 25ms..8s at 16kHz");
    std::vector<float> wave(pcm.size());
    for (size_t i=0;i<pcm.size();++i) {
      if (!std::isfinite(pcm[i])) throw std::runtime_error("Nonfinite PCM");
      // Match numpy round-to-nearest-even in the evaluated PCM16 frontend.
      wave[i]=std::nearbyint(std::max(-1.f,std::min(32767.f/32768.f,pcm[i]))*32768.f);
    }
    vad::Fbank fbank(80,16000,400,160);
    std::vector<float> feat;
    fbank.Compute(wave,&feat);
    for (size_t i=0;i<feat.size();++i) feat[i]=(feat[i]-cmvn_[i%80])*cmvn_[80+i%80];
    return feat;
  }
  std::vector<float> inferFeatures(const std::vector<float>& feat) const {
    const int frames=static_cast<int>(feat.size()/80);
    if (frames<=0 || feat.size()%80) throw std::runtime_error("Invalid feature shape");
    ncnn::Mat input(80,frames);
    if (input.empty()) throw std::runtime_error("FireRed allocation failed");
    std::memcpy(input.data,feat.data(),feat.size()*sizeof(float));
    auto ex=net_.create_extractor();
    ncnn::Mat output;
    if(ex.input("in0",input) || ex.extract("out0",output))
      throw std::runtime_error("FireRed inference failed");
    const size_t logical=static_cast<size_t>(output.w)*output.h*output.d*output.c*output.elempack;
    if(logical!=static_cast<size_t>(frames)) {
      throw std::runtime_error("FireRed output length invalid: expected="+std::to_string(frames)+
          " actual="+std::to_string(logical));
    }
    const float* p=output;
    std::vector<float> result(p,p+frames);
    for (float v:result) if(!std::isfinite(v) || v<0 || v>1) throw std::runtime_error("Invalid FireRed probability");
    return result;
  }
  std::vector<float> infer(const std::vector<float>& pcm) const { return inferFeatures(features(pcm)); }
 private:
  std::vector<unsigned char> model_; // NCNN may reference weight memory.
  ncnn::Net net_;
  std::vector<float> cmvn_;
};
}
