#include "utils/fnv1a.h"
#include "utils/test_assert.h"
#include <string>

int main() {
    // Determinism: same input twice -> same hash
    const std::string p = "C:/Some/Path/with 中文 + #symbols/book.epub";
    CHECK_TRUE(pb::Fnv1a64(p) == pb::Fnv1a64(p));
    CHECK_TRUE(pb::Fnv1a64Hex(p) == pb::Fnv1a64Hex(p));

    // Known FNV-1a 64 vectors (from FNV test suite)
    // fnv1a64("") = cbf29ce484222325
    CHECK_TRUE(pb::Fnv1a64Hex("") == "cbf29ce484222325");
    // fnv1a64("a") = af63dc4c8601ec8c
    CHECK_TRUE(pb::Fnv1a64Hex("a") == "af63dc4c8601ec8c");

    // Output format: 16 lowercase hex chars, ASCII, URL-safe
    const std::string h = pb::Fnv1a64Hex("whatever path");
    CHECK_TRUE(h.length() == 16);
    for (char c : h) CHECK_TRUE((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));

    // Distinct inputs differ
    CHECK_TRUE(pb::Fnv1a64("a") != pb::Fnv1a64("b"));

    return pb_t::Summary("fnv1a_test");
}
