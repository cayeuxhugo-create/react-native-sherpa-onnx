// Copyright (c) 2017 Binbin Zhang (fbank computation structure).
// FSMN frontend adaptation: Hamming, float PCM, LFR and double CMVN.
// See licenses/fsmn and the shared FireRed FFT's Apache-2.0 notices.
#ifndef GUARDIAN_FSMN_FRONTEND_H_
#define GUARDIAN_FSMN_FRONTEND_H_

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

#include "../firered/frontend/fft.h"

namespace guardian_fsmn {

/** Upstream WavFrontend: 16 kHz Hamming FBANK80, LFR(m=5,n=1), CMVN.
 * Input is normalized Float32 PCM, multiplied by 32768 without int16 rounding.
 * snip_edges=true, dither=0, remove_dc=true, preemphasis=.97, FFT512.
 * The 800 CMVN doubles are additive shifts followed by multiplicative scales.
 * Each Compute call is independent; no boundary or previous-clip state leaks.
 */
class Frontend {
 public:
  static constexpr int kFrameLength = 400;
  static constexpr int kFrameShift = 160;
  static constexpr int kMelBins = 80;
  static constexpr int kFeatureDim = 400;

  explicit Frontend(const std::vector<double>& cmvn800) : cmvn_(cmvn800) {
    if (cmvn_.size() != 2 * kFeatureDim ||
        !std::all_of(cmvn_.begin(), cmvn_.end(), [](double v) { return std::isfinite(v); })) {
      throw std::invalid_argument("FSMN CMVN must contain 800 finite doubles");
    }
    bitrev_.resize(kFftPoints);
    sintbl_.resize(kFftPoints + kFftPoints / 4);
    vad::make_bitrev(kFftPoints, bitrev_.data());
    vad::make_sintbl(kFftPoints, sintbl_.data());
    window_.resize(kFrameLength);
    for (int i = 0; i < kFrameLength; ++i) {
      window_[i] = static_cast<float>(0.54 - 0.46 * std::cos(M_2PI * i / (kFrameLength - 1)));
    }
    const float mel_low = MelScale(20.0f);
    const float delta = (MelScale(8000.0f) - mel_low) / (kMelBins + 1);
    filters_.resize(kMelBins);
    for (int bin = 0; bin < kMelBins; ++bin) {
      const float left = mel_low + bin * delta;
      const float center = mel_low + (bin + 1) * delta;
      const float right = mel_low + (bin + 2) * delta;
      for (int i = 0; i < kFftPoints / 2; ++i) {
        const float mel = MelScale(16000.0f * i / kFftPoints);
        if (mel > left && mel < right) {
          const float weight = mel <= center ? (mel - left) / (center - left)
                                            : (right - mel) / (right - center);
          filters_[bin].push_back({i, weight});
        }
      }
    }
  }

  static int NumFrames(int samples) {
    return samples < kFrameLength ? 0 : 1 + (samples - kFrameLength) / kFrameShift;
  }

  // Exposed for comparison against kaldi-native-fbank's unnormalized frames.
  int ComputeFbank(const std::vector<float>& pcm, std::vector<float>* output) const {
    const int frames = NumFrames(static_cast<int>(pcm.size()));
    output->assign(frames * kMelBins, 0.0f);
    std::vector<float> real(kFftPoints), imaginary(kFftPoints), power(kFftPoints / 2);
    for (int frame = 0; frame < frames; ++frame) {
      const int start = frame * kFrameShift;
      float mean = 0.0f;
      for (int i = 0; i < kFrameLength; ++i) {
        real[i] = pcm[start + i] * 32768.0f;
        mean += real[i];
      }
      mean /= kFrameLength;
      for (int i = 0; i < kFrameLength; ++i) real[i] -= mean;
      for (int i = kFrameLength - 1; i > 0; --i) real[i] -= .97f * real[i - 1];
      real[0] -= .97f * real[0];
      for (int i = 0; i < kFrameLength; ++i) real[i] *= window_[i];
      std::fill(real.begin() + kFrameLength, real.end(), 0.0f);
      std::fill(imaginary.begin(), imaginary.end(), 0.0f);
      vad::fft(bitrev_.data(), sintbl_.data(), real.data(), imaginary.data(), kFftPoints);
      for (int i = 0; i < kFftPoints / 2; ++i) {
        power[i] = real[i] * real[i] + imaginary[i] * imaginary[i];
      }
      for (int bin = 0; bin < kMelBins; ++bin) {
        float energy = 0.0f;
        for (const auto& weight : filters_[bin]) energy += weight.second * power[weight.first];
        (*output)[frame * kMelBins + bin] = std::log(std::max(energy, std::numeric_limits<float>::epsilon()));
      }
    }
    return frames;
  }

  int Compute(const std::vector<float>& pcm, std::vector<float>* features) const {
    std::vector<float> fbank;
    const int frames = ComputeFbank(pcm, &fbank);
    features->resize(frames * kFeatureDim);
    for (int frame = 0; frame < frames; ++frame) {
      for (int context = 0; context < 5; ++context) {
        const int source = std::max(0, std::min(frames - 1, frame + context - 2));
        for (int bin = 0; bin < kMelBins; ++bin) {
          const int dim = context * kMelBins + bin;
          // Upstream promotes Float32 FBANK to Float64 through am.mvn, and
          // casts only the final normalized features back to Float32.
          (*features)[frame * kFeatureDim + dim] = static_cast<float>(
              (static_cast<double>(fbank[source * kMelBins + bin]) + cmvn_[dim]) *
              cmvn_[kFeatureDim + dim]);
        }
      }
    }
    return frames;
  }

 private:
  static constexpr int kFftPoints = 512;
  static float MelScale(float frequency) { return 1127.0f * std::log(1.0f + frequency / 700.0f); }
  std::vector<double> cmvn_;
  std::vector<int> bitrev_;
  std::vector<float> sintbl_, window_;
  std::vector<std::vector<std::pair<int, float>>> filters_;
};

}  // namespace guardian_fsmn
#endif  // GUARDIAN_FSMN_FRONTEND_H_
