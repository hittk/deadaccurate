#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

namespace deadaccurate {

// Single-producer single-consumer lock-free ring buffer of float frames.
//
// The producer is the AAudio data callback and the consumer is the DSP
// thread (docs/02-architecture.md section 3.1). Neither side ever blocks or
// allocates after construction. Capacity is rounded up to a power of two so
// index wrapping is a mask, not a modulo.
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t minCapacity)
        : capacity_(NextPowerOfTwo(minCapacity)),
          mask_(capacity_ - 1),
          buffer_(capacity_) {}

    size_t capacity() const { return capacity_; }

    // Producer side. Returns the number of samples written; if the buffer is
    // full the remainder is dropped (the callback must never wait).
    size_t Write(const float* data, size_t count) {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t free = capacity_ - (head - tail);
        const size_t n = count < free ? count : free;
        for (size_t i = 0; i < n; ++i) {
            buffer_[(head + i) & mask_] = data[i];
        }
        head_.store(head + n, std::memory_order_release);
        return n;
    }

    // Consumer side. Returns the number of samples read into `out`.
    size_t Read(float* out, size_t maxCount) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t available = head - tail;
        const size_t n = maxCount < available ? maxCount : available;
        for (size_t i = 0; i < n; ++i) {
            out[i] = buffer_[(tail + i) & mask_];
        }
        tail_.store(tail + n, std::memory_order_release);
        return n;
    }

    // Consumer-side view of how many samples are waiting.
    size_t AvailableToRead() const {
        return head_.load(std::memory_order_acquire) -
               tail_.load(std::memory_order_relaxed);
    }

private:
    static size_t NextPowerOfTwo(size_t v) {
        size_t p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    const size_t capacity_;
    const size_t mask_;
    std::vector<float> buffer_;
    std::atomic<size_t> head_{0};  // written by producer
    std::atomic<size_t> tail_{0};  // written by consumer
};

}  // namespace deadaccurate
