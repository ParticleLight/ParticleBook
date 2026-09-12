# ParticleBook 改进计划 · 第二轮（Review 驱动）

> **状态：已执行 —— 除 §4.2（M7 真·类型级契约生成）外全部落地。**
> 分支 `improve/foundation`，本次新增 12 个提交；仓库工作树干净。
>
> 干净克隆端到端验证（完整复刻 build.yml 全部步骤，10 步全绿）：
> npm ci / tsc --noEmit / npm run build / i18n:check / 桥接契约 / 版本同步
> / cmake configure / cmake build / ctest 7-7 / smoke(exe+renderer)。
>
> 执行中额外发现并修复的真实缺陷（不在原计划内）：
> ① 在线书源搜索永远返回空 —— store 把 C++ 返回的数组当对象用（TypeScript 一直在报错）
> ② latest.yml 解析「首个 url 获胜」在含 .blockmap 的多文件布局下会选中 blockmap 及其错误哈希
> ③ DatabaseService::NextId() 与 ZLibraryService::IsActive() 均为零调用死代码
>
> 测试套件：5 → 7（新增 database_test、mobi_patch_test），全部经变异测试证明有牙齿。
> 依据：上一轮全面 review 的 11 项发现 + 第一轮计划兑现度核查。
> 基线：分支 improve/foundation，10 个 commit，ctest 5/5、桥接契约 92 一致、nmake EXIT=0。
> 本计划只列【尚未修复】的项；review 中已当场修复的 4 项见文末附录，不重复列入。
