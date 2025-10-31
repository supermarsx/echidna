#pragma once

#include <atomic>
#include <cstddef>
#include <memory>
#include <type_traits>
#include <vector>

namespace echidna::dsp::runtime {

template <typename T>
class RingBuffer {
 public:
  explicit RingBuffer(size_t capacity)
      : capacity_(normalise_capacity(capacity)),
        mask_(capacity_ - 1),
        storage_(capacity_) {
    static_assert(std::is_copy_assignable_v<T>,
                  "RingBuffer requires copy assignable types");
  }

  bool push(const T &value) {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t next = increment(head);
    if (next == tail_.load(std::memory_order_acquire)) {
      return false;
    }
    storage_[head] = value;
    head_.store(next, std::memory_order_release);
    return true;
  }

  bool pop(T &out) {
    size_t tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) {
      return false;
    }
    out = storage_[tail];
    tail_.store(increment(tail), std::memory_order_release);
    return true;
  }

  bool peek(T &out) const {
    size_t tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) {
      return false;
    }
    out = storage_[tail];
    return true;
  }

  size_t capacity() const { return capacity_; }

  size_t size() const {
    size_t head = head_.load(std::memory_order_acquire);
    size_t tail = tail_.load(std::memory_order_acquire);
    return (head + capacity_ - tail) & mask_;
  }

  bool empty() const { return head_.load() == tail_.load(); }

 private:
  static size_t normalise_capacity(size_t requested) {
    size_t cap = 1;
    while (cap < requested) {
      cap <<= 1U;
    }
    return cap;
  }

  size_t increment(size_t value) const { return (value + 1U) & mask_; }

  const size_t capacity_;
  const size_t mask_;
  std::vector<T> storage_;
  std::atomic<size_t> head_{0};
  std::atomic<size_t> tail_{0};
};

}  // namespace echidna::dsp::runtime
