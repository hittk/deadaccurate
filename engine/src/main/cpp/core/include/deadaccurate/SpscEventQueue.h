#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

#include "deadaccurate/Events.h"

namespace deadaccurate {

// Single-producer single-consumer queue of engine events. The producer is
// the DSP thread, the consumer is the Kotlin polling thread draining via
// JNI. Push drops the event when full rather than blocking — the consumer
// polls faster than events are produced, so a full queue means the app is
// backgrounded/frozen and stale telemetry is worthless anyway.
class SpscEventQueue {
public:
    explicit SpscEventQueue(size_t minCapacity)
        : capacity_(NextPowerOfTwo(minCapacity)),
          mask_(capacity_ - 1),
          buffer_(capacity_) {}

    size_t capacity() const { return capacity_; }

    // Producer side. Returns false (dropping the event) when full.
    bool Push(const Event& event) {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        if (head - tail >= capacity_) {
            return false;
        }
        buffer_[head & mask_] = event;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    // Consumer side. Returns the number of events copied into `out`.
    size_t Drain(Event* out, size_t maxCount) {
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
    std::vector<Event> buffer_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

}  // namespace deadaccurate
