#pragma once
// Win32 command-line argument quoting (CommandLineToArgvW reverse rule).
// Builds a single argument string such that CreateProcessW / &&  preserves it
// byte-exact. Handles the classic trailing-backslash + quote corner cases:
//   * every backslash before a quote must be doubled
//   * a run of trailing backslashes must be doubled before the closing quote
// Header-only, inline.

#include <string>

namespace pb {

    inline std::wstring QuoteCmdArg(const std::wstring& arg)
    {
        if (arg.empty()) return L"\"\"";   // empty -> quoted empty

        // No whitespace / meta chars? safe unquoted. Windows treats these
        // as needing quoting. For robustness we quote unless unquoted-safe.
        bool needQuote = false;
        for (wchar_t c : arg) {
            if (c == L' ' || c == L'\t' || c == L'"' || c == L'\n'
                || c == L'\r' || c == L'^' || c == L'&' || c == L'|'
                || c == L'<' || c == L'>' || c == L'%' || c == L'('
                || c == L')') { needQuote = true; break; }
        }
        if (!needQuote) return arg;

        std::wstring out;
        out.reserve(arg.size() + 8);
        out.push_back(L'"' );
        size_t bs = 0;
        for (wchar_t c : arg) {
            if (c == L'\\') {
                bs++;
                continue;
            }
            if (c == L'"' ) {
                // double the preceding run of backslashes, then escape the quote
                out.append(bs * 2 + 1, L'\\');
                out.push_back(L'"' );
                bs = 0;
                continue;
            }
            if (bs) { out.append(bs, L'\\'); bs = 0; }
            out.push_back(c);
        }
        // trailing backslashes: double them before the closing quote
        out.append(bs * 2, L'\\');
        out.push_back(L'"' );
        return out;
    }

    // Join several tokens into one CreateProcessW command line,
    // quoting each as needed.
    inline std::wstring JoinCmdLine(std::initializer_list<std::wstring> args)
    {
        std::wstring out;
        bool first = true;
        for (const auto& a : args) {
            if (!first) out.push_back(L' ');
            first = false;
            out += QuoteCmdArg(a);
        }
        return out;
    }

} // namespace pb
