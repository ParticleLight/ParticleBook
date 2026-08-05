cmake_minimum_required(VERSION 3.25)
project(ParticleBook VERSION 2.1.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# ── Generated version (single source of truth: project VERSION above) ──────
configure_file("${CMAKE_SOURCE_DIR}/src/pb_version.h.in"   "${CMAKE_BINARY_DIR}/src/pb_version.h"   @ONLY)
configure_file("${CMAKE_SOURCE_DIR}/src/ParticleBook.rc.in" "${CMAKE_BINARY_DIR}/src/ParticleBook.rc" @ONLY)

# ── Dependencies (header-only) ──────────────────────────────────
add_library(nlohmann_json INTERFACE)
target_include_directories(nlohmann_json INTERFACE "${CMAKE_SOURCE_DIR}/third_party")

# WebView2 SDK
set(WV2_ROOT "${CMAKE_SOURCE_DIR}/third_party/WebView2/build/native")

# ── Ensure mutool.exe exists (PDF rendering) ─────────────────────
# The extracted mupdf-1.25.0-windows/ directory is not committed (mutool.exe
# matched by *.exe in .gitignore), so unpack it from the bundled zip on a
# fresh clone.
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/third_party/mupdf-1.25.0-windows/mutool.exe")
    message(STATUS "mutool.exe not found — unpacking mupdf-windows.zip")
    execute_process(COMMAND "${CMAKE_COMMAND}" -E tar xzf
        "${CMAKE_SOURCE_DIR}/third_party/mupdf-windows.zip"
        WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}/third_party"
        RESULT_VARIABLE _mupdf_unpack)
    if(NOT _mupdf_unpack EQUAL 0)
        message(FATAL_ERROR "Failed to unpack mupdf-windows.zip — PDF rendering unavailable")
    endif()
endif()

# Main executable
add_executable(ParticleBook WIN32
    src/main.cpp
    src/App.cpp
    src/WebViewHost.cpp
    src/BridgeServer.cpp
    src/services/DatabaseService.cpp
    src/services/LibraryService.cpp
    src/services/PdfService.cpp
    src/services/BookSourceService.cpp
    src/services/ZLibraryService.cpp
    src/services/ContentCache.cpp
    src/utils/ZipReader.cpp
    src/handlers/DbHandlers.cpp
    src/handlers/FileHandlers.cpp
    third_party/tinyxml2.cpp
    third_party/miniz.c
    third_party/miniz_tdef.c
    third_party/miniz_tinfl.c
    "${CMAKE_BINARY_DIR}/src/ParticleBook.rc"
)

# Enable /EHa so catch(...) can handle SEH exceptions (access violations etc.)
# /utf-8: tell MSVC source files are UTF-8 (otherwise it interprets them as GBK on Chinese systems,
# corrupting Chinese string literals that get embedded in injected JS)
target_compile_options(ParticleBook PRIVATE /EHa /utf-8)

target_include_directories(ParticleBook PRIVATE
    src/
    "${CMAKE_BINARY_DIR}/src/"  # generated pb_version.h
    "${WV2_ROOT}/include"
    "${CMAKE_SOURCE_DIR}/third_party"
)

target_link_libraries(ParticleBook PRIVATE
    nlohmann_json
    "${WV2_ROOT}/x64/WebView2LoaderStatic.lib"
    ws2_32          # for ntohs etc.
    user32
    shell32
    ole32
    shlwapi
    version
    comdlg32
)

# Copy WebView2 DLL to output (edge runtime dependency, present on Win11 anyway)
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${WV2_ROOT}/x64/WebView2Loader.dll"
        "$<TARGET_FILE_DIR:ParticleBook>"
)

# Copy Z-Library toolbar script to output
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${CMAKE_SOURCE_DIR}/src/zlib_toolbar.js"
        "$<TARGET_FILE_DIR:ParticleBook>/zlib_toolbar.js"
)

# Copy mutool.exe for MuPDF PDF rendering
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${CMAKE_SOURCE_DIR}/third_party/mupdf-1.25.0-windows/mutool.exe"
        "$<TARGET_FILE_DIR:ParticleBook>"
)

# ── Copy frontend to build output ────────────────────────────────
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_directory
        "${CMAKE_SOURCE_DIR}/../out/renderer"
        "$<TARGET_FILE_DIR:ParticleBook>/renderer"
)

# ── Install ─────────────────────────────────────────────────────
install(TARGETS ParticleBook RUNTIME DESTINATION .)
install(FILES "${CMAKE_SOURCE_DIR}/third_party/mupdf-1.25.0-windows/mutool.exe" DESTINATION .)
install(DIRECTORY "$<TARGET_FILE_DIR:ParticleBook>/renderer" DESTINATION renderer)
