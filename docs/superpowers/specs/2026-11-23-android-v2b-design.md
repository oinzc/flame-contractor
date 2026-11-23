# Flame Contractor 独立 Android 版 v2B（本地图片发送与多账号管理）设计规范

- **日期**：2026-11-23
- **状态**：待用户确认批准
- **范围**：`android/`（Room `version = 4`、本地图片模板 `TemplateType.IMAGE` 与离屏 WebView 传图、AndroidX WebView `ProfileStore` 多账号隔离与独立后台调度、跨端 JSON 备份兼容与无损导入提示）
- **基线版本**：`v0.4.0` + Phase 2A（Room DB `version = 3`；5 主导航 Tab：`今日 / 好友 / 模板 / 记录 / 设置`）

---

## 1. 背景、目标与非目标

### 1.1 目标
1. **Android 本地图片消息发送（`TemplateType.IMAGE`）**：支持用户通过系统相册/文件选择器选取本地图片作为消息模板，并在前/后台离屏 WebView 自动续火与单发测试时完成图片上传、确认弹窗发送与非头像媒体气泡签名确认。
2. **Android 多账号切换与独立后台托管**：基于 AndroidX WebView `ProfileStore` 为多个抖音账号提供隔离的 Cookie / `localStorage` / `IndexedDB` 运行环境，支持界面一键切换与账号增删改停；各「已启用」账号的每日自动续火与周期官方火花自动扫描在后台独立计算、通过 `ExecutionMutex` 严格串行执行，互不干扰。
3. **跨端兼容与安全守卫继承**：
   - `UNKNOWN_REQUIRES_REVIEW` 永不自动重发；
   - 官方火花快照更新严禁修改 `streak_days`；
   - 跨端 JSON 备份继续采用轻量化方案，保留 `image` 模板配置与目标外键关联（不塞入 Base64 图片字节），导入或换机后缺失本地图片的文件安全阻塞待人工补选。

### 1.2 非目标
- 本期不修改 `backend/`、`frontend/`、`desktop/` 与 `.github/workflows/publish-ghcr.yml`；确保 `android/app/src/main/assets/selectors_bundle.json` 与 `backend/app/execution/selectors_bundle.json` 保持完全一致以通过 `verifySelectorBundleSync` 门禁。
- 不做并发多 WebView 实例，进程内始终保持单一 `ExecutionMutex` 串行队列以控制内存与风控风险。
- 不在本期重构「设置」页卡片整体排布（留待后续统一 UI 梳理阶段）。

---

## 2. 真机前置可行性探测实证（零真实发送）

在正式设计前，已在已连接 Android 设备（`WebView 157.0.8103.50`）上通过临时 debug 探测入口完成零实际消息发送的可行性验证（随后已彻底移除临时 debug 源码文件，工作树保持干净）：

| 验证项 | 实测结论 | 对设计的关键约束 |
|---|---|---|
| **WebView `ProfileStore` 多环境隔离** | `WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) == true`；新建隔离 Profile 打开 `https://www.douyin.com/chat` 后，`isolatedLoggedIn = false`、`isolatedHasUserInfo = false`、`isolatedCookieLen = 1204`，且主 Profile Cookie 长度前后完全一致（`4568 -> 4568`）。 | Cookie 与 `localStorage` 完全隔离，可用作多账号登录态容器；`default` 账号必须继续绑定 `"Default"` Profile 以无损继承老用户已有登录态。 |
| **`ProfileStore.deleteProfile` 生命周期** | 在同一进程内销毁隔离 `WebView` 后等待 `500ms` 立即调用 `store.deleteProfile(...)`，抛出 `IllegalStateException: Cannot delete in-use profile`。 | **禁止假定进程内删完 WebView 可立即同步删除 Profile**；删除账号时须先清库、删图并清空该 Profile 的 `CookieManager` / `WebStorage`，再将该 Profile 名称写入待清理列表，留待下次进程冷启 `FlameApplication.onCreate()` 抢在任何 WebView 初始化前执行 `ProfileStore.deleteProfile(...)`。 |
| **完全离屏 WebView 传图机制** | 在未挂载窗口的离屏 `WebView(applicationContext)`（`1440x900`）上，方案 A（向 `.semi-upload-add` 派发原生 `MotionEvent` 点击 → 触发 `WebChromeClient.onShowFileChooser(mode=1)` → 传入 `FileProvider` `content://` URI）与方案 B（JS `DataTransfer` 注入 `File` 到 `input[type="file"]` 并派发 `input`/`change`）**均成功弹出带预览图的发送确认弹窗**（`hasImgPreview = true`, `imgCount = 1`），点击「取消」后非头像媒体计数前后一致（零误发）。 | 离屏后台可稳定上传图片；采用「方案 A 为主 + 方案 B 兜底」双保险链路。 |
| **图片发送确认弹窗与 `file input` 选择器** | 页面存在 2 个 `accept=""` 的 `<input type="file">`（首个 `multiple = true`）；确认弹窗出现时 DOM 中有 2 个 `.semi-modal, [role="dialog"]`（有效交互弹窗为最顶层可见节点），按钮文本为 **`["取消", "发送（1）"]`**——桌面端既有 `IMAGE_DIALOG_SEND` CSS 选择器（`button[class*="primary"]`）及精确文本 `"发送"` 均**未命中**（`sendBtnFound = false`）。 | **重大修正**：不可直接依赖 `selectors_bundle.json` 现有 `IMAGE_DIALOG_SEND` 主按钮类名或精确 `"发送"`；必须在 `DouyinJsScripts` 对顶层可见弹窗的 `button, [role="button"]` 兼容匹配 `^发送(?:\s*[（(]\d+[)）])?$`（或优先既有选择器失败后按正则前缀命中），否则发送阶段必定找不到确认按钮。 |

---

## 3. 数据模型与 Room 数据库升级（v3 → v4）

### 3.1 `TemplateType` 与实体扩展
1. **`Enums.kt`**：`TemplateType` 新增 `IMAGE("image")`。
2. **`Entities.kt` — `MessageTemplateEntity`**：在末尾追加字段并保留默认值，确保既有测试与构造器向后兼容：
   ```kotlin
   @ColumnInfo(name = "image_path")
   val imagePath: String? = null
   ```
   - `imagePath` 仅保存应用私有目录下的受控相对路径/文件标识（或由 `TemplateImageStore` 解析后的私域文件路径），若为 `null`、空白或对应磁盘文件不存在，视为「待补充本地图片」。

### 3.2 `FlameDatabase` 与 `MIGRATION_3_4`
- 版本升级：`version = 4`。
- `MIGRATION_3_4_STATEMENTS`：
  ```kotlin
  val MIGRATION_3_4_STATEMENTS: List<String> = listOf(
      "ALTER TABLE message_templates ADD COLUMN image_path TEXT",
  )
  ```
- 保留 `MIGRATION_1_2` 与 `MIGRATION_2_3`，`addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`。

### 3.3 DAO 扩展
- **`AccountDao`**：
  - `observeAll(): Flow<List<AccountEntity>>`（按 `created_at ASC` 排序，驱动全局账号切换器与多账号异常红点）；
  - `deleteById(accountId: String)`（配合 `TargetDao.deleteAllByAccount`、`SendJobDao.deleteAllByAccount` 等完成事务性删除）。
- **`TemplateDao`**：
  - `deleteAllByAccount(accountId: String)`（按账号删除全部模板，外键级联清理 `template_pool_items`）；
  - `listImagePaths(accountId: String): List<String>`（或从 `listTemplates` 读取用于孤立文件清理）。
- **`ScanRunDao`**：
  - `deleteAllByAccount(accountId: String)`（账号删除时解除 `scan_runs.account_id` 的 `FOREIGN KEY RESTRICT` 约束）。
- 同步更新 `DouyinJsScriptsTest` 内假 DAO 与新增 `FlameDatabaseMigrationTest` 对 `MIGRATION_3_4` 的校验。

---

## 4. 本地图片发送（`TemplateType.IMAGE`）

### 4.1 私有存储与安全归一化：`TemplateImageStore`
- **存储目录**：`context.filesDir/template_images/`，配专属 `FileProvider`（`authorities = "${applicationId}.fileprovider"`，`file_paths.xml` 仅授权 `<files-path name="template_images" path="template_images/" />`），严禁暴露整个 `filesDir` 或外置存储路径。
- **入库规范化与防 OOM / 去隐私**：
  1. 选图后通过 `ContentResolver.openInputStream(uri)` 按 **`15 MB + 1` 字节上限**流式读入字节上限（超限立即中断报错，防止超大原图撑爆内存）；
  2. 解析 MIME 与图像头部（支持 `image/jpeg`、`image/png`、`image/webp`、`image/gif`）：
     - 若为 GIF 动图：校验大小 `<= 5 MB` 且字节头合法（`GIF87a`/`GIF89a`），超过 5 MB 拒绝提示，否则直接落盘保留动画字节；
     - 若为静态图片：用 `BitmapFactory.Options.inJustDecodeBounds` 计算采样率（最长边限制 `2048px`），按采样率解码后处理 EXIF 方向旋转并重新编码为干净 PNG/JPEG（完全剥离 EXIF GPS、设备型号与拍摄参数等隐私元数据），确保落盘文件 `<= 10 MB`；
  3. 生成纯随机文件名（`<uuid>.jpg` / `<uuid>.png` / `<uuid>.gif`），丢弃用户相册原始文件名（防止含真实人物姓名或时间地点）。
- **孤立文件回收**：更换模板图片、删除图片模板、或删除整个账号时，比对数据库所有 `MessageTemplateEntity.imagePath` 引用后安全清理无引用文件，避免垃圾膨胀。

### 4.2 离屏 WebView 发送执行链（`DouyinWebExecutor.sendImage`）
在 `ExecutionForegroundService` 调度/单发入口对 `TemplateType.IMAGE` 校验与分派后，调用 `DouyinWebExecutor.sendImage(...)`（保留复用外层已建立的 `withOffscreenSession` 与会话打开核对逻辑）：
1. **前置本地文件门禁（绝不静默跳过或空发）**：
   - 检查 `template.imagePath` 非空且对应文件存在可读、字节数 `> 0`。
   - 若缺失或损坏：直接返回 `SendExecutionResult(jobStatus = JobStatus.FAILED_TERMINAL, failureClass = FailureClass.OTHER, inputMethodUsed = "image_upload", summaryDetail = "图片模板缺少有效的本地图片文件，请前往「模板」页重新选择")`。
2. **会话就绪与历史气泡水合**：
   - 执行目标身份核对（`openAndVerifyConversation`）与输入框就绪确认（`waitForEditorReady`）；
   - 调用 `waitForMessageListSettled(session, bundle)` 等待历史消息和历史图片稳定渲染完毕。
3. **双路径上传拉起确认弹窗**：
   - 检查页面上传入口（`.semi-upload-add` 与 `input[type="file"]`）；若两者均不存在，返回 `FAILED_TERMINAL`（`DOM_CHANGED`, `coreSkeletonBroken = true`）。
   - **主路径（方案 A：`MotionEvent` + `onShowFileChooser` + `FileProvider`）**：
     - 生成对应私有文件的 `FileProvider` `content://` URI 并装配到离屏 WebView 的临时 `WebChromeClient.onShowFileChooser` 一次性回调槽；
     - 读取 `.semi-upload-add` 中心视口坐标，在主线程对 WebView 派发原生 `MotionEvent.ACTION_DOWN` / `ACTION_UP`（若未触发 `onShowFileChooser`，依次兜底 JS `.semi-upload-add.click()` 与 `input[type="file"].click()`）；
     - 轮询最长 `6s` 等待顶层可见确认弹窗出现预览图及发送按钮。
   - **降级路径（方案 B：JS `DataTransfer`）**：
     - 若方案 A 未成功拉起确认弹窗，清空回调槽并确认无弹窗残留后，由 Kotlin 读取私有图片字节转 Base64（限制仅内存瞬时传递，绝不写入日志），通过 JS 解码构造 `File` 与 `DataTransfer` 写入首个匹配的图片 `input[type="file"]`（重置 React `_valueTracker` 后派发 `input` 与 `change` 事件），轮询最长 `6s` 等待确认弹窗出现。
   - 若双路径均未拉起带确认按钮的可见弹窗，返回 `FAILED_RETRYABLE`（`FailureClass.OTHER`，不进入 `CONFIRMING`）。
4. **不可逆发送确认边界（对齐 `SendConfirmer` 安全不变量）**：
   - **必须在弹窗就绪后、点击确认发送前一刻**采样 `beforeMediaMultiset`（`DouyinJsScripts.buildSampleOutgoingMediaMultisetJs`）；
   - 回调 `onStageConfirming?.invoke()` 将 `SendJobEntity` 推至 `CONFIRMING` 状态（若进程在点击后强杀，重启即转入 `UNKNOWN_REQUIRES_REVIEW` 阻断自动重发）；
   - 在最顶层可见弹窗（`.semi-modal, [role="dialog"]` 中最后一个可见容器）内点击发送按钮：依次尝试既有 `IMAGE_DIALOG_SEND` 选择器，若未命中则匹配文本满足 `^发送(?:\s*[（(]\d+[)）])?$`（兼容 `"发送（1）"` / `"发送"`）的可见 `button, [role="button"]`；
   - 复用 `SendConfirmer.confirmStickerAfterClick` 轮询 `15s` 比对 `afterMultiset` 与 `beforeMultiset`：
     - 出现新增非头像媒体签名 → **`JobStatus.SUCCEEDED`**（`inputMethodUsed = "image_chooser"` 或 `"image_datatransfer"`）；
     - 页面出现明确失败标记 → **`JobStatus.FAILED_RETRYABLE`**；
     - 15s 超时既无新增签名又无明确失败 → **`JobStatus.UNKNOWN_REQUIRES_REVIEW`**（`FailureClass.UNKNOWN_OUTCOME`，弹出高优通知，严禁自动重试）。

### 4.3 「模板」「好友」UI 与跨端备份集成
- **`TemplatesViewModel` / `TemplatesScreen`**：
  - 模板编辑器类型切换新增第 4 项「图片（`IMAGE`）」；选图使用 `ActivityResultContracts.OpenDocument()`（`arrayOf("image/*")`），展示本地缩略图、图片格式与字节数，支持更换图片；若图片文件缺失则高亮「本地图片缺失，请重新选择」并禁止保存未选图的新图片模板；
  - 模板列表卡片展示缩略图小预览或「缺少本地图片」警示徽标，且保留既有「测试发送」入口用于单发测试图片模板。
- **`TargetsViewModel` / `TargetsScreen` / `TodayViewModel`**：
  - 好友编辑选择默认模板与「今日」单发测试默认展示支持 `IMAGE` 类型模板；若目标绑定了缺失本地图文件的图片模板，在目标卡片呈现「待补图」提示，调度器仍在窗口期触发（或在尝试时按前置校验明确报错为缺失图片并通知用户）避免无声失败。
- **跨端 JSON 备份（`BackupCodec` & `BackupManager`）**：
  - **导出**：移除原有跳过 `image` 的限制，正常导出 `"type": "image"`、`name`、`enabled` 与 `"content": ""`，并**刻意省略 `"image_path"` 键**（既不泄露本机私有文件路径，也不输出显式 `null`，以免回导至桌面/Docker 后端时被后端 `_apply_row_updates` 覆盖掉桌面端同名/同 ID 图片模板原有的 `image_path`）；
  - **导入**：解析 `type == "image"` 时正常建立/更新 `MessageTemplateEntity(type = TemplateType.IMAGE)` 并保留 `ContractTargetEntity.templateId` 外键绑定，不再强行清空新目标的 `templateId` 或计入跳过删除：
    - 若按 `id` 或自然键匹配到本地已有 `IMAGE` 模板且其本地 `imagePath` 文件真实有效，则**保留本地现有图片文件引用**；
    - 若为新建或本地文件无效，置 `imagePath = null`，并在 `BackupInspection` 与 `BackupImportReport` 中清晰统计 `missingImageTemplateCount`（提示「已保留 N 个图片模板及好友绑定，请前往『模板』页重新选择本地图片」）。

---

## 5. 多账号切换与独立后台托管

### 5.1 WebView 多环境隔离与清理：`WebViewProfileRegistry`
- **Profile 命名映射规则**：
  - 当 `accountId == DEFAULT_ACCOUNT_ID`（`"default"`）时，映射为系统默认 Profile `Profile.DEFAULT_PROFILE_NAME`（`"Default"`），**不切换名称**，保证从单账号版本平滑升级后原有登录态、Cookie、`IndexedDB` 100% 保留无需重新扫码。
  - 其他新增账号（UUID/随机 ID）确定性映射为 `"acct_${sanitizeProfileSuffix(accountId)}"`。
- **WebView 绑定与 Cookie/WebStorage 访问隔离**：
  - 抽取统一辅助对象 `WebViewProfileRegistry`：
    - 根据 `accountId` 检查 `WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)`；
    - 仅对非默认 Profile 在创建 `WebView` 后、`settings`/`loadUrl` 之前调用 `WebViewCompat.setProfile(webView, profileName)`；
    - 提供 `cookieManagerFor(accountId)` 与 `clearProfileSession(accountId)`：非默认账号通过 `ProfileStore.getInstance().getOrCreateProfile(profileName).cookieManager` 独立管理 Cookie；清理单个账号的登录态（例如 `LoginWebViewActivity` 的 `CLEAR_AND_RELOGIN` 或账号删除）时**仅清理该账号对应 Profile 的 Cookie**（并仅对默认单一账号场景或按单站点范围可控时才清 `WebStorage`），严禁为清理 B 账号而误伤 A 账号已登录的 Cookie 与本地存储；删除非默认账号后交由冷启动 `deleteProfile` 连同其 Profile 数据目录一并彻底销毁！
- **冷启动延迟清理已删 Profile（解决实测 `IllegalStateException`）**：
  - 新增 `PendingProfileCleanupStore`（基于 `SharedPreferences` 记录待删除非默认 Profile 名称集合）。
  - 删除账号时：
    1. 在 DB 单事务中按拓扑顺序删除该账号的 `send_jobs`（级联 `send_attempts`）、`contract_targets`、`scan_runs`、`message_templates`（级联 `template_pool_items`）、`accounts`；
    2. 清理仅属于该账号模板的 `template_images/` 本地文件；
    3. 清空该账号对应 Profile 的 `cookieManager`；
    4. 若系统支持 `MULTI_PROFILE`，将该 Profile 名称写入 `PendingProfileCleanupStore`，并在下一个冷启动时机 `FlameApplication.onCreate()` 最早期（所有 `WebView` 与 `ExecutionForegroundService` 启动之前）执行 `ProfileStore.getInstance().deleteProfile(name)`，成功则从待清理集合移除（若系统报告已不存在亦视为清理成功移除）。
- **低版本系统 WebView 降级**：若 `MULTI_PROFILE` 不被支持，隐藏/禁用新增多账号能力并提示「当前系统 WebView 不支持多环境隔离」，仅保留默认单账号正常运行。

### 5.2 全局活跃账号状态：`ActiveAccountStore` 与账号管理 UI
- **`ActiveAccountStore`**：
  - 持有并持久化当前选中的 `activeAccountId: StateFlow<String>`（基于 `SharedPreferences` + 监听 `AccountDao.observeAll()`）。
  - 自愈回退规则：若持久化 ID 不再存在于当前账号列表中（如已被删除或刚导入新账号），自动选中列表第一个账号；若数据库完全为空则保持 `DEFAULT_ACCOUNT_ID`。
- **UI 账号切换器（不堆叠「设置」页）**：
  - 在 [AdaptiveNavShell.kt](../../../android/app/src/main/java/io/github/oinzc/flamecontractor/ui/shell/AdaptiveNavShell.kt) 顶部紧凑状态栏（手机端位于内容区顶部轻量横条，平板端位于左侧导航栏顶部徽标下方或内容区顶部紧凑栏）展示当前活跃账号：`displayName`、`AccountStatus` 健康色点、`已启用/已停用` 标记，并在其他账号存在 `AUTH_EXPIRED` / `VERIFICATION_REQUIRED` / `DOM_BROKEN` / `COOLDOWN` / `UNKNOWN_REQUIRES_REVIEW` 时显示紧凑异常角标。
  - 点击切换栏打开 **`AccountManagerDialog`**：
    - 账号列表卡片（单选切换活跃账号，显示每个账号的昵称、健康状态、启用开关）；
    - **「添加抖音号」**：若支持 `MULTI_PROFILE` 则创建新 `AccountEntity(id = uuid, displayName = ...)` 并通过数据库事务同步为该新账号播种默认文本模板「每日续火」与 3 个默认表情模板（比心/早上好/续火花），随后自动切换并可立即发起扫码登录；
    - **重命名 / 启用停用开关 / 删除账号**（至少保留 1 个账号，仅在 `accounts.size > 1` 时允许删除，弹窗二次确认删除所有相关数据与登录态）。
- **全页面 ViewModel 按 `activeAccountId` 响应式/键值隔离绑定**：
  - `MainActivity` 传入 `activeAccountId` 给 `TodayRoute`、`TargetsRoute`、`TemplatesRoute`、`HistoryRoute`、`SettingsRoute`，使用 `viewModel(key = "today_$activeAccountId", ...)` 等保证切换账号时 UI 状态、表单草稿、扫描快照完全重置并立即订阅新账号数据，杜绝跨账号状态串台。
  - **`ExecutionRuntimeBus` / `ExecutionLiveState` 归属隔离**：在 `ExecutionLiveState` 新增 `val accountId: String = DEFAULT_ACCOUNT_ID`（及 `latestScanSnapshotAccountId: String?`），UI（如 `TargetsViewModel` 自动弹出扫描对账抽屉与 `TodayViewModel` 实时进度条）仅渲染与当前 `activeAccountId` 匹配的快照，同时在多账号运行时显示「正在后台执行：[账号名]」轻提示。

### 5.3 多账号独立后台串行调度与通知路由
- **`ScheduleCoordinator` 多账号聚合与调度**：
  - `rescheduleAll(now)` 从单账号改为遍历 `database.accountDao().listAll()`：
    - 对每个账号独立评估 `DailySchedulePlanner.evaluate(...)` 与 `AutoScanPlanner.evaluate(...)`；
    - 汇总所有账号下最早的 `nextWakeAt` 注册主唤醒闹钟（`REQUEST_CODE_MAIN_WAKE = 4101`）；
    - 汇总所有账号下最早的 `nextAutoScanAt` 注册自动扫描闹钟（`REQUEST_CODE_AUTO_SCAN = 4104`）；
    - 只要任一账号启用且健康，即维持 `SafetyNetWorker` 周期兜底；若全部账号均不可调度，取消全部闹钟。
  - `evaluateAndDispatchIfNeeded(triggerSource, now)` 逐一检查所有账号的到期任务（鉴权复判 / 到期定时发送 / 到期自动扫描），对每一个需执行的账号分别调用 `ExecutionDispatchContract.dispatchEvaluationIfNeeded(context, accountId = account.id, ...)`；
  - `inspectNextAutoScanAt(accountId = ..., now)` 支持按指定 `accountId` 查询，设置页展示的「下次预计自动同步」精确对应当前正在编辑的账号。
- **`ExecutionForegroundService` 多账号 Intent 队列与互斥串行**：
  - 优化 `pendingIntents` 入队去重策略：以 `(action, accountId, targetId)` 为联合键去重，防止快速连触发闹钟时相同命令重复排队，**同时允许不同账号的任务顺序进入队列依次执行**；
  - `ExecutionMutex` 全进程唯一，每个命令在锁内基于各自 `accountId` 创建绑定对应 `Profile` 的 WebView 会话、执行完毕冲刷该账号 Cookie 并彻底 `destroy()` 后再服务下一位账号，确保内存可控且 Session 零污染；
  - 每次账号执行结束统一调用 `ScheduleCoordinator.rescheduleAll()` 刷新全局最早下次唤醒时刻。
- **多账号通知归属与直达跳转**：
  - 在 [LocalNotifier.kt](../../../android/app/src/main/java/io/github/oinzc/flamecontractor/core/notify/LocalNotifier.kt) 为紧急本地通知补充 `accountId` 维度：当系统存在多个账号时，本地通知与 Bark 标题/正文中自动附带 `[${account.displayName}]` 标签（仅使用账号配置别名，不泄露平台敏感隐私），通知 Intent 携带 `EXTRA_ACCOUNT_ID`，PendingIntent `requestCode` 与 `notificationId` 按 `(category, accountId)` 区分避免多账号告警互相覆盖；
  - 用户点击通知打开应用时，[MainActivity.kt](../../../android/app/src/main/java/io/github/oinzc/flamecontractor/ui/shell/MainActivity.kt) 读取 `EXTRA_ACCOUNT_ID` 并调用 `activeAccountStore.selectAccount(accountId)` 自动切到涉事账号，同时按告警类型定位到「今日」或「好友」Tab。

---

## 6. 安全与隐私约束总结

1. **绝不无故发消息**：所有图片传输、选图取消、缺图拦截与跨账号扫描对账均不触碰不可逆「发送」按钮；`UNKNOWN_REQUIRES_REVIEW` 状态维持只读阻断，必须由人工在「今日」Tab 显式核对。
2. **本地图片隐私净化**：通过系统 SAF 选入的静态图必经重编码剥离 EXIF（不含 GPS 坐标、相机/手机型号）并使用随机 UUID 文件名存储于应用私有目录；GIF 限 5 MB 且不带原文件名；JSON 备份导出绝不泄露本机磁盘绝对路径或图片内容本身。
3. **跨账号零串台**：严禁在非 `"default"` 账号登录清理或退出时调用全局全量清除（仅清当前 `accountId` 对应 Profile Cookie 与站点数据）；`ExecutionLiveState` 严格按 `accountId` 过滤扫描对账快照；诊断报告 `SanitizedDiagnosticsBuilder` 只统计总账号数与输出当前脱敏选定账号信息，不泄露图片名、任何好友昵称、UID、Cookie 或设备识别信息。
4. **公仓红线**：所有 Git 提交继续严格采用 `147690512+oinzc@users.noreply.github.com`，零 AI 署名，严禁提交 `android/local.properties`、签名密钥、真实账号数据或本机工具绝对路径。

---

## 7. 验证策略

1. **Room 数据库与存储层单元测试（`android/app/src/test`）**：
   - `FlameDatabaseMigrationTest` 覆盖 `MIGRATION_3_4` 语句与升级后 `TemplateType.IMAGE` 读写；
   - `TemplateImageStoreTest`（纯 JVM / mock 解码或可测试边界抽象）验证 `15 MB + 1` 流截断拦截、GIF 体积守卫、UUID 命名与无引用孤立文件清理。
2. **图片模板备份与执行脚本单测**：
   - `BackupCodecTest` / `BackupManagerTest` 验证 `TemplateType.IMAGE` 导出兼容、导入保留绑定与本地已有有效图片保持、`missingImageTemplateCount` 计数精确；
   - `DouyinJsScriptsTest` 验证弹窗按钮同时覆盖 `"发送"` 与 `"发送（1）"`、`IMAGE_DIALOG_SEND` 失败回退、顶层可见多弹窗择优（`modalCount >= 2`）以及 JS `DataTransfer` 脚本语法与返回值解析；
   - `SendConfirmerTest` / `DouyinWebExecutor`（Fake JS / 状态机）验证缺图前置拦截（不点上传）、弹窗未出安全退回（`FAILED_RETRYABLE`）、点击后未见新增气泡进入 `UNKNOWN_REQUIRES_REVIEW`。
3. **多账号隔离、调度与 ViewModel 单测**：
   - `ActiveAccountStore` / `AccountManagerViewModel` 增删切改与自愈回退测试、新增账号默认模板播种测试；
   - `ScheduleCoordinator` 多账号最早闹钟聚合与多账号并发到期逐个派发测试、`(action, accountId, targetId)` 去重队列测试；
   - 全 5 个 ViewModel 单测随接口同步回归，确保已有与新增场景 100% 绿灯。
4. **全端门禁与真机实测**：
   - 执行 `verifySelectorBundleSync :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`、后端 `pytest -q`、前端 `npm test`、`bump_version.py --check` 与隐私提交规范审计；
   - 最后通过 `adb install -r` 覆盖安装到已连真机验证：升级保活老账号、真实发送一张本地图片验证非头像签名确认、新建第 2 个抖音号验证双环境登录隔离与闹钟序列化行为。

---

## 8. 留给实施者的边界

- `AccountManagerDialog` 与 `AdaptiveNavShell` 账号切换入口的视觉微调（对齐现有 [Theme.kt](../../../android/app/src/main/java/io/github/oinzc/flamecontractor/ui/theme/Theme.kt) 圆角、间距及手机/平板双形态展示，避免挤压底部 5 栏 Tab）；
- 模板卡片中图片缩略图的采样解码尺寸（如列表中仅解码小尺寸缩略图以免列表滑动占用过多内存）；
- 待本设计规范获用户批准后，先在 `docs/superpowers/plans/` 编写分步细化实施计划（划分零文件冲突支线）并经 `/swarm` 并行推进。
