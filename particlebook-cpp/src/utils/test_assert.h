#pragma once
// ── Minimal test assertion helpers (CTest-friendly) ─────────────────
// Deliberately dependency-free. A test executable includes this and calls
// CHECKs; failures increment a counter. main() returns 1 if any failed so
// CTest sees a red test. ~30 lines, no framework.

#include <cstdio>

namespace pb_t {
    inline int g_failures = 0;

    inline void ReportFail(const char* file, int line, const char* expr)
    {
        ++g_failures;
        std::printf("FAIL %s:%d  (%s)\n", file, line, expr);
    }

    inline int Summary(const char* testName)
    {
        if (g_failures == 0) {
            std::printf("PASS  %s\n", testName);
            return 0;
        }
        std::printf("FAIL  %s  (%d failure(s))\n", testName, g_failures);
        return 1;
    }
}

#define CHECK_TRUE(cond) do { if (!(cond)) pb_t::ReportFail(__FILE__, __LINE__, #cond); } while (0)

#define CHECK_EQ(a, b) do {     if (!((a) == (b))) {         std::printf("  got:  ["); std::printf("%s", " ");         pb_t::ReportFail(__FILE__, __LINE__, #a " == " #b);     } } while (0)
