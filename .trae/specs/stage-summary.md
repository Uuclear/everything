# 阶段 0-4b 实施总结 — 2026-09-16

> 本文档记录 subagent-driven 流水线跑完阶段 0 → 阶段 1 → 阶段 2 → 阶段 3 →
> 阶段 4a → 阶段 4b 后的开发总结，供后续维护/接手时快速对齐。
> 与 [everything_plan.md](../documents/everything_plan.md) L122-L134 互为补充：
> plan 文档是路线，本文档是过程中产生的"经验、踩坑、隐患、新想法"沉淀。

---

## 一、开发进度

按 plan L122-L134 已完成 6 个阶段：

| 阶段 | 内容 | 完成日 | 状态 |
|---|---|---|---|
| 0 | 工程骨架（monorepo / Go / Vue3 / Android / CI / Docker） | 2026-09-15 | ✅ |
| 1 | 核心底座（注册 / Argon2id / MK 信封 / 设备审批 / TOTP / 恢复密钥 / 增量同步 / SSE） | 2026-09-15 | ✅ |
| 2 | 密码库 + 证件（Web 完整 UI；Android 同步兼容） | 2026-09-15 | ✅ |
| 3 | 安卓采集器（通讯录/短信/通话记录增量同步） | 2026-09-16 | ✅ |
| 4a | 位置轨迹（前台服务 + Room 缓冲 + 密文块 + 地图回放） | 2026-09-16 | ✅ |
| 4b | 日程/日历（双端 CRUD + RRULE B 档 + 精确闹钟 + 同步） | 2026-09-16 | ✅ |
| 4c-8 | 待启动 | — | ⏳ |

**完成率（按阶段节点 9 个）**：6/9 ≈ **67%**。
**剩余范围**：AI Agent（阶段 6）、扩展模块（阶段 7：健康/家庭/记账/物品/旅行/目标等）、
分发与运维（阶段 8）。

---

## 二、本次推送

3 个 commit 同步至 origin/main（Uuclear/everything）：

```
f207a6a  Initial commit: Everything — 自托管人生操作系统
bbff7ee  chore(gitignore): 排除 chat/ 临时目录与 *.log 构建日志
59a2e6e  feat: 阶段 1 收尾 + 阶段 2 密码库 + 阶段 3 采集器 + 阶段 4a 轨迹 + 阶段 4b 日历
```

变更统计：155 新增文件 + 48 修改文件，+5210/-756 行。`chat/` 与 `*.log` 已
`.gitignore` 屏蔽；规格源（`.trae/specs/*/spec.md` + `tasks.md`）已纳入版本控制。

---

## 三、踩过的坑（实测教训）

| # | 类别 | 现象 | 修复 |
|---|---|---|---|
| 1 | JUnit 签名 | `assertTrue(condition, message)` 参数顺序错位 5 处 | 改 `assertTrue(message, condition)` |
| 2 | Kotlin 反射 | `kotlin.reflect.ReadOnlyProperty` Unresolved | 改为 `kotlin.properties.ReadOnlyProperty`（包路径错），且 `override operator fun` 必须带 `operator` |
| 3 | Compose 测试 | suspend 函数 `eventsRepo.getById` 在非协程上下文调用 | `runBlocking { ... }` 包裹 |
| 4 | 类型不匹配 | `RemoteRecord.version: Int` vs `RecordEntity.version: Long` | `.toInt()` → `.toLong()` |
| 5 | Web 路由挂载 | T8 子代理实现了 `CalendarView.vue` / `EventEditorDialog.vue` 但忘了注册 `/vault/calendar` 路由 + AppShell 菜单项，**组件可达但用户访问不到** | T12 门禁后主会话补修（路由 + 侧栏菜单） |
| 6 | TS 严格门禁 | vitest 12 files/135 tests 全绿，但 `vue-tsc` 报 13 处 TS 错误 | 主会话一次性收敛：`types.ts` RRuleEnd 漏 `kind:'date'` 分支；`reminders: ReminderItem[]` → `number[]`；`presetStartTs: number` → `number \| null`；EditorDialog props 改 `import type { Frequency, Weekday }`；CalendarView/WeekView 多处 unused 删除 |
| 7 | PowerShell | 不支持 `cmd1 && cmd2` | 改 `cmd1 ; cmd2` |
| 8 | Git reset | `git reset HEAD -- path1 path2` 会把整个 stage 都清空（非按路径 reset） | 改 `git reset HEAD path1 path2`（单 `--`）或 `git rm --cached path` |
| 9 | 临时文件 | `chat/` 与 `*.log` 未在初始 `.gitignore`，会被 `git add -A` 全部纳入 | commit 1 加入 `.gitignore` 屏蔽规则 |

---

## 四、技术难点（设计层面取舍）

1. **跨端"展开算法"字节级一致**：`Recurrence.kt`（Kotlin）vs `expand.ts`（TypeScript）
   独立实现同一套 RRULE B 档子集 + exdate 跳过 + count/date 终止；共享 fixture
   `cases.json`（SHA-256 完全一致）+ 双方各跑一遍保证不变式。
2. **AAD 三端对齐**：`eve:v1:record:{id}:{module}:{BE(uint64 version)}` 字节级一致；
   任一端写错字节序/大小端 → "加密成功但解密失败"。
3. **链式 AlarmManager 调度**：每个事件触发一次"下一闹钟"，不在远期物化实例；
   三保险：ReminderReceiver 触发后 `rebuildChain` / BootReceiver 开机重建 / 
   CollectorWorker 同步后重建。
4. **MK 不可用静默停采/停同步**：所有"密文上行"路径都先 `check authManager.masterKey`，
   无 MK 静默放弃（不抛错、不重试）——避免污染统计 + 不让明文跌入错误流。
5. **Compose UI 测试**：CalendarScreenTest 需要 Robolectric + ComposeTestRule +
   EventReminderLogDao mock（构造反射）+ EventsRepository 反射构造绕过 final 构造。

---

## 五、需要修正 / 关注的隐患

| 编号 | 严重度 | 描述 | 触发条件 |
|---|---|---|---|
| **H-1** | 中 | **FU-7 真机冒烟**至今未跑（4a spec 5 项 + 4b 沿用）；AndroidTest 4 套件只在 JVM/单元层验证 | 阶段 5/6 启动前必须跑 1-2 台国产 ROM 真机冒烟（小米/华为/OPPO 各一台） | 2026-09-16 出 [fu7-manual.md](../smoke/fu7-manual.md)；emulator 可跑 5 项中 4 项（除 FU-7.2 保活），国产 ROM 真机冒烟仍待手动完成 |
| **H-2** | 低 | **spec.md AC-16 缺失**：plan 文档写 16 条 AC，实际只列 15 条 | spec 维护者确认是否补 AC-16 |
| **H-3** | 低 | **RRULE B 档子集**：当前只 DAILY/WEEKLY/MONTHLY/YEARLY，HOURLY / BYDAY 复杂规则 / RDATE / EXRULE 缺失 | 阶段 7 扩展 |
| **H-4** | 低 | **Web 浏览器通知**：依赖 Notification API + Service Worker，本期未实现 | 阶段 7 Web 端补 |
| **H-5** | 低 | **服务端 events/records handler 引用了 4a/4b 才出现的字段**，但 spec 明示"服务端零改动" | 阶段 5/6 触发时补 CI schema 兼容性测试 |
| **H-6** | 低 | **3 条 Review Nit**：跨端 `isEmptyRRule` 实现差异 / `RecurrenceTest` 聚合显示 / `ReminderScheduler` 每次 `new RealAlarmScheduler` | 不阻塞 4b；4c 顺手处理 |
| **H-7** | 中 | **Go 工具链本地不可用**，CI 跑 Go 测试覆盖率为 0 | 阶段 5 前必须把 Go 测试拉进 CI（GitHub Actions） | 2026-09-16 重写 `.github/workflows/ci.yml`：拆 server-test / server-cross 双 job；web job 增 vitest + vue-tsc；android job 增 testDebugUnitTest；server-test 增 -race + coverage |

---

## 六、新想法 / 增量设计

1. **阶段 5 财务模块前置研究**：账户/资产 = place 类型条目；交易 = event 类型
   扩展（reuse 4b RRULE 做"信用卡还款日提醒"）。**4b 已打通的"加密事件 +
   本地闹钟 + 跨设备同步"基础设施可被阶段 5 零增量复用**。

2. **AI Agent 阶段 6 提前**：4b spec §未做登记"编辑器与服务端 AI Agent 联动"，
   但 Agent 抽象层（Provider + 工具注册表 + 解锁会话）尚未动工。**建议阶段 6
   优先级**：先接 Ollama 本地 → 再做"日历/密码库/轨迹"3 个工具调用 → 绕过
   "财务/物品"模块先开放自然语言入口。

3. **Web 端路由架构升级**：4b 修 B-1 时发现 `AppShell.vue` 菜单是硬编码数组；
   可改为插件化（模块自注册菜单项 + 路由）。阶段 7 扩展模块多时 AppShell
   会变长，**插件化是必要的**。

4. **Android UI 组件复用**：4b 的 EventEditorScreen + RRuleBuilder + MonthGrid
   + WeekGrid + EventBlock 已实现一套基础组件；阶段 5"记账编辑器"、阶段 7
   "健康记录"都可复用。**建议提取到 `ui/components/` 共享包**。

5. **加密附件前置**：阶段 3 spec §未做登记"附件上传不在本期（顺延）"；
   阶段 5/6 强烈依赖附件（扫描件、照片轨迹、健康报告 PDF）。
   **阶段 5 启动前必须先把附件块存储 + 上传/下载协议落地**。

6. **Web 端 Service Worker 离线优先**：4a/4b 都是"在线为主"，但密码库
   离线访问是刚需（出差/无网）。**建议阶段 5 把 IndexedDB 优先 +
   后台同步做成基础设施**。

7. **测试矩阵自动化**：当前各阶段"门禁"靠手动跑 `npx` / `gradlew` / `go test`
   （Go 还没跑通）。**阶段 8 启动时建议引入统一的 `scripts/gate.sh`**：
   自动跑三端 + AC 映射 + 零知识 grep。

---

## 七、子代理驱动流水线经验

- **每个 Task 派全新上下文 `general_purpose_task` 子代理**；主会话负责批间
  Grep 抽查 + 复跑门禁 + 回填 TR-x。
- **门禁报告 + Review 报告独立文件**（`gating-report.md` / `review.md`），
  让评审者可以重新跑一遍而不被实现证据干扰。
- **TR-x 行格式**：`[x] **TR-N.M [rule]** 任务描述 — Pass Condition +
  Status + Completion Evidence`；rubric 阈值 ≥ 4（按设计）。
- **主会话直接修补优先**：当错误类型已明 + 行号已知（如本会话 CalendarScreenTest
  14 处错误），主会话 SearchReplace 比再派子代理更快更可控。

---

## 八、下一步建议（顺序）

1. ✅ **本轮**：H-1 真机冒烟 + H-7 Go CI —— 立即处理
2. 阶段 5 启动前：附件块存储（idea #5）
3. 阶段 5 启动：财务模块（idea #1）
4. 阶段 6 启动：AI Agent 抽象层（idea #2）
5. 阶段 7 启动：Web 路由插件化（idea #3）+ Android 组件复用（idea #4）
6. 阶段 8 启动：分发与运维 + gate.sh 自动化（idea #7）