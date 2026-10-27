; ParticleBook NSIS Installer
; First install: choose directory. Update: auto-detect existing path.

Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 64

!define PRODUCT_NAME "ParticleBook"
!ifndef PRODUCT_VERSION
!define PRODUCT_VERSION "2.2.0"   ; fallback only — release always passes /DPRODUCT_VERSION=
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

; ── PB-012 · 文件关联（只进「打开方式」，绝不抢默认）─────────────────────
;
; 安装器只写 HKCU（配合上面的 RequestExecutionLevel user，无需管理员），注册三层：
;   1) ProgID      HKCU\Software\Classes\ParticleBook.<ext>              —— 「用什么打开」
;   2) 候选入口    HKCU\Software\Classes\.<ext>\OpenWithProgids          —— 右键「打开方式」可见
;   3) 应用能力    HKCU\${REG_KEY}\Capabilities + HKCU\Software\RegisteredApplications
;                                                                       —— Windows 设置里由用户自己设默认
; 另外写 HKCU\Software\Classes\Applications\ParticleBook.exe：
;   exe 自身出现在「打开方式 → 选择其他应用」列表里（Win7 时代的老清单，仍然生效）。
;
; 刻意【不】写的东西（写了就是抢默认，属于 PB-012 的非目标）：
;   * HKCU\Software\Classes\.<ext> 的 (Default) 值  —— 那就是把该类型的默认程序改成我们
;   * HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\<ext>\UserChoice
;                                                    —— Windows 校验哈希，程序写入必被拒，也不该碰
;   * 任何 SetUserFTA / 强制默认 API
; 因此安装前后用户已有的默认阅读器完全不受影响；卸载只删我们自己的键。
; 完整键清单与手工验证步骤见 docs/FILE_ASSOCIATION.md。

; 注册一种扩展名：ProgID + 「打开方式」候选 + 默认应用能力里的文件类型。
; EXT 带点（".epub"），PROGID 形如 "ParticleBook.epub"。
; DESC 是 ProgID 的友好类型名（只有我们真成为默认时资源管理器「类型」列才会显示）。
!macro RegisterAssoc EXT PROGID DESC
    WriteRegStr HKCU "Software\Classes\${PROGID}" "" "${DESC}"
    WriteRegStr HKCU "Software\Classes\${PROGID}\DefaultIcon" "" '"$INSTDIR\ParticleBook.exe",0'
    WriteRegStr HKCU "Software\Classes\${PROGID}\shell\open\command" "" '"$INSTDIR\ParticleBook.exe" "%1"'
    ; 关键：只往 .<ext> 下面加 OpenWithProgids 值，不碰 .<ext> 的 (Default)
    WriteRegStr HKCU "Software\Classes\${EXT}\OpenWithProgids" "${PROGID}" ""
    ; Windows「设置 → 默认应用 → 按文件类型」里 ParticleBook 成为候选
    WriteRegStr HKCU "${REG_KEY}\Capabilities\FileAssociations" "${EXT}" "${PROGID}"
    ; exe 清单里声明支持的扩展名
    WriteRegStr HKCU "Software\Classes\Applications\ParticleBook.exe\SupportedTypes" "${EXT}" ""
!macroend

; 卸载时反注册。每一步都只删「我们写的那个值」：
;   * DeleteRegValue + DeleteRegKey /ifempty —— 用户/其它阅读器在同一扩展名下的键值原样保留；
;   * 绝不 DeleteRegKey 整个 .<ext>（那会连别人注册的 OpenWithProgids 一起删掉）。
!macro UnregisterAssoc EXT PROGID
    DeleteRegValue HKCU "${REG_KEY}\Capabilities\FileAssociations" "${EXT}"
    DeleteRegValue HKCU "Software\Classes\Applications\ParticleBook.exe\SupportedTypes" "${EXT}"
    DeleteRegValue HKCU "Software\Classes\${EXT}\OpenWithProgids" "${PROGID}"
    DeleteRegKey /ifempty HKCU "Software\Classes\${EXT}\OpenWithProgids"
    DeleteRegKey /ifempty HKCU "Software\Classes\${EXT}"
    DeleteRegKey HKCU "Software\Classes\${PROGID}"
!macroend

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
    ; PB-024-B: $INSTDIR\resources 现在是本应用的注入脚本目录（resources/*.js），
    ; 不能再整目录删除；只清旧 Electron 版残留的 asar 包。
    Delete "$INSTDIR\resources\app.asar"
    RMDir /r "$INSTDIR\resources\app.asar.unpacked"

    SetOutPath "$INSTDIR"

    ; Core application
    File "..\build2\ParticleBook.exe"
    File "..\build2\mutool.exe"
    File "..\build2\WebView2Loader.dll"

    ; PB-024-A/B: Z-Library 注入脚本（App.cpp 启动时从 <exe>/resources/*.js 读取；
    ; 缺失会回退到内置最小脚本并打 WARN，所以这里必须随包分发）
    SetOutPath "$INSTDIR\resources"
    File "..\build2\resources\*.js"

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

; ── PB-012：文件关联（隐藏节，始终执行；只注册、不抢默认）────────────────
Section "-FileAssociations"
    ; exe 自身进「打开方式 → 选择其他应用」清单
    WriteRegStr HKCU "Software\Classes\Applications\ParticleBook.exe" "FriendlyAppName" "${PRODUCT_NAME}"
    WriteRegStr HKCU "Software\Classes\Applications\ParticleBook.exe" "ApplicationCompany" "${PRODUCT_PUBLISHER}"
    WriteRegStr HKCU "Software\Classes\Applications\ParticleBook.exe\shell\open\command" "" '"$INSTDIR\ParticleBook.exe" "%1"'

    ; 默认应用能力：RegisteredApplications 的值 = Capabilities 子键路径（相对 HKCU）
    WriteRegStr HKCU "${REG_KEY}\Capabilities" "ApplicationName" "${PRODUCT_NAME}"
    WriteRegStr HKCU "${REG_KEY}\Capabilities" "ApplicationDescription" "本地离线电子书阅读器（EPUB / PDF / MOBI / FB2 / TXT / CBZ / CBR）"
    WriteRegStr HKCU "${REG_KEY}\Capabilities" "ApplicationIcon" '"$INSTDIR\ParticleBook.exe",0'
    WriteRegStr HKCU "Software\RegisteredApplications" "${PRODUCT_NAME}" "${REG_KEY}\Capabilities"

    ; 7 种电子书扩展名。全部走 OpenWithProgids 候选，不写任何默认值。
    !insertmacro RegisterAssoc ".epub" "ParticleBook.epub" "EPUB 电子书"
    !insertmacro RegisterAssoc ".pdf"  "ParticleBook.pdf"  "PDF 文档"
    !insertmacro RegisterAssoc ".mobi" "ParticleBook.mobi" "MOBI 电子书"
    !insertmacro RegisterAssoc ".fb2"  "ParticleBook.fb2"  "FB2 电子书"
    !insertmacro RegisterAssoc ".txt"  "ParticleBook.txt"  "TXT 纯文本"
    !insertmacro RegisterAssoc ".cbz"  "ParticleBook.cbz"  "CBZ 漫画包"
    !insertmacro RegisterAssoc ".cbr"  "ParticleBook.cbr"  "CBR 漫画包"

    ; 通知 shell「文件关联变了」，资源管理器立刻刷新「打开方式」，不用重启。
    ; SHCNE_ASSOCCHANGED = 0x08000000, SHCNF_IDLIST = 0
    System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

Section "Uninstall"
    ; ── PB-012：先摘掉文件关联（只删我们写的值），再删文件；
    ; 这样即使 exe 被占用、目录删不干净，注册表也不会残留。
    !insertmacro UnregisterAssoc ".epub" "ParticleBook.epub"
    !insertmacro UnregisterAssoc ".pdf"  "ParticleBook.pdf"
    !insertmacro UnregisterAssoc ".mobi" "ParticleBook.mobi"
    !insertmacro UnregisterAssoc ".fb2"  "ParticleBook.fb2"
    !insertmacro UnregisterAssoc ".txt"  "ParticleBook.txt"
    !insertmacro UnregisterAssoc ".cbz"  "ParticleBook.cbz"
    !insertmacro UnregisterAssoc ".cbr"  "ParticleBook.cbr"

    ; exe 清单是纯我们自己的子树，可以整棵删
    DeleteRegKey HKCU "Software\Classes\Applications\ParticleBook.exe"
    ; 只删 RegisteredApplications 里的值，不删整个键（别的应用也在里面）
    DeleteRegValue HKCU "Software\RegisteredApplications" "${PRODUCT_NAME}"
    ; Capabilities 由文件末尾的 DeleteRegKey HKCU "${REG_KEY}" 一并清除

    System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'

    Delete "$INSTDIR\ParticleBook.exe"
    Delete "$INSTDIR\mutool.exe"
    Delete "$INSTDIR\WebView2Loader.dll"
    Delete "$INSTDIR\renderer\index.html"
    Delete "$INSTDIR\renderer\pdf.worker.min.mjs"
    Delete "$INSTDIR\renderer\assets\*.js"
    Delete "$INSTDIR\renderer\assets\*.css"
    RMDir "$INSTDIR\renderer\assets"
    RMDir "$INSTDIR\renderer"
    Delete "$INSTDIR\resources\*.js"
    RMDir "$INSTDIR\resources"
    Delete "$INSTDIR\uninstall.exe"
    RMDir "$INSTDIR"

    Delete "$DESKTOP\ParticleBook.lnk"
    Delete "$SMPROGRAMS\ParticleBook\ParticleBook.lnk"
    RMDir "$SMPROGRAMS\ParticleBook"

    DeleteRegKey HKCU "${REG_KEY}"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ParticleBook"
SectionEnd
