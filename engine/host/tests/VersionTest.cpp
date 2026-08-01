#include <cstring>

#include "TestFramework.h"
#include "deadaccurate/Version.h"

TEST(Version, IsNonEmpty) {
    EXPECT_TRUE(std::strlen(deadaccurate::EngineVersion()) > 0);
}
