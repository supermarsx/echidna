#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "ring_buffer.h"

namespace echidna::dsp::runtime {

struct AudioBlock {
  uint32_t sample_rate{0};
  uint32_t channels{0};
  size_t frames{0};
  std::vector<float> data;

  AudioBlock() = default;
  AudioBlock(uint32_t sr, uint32_t ch, size_t fr)
      : sample_rate(sr), channels(ch), frames(fr), data(fr * ch) {}

  void resize(uint32_t sr, uint32_t ch, size_t fr) {
    sample_rate = sr;
    channels = ch;
    frames = fr;
    data.resize(fr * ch);
  }
};

class BlockQueue {
 public:
  explicit BlockQueue(size_t capacity);

  bool push(const std::shared_ptr<AudioBlock> &block);
  std::shared_ptr<AudioBlock> pop();

  std::shared_ptr<AudioBlock> pop_wait(
      std::chrono::microseconds timeout);

  size_t size() const;

 private:
  RingBuffer<std::shared_ptr<AudioBlock>> ring_;
};

}  // namespace echidna::dsp::runtime
