#pragma once

// Minimal single-header test harness so the host tests have zero external
// dependencies. If test needs outgrow it (fixtures, parameterization),
// replace with GoogleTest fetched via CMake — the macros are API-compatible
// enough to make that a mechanical change.

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace testfw {

struct Test {
    std::string name;
    std::function<void()> fn;
};

inline std::vector<Test>& Registry() {
    static std::vector<Test> tests;
    return tests;
}

inline int& FailureCount() {
    static int failures = 0;
    return failures;
}

struct Registrar {
    Registrar(const char* name, std::function<void()> fn) {
        Registry().push_back({name, std::move(fn)});
    }
};

inline int RunAll() {
    int failed = 0;
    for (const auto& test : Registry()) {
        const int before = FailureCount();
        test.fn();
        const bool ok = FailureCount() == before;
        std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", test.name.c_str());
        if (!ok) {
            ++failed;
        }
    }
    std::printf("%zu tests, %d failed\n", Registry().size(), failed);
    return failed == 0 ? 0 : 1;
}

}  // namespace testfw

#define TEST(suite, name)                                                     \
    static void suite##_##name##_impl();                                      \
    static const ::testfw::Registrar suite##_##name##_registrar(              \
        #suite "." #name, suite##_##name##_impl);                             \
    static void suite##_##name##_impl()

#define EXPECT_TRUE(cond)                                                     \
    do {                                                                      \
        if (!(cond)) {                                                        \
            std::printf("  expectation failed at %s:%d: %s\n", __FILE__,      \
                        __LINE__, #cond);                                     \
            ++::testfw::FailureCount();                                       \
        }                                                                     \
    } while (false)

#define EXPECT_EQ(a, b) EXPECT_TRUE((a) == (b))
