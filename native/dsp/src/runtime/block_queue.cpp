#include "block_queue.h"

#include <chrono>
#include <thread>

namespace echidna::dsp::runtime {

BlockQueue::BlockQueue(size_t capacity) : ring_(capacity) {}

bool BlockQueue::push(const std::shared_ptr<AudioBlock> &block) {
  return ring_.push(block);
}

std::shared_ptr<AudioBlock> BlockQueue::pop() {
  std::shared_ptr<AudioBlock> block;
  if (!ring_.pop(block)) {
    return nullptr;
  }
  return block;
}

std::shared_ptr<AudioBlock> BlockQueue::pop_wait(
    std::chrono::microseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  std::shared_ptr<AudioBlock> block;
  while (std::chrono::steady_clock::now() < deadline) {
    if (ring_.pop(block)) {
      return block;
    }
    std::this_thread::yield();
  }
  return nullptr;
}

size_t BlockQueue::size() const { return ring_.size(); }

}  // namespace echidna::dsp::runtime
