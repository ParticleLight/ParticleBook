# mobitool (libmobi) — 第三方组件声明

本目录随 ParticleBook 分发 **mobitool.exe**，用于把 AZW3/KF8 电子书转换为 EPUB。

## 组件

- 名称：libmobi / mobitool
- 版本：0.12
- 上游：https://github.com/bfabiszewski/libmobi
- 固定 commit：`906274205c11944b628da1c553b255acb1af7c55`
- 许可证：**LGPL-3.0-or-later**（见同目录 `COPYING`）
- 构建方式：见仓库 `scripts/azw3/build-mobitool.ps1`（MSVC / CMake，无外部依赖）

## 分发与 LGPL 合规

- 本目录同时提供对应源码归档：`libmobi-0.12-9062742-src.tar.gz`（由上述 commit 的
  `git archive` 生成）；如未随包附带，亦可通过上游仓库该 commit 获取。
- `mobitool.exe` 为独立可执行文件，ParticleBook 通过子进程调用，不静态链接进主程序。
- 修改/重新构建：解压源码归档或 `git clone` 上游后 checkout 上述 commit，按
  `scripts/azw3/build-mobitool.ps1` 的参数构建即可重新生成同功能二进制。

## 使用

```
mobitool.exe -e -o <outdir> <book.azw3>   # 生成 <outdir>/<book>.epub
mobitool.exe -c -o <outdir> <book.azw3>   # 导出封面
mobitool.exe -d -o <outdir> <book.azw3>   # 导出 rawml HTML
```

DRM 加密文件不支持（会在导入时按“DRM 或格式不支持”报错）。
