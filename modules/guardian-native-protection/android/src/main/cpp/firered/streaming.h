#pragma once
#include "confirmation.h"
namespace guardian {
// Stateful, causal frontend: retain the 25ms window remainder and all eight
// DFSMN histories. Reset only on a capture discontinuity/session change.
class FireRedStreaming {
 public:
  FireRedStreaming(const std::string& param, const std::vector<unsigned char>& model,
                   const std::vector<float>& cmvn)
      : model_(model), cmvn_(cmvn), fbank_(80,16000,400,160) {
    if(cmvn.size()!=160 || model.empty())throw std::runtime_error("Invalid streaming assets");
    for(size_t i=0;i<cmvn.size();++i)
      if(!std::isfinite(cmvn[i]) || (i>=80 && cmvn[i]<=0))throw std::runtime_error("Invalid streaming CMVN");
    net_.opt.num_threads=1;net_.opt.use_vulkan_compute=false;
    net_.opt.use_packing_layout=false;net_.opt.use_fp16_packed=false;
    net_.opt.use_fp16_storage=false;net_.opt.use_fp16_arithmetic=false;
    const unsigned char* memory=model_.data();ncnn::DataReaderFromMemory reader(memory);
    if(net_.load_param_mem(param.c_str()) || net_.load_model(reader) || memory!=model_.data()+model_.size())
      throw std::runtime_error("Cannot load streaming model");
    reset();
  }
  void reset() { pending_.clear();cache_=ncnn::Mat(19,1024);if(cache_.empty())throw std::runtime_error("Streaming cache allocation failed");cache_.fill(0.f); }
  std::vector<float> accept(const std::vector<float>& pcm) {
    if(pcm.empty() || pcm.size()>800)throw std::runtime_error("Streaming chunk must be 1..800 samples");
    for(float v:pcm) {
      if(!std::isfinite(v))throw std::runtime_error("Nonfinite streaming PCM");
      pending_.push_back(std::nearbyint(std::max(-1.f,std::min(32767.f/32768.f,v))*32768.f));
    }
    std::vector<float> probabilities;
    while(pending_.size()>=400) {
      std::vector<float> wave(pending_.begin(),pending_.begin()+400),feat;
      fbank_.Compute(wave,&feat);
      ncnn::Mat input(80,1);if(input.empty())throw std::runtime_error("Streaming feature allocation failed");for(int i=0;i<80;++i)input.row(0)[i]=(feat[i]-cmvn_[i])*cmvn_[80+i];
      auto ex=net_.create_extractor();ncnn::Mat output,next;
      if(ex.input("in0",input) || ex.input("in1",cache_) || ex.extract("out0",output) || ex.extract("out1",next))
        throw std::runtime_error("Streaming inference failed");
      if(output.w*output.h*output.d*output.c*output.elempack!=1 || next.w!=19 || next.h!=1024 || next.elempack!=1 || next.c!=1)
        throw std::runtime_error("Streaming output/cache shape invalid");
      const float p=static_cast<const float*>(output)[0];
      if(!std::isfinite(p) || p<0 || p>1)throw std::runtime_error("Invalid streaming probability");
      cache_=next.clone();if(cache_.empty())throw std::runtime_error("Streaming allocation failed");
      probabilities.push_back(p);pending_.erase(pending_.begin(),pending_.begin()+160);
    }
    return probabilities;
  }
 private:
  std::vector<unsigned char> model_;
  ncnn::Net net_;
  std::vector<float> cmvn_,pending_;
  vad::Fbank fbank_;
  ncnn::Mat cache_;
};
}
