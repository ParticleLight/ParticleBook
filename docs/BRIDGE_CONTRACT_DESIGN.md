# M7 桥接契约：类型级生成 方案设计

> 状态：**设计稿，待确认后再动代码。** 依据：对存根 / d.ts / C++ 注册三方的实测侦察。

## 1. 现状与实测数据

| 事实 | 实测值 |
|---|---|
| 桥接方法总数 | 92（存根、d.ts、C++ 注册三方一致） |
| d.ts 成员中带 any 的 | **38 / 92（41%）** |
| d.ts 中带联合类型的 | 10 |
| 存根可解析出的成员 + 参数 | 92（20 个无参、72 个有参） |
| 存根参数名 vs d.ts 参数名 | **不一致**（存根用缩写 readFile(path)、updateReadingProgress(bid, prog)；d.ts 用可读名 filePath、bookId/progress） |

三条结论直接决定设计：

1. **存根只能提供「成员名 + 元数」，不能提供参数名，也不能提供类型。** 参数名与类型只存在于 d.ts，无机器可读来源。
2. **C++ 侧没有类型元数据**：注册形式是方法名字符串加 lambda，类型不可推导。
3. **已有校验覆盖了名字，但没覆盖元数**：现有脚本比对存根成员名与 d.ts 成员名；若某成员参数个数变了而 d.ts 没跟上，**当前无任何检查会发现**。

## 2. 诚实的能力边界（必须先讲清）

**本方案不会让类型变得更准确。** 类型的真实来源始终是 C++ 侧拼装的 json，机器读不出来；41% 的 any 也照旧。

它买到的是三样：

- **d.ts 不可能漂移**：改成生成物后，CI 重新生成并与仓库文件比对，过期即失败。
- **元数纳入校验**（当前完全缺失的一项）。
- **类型只有一个家**：将来逐个消灭 any 时改一处即可，不会再「改了 d.ts 忘了同步」。

买不到的是：类型正确性。那需要另一件事——把 38 个 any 换成真实形状，属**手工标注工作**（见 §6 的可选加固）。

## 3. 方案

### 3.1 权威表 `bridge/contract.mjs`

```js
// 桥接契约的唯一权威来源。electron.d.ts 由它生成，勿手改生成物。
export const contract = [
  { member: 'openFile', method: 'dialog:openFile', group: 'Files',
    params: [], returns: 'Promise<string | null>' },

  { member: 'readFile', method: 'file:read', group: 'Files',
    params: [{ n: 'filePath', t: 'string' }],
    returns: 'Promise<Uint8Array | number[] | PbVirtualHostRef>' },

  { member: 'getBookMetadata', method: 'book:metadata', group: 'Files',
    params: [{ n: 'filePath', t: 'string' }], returns: 'Promise<any>' },
  // …共 92 条
];
```

类型一律写成**字面 TS 字符串**，生成器不做类型解析——因此不需要写 parser，也没有解析歧义风险。

### 3.2 生成器 `scripts/gen-bridge-dts.mjs`

输出 `src/renderer/types/electron.d.ts`，结构：

```ts
// 此文件由 scripts/gen-bridge-dts.mjs 从 bridge/contract.mjs 生成，请勿手改。
// 改契约请改表，然后 npm run gen:bridge-dts。
type PbVirtualHostRef = { _pb_url: string }
type PbFileContent = Uint8Array | number[] | PbVirtualHostRef

interface ElectronAPI {
  openFile: () => Promise<string | null>
  readFile: (filePath: string) => Promise<Uint8Array | number[] | PbVirtualHostRef>
  // …
}

declare interface Window { electronAPI: ElectronAPI /* 加手写附加项 */ }
```

参数输出规则：params 为空则输出 `()`；否则 `(a: T, b?: U)`（条目里 `o: true` 表示可选）。

### 3.3 校验扩展 `scripts/check-bridge-contract.js`

在现有「存根成员名 vs d.ts 成员名」之上加三条：

1. **存根成员名集合 == 表中成员名集合**（表与运行时对齐）；
2. **存根每个成员的参数个数 == 表中 params 长度**（新增覆盖）；
3. **生成物新鲜度**：现场重新生成并与仓库里的 d.ts 逐字比对，不一致即失败（防漂移的关键一环）。

package.json 增加 `gen:bridge-dts` 脚本；CI 沿用现有契约校验步骤（其中已含第 3 条）。

## 4. 明确不做的事

- **不生成 JS 存根**。存根留在 BridgeServer.cpp 手写（它含参数映射与特殊逻辑，如 readFile 的虚拟主机分支）。生成它要改桥接核心，而对类型真实性零收益。改为**校验其名字与元数**。
- **不改动 C++ 方法注册**，避免触碰运行时。
- **不承诺消灭 any**，那是独立的手工标注任务（§6）。

## 5. 验收标准

必须全部通过，才可宣称完成：

1. **生成物与现状语义等价**：92 个成员逐一比对「成员名 → 签名」，与现行手写 d.ts 完全一致（差异只允许在注释与分组顺序）。
2. `npx tsc --noEmit` = **0 错误**（它会把渲染层全部调用点当作类型回归测试）。
3. `npm run build`、7 个单测、契约校验、版本校验全部通过。
4. **干净克隆全链路 CI** 10 步全绿（同前两轮标准）。
5. **变异测试**：
   - 表中删掉某成员 → 校验失败（存根有、表无）；
   - 表中把某成员参数改少一个 → 元数校验失败；
   - 手改生成物一个字符 → 新鲜度校验失败。

## 6. 可选加固（不在本方案内，供决策）

真正能防住「类型与 C++ 实际返回不符」这类 bug（本项目已发生两次：readFile 返回形状、searchBooks 返回形状）的，是把 38 个 any 换成从 C++ 实现读出来的真实形状，例如：

```ts
interface PbBook { id: number; title: string; file_path: string; format: string;
                  cover_path?: string; added_at: string; last_opened?: string }
getBooks: () => Promise<PbBook[]>
```

这是**手工标注**（每个形状都要读对应 C++ handler 的 json 拼装），约 38 处，可按使用频率分批做。它比「生成」更能减少运行时 bug，但工作量更大，且需要你验收类型是否贴合业务语义。

**建议**：先做 §3 的生成方案（结构性收益确定、风险可控、验收客观），把 §6 单列一项按需推进。
