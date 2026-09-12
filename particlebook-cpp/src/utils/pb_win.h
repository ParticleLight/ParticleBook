#pragma once
// ── The single sanctioned entry point for <windows.h> ───────────────
//
// WIN32_LEAN_AND_MEAN must be defined BEFORE <windows.h> is pulled in,
// otherwise the full Win32 surface (including msxml.h) is exposed. That
// matters here: msxml.h defines a global XMLDocument, which collides with
// tinyxml2::XMLDocument in LibraryService.cpp (error C2872, ambiguous symbol).
//
// Previously this guard lived inside utils/encoding.h — a code-conversion
// utility that had no business defining a global build macro as a side
// effect. It now lives here so the intent is explicit and there is one
// place to include when a translation unit needs the Win32 API.
//
// NOTE: deliberately does NOT define NOMINMAX — existing code may rely on
// the min/max macros.

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
