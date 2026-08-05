; ParticleBook NSIS Installer
; First install: choose directory. Update: auto-detect existing path.

Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 64

!define PRODUCT_NAME "ParticleBook"
!ifndef PRODUCT_VERSION
!define PRODUCT_VERSION "2.0.3"   ; override via: makensis /DPRODUCT_VERSION=2.0.4
!endif
!define PRODUCT_PUBLISHER "ParticleLight"
!define REG_KEY "Software\ParticleBook"

Name "${PRODUCT_NAME} v${PRODUCT_VERSION}"
OutFile "..\build2\ParticleBook-Setup-v${PRODUCT_VERSION}.exe"
Icon "..\assets\app.ico"
InstallDir "$LOCALAPPDATA\Programs\ParticleBook"
BrandingText " "

!include "MUI2.nsh"
!define MUI_ICON "..\assets\app.ico"
!define MUI_UNICON "..\assets\app.ico"

; Pages
!insertmacro MUI_PAGE_WELCOME
!define MUI_PAGE_CUSTOMFUNCTION_PRE SkipDirIfInstalled
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

Var AlreadyInstalled

Function .onInit
    ; Detect a running app to avoid a half-updated install (locked exe/dll).
    ; Class name matches WebViewHost.cpp "ParticleBook_MainWindow".
    FindWindow $0 "" "ParticleBook_MainWindow"
    StrCmp $0 0 notrunning
    MessageBox MB_OK|MB_ICONEXCLAMATION "ParticleBook 正在运行。请先关闭应用，再运行安装程序。"
    Abort
notrunning:

    IfSilent 0 +2
    SetAutoClose true

    ; Check if already installed
    ReadRegStr $0 HKCU "${REG_KEY}" "InstallDir"
    StrCmp $0 "" done
    StrCpy $INSTDIR $0
    StrCpy $AlreadyInstalled 1
done:
FunctionEnd

Function SkipDirIfInstalled
    StrCmp $AlreadyInstalled 1 0 +2
    Abort
FunctionEnd

Section "Install"
    ; ── Clean up old Electron version ──────────────────────────
    ; Remove Electron/Chromium files that the C++ version doesn't use
    ; ParticleBook.exe will be overwritten, but Electron extras remain
    Delete "$INSTDIR\chrome_100_percent.pak"
    Delete "$INSTDIR\chrome_200_percent.pak"
    Delete "$INSTDIR\icudtl.dat"
    Delete "$INSTDIR\snapshot_blob.bin"
    Delete "$INSTDIR\v8_context_snapshot.bin"
    Delete "$INSTDIR\vk_swiftshader_icd.json"
    Delete "$INSTDIR\resources.pak"
    Delete "$INSTDIR\LICENSE.electron.txt"
    Delete "$INSTDIR\LICENSES.chromium.html"
    Delete "$INSTDIR\*.dll"
    Delete "$INSTDIR\*.bin"
    RMDir /r "$INSTDIR\locales"
    RMDir /r "$INSTDIR\resources"

    SetOutPath "$INSTDIR"

    ; Core application
    File "..\build2\ParticleBook.exe"
    File "..\build2\mutool.exe"
    File "..\build2\WebView2Loader.dll"

    ; Renderer
    SetOutPath "$INSTDIR\renderer"
    File "..\build2\renderer\index.html"
    File /nonfatal "..\build2\renderer\pdf.worker.min.mjs"

    SetOutPath "$INSTDIR\renderer\assets"
    File "..\build2\renderer\assets\*.js"
    File "..\build2\renderer\assets\*.css"

    SetOutPath "$INSTDIR"

    ; Save install path for future updates
    WriteRegStr HKCU "${REG_KEY}" "InstallDir" "$INSTDIR"

    ; Desktop shortcut
    CreateShortCut "$DESKTOP\ParticleBook.lnk" "$INSTDIR\ParticleBook.exe" "" "$INSTDIR\ParticleBook.exe" 0

    ; Start Menu
    CreateDirectory "$SMPROGRAMS\ParticleBook"
    CreateShortCut "$SMPROGRAMS\ParticleBook\ParticleBook.lnk" "$INSTDIR\ParticleBook.exe" "" "$INSTDIR\ParticleBook.exe" 0

    ; Uninstaller
    WriteUninstaller "$INSTDIR\uninstall.exe"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook" "DisplayName" "${PRODUCT_NAME}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook" "UninstallString" "$INSTDIR\uninstall.exe"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook" "DisplayVersion" "${PRODUCT_VERSION}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook" "Publisher" "${PRODUCT_PUBLISHER}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook" "DisplayIcon" "$INSTDIR\ParticleBook.exe"
SectionEnd

Section "Uninstall"
    Delete "$INSTDIR\ParticleBook.exe"
    Delete "$INSTDIR\mutool.exe"
    Delete "$INSTDIR\WebView2Loader.dll"
    Delete "$INSTDIR\renderer\index.html"
    Delete "$INSTDIR\renderer\pdf.worker.min.mjs"
    Delete "$INSTDIR\renderer\assets\*.js"
    Delete "$INSTDIR\renderer\assets\*.css"
    RMDir "$INSTDIR\renderer\assets"
    RMDir "$INSTDIR\renderer"
    Delete "$INSTDIR\uninstall.exe"
    RMDir "$INSTDIR"

    Delete "$DESKTOP\ParticleBook.lnk"
    Delete "$SMPROGRAMS\ParticleBook\ParticleBook.lnk"
    RMDir "$SMPROGRAMS\ParticleBook"

    DeleteRegKey HKCU "${REG_KEY}"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook"
SectionEnd
