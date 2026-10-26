cmake_minimum_required(VERSION 3.25)
project(ParticleBook VERSION 2.2.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# 字符集必须是 Unicode：本工程全程用带 W 后缀的 Win32 API，但未加后缀的调用
# （如 DefWindowProc）在缺这个宏时会静默展开成 ANSI 版 —— 曾导致窗口标题被截成
# 首字符 "P"（WM_SETTEXT 里的宽字符串被按 ANSI 解读）。此处显式声明，避免复发。
add_compile_definitions(UNICODE _UNICODE)

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
    src/services/BackupService.cpp
    src/services/DictionaryService.cpp
    src/services/TranslationService.cpp
    src/services/ExportService.cpp
    src/services/SearchIndexService.cpp
    src/domain/Locator.cpp
    src/domain/Annotation.cpp
    src/utils/ZipReader.cpp
    src/handlers/DbHandlers.cpp
    src/handlers/FileHandlers.cpp
    src/handlers/BackupHandlers.cpp
    src/handlers/ExportHandlers.cpp
    src/handlers/TtsHandlers.cpp
    src/handlers/TranslationHandlers.cpp
    src/handlers/AnnotationHandlers.cpp
    src/handlers/SearchHandlers.cpp
    src/platform/SingleInstance.cpp
    src/platform/UiDispatcher.cpp
    src/platform/JobService.cpp
    src/platform/SecretStore.cpp
    src/platform/SettingsService.cpp
    src/platform/LogService.cpp
    src/platform/SapiTts.cpp
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
    crypt32
    sapi
)

# Copy WebView2 DLL to output (edge runtime dependency, present on Win11 anyway)
add_custom_command(TARGET ParticleBook POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${WV2_ROOT}/x64/WebView2Loader.dll"
        "$<TARGET_FILE_DIR:ParticleBook>"
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

# ── Unit tests (pure header utils — no Win32/GUI deps) ───────────────
# Each test .cpp has its own main(), so build one small executable per test.
enable_testing()
set(PB_TEST_SOURCES
    tests/unit/encoding_test.cpp
    tests/unit/base64_test.cpp
    tests/unit/fnv1a_test.cpp
    tests/unit/update_meta_test.cpp
    tests/unit/cmdline_test.cpp
    tests/unit/mobi_patch_test.cpp
)
foreach(_t IN LISTS PB_TEST_SOURCES)
    get_filename_component(_name "${_t}" NAME_WE)
    add_executable("${_name}" "${_t}")
    target_compile_options("${_name}" PRIVATE /EHa /utf-8)
    target_include_directories("${_name}" PRIVATE src/ "${CMAKE_BINARY_DIR}/src/")
    add_test(NAME "${_name}" COMMAND "${_name}")
endforeach()

# ── database_test: needs a real translation unit, not just headers ────
# DatabaseService has no Win32 GUI dependency (only std + nlohmann/json), so
# its atomic-write / corrupt-backup logic can be tested directly. It is the
# app's data-loss protection, hence the priority.
add_executable(database_test
    tests/unit/database_test.cpp
    src/services/DatabaseService.cpp
)
target_compile_options(database_test PRIVATE /EHa /utf-8)
target_include_directories(database_test PRIVATE
    src/
    "${CMAKE_BINARY_DIR}/src/"
    "${CMAKE_SOURCE_DIR}/third_party"   # nlohmann/json.hpp
)
add_test(NAME database_test COMMAND database_test)

# ── PB-020 JobService / PB-023 SecretStore ────────────────────────
add_executable(job_service_test
    tests/unit/job_service_test.cpp
    src/platform/JobService.cpp
)
target_compile_options(job_service_test PRIVATE /EHa /utf-8)
target_include_directories(job_service_test PRIVATE src/)
add_test(NAME job_service_test COMMAND job_service_test)

add_executable(secret_store_test
    tests/unit/secret_store_test.cpp
    src/platform/SecretStore.cpp
)
target_compile_options(secret_store_test PRIVATE /EHa /utf-8)
target_include_directories(secret_store_test PRIVATE src/)
target_link_libraries(secret_store_test PRIVATE crypt32)
add_test(NAME secret_store_test COMMAND secret_store_test)

# ── PB-023 SettingsService (pure std + json; no Win32) ─────────────
add_executable(settings_service_test
    tests/unit/settings_service_test.cpp
    src/platform/SettingsService.cpp
)
target_compile_options(settings_service_test PRIVATE /EHa /utf-8)
target_include_directories(settings_service_test PRIVATE
    src/
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME settings_service_test COMMAND settings_service_test)

# ── PB-016 LogService (pure std + Win32 file APIs; no GUI) ───────────
add_executable(log_service_test
    tests/unit/log_service_test.cpp
    src/platform/LogService.cpp
)
target_compile_options(log_service_test PRIVATE /EHa /utf-8)
target_include_directories(log_service_test PRIVATE src/)
add_test(NAME log_service_test COMMAND log_service_test)

# ── PB-001 SAPI TTS (COM/SAPI; no GUI) ─────────────────────────────
add_executable(sapi_tts_test
    tests/unit/sapi_tts_test.cpp
    src/platform/SapiTts.cpp
)
target_compile_options(sapi_tts_test PRIVATE /EHa /utf-8)
target_include_directories(sapi_tts_test PRIVATE src/)
target_link_libraries(sapi_tts_test PRIVATE sapi ole32)
add_test(NAME sapi_tts_test COMMAND sapi_tts_test)

# ── PB-011 BackupService (zip writer + BCrypt sha256) ───────────────
add_executable(backup_service_test
    tests/unit/backup_service_test.cpp
    src/services/BackupService.cpp
    src/utils/ZipReader.cpp
    third_party/miniz.c
    third_party/miniz_tdef.c
    third_party/miniz_tinfl.c
)
target_compile_options(backup_service_test PRIVATE /EHa /utf-8)
target_include_directories(backup_service_test PRIVATE
    src/
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME backup_service_test COMMAND backup_service_test)

# ── PB-021 Locator / Annotation model (pure std + json) ────────────
add_executable(annotation_test
    tests/unit/annotation_test.cpp
    src/domain/Locator.cpp
    src/domain/Annotation.cpp
)
target_compile_options(annotation_test PRIVATE /EHa /utf-8)
target_include_directories(annotation_test PRIVATE
    src/
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME annotation_test COMMAND annotation_test)

# ── PB-010 DictionaryService (ECDICT/StarDict; miniz tinfl) ─────────
add_executable(dictionary_service_test
    tests/unit/dictionary_service_test.cpp
    src/services/DictionaryService.cpp
    third_party/miniz.c
    third_party/miniz_tinfl.c
    third_party/miniz_tdef.c
)
target_compile_options(dictionary_service_test PRIVATE /EHa /utf-8)
target_include_directories(dictionary_service_test PRIVATE
    src/
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME dictionary_service_test COMMAND dictionary_service_test)

# ── PB-002 TranslationService (OpenAI-compatible; mockable transport) ─
add_executable(translation_service_test
    tests/unit/translation_service_test.cpp
    src/services/TranslationService.cpp
    src/platform/SettingsService.cpp
)
target_compile_options(translation_service_test PRIVATE /EHa /utf-8)
target_include_directories(translation_service_test PRIVATE
    src/
    "${CMAKE_BINARY_DIR}/src/"
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME translation_service_test COMMAND translation_service_test)

# ── PB-004 ExportService (notes/highlights; Markdown/CSV/JSON) ──────
add_executable(export_service_test
    tests/unit/export_service_test.cpp
    src/services/ExportService.cpp
)
target_compile_options(export_service_test PRIVATE /EHa /utf-8)
target_include_directories(export_service_test PRIVATE
    src/
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME export_service_test COMMAND export_service_test)

# ── PB-006 SearchIndexService (EPUB/TXT/HTML/MD full-text) ──────────
add_executable(search_index_test
    tests/unit/search_index_test.cpp
    src/services/SearchIndexService.cpp
    src/utils/ZipReader.cpp
    third_party/miniz.c
    third_party/miniz_tdef.c
    third_party/miniz_tinfl.c
)
target_compile_options(search_index_test PRIVATE /EHa /utf-8)
target_include_directories(search_index_test PRIVATE
    src/
    "${CMAKE_SOURCE_DIR}/third_party"
)
add_test(NAME search_index_test COMMAND search_index_test)
