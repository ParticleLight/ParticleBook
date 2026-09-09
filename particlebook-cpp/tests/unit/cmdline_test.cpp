#include "utils/win_cmd.h"
#include "utils/test_assert.h"
#include <string>

int main() {
    // Simple token stays unquoted
    CHECK_TRUE(pb::QuoteCmdArg(L"pages") == L"pages");
    CHECK_TRUE(pb::QuoteCmdArg(L"abc.exe") == L"abc.exe");

    // Whitespace forces quoting
    CHECK_TRUE(pb::QuoteCmdArg(L"my file.pdf") == L"\"my file.pdf\"");

    // Empty -> empty quotes
    CHECK_TRUE(pb::QuoteCmdArg(L"") == L"\"\"");

    // Path with SPACE + trailing backslash: quoted, trailing run doubled.
    // input:  C:\my books\
    // output: "C:\my books\\"
    CHECK_TRUE(pb::QuoteCmdArg(L"C:\\my books\\") == L"\"C:\\my books\\\\\"");

    // Embedded quote: backslash inserted before the quote inside quotes: "a\"b"
    CHECK_TRUE(pb::QuoteCmdArg(L"a\"b") == L"\"a\\\"b\"");

    // JoinCmdLine joins with space, quoting each
    CHECK_TRUE(pb::JoinCmdLine({L"mutool", L"pages", L"a b.pdf"}) ==
               L"mutool pages \"a b.pdf\"");

    return pb_t::Summary("cmdline_test");
}
