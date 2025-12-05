#include "block_queue.h"

/**
 * @file block_queue.cpp
 * @brief BlockQueue implementation used by hybrid DSP worker threads.
 */

#include <chrono>
#include <thread>

namespace echidna::dsp::runtime
{

  /**
   * @brief Construct a BlockQueue with specified capacity.
   */
  BlockQueue::BlockQueue(size_t capacity) : ring_(capacity) {}

  /**
   * @brief Try to push a block. Returns false if the underlying ring buffer
   * is full.
   */
  bool BlockQueue::push(const std::shared_ptr<AudioBlock> &block)
  {
    return ring_.push(block);
  }

  /**
   * @brief Pop a block if one is available, otherwise return nullptr.
   */
  std::shared_ptr<AudioBlock> BlockQueue::pop()
  {
    std::shared_ptr<AudioBlock> block;
    if (!ring_.pop(block))
    {
      return nullptr;
    }
    return block;
  }

  /**
   * @brief Repeatedly attempt to pop from the ring buffer until timeout.
   */
  std::shared_ptr<AudioBlock> BlockQueue::pop_wait(
      std::chrono::microseconds timeout)
  {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    std::shared_ptr<AudioBlock> block;
    while (std::chrono::steady_clock::now() < deadline)
    {
      if (ring_.pop(block))
      {
        return block;
      }
      std::this_thread::yield();
    }
    return nullptr;
  }

  /**
   * @brief Return number of elements in the queue.
   */
  size_t BlockQueue::size() const { return ring_.size(); }

} // namespace echidna::dsp::runtime
