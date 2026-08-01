#include <atomic>
#include <thread>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/SpscRingBuffer.h"

using deadaccurate::SpscRingBuffer;

TEST(SpscRingBuffer, RoundsCapacityUpToPowerOfTwo) {
    EXPECT_EQ(SpscRingBuffer(1000).capacity(), 1024u);
    EXPECT_EQ(SpscRingBuffer(1024).capacity(), 1024u);
}

TEST(SpscRingBuffer, WriteThenReadPreservesData) {
    SpscRingBuffer rb(8);
    const float in[] = {1.0f, 2.0f, 3.0f};
    EXPECT_EQ(rb.Write(in, 3), 3u);
    EXPECT_EQ(rb.AvailableToRead(), 3u);

    float out[3] = {};
    EXPECT_EQ(rb.Read(out, 3), 3u);
    EXPECT_EQ(out[0], 1.0f);
    EXPECT_EQ(out[1], 2.0f);
    EXPECT_EQ(out[2], 3.0f);
    EXPECT_EQ(rb.AvailableToRead(), 0u);
}

TEST(SpscRingBuffer, DropsWhenFullInsteadOfBlocking) {
    SpscRingBuffer rb(4);
    const float in[] = {1, 2, 3, 4, 5, 6};
    EXPECT_EQ(rb.Write(in, 6), 4u);

    float out[6] = {};
    EXPECT_EQ(rb.Read(out, 6), 4u);
    EXPECT_EQ(out[3], 4.0f);
}

TEST(SpscRingBuffer, WrapsAroundCorrectly) {
    SpscRingBuffer rb(4);
    float out[4] = {};
    // Cycle several times past the wrap point in odd-sized chunks.
    float next = 0.0f;
    float expected = 0.0f;
    for (int cycle = 0; cycle < 10; ++cycle) {
        float in[3];
        for (float& sample : in) {
            sample = next++;
        }
        EXPECT_EQ(rb.Write(in, 3), 3u);
        EXPECT_EQ(rb.Read(out, 3), 3u);
        for (int i = 0; i < 3; ++i) {
            EXPECT_EQ(out[i], expected++);
        }
    }
}

TEST(SpscRingBuffer, ConcurrentProducerConsumerDeliversInOrder) {
    SpscRingBuffer rb(256);
    constexpr int kTotal = 100000;
    std::atomic<bool> done{false};

    std::thread producer([&] {
        float value = 0.0f;
        int written = 0;
        while (written < kTotal) {
            float chunk[64];
            const int want =
                (kTotal - written) < 64 ? (kTotal - written) : 64;
            for (int i = 0; i < want; ++i) {
                chunk[i] = value + static_cast<float>(i);
            }
            const size_t n = rb.Write(chunk, static_cast<size_t>(want));
            written += static_cast<int>(n);
            value += static_cast<float>(n);
        }
        done.store(true);
    });

    int read = 0;
    float expected = 0.0f;
    bool inOrder = true;
    while (read < kTotal) {
        float out[64];
        const size_t n = rb.Read(out, 64);
        for (size_t i = 0; i < n; ++i) {
            if (out[i] != expected) {
                inOrder = false;
            }
            ++expected;
        }
        read += static_cast<int>(n);
        if (n == 0 && done.load() && rb.AvailableToRead() == 0 &&
            read < kTotal) {
            break;  // producer finished but data went missing
        }
    }
    producer.join();
    EXPECT_TRUE(inOrder);
    EXPECT_EQ(read, kTotal);
}
