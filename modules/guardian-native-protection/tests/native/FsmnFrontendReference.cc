#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
#include "fsmn/frontend.h"

template <typename T> std::vector<T> Read(const char* path) {
  std::ifstream stream(path, std::ios::binary | std::ios::ate);
  if (!stream) throw std::runtime_error("Cannot open input");
  const auto bytes = stream.tellg();
  if (bytes < 0 || bytes % sizeof(T)) throw std::runtime_error("Invalid input size");
  std::vector<T> result(static_cast<size_t>(bytes) / sizeof(T));
  stream.seekg(0);
  stream.read(reinterpret_cast<char*>(result.data()), bytes);
  return result;
}
void Write(const char* path, const std::vector<float>& values) {
  std::ofstream stream(path, std::ios::binary);
  stream.write(reinterpret_cast<const char*>(values.data()), values.size() * sizeof(float));
}
int main(int argc, char** argv) {
  if (argc != 5) return 2;
  try {
    const auto pcm = Read<float>(argv[1]);
    guardian_fsmn::Frontend frontend(Read<double>(argv[2]));
    std::vector<float> fbank, normalized;
    const int frames = frontend.ComputeFbank(pcm, &fbank);
    if (frontend.Compute(pcm, &normalized) != frames) return 3;
    Write(argv[3], fbank);
    Write(argv[4], normalized);
    std::cout << frames << '\n';
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
