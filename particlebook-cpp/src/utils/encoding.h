#pragma once
// ── UTF-8 <-> UTF-16 conversion helpers ─────────────────────────────
// Single source of truth for code-page conversion across the project.
// Was previously copy-pasted (with subtle behaviour differences, e.g. a
// len==0 early-return vs not) into 7 translation units. This header
// unifies the semantics:
//   * empty input  -> empty output
//   * conversion failure (MultiByteToWideChar returns <=0) -> empty output
//   * trailing NUL terminator is trimmed from the result
// Header-only, inline — no linker dependency.
// NOTE: define WIN32_LEAN_AND_MEAN before <windows.h> so MSXML/COM headers
// are NOT pulled in. Some TUs (e.g. LibraryService.cpp / tinyxml2) define
// their own XMLDocument and would otherwise collide with msxml's global
// XMLDocument. This header must be safe to include regardless of whether the
// includer defined WIN32_LEAN_AND_MEAN itself.

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <string>
#include <windows.h>

namespace pb {
    inline std::wstring Utf8ToWide(const std::string& s)
    {
        if (s.empty()) return L"";
        int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
        if (len <= 0) return L"";
        std::wstring w(static_cast<size_t>(len), L'\0');
        MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
        while (!w.empty() && w.back() == L'\0') w.pop_back();
        return w;
    }

    inline std::string WideToUtf8(LPCWSTR w)
    {
        if (!w || w[0] == L'\0') return "";
        int len = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
        if (len <= 0) return "";
        std::string s(static_cast<size_t>(len), '\0');
        WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], len, nullptr, nullptr);
        while (!s.empty() && s.back() == '\0') s.pop_back();
        return s;
    }

    // Convenience overloads that accept std::wstring / convert in-place idioms.
    inline std::string WideToUtf8(const std::wstring& w)
    {
        return WideToUtf8(w.empty() ? L"" : w.c_str());
    }
} // namespace pb
