// ============================================================================
// 真机冒烟手册：B8 阶段 — AI 联动记账（OCR + 语音）（stage5-finance-v2 / Task 9）
// ============================================================================
//
// 路径：docs/smoke/finance-v2-ai-manual.md
// 适用：B8 批次交付后真机 / 模拟器人工冒烟，验证
//   ① 端侧 OCR / 语音识别全流程可用；
//   ② 三要素 / 分类预填正确写入编辑器；
//   ③ 权限拒绝路径不阻断手工记账；
//   ④ 零知识红线：识别原文不入库、不打日志、不随任何状态持久化。
//
// 适用版本：commit `01d9aaa` 及以上。
//
// 设备建议：Android 10+ 真机；推荐含相机；模拟器若无相机可走语音路径。
//
// 准备工作：
//   1. 安装 debug 包：cd android && ./gradlew.bat installDebug
//   2. 准备测试小票（便利店发票 / 餐厅小票），保证印刷清晰含
//      「合计 / 应付 / TOTAL」金额行 + 商家名 + 日期（任一格式）；
//   3. 准备一组语音话术模板（见场景 SMOKE-V2-AI-S2）。
//
// 与已有手册关系：与 FU-7 同模板（结构 + 字段命名一致）。
// ============================================================================

## 1. 范围与不变式

| 项 | 内容 |
| --- | --- |
| 受影响 | Android 端 FinanceEditor（OCR / 语音入口 + 预填） |
| 不受影响 | Web 端（明确不做 OCR / 语音）；Android 端既有手工记账、附件、预算等链路 |
| 关键不变式 | OCR / 语音原文不入库；仅 amountMinor / occurredAtMs / merchant / category 四个限定字段可写入编辑器 buffer |
| 通过标准 | 6 场景全部通过；adb logcat 无明文识别文本与 OCR / 语音相关日志 |

---

## 2. 场景总览

| ID | 场景 | 关键断言 |
| --- | --- | --- |
| SMOKE-V2-AI-S1 | OCR 授权 + 拍照识别小票 → 预填 | 金额、日期、商家三项正确写入 buffer；commit 后 Room tx 记录三字段一致 |
| SMOKE-V2-AI-S2 | 语音「餐饮三十五元」→ 预填分类与金额 | 金额 3500 分；category="餐饮"；occurredAtMs = now |
| SMOKE-V2-AI-S3 | OCR 权限拒绝路径 | 显示权限说明 + 关闭按钮；点关闭返回编辑器；仍可手工记账 |
| SMOKE-V2-AI-S4 | 语音权限拒绝路径 | 显示权限说明 + 关闭按钮；不影响手工记账 |
| SMOKE-V2-AI-S5 | 识别失败（小票涂抹 / 语音含噪声） | OCR → 「无结果」+ 重试；语音 → 错误码 7 / 「未听清，请重试」 |
| SMOKE-V2-AI-S6 | 零知识核查 | 抓包 / logcat / 设备 data/data 包下均无识别原文；编辑器 buffer 仅含四项限定字段 |

---

## 3. 场景 SMOKE-V2-AI-S1：OCR 授权 + 拍照识别小票 → 预填

**前置**：手机已安装 debug 包；准备一张中文便利店小票（含「合计 ¥35.00」「2026-09-24」「晨星便利店」字样）。

**步骤**：

1. 启动 App → 进入 VaultScreen → 财务 → 新建记账（TX）；
2. 在 TX 编辑器底部点击「扫描小票」按钮（testTag `tx_editor_ocr_entry`）；
3. 系统弹相机权限框 → 同意；
4. Sheet 弹出相机预览，对准小票，点击「拍照识别」（testTag `ocr_scanner_capture`）；
5. 等待约 1~2 秒，识别完成后应展示三要素预览：
   - 「金额：35 元」
   - 「日期：2026-09-24」
   - 「商家：晨星便利店」
6. 点击「使用该结果」（testTag `ocr_scanner_use_hint`），Sheet 关闭；
7. 回到编辑器，断言：
   - amount 输入框 = `35`
   - 日期字段 = `2026-09-24`
   - note 字段 = `晨星便利店`
   - category 字段保持空（OCR 不分类）
8. 点保存 → 重新进入 tx 详情，断言三字段仍存在（已写入 Room + 加密通道）。

**断言关键**：

- 三要素预览渲染时间不超过 5 秒（含相机启动 + 一帧识别）；
- 编辑器 buffer 仅四项限定字段被写入，note 仅在原 note 为空时填入 merchant。

---

## 4. 场景 SMOKE-V2-AI-S2：语音「餐饮三十五元」→ 预填分类与金额

**前置**：系统设置已开启设备自带语音识别服务（Google / 厂商引擎）；首次打开语音 Sheet 会自动申请 RECORD_AUDIO。

**步骤**：

1. 进入 TX 编辑器 → 点击「语音记账」按钮（testTag `tx_editor_speech_entry`）；
2. 系统弹麦克风权限框 → 同意；
3. Sheet 显示「开始说话」（testTag `speech_recorder_toggle`）按钮，点击；
4. 朗读「餐饮，三十五元」（间隔 1 秒），再次点击停止；
5. Sheet 应展示两要素预览：
   - 「金额：35 元」
   - 「分类：餐饮」
6. 点击「使用该结果」→ Sheet 关闭；
7. 回到编辑器，断言：
   - amount 输入框 = `35`
   - category 下拉框选中「餐饮」
   - occurredAt 字段 = now（语音默认当前时间）
   - note 字段不变（语音不写商家）

**断言关键**：

- 识别结果不到 5 秒；
- 聆听过程中预览区不渲染任何「部分识别文本」（零知识红线）；
- 编辑器 note 字段保持原值。

---

## 5. 场景 SMOKE-V2-AI-S3：OCR 权限拒绝路径

**前置**：adb shell pm revoke com.everything.eve android.permission.CAMERA（如已授权）。

**步骤**：

1. 卸载并重新安装 debug 包（保证权限态干净）；
2. 进入 TX 编辑器 → 点击「扫描小票」按钮；
3. 系统弹相机权限框 → 拒绝；
4. Sheet 应展示：
   - testTag `ocr_scanner_permission_blocked` 提示文本（`finance_ai_camera_permission_text`）
   - 「关闭」按钮
6. 点击「关闭」→ Sheet 关闭，回到编辑器；
7. 断言：
   - 编辑器所有手工字段仍可正常输入；
   - 重新点「扫描小票」仍可弹出权限框（可二次申请）。

**断言关键**：拒绝授权不阻断任何手工记账流程。

---

## 6. 场景 SMOKE-V2-AI-S4：语音权限拒绝路径

**前置**：adb shell pm revoke com.everything.eve android.permission.RECORD_AUDIO。

**步骤**：

1. 重新安装 debug 包；
2. 进入 TX 编辑器 → 点击「语音记账」按钮；
3. 系统弹麦克风权限框 → 拒绝；
4. Sheet 应展示 `speech_recorder_permission_blocked` 提示 + 「关闭」按钮；
5. 点击「关闭」→ Sheet 关闭；
6. 断言：编辑器手工记账不受任何影响。

---

## 7. 场景 SMOKE-V2-AI-S5：识别失败路径

### 7.1 OCR 识别失败

**步骤**：

1. 进入 OCR Sheet，对准严重涂抹 / 光线不足的小票；
2. 点「拍照识别」；
3. Sheet 应展示 testTag `ocr_scanner_no_result` 提示（`finance_ai_ocr_no_result`）；
4. 点击「重试」按钮 → 重新对准清晰小票 → 再次拍照；
5. 二次识别成功后展示三要素预览。

**断言**：重试后可恢复正常识别流程；不阻断编辑器。

### 7.2 语音识别失败

**步骤**：

1. 进入语音 Sheet，点击「开始说话」；
2. 朗读纯噪声（如敲击麦克风），停顿 3 秒后再次点击停止；
3. Sheet 应展示错误提示（`finance_ai_speech_error_format` 含错误码 7「未听清」），并提供「重试」按钮；
4. 点击重试后正常朗读「餐饮，二十元」→ 识别成功。

**断言**：错误码 9（INSUFFICIENT_PERMISSIONS）应改走权限引导文案，而非通用错误格式。

---

## 8. 场景 SMOKE-V2-AI-S6：零知识核查

**前置**：开启 `adb logcat`（过滤 `com.everything.eve:* *:S`）；准备 tcpdump 或 charles 抓包。

**步骤**：

1. 跑完场景 S1（小票 OCR）、S2（语音），全程保持 logcat + 抓包开启；
2. 抓包断言：
   - 整个 OCR / 语音会话期间无任何外部域名连接（ML Kit 与 SpeechRecognizer 均为 on-device）；
   - 应用不上传任何图像、音频或识别文本到后端；
3. logcat 断言：
   - 无 `OcrScannerEngine`、`SpeechRecorderEngine`、`OcrParser`、`SpeechParser` 等关键字日志；
   - 无识别原文（中文字符串）与小票金额；
4. 设备本地核查：
   - `adb shell run-as com.everything.eve ls databases/`：`eve.db`（Room 数据库）大小增长只来自业务表，无附件或临时识别文本；
   - `adb shell run-as com.everything.eve ls files/`：无 `.png` / `.jpg` / `.wav` / `.txt` 等残留（拍照 / 录音后即释放）。
5. 编辑器 buffer dump（开发模式 / 自建 debug 入口）：
   - `Buffer` 仅含 `amountMinor / occurredAtMs / merchant / category / note` 等业务字段；
   - `note` 字段在 OCR 路径下仅在原值为空时填入 merchant；语音路径下不写入。

**断言**：以上 5 步全部通过 = 零知识红线合规。

---

## 9. 失败上报模板

```
冒烟失败报告：
- 场景：SMOKE-V2-AI-S{1~6}
- 设备：{型号} Android {版本}
- commit：{git rev-parse HEAD}
- 复现步骤：{1/2/3/...}
- 期望：{...}
- 实际：{...}
- logcat：{关键片段 / 文件链接}
- 截图：{附件}
```

---

## 10. 与既有手册交叉引用

- FU-7 同款模板：`docs/smoke/fu7-manual.md`（结构、字段命名、断言粒度参照）；
- 端到端冒烟：`docs/smoke/stage5-finance-e2e.md`（覆盖 B1~B8 主链路，可与本手册合并冒烟）；
- 历史归档：`docs/smoke/stage4b-e2e.md`。