#pragma once
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include "datareader.h"
#include "net.h"
#include "frontend.h"

namespace guardian_fsmn {
/** Immutable weights; every call creates fresh features and a fresh extractor.
 * The fixed FP32 graph emits 248 posteriors; class zero is upstream silence. */
class Model {
 public:
  Model(const std::string& param, const std::vector<unsigned char>& weights,
        const std::vector<double>& cmvn) : weights_(weights), frontend_(cmvn) {
#if !defined(NCNN_OPENMP) || NCNN_OPENMP != 0 || defined(_OPENMP) || NCNN_SIMPLEOMP
    throw std::runtime_error("FSMN requires a serial NCNN build (NCNN_OPENMP=0)");
#endif
    if (param.empty() || weights.empty()) throw std::invalid_argument("FSMN assets empty");
    net_.opt.num_threads = 1;
    net_.opt.use_vulkan_compute = false;
    net_.opt.use_packing_layout = false;
    net_.opt.use_fp16_packed = false;
    net_.opt.use_fp16_storage = false;
    net_.opt.use_fp16_arithmetic = false;
    net_.opt.use_bf16_storage = false;
    net_.opt.use_int8_inference = false;
    const unsigned char* memory = weights_.data();
    ncnn::DataReaderFromMemory reader(memory);
    if (net_.load_param_mem(param.c_str()) || net_.load_model(reader) ||
        memory != weights_.data() + weights_.size())
      throw std::runtime_error("FSMN NCNN FP32 model load failed");
  }

  std::vector<float> features(const std::vector<float>& pcm) const {
    if (pcm.size() < 400 || pcm.size() > 128000)
      throw std::invalid_argument("FSMN expects 25ms..8s at 16kHz");
    for (float sample : pcm) if (!std::isfinite(sample)) throw std::invalid_argument("FSMN PCM nonfinite");
    std::vector<float> result;
    const int frames = frontend_.Compute(pcm, &result);
    if (frames != 1 + (static_cast<int>(pcm.size()) - 400) / 160 || result.size() != static_cast<size_t>(frames) * 400)
      throw std::runtime_error("FSMN frontend frame count mismatch");
    return result;
  }

  std::vector<float> posteriors(const std::vector<float>& features) const {
    if (features.empty() || features.size() % 400 || features.size() > 799 * 400)
      throw std::invalid_argument("FSMN feature shape invalid");
    for (float value : features) if (!std::isfinite(value)) throw std::invalid_argument("FSMN feature nonfinite");
    const int frames = static_cast<int>(features.size() / 400);
    ncnn::Mat input(400, frames);
    if (input.empty()) throw std::runtime_error("FSMN feature allocation failed");
    std::memcpy(input.data, features.data(), features.size() * sizeof(float));
    auto extractor = net_.create_extractor();
    ncnn::Mat output;
    if (extractor.input("speech", input) || extractor.extract("logits", output))
      throw std::runtime_error("FSMN NCNN inference failed");
    if (output.dims != 2 || output.w != 248 || output.h != frames || output.elempack != 1 || output.elemsize != sizeof(float))
      throw std::runtime_error("FSMN posterior shape invalid");
    std::vector<float> result(static_cast<size_t>(frames) * 248);
    for (int frame = 0; frame < frames; ++frame) {
      const float* row = output.row(frame);
      double sum = 0;
      for (int bin = 0; bin < 248; ++bin) {
        const float p = row[bin];
        if (!std::isfinite(p) || p < 0 || p > 1) throw std::runtime_error("FSMN posterior invalid");
        result[static_cast<size_t>(frame) * 248 + bin] = p;
        sum += p;
      }
      if (std::abs(sum - 1.0) > .002) throw std::runtime_error("FSMN softmax normalization invalid");
    }
    return result;
  }

  std::vector<float> infer(const std::vector<float>& pcm) const {
    const auto full = posteriors(features(pcm));
    std::vector<float> silence(full.size() / 248);
    for (size_t frame = 0; frame < silence.size(); ++frame) silence[frame] = full[frame * 248];
    return silence;
  }

 private:
  std::vector<unsigned char> weights_;  // NCNN may retain references into storage.
  Frontend frontend_;
  ncnn::Net net_;
};
}  // namespace guardian_fsmn
