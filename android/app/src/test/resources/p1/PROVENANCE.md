# P1 真实 EPUB 测试夹具来源

本目录只用于 task-155 的独立 oracle 测试；不参与生产构建。

| 文件 | 字节 | SHA-256 | 来源 |
| --- | ---: | --- | --- |
| moby-dick.epub | 1,165,503 | 4f0a70cac781ab8f30b5efb8d20ee78db9e7e1fc23e0c261e35c8690f48db34c | docs/android-port/fixtures/books/standardebooks/herman-melville_moby-dick.epub（Standard Ebooks，公有领域） |
| wizard-of-oz.epub | 681,119 | 0b52de91b003bd32f0831142258a142836983060547c054623eeab6d936f7754 | docs/android-port/fixtures/books/standardebooks/l-frank-baum_the-wonderful-wizard-of-oz.epub（Standard Ebooks，公有领域） |
| multilevel-toc.epub | 4,129 | 8f1e1fe88ca6692fa8d54cc66b9049eeddfe7f34521411be15658ce0a235ed37 | docs/android-port/fixtures/books/synthetic/multilevel-toc.epub（ParticleBook 合成夹具，多级目录/GBK 编码） |

复制时间：2026-11-15；仅复制，未修改内容。测试从 classpath /p1/books/ 读取这些文件。
