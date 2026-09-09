#include "utils/encoding.h"
#include "utils/test_assert.h"
#include <string>
#include <windows.h>

int main() {
    // Chinese round-trip
    const std::string zh = "中文内容abc";
    const std::wstring w = pb::Utf8ToWide(zh);
    CHECK_TRUE(w == L"\u4e2d\u6587\u5185\u5bb9abc");
    CHECK_TRUE(pb::WideToUtf8(w.c_str()) == zh);

    // Empty input -> empty output
    CHECK_TRUE(pb::Utf8ToWide("").empty());
    CHECK_TRUE(pb::WideToUtf8(L"").empty());

    // Emoji (surrogate pair)
    const std::string emoji = "\xF0\x9F\x98\x80"; // U+1F600
    const std::wstring we = pb::Utf8ToWide(emoji);
    CHECK_TRUE(we.length() == 2); // surrogate pair
    CHECK_TRUE(pb::WideToUtf8(we.c_str()) == emoji);

    // Trailing NUL is trimmed (no double NUL in result)
    std::wstring withZero = L"abc";
    withZero.push_back(L'\0');
    CHECK_TRUE(pb::WideToUtf8(withZero.c_str()) == "abc");

    // ASCII passthrough
    CHECK_TRUE(pb::Utf8ToWide("hello") == L"hello");

    return pb_t::Summary("encoding_test");
}
