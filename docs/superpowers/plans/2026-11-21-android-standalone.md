# 抖音续火工（Flame Contractor）独立 Android 版实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execution strategy: **M1 (Task 1) runs serially on the main track** against the test tablet to clear the windowless-Service WebView gate; **M2/M3 (Tasks 2–9) run via a lean 4-member `/swarm`** (`core-domain`, `webview-exec`, `compose-ui`, `verifier`) on non-overlapping module directories; **M4 (Task 10) merges cleanly and completes CI/Release & device acceptance**.

**Goal:** 构建完全脱离 PC 与外部服务端独立运行的开源 Android 原生应用（`.apk`），基于系统 Chromium WebView 离屏执行器实现安全可验证的抖音每日续火闭环。

**Architecture:** 在仓库 `android/` 搭建独立 Gradle 多包模块化工程：Room SQLite 持久化状态机与每日幂等任务；`WebViewCompat.addDocumentStartJavaScript` 注入桌面信号与网络旁路钩子 + 修复闭包的 IndexedDB 读取完成唯一身份/群聊 protobuf 校验；`AlarmManager` 主唤醒 + `30 分钟` 纯 SQLite `WorkManager` 兜底调度 `ExecutionForegroundService`（虚拟 `1440×900` 离屏布局 + `js_execCommand` 无焦点输入 + 10 分钟 `WakeLock` 上限）完成发送并触发本机/Bark 通知；上层为手机/平板自适应 Jetpack Compose Material 3 原生 UI。

**Tech Stack:** Kotlin 2.0.21, Gradle 8.11.1, AGP 8.7.3, JDK 17, Android SDK 35 (`minSdk = 26`, `targetSdk = 35`), Jetpack Compose + Material 3, Room (KSP), `androidx.webkit:webkit:1.12.1`, WorkManager, OkHttp, Android Keystore AES-GCM, JUnit4 / Kotlin Coroutines Test / MockWebServer.

**Spec:** `docs/superpowers/specs/2026-11-21-android-standalone-design.md`

## Global Constraints

- 目录隔离：所有 Android 代码仅创建于 `android/`，工作流仅新增 `.github/workflows/ci-android.yml` 与 `.github/workflows/release-android.yml`；严禁改动 `backend/`、`frontend/`、`desktop/` 运行时逻辑与 `release-desktop.yml`。
- 标识与工具链：正式版 `applicationId = "io.github.oinzc.flamecontractor"`（debug 后缀 `".debug"`），`namespace = "io.github.oinzc.flamecontractor"`，`minSdk = 26`，`targetSdk = 35`，`compileSdk = 35`，JDK `17`，`android:allowBackup="false"`。
- 定位器契约：`backend/app/execution/selectors_bundle.json` 为唯一基线源；`android/app/src/main/assets/selectors_bundle.json` 必须与之字节一致；本地覆写存于 `filesDir/selectors_override.json`，锁定 `CHAT_URL = "https://www.douyin.com/chat"` 不可覆写，单串 `<= 512`、数组 `1..20`、禁 `\x00`/`\r`/`\n`。
- 绝不重发与安全红线：当日任一槽位为 `UNKNOWN_REQUIRES_REVIEW` / `SENDING` / `CONFIRMING` 时硬禁止自动、首页批量及卡片单发测试；崩溃恢复遇 `SENDING` / `CONFIRMING` 必转 `UNKNOWN_REQUIRES_REVIEW`；`official spark_days` 绝不覆写本地 `streak_days`；同名冲突（`ambiguous`）禁止绑定或发送。
- 首版边界与零隐私：首版单账号（`account_id = "default"`，全表保留 `account_id` 列）；首版仅手动扫描（无 `auto_scan` / 火花恶化推送）；禁止跨午夜窗口（`00:00 <= send_window_start < send_window_end <= 23:55`）；定时执行全程禁止弹窗抢焦点；Logcat、诊断导出与错误文本严禁出现昵称、`sec_uid`、`uid`、会话 ID、聊天正文、Cookie 或完整 Bark key。
- 仓库提交规范（Windows PowerShell 禁止 `&&`，设置 `JAVA_HOME`/`ANDROID_HOME`/`GRADLE_USER_HOME`）：Git 作者固定 `oinzc <147690512+oinzc@users.noreply.github.com>`，简体中文 Conventional Commits，禁止 AI 署名。

## Review Focus

- **同日跨槽位与改模板幂等边界**：某目标当日 `daily` 已为 `UNKNOWN_REQUIRES_REVIEW` / `SUCCEEDED` / `CANCELLED` 后修改绑定模板、重登或触发单发测试（`manual:<epochMs>`），必须严格遵守硬禁止与不重复自动建单（由 Task 4 `SendGuardTest` 钉死）。
- **NFKC 同名消歧、protobuf 校验与换号 `self_uid` 隔离**：`ambiguous` 同名多人/多群聊、伪造 `field 1 != field 2` protobuf、以及单账号重登检测到新 `self_uid` 时必须拒绝绑定或暂停发单（由 Task 2 与 Task 6 钉死）。
- **过窗截断、60 分钟固定宽限与重试上限防护**：过 `send_window_end` 或 `send_time_hhmm + 60 分钟` 后即使 `WorkManager` 唤醒也必须跳过当日；`FAILED_RETRYABLE` 严控 `RETRY_MAX_COUNT = 2`（5/15 分钟退避）且跨午夜废除昨日待发单（由 Task 5 `DailySchedulePlannerTest` 与 Task 4 钉死）。
- **多 Store IndexedDB 闭包隔离与 Slate 零宽字符残余判定**：多 store 异步读取必须使用 `event.target.result` 独立作用域（修复 Spike 中的 `InvalidStateError`），编辑器写入前后均须剥离 `[\s\u200B\u200C\u200D\uFEFF]` 验证（由 Task 1 与 Task 6 钉死）。
- **Bark 多行 `curl` 密钥解析/脱敏与定位器控制字符注入防护**：覆盖 JSON/表单/GET `curl`、占位符 `<device_key>` 拒绝、日志与 UI `maskBarkUrl` 仅露末 4 位，以及 `CHAT_URL` 篡改与 `\r\n` 注入拦截（由 Task 3 钉死）。

---

## 文件与里程碑目录划分（零文件冲突边界）

```text
android/
├── gradle.properties, settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle/wrapper/*
└── app/src/
    ├── main/AndroidManifest.xml & assets/selectors_bundle.json
    ├── main/java/io/github/oinzc/flamecontractor/
    │   ├── app/            # [Task 7] FlameApp, AppContainer, strings/theme
    │   ├── core/
    │   │   ├── identity/   # [Task 2] DouyinProtobufParser, IdentityDisambiguator
    │   │   ├── selectors/  # [Task 3] SelectorBundleStore, SelectorBundleValidator
    │   │   └── notify/     # [Task 3] BarkUrlNormalizer, EncryptedSecretStore, BarkNotifier, LocalNotifier
    │   ├── data/db/        # [Task 4] Entities, DAOs, Converters, FlameDatabase
    │   ├── domain/         # [Task 4] TemplatePoolPicker, SendGuard, AccountHealthReducer
    │   ├── scheduler/      # [Task 5] DailySchedulePlanner, ScheduleCoordinator, Receivers, SafetyNetWorker
    │   ├── execution/      # [Task 1: webview/ & OffscreenProbeService; Task 6: DouyinJsScripts, DouyinWebExecutor, ExecutionForegroundService, LoginWebViewActivity, AccountSwitchGuard]
    │   └── ui/
    │       ├── shell/      # [Task 7] NavigationShell, AdaptiveLayout
    │       ├── today/      # [Task 7] TodayScreen, TodayViewModel, RecoveryDialogs
    │       ├── targets/    # [Task 8] TargetsScreen, ScanReconcileSheet, TargetsViewModel
    │       ├── templates/  # [Task 8] TemplatesScreen, PoolEditorDialog, TemplatesViewModel
    │       └── settings/   # [Task 9] SettingsScreen, RomSurvivalChecker, DiagnosticsViewModel
    └── test/java/io/github/oinzc/flamecontractor/... # 与各包一对一对应 JVM 单测
```

---

### Task 1: Android 工程骨架、基线契约同步与 M1 纯 Service 离屏 WebView 真机门禁

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle/wrapper/gradle-wrapper.jar`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/assets/selectors_bundle.json`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/webview/DesktopWebViewFactory.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/OffscreenProbeService.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/execution/webview/DesktopUserAgentTest.kt`

**Interfaces:**
- Consumes: `backend/app/execution/selectors_bundle.json`
- Produces: `DesktopWebViewFactory.buildDesktopUserAgent(defaultUa: String): String`, `DesktopWebViewFactory.createOffscreenWebView(context: Context): WebView`, Gradle task `verifySelectorBundleSync`

- [ ] **Step 1: 编写失败单测验证桌面 UA 版本提取与兜底行为**
  在 `DesktopUserAgentTest.kt` 编写测试：`extractsChromiumVersionIntoWindowsDesktopUa()`（传入 Android WebView UA 提取 `Chrome/157.0.8103.50` 拼为 Windows 桌面 UA）、`fallsBackToSafeVersionWhenTokenMissing()`（缺失 `Chrome/` 时回退 `135.0.0.0`）。

- [ ] **Step 2: 创建 Gradle 工程骨架、Wrapper、`selectors_bundle.json` 同步任务与清单文件**
  复用已验证的 JDK 17 + Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.0.21 + SDK 35 建好 `android/`；在 `app/build.gradle.kts` 注册 `verifySelectorBundleSync` 任务严格字节比对 `../backend/app/execution/selectors_bundle.json` 与 `src/main/assets/selectors_bundle.json`（不匹配则失败）；在 `AndroidManifest.xml` 声明 `android:allowBackup="false"`、`INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`SCHEDULE_EXACT_ALARM`。先跑单测确认失败（`DesktopWebViewFactory` 尚不存在）。
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DesktopUserAgentTest"`
  Expected: FAIL（`Unresolved reference: DesktopWebViewFactory`）。

- [ ] **Step 3: 实现 `DesktopWebViewFactory` 与 M1 `OffscreenProbeService`**
  `DesktopWebViewFactory` 实现 `buildDesktopUserAgent(defaultUa)`、`createOffscreenWebView(context)`（虚拟 `measure/layout(1440, 900)`、第三方 Cookie、`DOCUMENT_START_SCRIPT` 桌面 navigator 注入与非 http/https 协议拦截）；`OffscreenProbeService` 为 `dataSync` 前台服务，支持通过只读调试广播在**无 Activity 窗口**状态下加载 `https://www.douyin.com/chat`，输出仅含布尔/计数（零 PII）的 `OFFSCREEN_PROBE` 日志（确认 M1 离屏 `loggedIn` / 会话数 / Slate 非发送 `js_execCommand` 是否在纯 Service 成立）。

- [ ] **Step 4: 跑通单测、契约校验与真机测试设备纯 Service 离屏门禁**
  Run: `.\gradlew.bat verifySelectorBundleSync :app:testDebugUnitTest :app:assembleDebug`
  Expected: PASS 且生成 `app-debug.apk`。接着在测试平板安装并用 `am start-foreground-service`（不启动任何 Activity，若未登录可先通过临时测试 Activity 扫码建立 Cookie 后再强杀启动纯 Service 验证）验证 `OFFSCREEN_PROBE` 在零窗口下完成 `loggedIn=true`、列表计数与 `js_execCommand` 非发送写入确认。若遇 ROM 阻止无窗口 WebView 绘制，立即按规范 §3.3 停下记录硬件现象与重规划。

- [ ] **Step 5: 提交 M1 骨架与门禁基线**
  Commit: `feat(android): 搭建独立工程骨架并验证纯 Service 离屏 WebView 门禁`

---

### Task 2: 群聊 Protobuf 解析器与严格身份消歧引擎（M2 并行模块 A）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/identity/DouyinProtobufParser.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/identity/IdentityDisambiguator.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/core/identity/DouyinProtobufParserTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/core/identity/IdentityDisambiguatorTest.kt`

**Interfaces:**
- Consumes: 独立无外部依赖（字节数组与内存身份记录）
- Produces:
  - `data class ConversationRecord(val platformConversationId: String, val title: String, val conversationType: String = "group")`
  - `data class IdentityRecord(val platformUserId: String, val platformHandle: String?, val platformHandleSource: String?, val nickname: String?, val remarkName: String?)`
  - `enum class MatchStatus { BOUND, AMBIGUOUS, NOT_OBSERVED }`
  - `DouyinProtobufParser.parseGroupConversations(data: ByteArray): List<ConversationRecord>`
  - `IdentityDisambiguator.stripUnreadSuffix(rawRightTitle: String?): String?`
  - `IdentityDisambiguator.matchUser(label: String, records: Collection<IdentityRecord>): IdentityMatch`
  - `IdentityDisambiguator.matchGroup(label: String, records: Collection<ConversationRecord>): ConversationMatch`

- [ ] **Step 1: 编写失败单测（含 Review Focus #2）**
  在两个测试类中覆盖：`parsesValidGroupProtobufWithMatchingVarintId()`、`rejectsCorruptedOrMismatchedField1Field2Protobuf()`、`skipsNonGroupConversationType()`、`marksDuplicateNfkcNormalizedNicknamesAmbiguous()`、`bindsSingleExactUserAndStripsUnreadCountSuffix()`（例如 `"测试好友\n(9)"` → `"测试好友"`，仅移除末尾数字括号不影响含普通文字标题）。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.core.identity.*"`
  Expected: FAIL（类未定义）。

- [ ] **Step 3: 实现无依赖 Protobuf 解码与身份消歧**
  `DouyinProtobufParser` 按 wire format 迭代解码 varint/64-bit/length-delimited/32-bit（最大嵌套深度 `10`，遇到 group wire type 跳过返回空，严格校验 15–25 位 ASCII 数字字符串 `field 1 == field 2 varint && field 3 == 2 && field 5` UTF-8 长度 `1..80` 无控制字符）；`IdentityDisambiguator` 执行 NFKC + 空白折叠标准化、按 `remark_name` / `nickname` / `unique_id` / `short_id` 索引集合并在 `ids.size > 1` 返回 `AMBIGUOUS`。

- [ ] **Step 4: 验证单测全绿并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.core.identity.*"`
  Expected: PASS。
  Commit: `feat(android): 实现群聊 protobuf 解析与同名身份消歧`

---

### Task 3: 定位器包热覆写、Bark 解析脱敏与双通道通知器（M2 并行模块 B）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/selectors/SelectorBundle.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/selectors/SelectorBundleValidator.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/selectors/SelectorBundleStore.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/notify/BarkUrlNormalizer.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/notify/EncryptedSecretStore.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/notify/BarkNotifier.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/core/notify/LocalNotifier.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/core/selectors/SelectorBundleValidatorTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/core/notify/BarkUrlNormalizerTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/core/notify/BarkNotifierTest.kt`

**Interfaces:**
- Consumes: `assets/selectors_bundle.json`, `OkHttpClient`
- Produces:
  - `SelectorBundleValidator.validateOverrides(base: SelectorBundle, rawJson: String): Map<String, Any>`
  - `SelectorBundleStore.current(): SelectorBundle`, `SelectorBundleStore.digest(): String`, `SelectorBundleStore.saveOverrides(rawJson: String): SelectorBundle`, `SelectorBundleStore.clearOverrides(): SelectorBundle`
  - `BarkUrlNormalizer.normalize(rawInput: String): String`, `BarkUrlNormalizer.mask(rawUrl: String?): String?`
  - `EncryptedSecretStore.saveBarkUrl(url: String?)`, `EncryptedSecretStore.readBarkUrl(): String?`
  - `BarkNotifier.send(title: String, body: String, level: NotifyLevel): Boolean`
  - `LocalNotifier.notifyDailySummary(...)`, `LocalNotifier.notifyUrgentAlert(category: UrgentCategory, ...)`

- [ ] **Step 1: 编写失败单测（含 Review Focus #5）**
  覆盖：`rejectsChatUrlOverrideAndControlCharacters()`（锁死 `CHAT_URL`、超 `512` 字符、空列表或 `> 20` 项、含 `\r`/`\n`/`\u0000`）；`computes12HexDigestMatchingPythonBundle()`；`normalizesPlainUrlGetCurlFormPostAndJsonPushCurl()`、`rejectsPlaceholderDeviceKey()`、`masksDeviceKeyKeepingLast4Chars()`；使用 `MockWebServer` 测 `BarkNotifier` V2 JSON 请求体与 query `?level=timeSensitive` 失败不抛异常。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.core.selectors.*" --tests "io.github.oinzc.flamecontractor.core.notify.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现定位器双层存储与加密 Bark / 本机通知**
  对齐 `backend/app/execution/selectors.py` 实现基线 + `filesDir/selectors_override.json` 原子覆写与 12 位 SHA-256 摘要；按 `backend/app/notifications.py` 实现 `normalize`/`mask` 与 6 秒超时的 OkHttp V2 JSON 推送；`EncryptedSecretStore` 使用 Keystore AES-GCM；`LocalNotifier` 注册 `daily_summary` 与 `alerts_urgent` 通道并附点击回跳 `PendingIntent`。

- [ ] **Step 4: 验证单测全绿并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.core.selectors.*" --tests "io.github.oinzc.flamecontractor.core.notify.*"`
  Expected: PASS。
  Commit: `feat(android): 实现定位器热覆写校验与 Bark/本机双通道通知`

---

### Task 4: Room 数据库模型、安全发送闸门与账号健康状态机（M2 并行模块 C）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/data/db/Enums.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/data/db/Entities.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/data/db/Daos.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/data/db/FlameDatabase.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/domain/TemplatePoolPicker.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/domain/SendGuard.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/domain/AccountHealthReducer.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/domain/TemplatePoolPickerTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/domain/SendGuardTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/domain/AccountHealthReducerTest.kt`

**Interfaces:**
- Consumes: Room KSP, Coroutines
- Produces:
  - Entities: `AccountEntity`, `ContractTargetEntity`, `MessageTemplateEntity`, `TemplatePoolItemEntity`, `SendJobEntity`, `SendAttemptEntity`, `ScanRunEntity`
  - `TemplatePoolPicker.pick(items: List<TemplatePoolItemEntity>, weekdayIndex: Int): TemplatePoolItemEntity?` + DAO `consumePoolItem(id, now)`
  - `SendGuard.canDispatchDaily(dailyJobs: List<SendJobEntity>): Boolean`, `SendGuard.checkManualSend(todayJobs: List<SendJobEntity>): ManualSendDecision` (`BLOCKED_UNKNOWN_OR_INFLIGHT | CONFIRM_ALREADY_SUCCEEDED | ALLOWED`), `SendGuard.recoverCrashedJobs(...)`
  - `AccountHealthReducer.onAuthProbeResult(...)`, `AccountHealthReducer.onBatchFinished(batchOutcomes: List<JobOutcome>)`, `AccountHealthReducer.recoverFromCooldown(...)`, `AccountHealthReducer.recoverFromDomBroken(...)`

- [ ] **Step 1: 编写失败单测（含 Review Focus #1 与部分 #2/#3）**
  - `SendGuardTest`：`hardBlocksBothDailyAndManualWhenUnknownOrInflightExistsToday()`、`skipsDailyDispatchAfterTemplateChangeWhenSucceededOrCancelled()`、`allowsDailyRetryOnlyUpToMaxCount2()`、`transitionsCrashedSendingOrConfirmingToUnknownRequiresReview()`。
  - `AccountHealthReducerTest`：`ignoresNetworkErrorsInAuthAndFailureStreak()`、`requiresTwoConsecutiveAuthFailuresForAuthExpired()`、`triggersVerificationRequiredImmediatelyWithoutTolerance()`、`incrementsFailureStreakAtMostOncePerBatchAndCooldownsAt3()`、`resetsStreakOnAnySuccessAndSupportsExplicitRecovery()`。
  - `TemplatePoolPickerTest`：`filtersByWeekdayBitmaskAndOrdersNeverUsedThenLru()`、`previewDoesNotConsumeLastUsedAt()`。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.domain.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现 Room 7 表（带默认账号/模板种子）与纯领域规则**
  全表包含 `accountId`、唯一约束（`uq_target_account_nickname`/`platform_user_id`/`platform_conversation_id`、`uq_send_job_daily_slot(account_id, target_id, business_date, message_slot_id)`）、软删除 `deletedAt` 复活支持；`FlameDatabase` 首次打开种子写入 `"default"` 账号、默认「比心」`STICKER` 模板与默认 `TEXT` 模板。所有枚举、常量（`AUTH_CHECK_TOLERANCE = 2`、`RETRY_MAX_COUNT = 2`、`FAILURE_STREAK_LIMIT = 3`）严格对齐规范 §5。

- [ ] **Step 4: 验证单测全绿并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.domain.*"`
  Expected: PASS。
  Commit: `feat(android): 实现 Room 持久化模型、防重发闸门与账号健康状态机`

---

### Task 5: 每日同日调度规划、`AlarmManager` 主唤醒与 30 分钟纯 SQLite 兜底（M2 并行模块 D）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/scheduler/DailySchedulePlanner.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/scheduler/ScheduleCoordinator.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/scheduler/AlarmWakeReceiver.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/scheduler/SystemTimeBootReceiver.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/scheduler/SafetyNetWorker.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`（登记 Receivers）
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/scheduler/DailySchedulePlannerTest.kt`

**Interfaces:**
- Consumes: `AccountEntity`, `ContractTargetEntity`, `SendJobEntity`（Task 4）；纯 Java Time 计算
- Produces:
  - `DailySchedulePlanner.validateWindow(startHhmm: String, endHhmm: String): Boolean`（强制 `00:00 <= start < end <= 23:55`）
  - `DailySchedulePlanner.computeTargetDueTime(...)`, `DailySchedulePlanner.evaluateNow(...)`（返回到期列表、`NETWORK` 最多 5 次 / `FAILED_RETRYABLE` 最多 2 次 5/15 分钟退避及下次唤醒时刻，过窗/过 60 分钟宽限返回跳过不排班）
  - `ScheduleCoordinator.rescheduleAll(now: Instant)`, `ScheduleCoordinator.scheduleAuthRecheckIn10Minutes()`, `ScheduleCoordinator.scheduleRetryBackoff(attemptNo: Int)`

- [ ] **Step 1: 编写失败单测（含 Review Focus #3）**
  覆盖：`rejectsCrossMidnightWindowInV1()`、`clampsWindowJitterBeforeWindowEndMinus2Minutes()`、`grants60MinuteGraceForFixedSendTimeAndSkipsAfterGrace()`、`neverDispatchesPreviousBusinessDateAfterMidnight()`、`computesDeterministicWindowTimeAndBatchesNearbyTargets()`、`backsOff5mThen15mAndStopsAtRetryMax2()`、`schedules10MinuteAuthRecheckWhenFailCountIs1()`。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.scheduler.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现日计划规划器、闹钟协调与 30 分钟纯 SQLite `SafetyNetWorker`**
  `ScheduleCoordinator` 使用 `setExactAndAllowWhileIdle`（无权限时降级 `setAndAllowWhileIdle`）；`SafetyNetWorker` 配置每 `30 分钟` 定期检查 SQLite，仅在仍有未过窗到期单据时拉起 Task 6 的前台执行服务（不直接接触 WebView）。

- [ ] **Step 4: 验证单测全绿并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.scheduler.*"`
  Expected: PASS。
  Commit: `feat(android): 实现同日窗口防过窗调度器与 30 分钟 SQLite 兜底`

---

### Task 6: 桌面模式 WebView 完整执行引擎、前台执行服务与换号/扫码活动（M3 核心模块）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/ExecutionMutex.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/DouyinJsScripts.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/SendConfirmer.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/AccountSwitchGuard.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/DouyinWebExecutor.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/ExecutionForegroundService.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/LoginWebViewActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`（登记 `ExecutionForegroundService` 与 `LoginWebViewActivity`，并移除已由前者替代的 M1 临时 `OffscreenProbeService`）
- Remove: `android/app/src/main/java/io/github/oinzc/flamecontractor/execution/OffscreenProbeService.kt`（由正式 `ExecutionForegroundService` 接管，避免 Release 包残留调试入口）
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/execution/SendConfirmerTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/execution/AccountSwitchGuardTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/execution/DouyinJsScriptsTest.kt`

**Interfaces:**
- Consumes: Task 1 (`DesktopWebViewFactory`), Task 2 (`DouyinProtobufParser`, `IdentityDisambiguator`), Task 3 (`SelectorBundleStore`, `LocalNotifier`, `BarkNotifier`), Task 4 (DAOs, `SendGuard`, `AccountHealthReducer`, `TemplatePoolPicker`), Task 5 (`DailySchedulePlanner`, `ScheduleCoordinator`)
- Produces:
  - `DouyinWebExecutor.scanAllChats(...)`, `DouyinWebExecutor.probeStructure(...)`, `DouyinWebExecutor.sendText(...)`, `DouyinWebExecutor.sendSticker(...)`
  - `AccountSwitchGuard.checkLoginIdentity(prevUid, newUid, prevNick, newNick, hasTargets): SwitchDecision`
  - `ExecutionForegroundService.enqueueScheduledRun(context)`, `enqueueManualBatch(context)`, `enqueueSingleSend(context, targetId, overrideText)`
  - `LoginWebViewActivity.createIntent(context, mode: LoginMode)`（`PRESERVE_SESSION | CLEAR_AND_RELOGIN | INSPECT_OR_VERIFY`）

- [ ] **Step 1: 编写失败单测（覆盖 Review Focus #2/#4 及确认链路）**
  - `SendConfirmerTest`：`returnsRetryableWhenFailureMarkerAppears()`、`requires3ConsecutiveClearedPollsForSucceeded()`、`returnsUnknownRequiresReviewOnTimeoutWithoutRetrying()`、`detectsNewStickerMediaBubbleViaMultisetCounterDiffEvenWhenNodeCountConstant()`。
  - `AccountSwitchGuardTest`：`requiresUserChoiceWhenSelfUidChangesWithExistingTargets()`、`allowsSeamlessReloginWhenSameSelfUidMatches()`。
  - `DouyinJsScriptsTest`：断言生成的 IndexedDB 脚本使用局部作用域 / `event.target.result`（不含循环共享 `var` 句柄）与零宽字符正则。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.execution.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现完整执行器、`ExecutionForegroundService` 与 `LoginWebViewActivity`**
  - 文本发送：写前空校验 → 优先 `js_execCommand`（可见 Activity 可选回退 `android_ime` `InputConnection.commitText`）→ 去零宽验证写入 → 点击 `SEND_BUTTONS` → `15s` 双重确认（outgoing 气泡或 `SendConfirmer` 3 次清空）；未命中即落 `UNKNOWN_REQUIRES_REVIEW` 立刻推高优通知 + Bark。
  - 表情发送：采样 `isFromMe` 非头像媒体签名 → 精确分类/名称/兜底点击 → `15s` 多重集差集确认 → `finally` 复位输入框（Escape）。
  - 服务控制：`ExecutionMutex` 串行、`10 分钟` `PARTIAL_WAKE_LOCK` 硬上限、好友间 `3–8 秒` 随机冷却、退出/取消时把未决 `CONFIRMING` 收敛为 `UNKNOWN_REQUIRES_REVIEW`；批后清理 WebView 并调用 `ScheduleCoordinator.rescheduleAll`。
  - `LoginWebViewActivity`：实现 §6.5 按状态分流及安全清理 Cookie + `WebStorage` + 换号弹窗。

- [ ] **Step 4: 验证单测全绿并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.execution.*"`
  Expected: PASS。
  Commit: `feat(android): 实现桌面模式 WebView 执行引擎、离屏续火服务与扫码登录`

---

### Task 7: 应用容器、自适应 Compose 导航框架与「今日」首页（M3 UI 模块 A）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/app/FlameApplication.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/app/AppContainer.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/shell/MainActivity.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/shell/AdaptiveNavShell.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/today/TodayViewModel.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/today/TodayScreen.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`（注册 `FlameApplication` 与主 `MainActivity`）
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/ui/today/TodayViewModelTest.kt`

**Interfaces:**
- Consumes: DAOs, `ScheduleCoordinator`, `SelectorBundleStore`, `EncryptedSecretStore`, `BarkNotifier`, `LocalNotifier`, `SendGuard`, `AccountHealthReducer`, `TemplatePoolPicker`, `DouyinWebExecutor`, `ExecutionForegroundService`, `LoginWebViewActivity`
- Produces:
  - `class AppContainer(context: Context)`（暴露 `database`, `selectorBundleStore`, `secretStore`, `barkNotifier`, `localNotifier`, `scheduleCoordinator`, `sendGuard`, `accountHealthReducer`, `templatePoolPicker`, `webExecutor`）
  - `@Composable fun AdaptiveNavShell(todayContent: @Composable () -> Unit, targetsContent: @Composable () -> Unit, templatesContent: @Composable () -> Unit, settingsContent: @Composable () -> Unit)`（`<600dp` 底部 `NavigationBar`，`>=600dp` 左侧 `NavigationRail`）
  - `@Composable fun TodayRoute(container: AppContainer, onOpenTab: (Int) -> Unit)`

- [ ] **Step 1: 编写 `TodayViewModelTest` 失败测试**
  验证：`resolvingUnknownAsSucceededIncrementsStreakOnce()`、`resolvingUnknownAsCancelledPreventsTodayReautoSend()`、`clearingCooldownResetsFailureStreakToZero()`、`reloginFromHealthyShowsKeepOrClearChoice()`。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.ui.today.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现 `AppContainer`、自适应外壳与 `TodayScreen`**
  呈现：账号健康胶囊 + `[扫码/重登]` 分流对话框；4 类置顶高优横幅（`UNKNOWN_REQUIRES_REVIEW` 终裁弹窗无自动重发按钮、`COOLDOWN`「解除熔断」、`DOM_BROKEN` 跳转体检/覆写、`AUTH_EXPIRED`/`VERIFICATION_REQUIRED` 直跳扫码/验证）；今日总览卡（一键执行今日续火 + 跳转到手动扫描）；最近发送/Attempt 明细列表；开机及冷启动执行一次僵尸单收敛。

- [ ] **Step 4: 验证单测与 Compose 编译通过并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.ui.today.*" :app:compileDebugKotlin`
  Expected: PASS。
  Commit: `feat(android): 实现自适应 Compose 主框架与今日总览及异常恢复界面`

---

### Task 8: 「好友与扫描对账」及「模板/文案池」原生页面（M3 UI 模块 B）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/targets/TargetsViewModel.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/targets/TargetsScreen.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/targets/ScanReconcileSheet.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/templates/TemplatesViewModel.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/templates/TemplatesScreen.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/ui/targets/ScanReconcileViewModelTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/ui/templates/TemplatesViewModelTest.kt`

**Interfaces:**
- Consumes: `AppContainer` (`TargetDao`, `TemplateDao`, `SendJobDao`, `ExecutionForegroundService`, `SendGuard`)
- Produces: `@Composable fun TargetsRoute(...)` 与 `@Composable fun TemplatesRoute(...)` 接入 `AdaptiveNavShell`

- [ ] **Step 1: 编写失败单测**
  - `ScanReconcileViewModelTest`：`sortsOfficialSparksBeforeNonSparksAndPreselectsUnimportedValidOnesOnce()`、`disablesSelectionForAmbiguousEntries()`、`singleTargetManualSendHardBlocksUnknownTodayAndPromptsOnceIfAlreadySucceeded()`、`officialSparkSnapshotImportNeverOverwritesLocalStreakDays()`。
  - `TemplatesViewModelTest`：`poolItemWeekdayMask0AndBitwiseSaveCorrectly()`。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.ui.targets.*" --tests "io.github.oinzc.flamecontractor.ui.templates.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现好友列表、扫描对账抽屉与模板/文案池编辑 UI**
  对齐桌面端 UX：搜索筛选、群聊/好友角标、官方火花徽标与本地连续天数独立双标展示、一次性预选可导入火花对象、全选/清空、卡片单发测试受 `SendGuard` 拦截、软删除复活、文案池星期多选与 LRU 上次日期展示、原生表情配置（比心及兜底索引）。

- [ ] **Step 4: 验证单测与编译通过并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.ui.targets.*" --tests "io.github.oinzc.flamecontractor.ui.templates.*" :app:compileDebugKotlin`
  Expected: PASS。
  Commit: `feat(android): 实现好友扫描对账导入与模板文案池原生界面`

---

### Task 9: 「设置、HyperOS 保活体检、定位器覆写与脱敏诊断」页面（M3 UI 模块 C）

**Files:**
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/settings/RomSurvivalChecker.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/settings/SanitizedDiagnosticsBuilder.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/settings/SettingsViewModel.kt`
- Create: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/settings/SettingsScreen.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/ui/settings/SanitizedDiagnosticsBuilderTest.kt`
- Test: `android/app/src/test/java/io/github/oinzc/flamecontractor/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `AppContainer` (`AccountDao`, `ScheduleCoordinator`, `SelectorBundleStore`, `BarkUrlNormalizer`, `EncryptedSecretStore`, `BarkNotifier`, `DouyinWebExecutor`)
- Produces: `@Composable fun SettingsRoute(...)`

- [ ] **Step 1: 编写失败单测**
  - `SanitizedDiagnosticsBuilderTest`：`redactsAllPiiAndBarkKeysLeavingOnlyVersionsDigestAndCounts()`。
  - `SettingsViewModelTest`：`rejectsCrossMidnightWindowInput()`、`parsesAndMasksPastedBarkCurlAndPreservesSecretInKeystore()`、`resetsDomBrokenAfterVerifiedStructureProbeOrValidOverride()`。

- [ ] **Step 2: 运行测试确认失败**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.oinzc.flamecontractor.ui.settings.*"`
  Expected: FAIL。

- [ ] **Step 3: 实现设置页、MIUI/HyperOS 一键跳转体检与脱敏导出**
  含：总开关/窗口（禁跨午夜）/精确时刻/抖动与 3–8s 冷却配置；本机通知 + Bark 粘贴解析/发测试通知；4 项保活检测与安全跳转（通知、电池无限制、精确闹钟、自启动引导且无活动时安全回退应用详情页）；只读网页结构体检弹窗（触发后绝不输入/点击发送）+ 覆写 JSON 编辑与恢复默认 + 脱敏诊断导出。

- [ ] **Step 4: 验证单测与完整组装通过并提交**
  Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  Expected: PASS。
  Commit: `feat(android): 实现设置调度、小米保活体检、定位器覆写与脱敏诊断`

---

### Task 10: Android CI / Release APK 流水线与真机测试设备 M2–M4 全链路验收

**Files:**
- Create: `.github/workflows/ci-android.yml`
- Create: `.github/workflows/release-android.yml`
- Modify: `android/app/src/main/java/io/github/oinzc/flamecontractor/ui/shell/MainActivity.kt`（在 `AdaptiveNavShell` 中最终接通 `TodayRoute` / `TargetsRoute` / `TemplatesRoute` / `SettingsRoute` 四个真实路由）
- Modify: `README.md`（补充 Android 独立安装包说明与权限指引，不动 Windows/Docker 章节）

**Interfaces:**
- Consumes: `android/` 全量源码、`.github/workflows/` 现有标签触发习惯
- Produces: 独立 Android CI 检查与 Tag Release APK 构建/附加流水线

- [ ] **Step 1: 接通 4 个路由入口并编写 `.github/workflows/ci-android.yml` 与 `.github/workflows/release-android.yml`**
  - 在 `MainActivity.kt` 将 `TodayRoute`、`TargetsRoute`、`TemplatesRoute`、`SettingsRoute` 正式注入 `AdaptiveNavShell`。
  - `ci-android.yml` 监听 `android/**` 与 `backend/app/execution/selectors_bundle.json`，跑 `verifySelectorBundleSync` + `testDebugUnitTest` + `lintDebug` + `assembleDebug` 并上传 CI Artifact。
  - `release-android.yml` 在 `v*` tag 与 `workflow_dispatch` 触发；检测签名 Secrets 是否齐全，齐全则签名发布 `FlameContractor-<version>-android-universal-release.apk` 并 `gh release upload $tag ... --clobber`；无 Secrets（开源 Fork）则构建未签名/Debug APK 并打印提示，绝不卡死构建。

- [ ] **Step 2: 本地全量质量门禁核验**
  Run:
  ```powershell
  Set-Location android
  .\gradlew.bat verifySelectorBundleSync testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon
  ```
  Expected: 全部单元测试、Lint、契约校验与 APK 打包 `BUILD SUCCESSFUL`。

- [ ] **Step 3: 在测试平板执行 M2–M4 真机端到端验收**
  按规范 §9.3 逐项在真实设备上与用户协作验证：
  - **M2**：安装调试版 `app-debug.apk` → 完成扫码登录、`[扫码/重登]` 保留/清 Cookie 换号边界校验 → 手动扫描对账并导入测试好友与「比心」/文案池模板；
  - **M3**：由用户在卡片亲自点击选定测试对象执行 1 次单发文本 + 1 次原生表情，验证气泡确认、`streak_days + 1`、`UNKNOWN_REQUIRES_REVIEW` 模拟/状态栏与本机/Bark 回音；
  - **M4**：设定数分钟后闹钟窗并锁屏放置平板，验证屏幕不亮、零弹窗情况下按时唤醒静默发送完成、释放服务并收到测试通知，期间不读取/泄露任何真实个人昵称或 ID 到仓库。

- [ ] **Step 4: 更新 `README.md` Android 章节并提交终版收口**
  验证桌面与后端现有测试未受影响（`backend` `pytest -q` 与 `frontend` `npm test` 通过），提交最终 CI 与文档。
  Commit: `ci(android): 添加 Android CI/Release APK 流水线并更新使用文档`
