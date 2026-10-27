# third_party/mobitool

- `mobitool.exe`：libmobi 0.12 的 Windows x64 构建（MSVC，/MT，仅依赖 KERNEL32.dll），
  用于 AZW3/KF8 → EPUB 转换。
- `COPYING`：LGPL-3.0-or-later 许可证原文。
- `NOTICE.md`：组件来源、固定 commit、LGPL 合规说明。
- `libmobi-0.12-9062742-src.tar.gz`：对应源码归档（git archive）。

重新构建：在仓库根执行

```powershell
pwsh -File scripts/azw3/build-mobitool.ps1 -OutDir particlebook-cpp/third_party/mobitool
```

构建产物会覆盖本目录的 `mobitool.exe` 和 `COPYING`（源码归档需另行 git archive）。
CMake 在 POST_BUILD 阶段把 `mobitool.exe` 复制到 `ParticleBook.exe` 同目录。
