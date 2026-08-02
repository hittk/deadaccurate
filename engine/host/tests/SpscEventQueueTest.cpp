#include "TestFramework.h"
#include "deadaccurate/Events.h"
#include "deadaccurate/SpscEventQueue.h"

using deadaccurate::Event;
using deadaccurate::MakeLevelEvent;
using deadaccurate::SpscEventQueue;

TEST(SpscEventQueue, PushThenDrainPreservesOrder) {
    SpscEventQueue q(8);
    EXPECT_TRUE(q.Push(MakeLevelEvent(-10.0f, -5.0f, -60.0f, false, false)));
    EXPECT_TRUE(q.Push(MakeLevelEvent(-20.0f, -15.0f, -60.0f, false, false)));

    Event out[4];
    EXPECT_EQ(q.Drain(out, 4), 2u);
    EXPECT_EQ(out[0].v[1], -10.0f);
    EXPECT_EQ(out[1].v[1], -20.0f);
    EXPECT_EQ(q.Drain(out, 4), 0u);
}

TEST(SpscEventQueue, DropsWhenFullInsteadOfBlocking) {
    SpscEventQueue q(4);
    for (int i = 0; i < 4; ++i) {
        EXPECT_TRUE(q.Push(MakeLevelEvent(static_cast<float>(i), 0.0f, -60.0f, false, false)));
    }
    EXPECT_TRUE(!q.Push(MakeLevelEvent(99.0f, 0.0f, -60.0f, false, false)));

    Event out[8];
    EXPECT_EQ(q.Drain(out, 8), 4u);
    EXPECT_EQ(out[3].v[1], 3.0f);
}

TEST(SpscEventQueue, ReusableAfterWrapAround) {
    SpscEventQueue q(4);
    Event out[4];
    for (int cycle = 0; cycle < 10; ++cycle) {
        EXPECT_TRUE(q.Push(MakeLevelEvent(static_cast<float>(cycle), 0.0f, -60.0f, false, false)));
        EXPECT_EQ(q.Drain(out, 4), 1u);
        EXPECT_EQ(out[0].v[1], static_cast<float>(cycle));
    }
}
