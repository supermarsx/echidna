#include "hooks/opensl_buffer_fifo.h"

#include <array>
#include <cstdint>
#include <cstdio>

namespace
{
    int gFailures = 0;

    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++gFailures;
        }
    }
} // namespace

int main()
{
    using echidna::hooks::OpenSlBufferFifo;

    std::array<uint8_t, 8> a{};
    std::array<uint8_t, 16> b{};
    std::array<uint8_t, 24> c{};
    OpenSlBufferFifo fifo;
    Check(fifo.reserve(a.data(), a.size()).tracked,
          "first enqueue must be tracked");
    Check(fifo.reserve(b.data(), b.size()).tracked,
          "second enqueue must be tracked");
    Check(fifo.reserve(c.data(), c.size()).tracked,
          "third enqueue must be tracked");
    const auto popped_a = fifo.pop();
    const auto popped_b = fifo.pop();
    const auto popped_c = fifo.pop();
    Check(popped_a && popped_a->data == a.data() && popped_a->bytes == a.size(),
          "buffers must pop in FIFO order with exact byte counts");
    Check(popped_b && popped_b->data == b.data() && popped_b->bytes == b.size(),
          "second FIFO entry must preserve its exact range");
    Check(popped_c && popped_c->data == c.data() && popped_c->bytes == c.size(),
          "third FIFO entry must preserve its exact range");
    Check(!fifo.pop(), "empty FIFO must not guess a buffer");

    const auto failed_enqueue = fifo.reserve(a.data(), a.size());
    fifo.rollback(failed_enqueue);
    Check(!fifo.pop(), "failed OpenSL Enqueue must roll its reservation back");
    Check(fifo.reserve(b.data(), b.size()).tracked,
          "rollback must leave the next enqueue usable");
    fifo.clear();
    Check(!fifo.pop(), "Clear must discard every pending buffer");

    // Simulate a callback invoked synchronously from inside Enqueue: it pops
    // the reservation and recursively enqueues the replacement buffer.
    const auto synchronous = fifo.reserve(a.data(), a.size());
    const auto inside_callback = fifo.pop();
    Check(inside_callback && inside_callback->data == a.data(),
          "callback during Enqueue must observe the published reservation");
    Check(fifo.reserve(c.data(), c.size()).tracked,
          "recursive Enqueue from the callback must remain ordered");
    const auto recursive = fifo.pop();
    Check(recursive && recursive->data == c.data(),
          "recursively queued buffer must be delivered next");
    fifo.rollback(synchronous);
    Check(fifo.failed(),
          "ambiguous rollback after callback consumption must disable tracking");
    Check(!fifo.pop(), "disabled tracking must bypass instead of guessing");
    fifo.clear();

    std::array<uint8_t, OpenSlBufferFifo::kCapacity + 1> storage{};
    for (size_t i = 0; i < OpenSlBufferFifo::kCapacity; ++i)
    {
        Check(fifo.reserve(&storage[i], 1).tracked,
              "every advertised FIFO slot must accept one buffer");
    }
    Check(!fifo.reserve(&storage.back(), 1).tracked && fifo.failed(),
          "overflow must switch to truthful passthrough");
    Check(!fifo.pop(),
          "overflowed FIFO must not associate callbacks with stale entries");
    fifo.clear();
    Check(fifo.reserve(a.data(), a.size()).tracked,
          "successful Clear must recover bounded tracking");

    if (gFailures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "opensl_buffer_fifo_test: all checks passed\n");
    return 0;
}
