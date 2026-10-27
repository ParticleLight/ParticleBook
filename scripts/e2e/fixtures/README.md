# E2E fixtures — example.azw3

`example.azw3` 是 AZW3/KF8 导入测试样本（Mobi version 8，694,801 B）：

- 标题 `example`，作者 `Online-Convert.com`，由 calibre 2.4.0 生成；
- 正文/图片来自 online-convert.com 的 AZW3 示例（CC BY-SA 3.0），仅供本仓库 E2E 测试；
- SHA-256：`53ab77f6723cb286d35b539c5543b6da064e74d6d014a8fef8fea06684c5b674`。

用途：`scripts/e2e/smoke.mjs` 的 `azw3-import` 步骤通过真实导入链路（mobitool.exe → EPUB）
验证 AZW3 转 EPUB、书架入库（original_path 保留）、EPUB 阅读器正文渲染。无 DRM。
