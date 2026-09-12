#pragma once
// ── Minimal test assertion helpers (CTest-friendly) ─────────────────
// Deliberately dependency-free: a test executable includes this, uses the
// CHECK macros and returns Summary() from main() so CTest sees red/green.
// Failures print file:line, the expression, and (for CHECK_EQ) both values.

#include <cstdio>
#include <string>

namespace pb_t {
    inline int g_failures = 0;

    // Value formatting used by CHECK_EQ diagnostics.
    inline std::string Show(const std::string& v)  { return "\"" + v + "\""; }
    inline std::string Show(const char* v)         { return std::string("\"") + (v ? v : "(null)") + "\""; }
    inline std::string Show(long long v)           { return std::to_string(v); }
    inline std::string Show(unsigned long long v)  { return std::to_string(v); }
    inline std::string Show(int v)                 { return std::to_string(v); }
    inline std::string Show(unsigned v)            { return std::to_string(v); }
    inline std::string Show(long v)                { return std::to_string(v); }
    inline std::string Show(unsigned long v)       { return std::to_string(v); }
    inline std::string Show(double v)              { char b[40]; std::snprintf(b, sizeof b, "%g", v); return b; }
    inline std::string Show(bool v)                { return v ? "true" : "false"; }
    // NOTE: no std::size_t overload — on MSVC x64 size_t IS unsigned __int64 and
    // would collide with the unsigned long long overload above; the int/unsigned/
    // long/unsigned long/unsigned long long set already covers it on 32- and 64-bit.
    template <class T> inline std::string Show(const T&) { return "(unprintable)"; }

    inline void Fail(const char* file, int line, const char* what)
    {
        ++g_failures;
        std::printf("FAIL %s:%d  %s\n", file, line, what);
    }

    inline int Summary(const char* testName)
    {
        if (g_failures == 0) { std::printf("PASS  %s\n", testName); return 0; }
        std::printf("FAIL  %s  (%d failure(s))\n", testName, g_failures);
        return 1;
    }
}

#define CHECK_TRUE(cond) \
    do { if (!(cond)) pb_t::Fail(__FILE__, __LINE__, #cond); } while (0)

#define CHECK_EQ(a, b) \
    do { \
        auto&& _a = (a); auto&& _b = (b); \
        if (!(_a == _b)) { \
            pb_t::Fail(__FILE__, __LINE__, #a " == " #b); \
            std::printf("        left : %s\n", pb_t::Show(_a).c_str()); \
            std::printf("        right: %s\n", pb_t::Show(_b).c_str()); \
        } \
    } while (0)
