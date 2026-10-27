// Standalone parity runner. Audio/features are caller-supplied private files.
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>
#include "model.h"

static std::vector<unsigned char> read(const std::string& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) throw std::runtime_error("Cannot read host parity input");
  return std::vector<unsigned char>(std::istreambuf_iterator<char>(input), {});
}
static std::vector<float> floats(const std::vector<unsigned char>& bytes) {
  if (bytes.size() % sizeof(float)) throw std::runtime_error("Invalid float byte count");
  std::vector<float> result(bytes.size() / sizeof(float));
  std::memcpy(result.data(), bytes.data(), bytes.size());
  return result;
}
static void write(const std::string& path, const std::vector<float>& values) {
  std::ofstream output(path, std::ios::binary);
  output.write(reinterpret_cast<const char*>(values.data()), values.size() * sizeof(float));
  if (!output) throw std::runtime_error("Cannot write host parity output");
}
int main(int argc, char** argv) {
  try {
    if (argc < 4 || argc > 5) throw std::runtime_error("Usage: fsmn_host assets pcm.f32 silence.f32 [features.f32], or assets --features input.f32 output248.f32");
    const std::string root = argv[1];
    const auto p = read(root + "/fsmn_vad.ncnn.param"), w = read(root + "/fsmn_vad.ncnn.bin"), c = read(root + "/cmvn-f64.bin");
    if (c.size() != 800 * sizeof(double)) throw std::runtime_error("Invalid CMVN");
    std::vector<double> cmvn(800); std::memcpy(cmvn.data(), c.data(), c.size());
    guardian_fsmn::Model model(std::string(p.begin(), p.end()), w, cmvn);
    if (std::string(argv[2]) == "--features") {
      if (argc != 5) throw std::runtime_error("Feature parity requires input/output");
      write(argv[4], model.posteriors(floats(read(argv[3]))));
    } else {
      const auto pcm = floats(read(argv[2]));
      write(argv[3], model.infer(pcm));
      if (argc == 5) write(argv[4], model.features(pcm));
    }
  } catch (const std::exception& error) { std::cerr << error.what() << "\n"; return 1; }
}
