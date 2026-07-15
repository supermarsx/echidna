#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <optional>

namespace echidna::hooks
{
    struct OpenSlQueuedBuffer
    {
        void *data{nullptr};
        uint32_t bytes{0};
    };

    struct OpenSlBufferReservation
    {
        uint32_t ticket{0};
        bool tracked{false};
    };

    /**
     * A bounded single-producer/single-consumer FIFO for OpenSL buffer queues.
     * Enqueue publishes before calling the OpenSL implementation so a callback
     * made synchronously from Enqueue can see the buffer. On any ambiguous
     * overflow/race the FIFO permanently bypasses processing until Clear,
     * preserving callback ordering without guessing a buffer address.
     */
    class OpenSlBufferFifo
    {
    public:
        static constexpr size_t kCapacity = 64;

        OpenSlBufferReservation reserve(const void *buffer, uint32_t bytes)
        {
            OpenSlBufferReservation reservation;
            if (!buffer || bytes == 0 || failed_.load(std::memory_order_acquire))
            {
                return reservation;
            }
            const uint32_t tail = tail_.load(std::memory_order_relaxed);
            const uint32_t head = head_.load(std::memory_order_acquire);
            if (tail - head >= kCapacity)
            {
                failed_.store(true, std::memory_order_release);
                return reservation;
            }
            buffers_[tail % kCapacity] = {const_cast<void *>(buffer), bytes};
            tail_.store(tail + 1, std::memory_order_release);
            reservation.ticket = tail;
            reservation.tracked = true;
            return reservation;
        }

        void rollback(const OpenSlBufferReservation &reservation)
        {
            if (!reservation.tracked)
            {
                return;
            }
            uint32_t expected = reservation.ticket + 1;
            if (tail_.compare_exchange_strong(expected,
                                              reservation.ticket,
                                              std::memory_order_acq_rel,
                                              std::memory_order_relaxed))
            {
                buffers_[reservation.ticket % kCapacity] = {};
                return;
            }
            failed_.store(true, std::memory_order_release);
        }

        std::optional<OpenSlQueuedBuffer> pop()
        {
            if (failed_.load(std::memory_order_acquire))
            {
                return std::nullopt;
            }
            const uint32_t head = head_.load(std::memory_order_relaxed);
            const uint32_t tail = tail_.load(std::memory_order_acquire);
            if (head == tail)
            {
                return std::nullopt;
            }
            const OpenSlQueuedBuffer buffer = buffers_[head % kCapacity];
            head_.store(head + 1, std::memory_order_release);
            return buffer;
        }

        void clear()
        {
            const uint32_t tail = tail_.load(std::memory_order_acquire);
            head_.store(tail, std::memory_order_release);
            failed_.store(false, std::memory_order_release);
        }

        void disable() { failed_.store(true, std::memory_order_release); }
        bool failed() const { return failed_.load(std::memory_order_acquire); }

    private:
        std::array<OpenSlQueuedBuffer, kCapacity> buffers_{};
        std::atomic<uint32_t> head_{0};
        std::atomic<uint32_t> tail_{0};
        std::atomic<bool> failed_{false};
    };
} // namespace echidna::hooks
