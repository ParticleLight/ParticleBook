# 文件关联 / 打开方式（PB-012）

> 目标：安装后右键「打开方式」里能看到 ParticleBook，并且用户可以在 Windows 设置里
> 把它设为默认 —— 但**安装器自己绝不抢默认**，也不碰用户已有的默认阅读器。
>
> 实现位置：`particlebook-cpp/scripts/installer.nsi`（PB-012-A，2026-10-26）。
> 应用内「打开 Windows 默认应用设置」按钮属于另一部分（需要改 App/Bridge，本文只定义
> 契约，见文末「应用侧接线」）。

## 1. 约束（来自 docs/BACKLOG.md PB-012）

| 约束 | 落地方式 |
|------|----------|
| 只注册 ProgID / OpenWithProgids / 应用能力 | 只写 `HKCU\Software\Classes\.<ext>\OpenWithProgids` 与 ProgID，不写 `.<ext>` 的默认值 |
| 不写默认关联、不调强制默认 API | 不写 `HKCU\...\Explorer\FileExts\<ext>\UserChoice`，不调 `SetUserFTA` / `IApplicationAssociationRegistration::SetDefault` |
| 安装/首次运行不自动成为默认 | 安装器写的是「候选」+「应用能力」；默认要用户自己在设置里选 |
| 卸载清理干净、不残留 | 逐个 `DeleteRegValue` + `DeleteRegKey /ifempty`，只删我们写的值 |
| 与已有默认阅读器共存 | 安装前后 `.<ext>` 的默认值、其它应用的 `OpenWithProgids` 原样保留（已实测） |
| 接收启动参数打开文件 | 命令写成 `"<安装目录>\ParticleBook.exe" "%1"`；路径解析与单实例转发是 PB-019（`main.cpp` + `SingleInstance`） |

## 2. 写入的注册表键（全部 HKCU，per-user，无需管理员）

安装器 `RequestExecutionLevel user` + 安装到 `$LOCALAPPDATA\Programs\ParticleBook`，
所以关联也只做**当前用户**：不写 HKLM、不影响其它 Windows 账户（与安装范围一致）。

下面 `<ext>` ∈ `.epub .pdf .mobi .fb2 .txt .cbz .cbr`，`<progid>` = `ParticleBook<ext>`。

### 2.1 ProgID（`!macro RegisterAssoc`）

| 键 | 值 | 作用 |
|----|----|------|
| `HKCU\Software\Classes\<progid>` | (默认) = 友好类型名（如 `EPUB 电子书`） | 类型描述，只有我们真成为默认时资源管理器「类型」列才显示 |
| `...\<progid>\DefaultIcon` | (默认) = `"<安装目录>\ParticleBook.exe",0` | 图标 |
| `...\<progid>\shell\open\command` | (默认) = `"<安装目录>\ParticleBook.exe" "%1"` | 双击/打开时实际执行的命令；`%1` 是文件路径（已加引号，路径带空格安全） |

### 2.2 打开方式候选（关键）

| 键 | 值 | 作用 |
|----|----|------|
| `HKCU\Software\Classes\.<ext>\OpenWithProgids` | `ParticleBook<ext>` = （空 REG_SZ） | **右键「打开方式」里出现 ParticleBook** 的全部秘密。只加这一个值，不改 `.<ext>` 的默认值 |

### 2.3 exe 自身清单

| 键 | 值 | 作用 |
|----|----|------|
| `HKCU\Software\Classes\Applications\ParticleBook.exe` | `FriendlyAppName` = ParticleBook，`ApplicationCompany` = ParticleLight | 「选择其他应用」列表里的显示名 |
| `...\Applications\ParticleBook.exe\shell\open\command` | (默认) = 同上命令 | 从「选择其他应用 → 浏览」选中 exe 时可用 |
| `...\Applications\ParticleBook.exe\SupportedTypes` | 7 个 `.<ext>` = （空） | 声明支持的类型 |

### 2.4 默认应用能力（让用户在设置里能选我们）

| 键 | 值 | 作用 |
|----|----|------|
| `HKCU\Software\ParticleBook\Capabilities` | `ApplicationName` = ParticleBook、`ApplicationDescription` = …、`ApplicationIcon` = `"<exe>",0` | 「设置 → 应用 → 默认应用」里的应用条目 |
| `...\Capabilities\FileAssociations` | `.<ext>` = `ParticleBook<ext>` ×7 | 该应用声明的文件类型 |
| `HKCU\Software\RegisteredApplications` | `ParticleBook` = `Software\ParticleBook\Capabilities` | 把上面的能力注册进 Windows 的「默认应用」列表 |

> `RegisteredApplications` 里的值名（`ParticleBook`）就是 Windows 11 深链
> `ms-settings:defaultapps?registeredAppUser=ParticleBook` 的参数，应用侧按钮用它。

### 2.5 刷新 shell

写完调用一次 `SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, 0, 0)`
（NSIS `System::Call 'shell32::SHChangeNotify(...)'`），资源管理器立即刷新「打开方式」，
不用重启 explorer.exe，也不用重启系统。

## 3. 刻意不写的东西（写了就是抢默认）

| 不写 | 原因 |
|------|------|
| `HKCU\Software\Classes\.<ext>` 的 (默认) 值 | 这一条就是「该扩展名的默认程序 = 我们」。安装器从头到尾不产生它 |
| `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\<ext>\UserChoice` | Win8+ 的用户默认项，带哈希校验；程序写入会被系统拒绝，而且属于「劫持默认」 |
| 任何 `SetUserFTA` / `SetDefaultBrowser` / `IApplicationAssociationRegistration` 调用 | 同上 |
| `HKCU\Software\Classes\.<ext>\ShellNew` / `MENU` 等 | 与关联无关的骚扰项 |

结果：用户原来的默认阅读器（例如 SumatraPDF 是 .pdf 默认）在安装 ParticleBook 后**完全不受影响**，
只有右键「打开方式」多了 ParticleBook 一项。

## 4. 卸载

`!macro UnregisterAssoc` 对每个扩展名执行（顺序很重要）：

1. `DeleteRegValue ...\Capabilities\FileAssociations <ext>`
2. `DeleteRegValue ...\Applications\ParticleBook.exe\SupportedTypes <ext>`
3. `DeleteRegValue ...\.<ext>\OpenWithProgids ParticleBook<ext>` —— 只删我们那个值
4. `DeleteRegKey /ifempty ...\.<ext>\OpenWithProgids` —— 空才删，别的应用还在这就留着
5. `DeleteRegKey /ifempty ...\.<ext>` —— 空才删（用户/别的安装器在该扩展名下还有值就留着）
6. `DeleteRegKey ...\<progid>` —— ProgID 是我们自己的，整棵删

然后：

- `DeleteRegKey HKCU "Software\Classes\Applications\ParticleBook.exe"`（我们自己的子树）
- `DeleteRegValue HKCU "Software\RegisteredApplications" ParticleBook`（**只删值，不删键**，别的应用也在这）
- 文件末尾已有的 `DeleteRegKey HKCU "Software\ParticleBook"` 一并清掉 `Capabilities` 与 `InstallDir`
- 再发一次 `SHChangeNotify`

卸载顺序上，关联清理放在**删文件之前**：即使 exe 被占用、目录删不干净，注册表也不会残留。

> 如果用户曾把 ParticleBook 设为默认：`UserChoice` 是 Windows 写的，我们不动它。
> 卸载后该类型会变成「没有默认应用」，用户点开时自己选新的 —— 这是正确且符合系统的行为；
> 试图改写 `UserChoice` 反而会被系统拒绝或造成「默认应用损坏」。

## 5. 安装 / 升级幂等性

- 安装器每次运行都会重写同一批键值（`WriteRegStr` 覆盖），重复安装/覆盖升级不会产生重复项。
- 隐藏节 `Section "-FileAssociations"` 不受组件页影响，静默安装（`/S`）也会执行。
- 卸载后重新安装可正常恢复关联（无残留状态）。

## 6. 应用侧接线（不在 PB-012-A 范围，供后续任务）

1. 设置页「打开默认应用设置」按钮：
   - Windows 11：`ms-settings:defaultapps?registeredAppUser=ParticleBook`
   - Windows 10 兜底：`ms-settings:defaultapps`
   （用 `ShellExecuteW` 打开即可；因为 `RegisteredApplications` 已经注册，Win11 会直接跳到
   ParticleBook 的默认应用页，用户自己勾选 .epub/.pdf/…）
2. 启动参数：安装器写的是 `"%1"`；`main.cpp` 已经在 `wWinMain` 里
   `CommandLineToArgvW` 取路径 → `SingleInstance` 转发给已运行的实例（PB-019）。
3. 首次用 ParticleBook 打开文件后，若想提示「设为默认」，只能引导用户去设置页，
   不要尝试静默修改。

## 7. 验证方式

### 7.1 编译安装包（本机已实测通过）

```bash
cd particlebook-cpp/scripts
makensis /DPRODUCT_VERSION=2.2.1 installer.nsi      # 需要 NSIS 3.x；产出 ..\build2\ParticleBook-Setup-v<版本>.exe
```

> ⚠ **installer.nsi 必须保持 UTF-8 with BOM**。脚本里有中文（安装器提示、ProgID 类型名），
> makensis 只有看到 BOM 才按 UTF-8 读；没有 BOM 时它按系统 ACP 读：
> 在中文 Windows 上直接报 `Bad text encoding`，在英文 Windows 上不报错但中文变成乱码。
> 用会吞 BOM 的编辑器（部分 IDE 的「另存为 UTF-8」）改完务必确认前 3 字节是 `EF BB BF`。

### 7.2 检查注册表（装完）

```bat
reg query "HKCU\Software\Classes\.epub\OpenWithProgids"
reg query "HKCU\Software\Classes\ParticleBook.epub\shell\open\command"
reg query "HKCU\Software\ParticleBook\Capabilities\FileAssociations"
reg query "HKCU\Software\RegisteredApplications" /v ParticleBook
reg query "HKCU\Software\Classes\Applications\ParticleBook.exe" /s
```

期望：7 个 `ParticleBook.<ext>` 值、命令形如
`"C:\Users\<你>\AppData\Local\Programs\ParticleBook\ParticleBook.exe" "%1"`。

### 7.3 确认没抢默认

```bat
:: 安装前后各跑一次，结果必须完全一致
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.epub\UserChoice"
reg query "HKCU\Software\Classes\.epub" /ve
```

### 7.4 右键菜单

资源管理器里对 `.epub` 文件右键 → **打开方式** → 应出现 ParticleBook
（Win11 新版菜单可能要先点「显示更多选项」/「选择其他应用」；「始终使用此应用」由用户点，
那是 Windows 写 `UserChoice`，不是安装器写的）。

### 7.5 卸载回归

卸载后上面 7.2 的五条查询应全部报「找不到」，而：

```bat
reg query "HKCU\Software\Classes\.epub" /ve                        :: 默认值仍是原来的
reg query "HKCU\Software\Classes\.epub\OpenWithProgids"          :: 只剩别的应用
reg query "HKCU\Software\RegisteredApplications"                  :: 只剩别的应用
```

### 7.6 沙箱自动化验证（2026-10-26 实跑记录）

因为直接装真机安装包会污染当前用户关联，验证时把 installer.nsi 里的两个宏
（`RegisterAssoc` / `UnregisterAssoc`）和安装/卸载段**原样抽出来**，只把注册表根
替换成 `HKCU\Software\_pbassoctest\…`，用 makensis 编成两个静音小程序依次执行：

1. 预置「用户已有状态」：`.epub` 默认值 = `epubfile`、`.epub` Content Type、
   `.epub\OpenWithProgids\OtherApp.epub`、`.pdf\OpenWithProgids\AnotherPdf`、
   `RegisteredApplications\SomeOtherApp`；
2. 跑注册 harness → 检查；
3. 跑反注册 harness → 检查。

实测结果（全部通过）：

- 安装后 `.epub` 的默认值仍是 `epubfile`，Content Type 仍在；`OtherApp.epub` / `AnotherPdf` /
  `SomeOtherApp` 全部保留；
- 7 个扩展名的 `OpenWithProgids` 都多了 `ParticleBook.<ext>`；
- 7 个 ProgID 的 `shell\open\command` 都是带引号的 `"...\ParticleBook.exe" "%1"`；
- `Capabilities\FileAssociations` 7 条、`RegisteredApplications\ParticleBook` 1 条、
  `Applications\ParticleBook.exe\SupportedTypes` 7 条；
- 卸载后：ParticleBook 的 ProgID / Applications 子树 / Capabilities / RegisteredApplications 值全部消失；
  我们新建的 `.mobi .fb2 .txt .cbz .cbr` 键也整键消失（`/ifempty` 生效）；
  `.epub` / `.pdf` 里别人的值原样保留。

## 8. 已知限制 / 风险

| 项 | 说明 |
|----|------|
| 仅当前用户 | 安装到 `%LOCALAPPDATA%`、只写 HKCU，所以别的 Windows 账户看不到关联。多用户机器若要全局注册需要 HKLM + 管理员权限，与现在的免安装式安装冲突，暂不做 |
| 「打开方式」列表缓存 | 已发 `SHChangeNotify`；极少数情况（企业策略、Win11 新版菜单）仍可能要先重启 explorer.exe 才刷新 |
| Win11 菜单折叠 | 新版右键菜单把「打开方式」收在二级；用户也可以「显示更多选项」走旧菜单 |
| `.txt` / `.pdf` 竞争激烈 | 我们只是候选之一，不会也不能挤掉系统默认（记事本 / Edge / Acrobat） |
| 用户设为默认后再卸载 | 该类型变「无默认应用」，需要用户重选；We 不改 `UserChoice`（系统禁止程序写） |
| 不注册 URL 协议 / 不注册「发送到」 | 不在 PB-012 范围 |
| 安装包签名 | 未签名时 SmartScreen 可能提示；与关联无关，属发布流程 |

## 9. 变更记录

- 2026-10-26（PB-012-A）：installer.nsi 新增 `RegisterAssoc` / `UnregisterAssoc` 宏 +
  隐藏安装节 `-FileAssociations` + 卸载段清理；7 种扩展名注册；沙箱验证通过；
  `installer.nsi` 保持 UTF-8 BOM（makensis 编码要求，见 7.1）。
