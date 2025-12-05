#pragma once

/**
 * @file block_queue.h
 * @brief Thread-safe ring-buffer backed queue for passing AudioBlock objects
 * between producer and consumer (worker) threads.
 */

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "ring_buffer.h"

namespace echidna::dsp::runtime {

/**
 * @brief Container representing an individual audio block scheduled for
 * processing.
 *
 * This struct holds the sample rate, channel count, frame count and sample
 * data for a block. The `cancelled` flag is used by the hybrid processing
 * system to indicate the block should be dropped.
 */
struct AudioBlock {
  uint32_t sample_rate{0};
  uint32_t channels{0};
  size_t frames{0};
  std::vector<float> data;
  std::atomic<bool> cancelled{false};

  /** Default constructor creates an empty block. */
  AudioBlock() = default;
  /**
   * @brief Construct an AudioBlock with given dimensions and allocated data.
   */
  AudioBlock(uint32_t sr, uint32_t ch, size_t fr)
      : sample_rate(sr),
        channels(ch),
        frames(fr),
        data(fr * ch),
        cancelled(false) {}

  /**
   * @brief Resize the block and pre-allocate sample storage.
   */
  void resize(uint32_t sr, uint32_t ch, size_t fr) {
    sample_rate = sr;
    channels = ch;
    frames = fr;
    data.resize(fr * ch);
    cancelled.store(false, std::memory_order_relaxed);
  }
};

/**
 * @brief Simple blocking/non-blocking queue for AudioBlock shared pointers.
 */
class BlockQueue {
 public:
  /**
   * @brief Create a BlockQueue with the specified capacity.
   * @param capacity Maximum number of outstanding blocks.
   */
  explicit BlockQueue(size_t capacity);

  /**
   * @brief Attempt to push a block onto the queue.
   * @param block Shared pointer to the AudioBlock to push.
   * @return true if pushed, false if the queue is full.
   */
  bool push(const std::shared_ptr<AudioBlock> &block);
  /**
   * @brief Pop an available block or return nullptr if empty.
   */
  std::shared_ptr<AudioBlock> pop();

    /**
     * @brief Pop an available block waiting up to the specified timeout.
     * @param timeout Maximum time to wait for a block to become available.
     * @return Shared pointer to the block or nullptr if timeout expired.
     */
    std::shared_ptr<AudioBlock> pop_wait(
      std::chrono::microseconds timeout);

  /**
   * @brief Return current number of elements in the queue.
   */
  size_t size() const;

 private:
  RingBuffer<std::shared_ptr<AudioBlock>> ring_;
};

}  // namespace echidna::dsp::runtime
