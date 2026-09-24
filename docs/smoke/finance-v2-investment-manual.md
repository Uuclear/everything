// ============================================================================
// 真机冒烟手册：Task 8 阶段 — 投资账户 + 手动行情（stage5-finance-v2）
// ============================================================================
//
// 路径：docs/smoke/finance-v2-investment-manual.md
// 适用：Task 8 批次交付后真机 / 浏览器人工冒烟，验证
//   ① 投资账户 holdings 字段可被解析与渲染；
//   ② 手动行情包可经 SAF / HTTP 双向同步；
//   ③ 投资账户市值聚合（缺价降级、多币种折算、归档账户口径）正确；
//   ④ Dashboard 投资卡片 + 月报占位行渲染正常；
//   ⑤ 零知识红线：行情包密文以 records 通道为唯一真理源，明文只入本地 Room 与浏览器内存。
//
// 适用版本：Task 8 提交后（baseline = B8 commit `01d9aaa` 之后的下一个 commit）。
//
// 设备建议：
//   - Android 10+ 真机（含调试桥）；
//   - Web 端：Chrome 100+ / Firefox 95+，已解锁 Vault；
//
// 准备工作：
//   1. 安装 debug 包：cd android && ./gradlew.bat installDebug
//   2. 准备测试行情 JSON（见场景 SMOKE-V2-INV-S1）；
//   3. 准备一组投资账户 holdings（见场景 SMOKE-V2-INV-S2）。
//
// 与已有手册关系：
//   - 模板结构与字段命名与 `docs/smoke/finance-v2-ai-manual.md` 一致；
//   - Web 端功能对位 `finance-v2-ai-manual.md` 的 Web 部分（本手册也覆盖 Web 端）。
// ============================================================================

## 1. 范围与不变式

| 项 | 内容 |
| --- | --- |
| 受影响 | Android `QuotesImportScreen` / `FinanceDashboard` 投资卡片 / 月报行；Web `SettingsQuotesSyncView` / `FinanceDashboard.vue` 投资卡片 / 月报行 |
| 不受影响 | 既有账户 / 卡 / 流水 / 订阅 / 保单 / 借款 / 合同 / 预算链路；OCR / 语音联动 |
| 关键不变式 | 行情包**整包密封**进 records 通道（`type="quote"`）；本地 `finance_quote` 表按 symbol 拆行；原文不入日志、不入通知文案、不随任意状态持久化到 SQLite 以外 |
| 通过标准 | 6 场景全部通过；adb logcat 无明文报价数字；HTTP 同步失败静默降级（不阻断 Dashboard 渲染） |

---

## 2. 场景总览

| ID | 场景 | 关键断言 |
| --- | --- | --- |
| SMOKE-V2-INV-S1 | 行情包 SAF 导入（Android） | `importPackage` 解析通过；records 一条 `id="quote@${ts}"`；本地 `finance_quote` 按 symbol 拆行 |
| SMOKE-V2-INV-S2 | Web 端 stock 账户 holdings + 行情包合并渲染 | Dashboard 投资卡片显示总市值；top holdings 降序前 5；缺价数正确 |
| SMOKE-V2-INV-S3 | 缺价降级（quoteTable 缺某 symbol） | 该持仓计入 `missingPriceHoldingCount`，**不**计入 `totalValue`；不抛错 |
| SMOKE-V2-INV-S4 | 多币种折算（holding.currency ≠ target） | 缺汇率时该持仓计入 `missingPriceHoldingCount`；命中汇率时按 `RateTable.convert` 折算到 targetCurrency |
| SMOKE-V2-INV-S5 | 归档账户口径（spec 待对齐） | Android `accountCount` **含**归档（当前实现）；Web `accountCount` **仅**非归档（当前实现） |
| SMOKE-V2-INV-S6 | 零知识核查 | 抓包 / logcat / 设备 data/data 包下均无明文报价数字；records envelope 只见密文 |

---

## 3. 场景 SMOKE-V2-INV-S1：行情包 SAF 导入（Android）

**前置**：手机已安装 debug 包；Vault 已解锁；准备一份行情 JSON（保存到 Downloads 目录）：

```json
{
  "version": 1,
  "ts": 1737600000000,
  "base": "USD",
  "quotes": [
    { "symbol": "AAPL", "priceMinor": 17500, "currency": "USD", "ts": 1737600000000 },
    { "symbol": "TSLA", "priceMinor": 24000, "currency": "USD", "ts": 1737600000000 }
  ]
}
```

**步骤**：

1. 启动 App → 进入 VaultScreen → 财务 → Dashboard；
2. 点击 Dashboard 顶部「投资行情」按钮（testTag `finance_open_quotes_settings`）；
3. 进入「行情包设置」全屏页（`QuotesImportScreen`）；
4. 点击「从文件选 JSON」→ SAF 选到 Downloads 上的 `quotes.json`；
5. 同步页应展示：
   - 「当前行情包时间戳」= `2026-01-22 12:00`；
   - 「报价数」= `2`；
   - 「币种基准」= `USD`；
   - 行情明细预览列表：AAPL 175.00 USD / TSLA 240.00 USD；
6. 返回 Dashboard，进入「投资账户市值」卡片；
   - 若已有 stock 账户 → 等待 1 秒自动重算，行情命中数 = 持仓数；
   - 若无 stock 账户 → 显示「持仓为空」空态文案，不报错。

**断言关键**：

- `adb shell run-as com.everything.eve sqlite3 eve.db "select id from finance_quote;"`
  应返回两行：`AAPL@1737600000000` / `TSLA@1737600000000`；
- `adb shell run-as com.everything.eve sqlite3 eve.db "select id from records where module='finance' and type='quote';"`
  应返回一行 `quote@1737600000000`；
- 导入过程 ≤ 2 秒（含 SAF 解析 + 整包密封 + 本地拆行 upsertAll）。

---

## 4. 场景 SMOKE-V2-INV-S2：Web 端 stock 账户 holdings + 行情包合并渲染

**前置**：Web 端 Vault 已解锁；行情包已通过 `importQuoteTable(json)` store action 导入（与场景 S1 镜像 JSON 一致）。

**步骤**：

1. 进入 `/#/finance` → Dashboard 模式；
2. 滚动到页面底部，找到「投资账户市值」v2-section（含 3 张数字卡：总市值 / 持仓账户数 / 缺价数）；
3. 断言 3 张卡片渲染：
   - 总市值：因 `finance.ts` store 当前 `investmentAccounts` 返回空数组，**总市值为 0.00 元**（骨架先行，待 B9+ 编辑器扩 holdings 后填入真实数据）；
   - 持仓账户数 = 0；
   - 缺价数 = 0；
4. 若通过 chrome devtools 临时注入两条 `investmentAccounts` fixture（一 USD AAPL 100 股 + 一 CNY 600 现金），重渲染后断言总市值 = 美元 + 折算后人民币（按当前 `rateTable`）。

**断言关键**：

- Web `investmentMarketValue` 函数对 `investmentAccounts = []` 早退返回 `totalValue="0.00"` / `accountCount=0`，**不**抛错；
- top holdings 列表长度为 0；
- Dashboard 月报卡末尾出现「投资账户市值」占位行（显示「—」）。

---

## 5. 场景 SMOKE-V2-INV-S3：缺价降级

**前置**：进入 Android 设置页，导入一份**只有 AAPL 报价**的行情 JSON（不含持仓中另一持仓 symbol）。

**步骤**：

1. 在 Dashboard 顶部入口注入 fixture：一个 stock 账户 (`kind="stock"`)，含两笔持仓
   - AAPL 100 股，cost_basis_minor = 15000；
   - TSLA 50 股，cost_basis_minor = 20000；
2. 导入「仅 AAPL 报价」行情包；
3. Dashboard 投资卡片应展示：
   - 总市值 = `100 × 17500 / 100 = 175.00 USD`（TSLA 持仓**不计入**）；
   - 缺价数 = `1`（TSLA）；
4. 点击卡片展开，断言 top holdings 仅含 AAPL 一行，TSLA 不参与排名。

**断言关键**：

- `missingPriceHoldingCount` 由聚合函数正确累加；
- 缺价不抛错；
- top holdings 仅命中价不命中 ranked。

---

## 6. 场景 SMOKE-V2-INV-S4：多币种折算

**前置**：导入一份 `base=USD` 的汇率包（含 USD/CNY = 7.25）。

**步骤**：

1. 注入 fixture：一个 stock 账户，含两笔持仓
   - AAPL 100 股 USD；
   - 0700.HK 200 股 HKD（参考价 3500 HKD）；
2. Dashboard 投资卡片应展示（以 CNY 为 targetCurrency）：
   - AAPL 部分：`100 × 17500 / 100 × 7.25 = 126875.00 CNY`；
   - 0700.HK 部分（无 HKD/CNY 汇率）：`missingPriceHoldingCount++`，不计入 totalValue；
3. 断言：缺价数 = 1；总市值 = 126875.00 CNY。

**断言关键**：

- 多币种折算路径走 `RateTable.convertMinor`（Web）/ `RateTables.convert`（Android）；
- 汇率缺失时该持仓降级（计入缺价，不抛错）；
- 汇率命中时精度对齐 BigDecimal `HALF_UP` 整数分（与 B5 / B6 同舍入口径）。

---

## 7. 场景 SMOKE-V2-INV-S5：归档账户口径（spec 待对齐）

**前置**：在两端注入相同 fixture：一个 active stock + 一个 archived stock，各 1 笔持仓。

**步骤**：

1. Android 端 dashboard 应展示 `accountCount = 2`（**含**归档，实现细节）；
2. Web 端 dashboard 应展示 `accountCount = 1`（**仅**非归档，当前实现）；
3. 两端 `topHoldings` 应一致（均含 active 账户的持仓，**不**包含 archived）；
4. 两端 `totalValue` 应一致（archive 账户的市值**不计入**）。

**断言关键**：

- `accountCount` 双端行为分歧**已知**，UI 同时附「统计口径差异」小字说明；
- spec FR-V2-D.3 期望为「仅非归档」，两端**后续批次**统一为 spec 口径；
- 本批次不阻断，UI 仍可用。

---

## 8. 场景 SMOKE-V2-INV-S6：零知识核查

**前置**：开启 `adb logcat`（过滤 `com.everything.eve:* *:S`）；准备 tcpdump 或 charles 抓包。

**步骤**：

1. 跑完场景 S1~S5，全程保持 logcat + 抓包开启；
2. 抓包断言：
   - 整包同步上行链路只见密文（`/records/batch` body 内为 `XChaCha20-Poly1305`
     ciphertext，AAD 含 `eve:v1:record:quote@...:finance:...` 形式）；
   - 明文报价数字（AAPL 175.00 等）**不出现**于任何 HTTP body / header / URL
     query；
3. logcat 断言：
   - 无 `QuoteTables` / `QuoteTableRepository` / `investmentMarketValue` 等
     关键字日志；
   - 无具体报价数字（仅含字段名 `symbol` / `ts` 等元信息）；
4. 设备本地核查：
   - `adb shell run-as com.everything.eve ls databases/`：`eve.db` 大小增长
     只来自业务表（新增 `finance_quote`），无临时识别文本；
   - `adb shell run-as com.everything.eve ls files/`：无 `.json` 残留
     （SAF 选完即释放）；
5. Web devtools 断言：
   - localStorage 仅存 `eve:finance:v1` 单一 key（quote 包作为 envelope 密文
     入 IndexedDB / localStorage 中 envelope 表），无明文报价数字；
   - 应用内存 dump（heap snapshot）仅在 Pinia store `quoteTable` 字段出现
     行情包对象，**不落** sessionStorage / cookies / IndexedDB 明文 store。

**断言**：以上 5 步全部通过 = 零知识红线合规。

---

## 9. 失败上报模板

```
冒烟失败报告：
- 场景：SMOKE-V2-INV-S{1~6}
- 设备 / 浏览器：{型号} Android {版本} / Chrome {版本}
- commit：{git rev-parse HEAD}
- 复现步骤：{1/2/3/...}
- 期望：{...}
- 实际：{...}
- logcat / devtools network：{关键片段 / 文件链接}
- 截图：{附件}
```

---

## 10. 与既有手册交叉引用

- B5 汇率包：`docs/finance.md` §7.7 与 `RateTableRepositoryTest` 测试模板
  （本手册镜像其冒烟结构）；
- B6 预算：`docs/finance.md` §7.7.4 零知识文案纪律（投资卡片渲染紧跟
  该纪律，**不**渲染具体报价数字到通知 / Toast / 控件文案）；
- B7 Web 通知：`docs/finance.md` §6.9（投资行情**不接入**通知通道，本
  手册不覆盖 push 路径）；
- B8 AI 联动：`docs/smoke/finance-v2-ai-manual.md`（OCR / 语音**不处理**
  投资持仓录入）；

