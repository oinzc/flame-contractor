# Flame Contractor · Android 平板横/竖屏与手机横屏自适应重构设计规范（方案 A）

- **日期**：2026-11-27
- **适用范围**：`android/` 独立客户端（标准模式 + 极简模式 + 设置中心 + 弹层 + 登录与新手引导）
- **基线版本**：`v0.7.1`（`67f2715`）
- **选定方案**：方案 A — 宽×高四档自适应底座 + 平板双栏工作台（已在交互原型阶段确认）

---

## 1. 背景与动机（Why）

前期 Android 端迭代主要围绕手机竖屏（`390–430 dp` 宽）完成，功能已全面闭环；但在平板横屏（典型 `~1138×711 dp`）、平板竖屏（`~711×1138 dp`）及手机横屏（典型 `~850×390 dp`）下存在系统性粗糙感。结合实机观测与全量 UI 代码排查，定位到 **6 项根因**：

1. **单一宽度断点（`maxWidth >= 600.dp`）导致“竖屏挤、横屏空、矮屏裁”**：
   - 平板竖屏总宽 `~711 dp`，被左侧 `~80 dp` `NavigationRail` 占用后内容区仅剩 `~631 dp`，却仍触发 `isWideScreen = maxWidth >= 600.dp`，导致「今日」强行切 `0.46:0.54` 双窄列（单栏不足 `290 dp`）、「好友」强行切 `GridCells.Fixed(2)`，卡片挤压变形。
   - 手机横屏宽度常超 `600 dp` 但高度仅 `360–430 dp`，只判宽度不判高度会触发 7 项竖直 `NavigationRail`，底部「反馈 / 设置」被挤压或直接裁切；底部弹窗（`ModalBottomSheet`）几乎顶满屏幕难以操作。
2. **导航壳层与顶栏入口双重重复**：
   - `AdaptiveNavShell` 的左侧 `NavigationRail` 已放置「账号切换 / 反馈 / 设置」，但 `TodayScreen`、`TargetsScreen`、`TemplatesScreen`、`HistoryScreen` 各自顶栏仍原样重复渲染这三组全局按钮。
   - 平板横屏 `1138 dp` 宽幅下左侧仅保留单薄的 `80 dp` 细栏，未能发挥大屏导航效率。
3. **4 处 Compose 修饰符顺序写反导致宽屏限宽完全失效**：
   - `SimpleTodayScreen.kt`（`.fillMaxWidth().widthIn(max = 520.dp)`）、`SettingsScreen.kt`（`.fillMaxWidth().widthIn(max = 760.dp)`）、`OnboardingWizardScreen.kt`（`.fillMaxSize().widthIn(max = 640.dp)`）、`ScanReconcileSheet.kt`（`.fillMaxWidth().widthIn(max = 760.dp)`）均将 `fillMax*` 写在 `widthIn(max = ...)` 之前。
   - 在 Compose 约束传播规则下，外层 `fillMaxWidth` 先锁定 `minWidth = maxWidth`，内层 `widthIn(max = ...)` 被迫抬升至全屏宽度，导致**极简模式主按钮被横向拉成 `1090 dp` 巨型长条**、设置页与引导页单行内容横跨 11 寸整屏、扫描对账弹窗铺满整条底边。
4. **核心二级/内容页面缺少平板大屏结构与冗余留白**：
   - `TemplatesScreen` 与 `HistoryScreen` 在平板横屏下仅是手机单列居中，且硬编码 `bottom = flameDockBottomPadding()`（白白留出 `104 dp` 不存在的手机底栏空白）与 `SnackbarHost(bottom = 86.dp)`。
   - `TodayScreen` 平板横屏左栏仅有 Hero 卡 + 2 张矮 Bento 卡，下半屏空出约 1/3 纯黑/纯白死白区。
   - `TargetsScreen`、`SettingsScreen` 未采用大屏 `List-Detail / Master-Detail` 双栏；进设置页会隐藏侧栏并全屏反复进出跳转。
   - `LoginScreen` 原生扫码区无任何宽屏限宽或横屏分栏。
5. **极简模式在平板横/竖屏与手机横屏下体验割裂**：
   - 除 `widthIn(max = 520.dp)` 失效拉爆主按钮外，缺少横向 `safeDrawing` 避让（横屏挖孔/侧边栏无保护）；平板横屏与手机横屏强制垂直 `SpaceBetween` 导致横屏高空旷或手机矮屏直接溢屏滚屏；大屏字号、圆点尺寸与按钮比例未随大屏尺度升级；极简模式点进「续火好友」后同样继承宽屏 2 列过宽卡片与贴底弹窗。
6. **今日页 Hero 摘要栏状态色误判 Bug**：
   - `TodayScreen.kt` 中 `HeroLiveProgressStrip` 使用 `lastMsg.contains("失败")` / `lastMsg.contains("跳过")`，把完全成功的文案 `"今日续火完成：成功 15 / 跳过 0 / 失败 0"` 误判为 `hasErrorHint = true`，导致全成功态始终渲染暗红错误框并常驻展开诊断日志按钮。

---

## 2. 目标、非目标与红线（Goals / Non-Goals / Anti-Goals）

### Goals（本次必达）
1. **统一「宽 × 高」窗口尺寸分级底座**：纯 Compose 计算（不新增重量级依赖），基于窗口实际可用 `maxWidth × maxHeight` 实时响应旋转、平板分屏与自由多窗口，划分 4 档布局形态：`COMPACT_PORTRAIT`、`COMPACT_HEIGHT_LANDSCAPE`、`TABLET_PORTRAIT`、`TABLET_LANDSCAPE`。
2. **全端视觉质感升级（黑白碳黑 + 克制暖橙基因一致）**：
   - **平板横屏**：常驻展开式侧边栏（`220.dp`）+ 顶栏彻底去重 + 四大核心双栏工作台（今日、好友 List-Detail、模板画廊+实时预览、记录左图右明细、设置 Master-Detail）+ 居中圆角浮层。
   - **平板竖屏**：精致 `NavigationRail`（`76.dp`）+ 顶栏去重 + 解除逼仄双窄列，转为舒展单列与卡片内多列指标 + 清除 `104.dp` 假底栏留白 + 居中圆角浮层。
   - **手机横屏**：紧凑可滚动 `62.dp` 图标 Rail + 超薄顶栏 + 横向避让 `safeDrawing` + 矮屏两栏/紧凑构图 + 居中轻量浮层，确保一屏可读可点、无元素裁切。
   - **极简模式深度适配**：修复限宽反向 Bug；平板横屏与手机横屏采用黄金左右构图（左大数字与圆点进度流，右主行动控制台）；平板竖屏优化字号/圆点/按钮尺度与呼吸感；补全横屏 `safeDrawing` 避让；同步优化从极简模式进入好友页/设置页的返回体验。
3. **补齐全应用一致性细节**：修复全部 4 处 `fillMax*.widthIn` 顺序 Bug、修复 `HeroLiveProgressStrip` 的 `"失败 0 / 跳过 0"` 误红 Bug、适配 `LoginScreen` / `OnboardingWizardScreen` / `TemplateEditorScreen` / `AccountManagerSheet` / `ScanReconcileSheet` / `AppUpdateSheet` 的横屏与平板排版。
4. **可测与实机验证**：关键布局分类器、极简模式尺度策略、摘要严重度判定与导航状态规则具备 JVM 单元测试；通过隔离的 `demo` 构建变体（独立包名 `io.github.oinzc.flamecontractor.demo`）在真机平板上逐页检查横屏、竖屏、浅/深双主题，以及通过 `adb shell wm size` 临时模拟手机横屏核验后还原。

### Non-Goals（明确不做）
- **不改动** `backend/`、`frontend/`、`desktop/`、`.github/workflows/` 及任何非 `android/` 模块。
- **不改变** Room 数据库 Schema、迁移版本、ViewModel 业务状态机、后台调度、WebView 发送引擎与安全不变量。
- **不做**桌面端那种高密度多列数据表格（保留 Android 原生触控卡片与最小 `48dp` 触控热区）。
- **不引入** `androidx.compose.material3.adaptive` 外部 Alpha/新依赖（用纯 Kotlin + `BoxWithConstraints` + `CompositionLocal` 零依赖实现，更轻、完全可控且不冲击当前 Compose BOM 兼容与 APK 体积）。

### Anti-Goals（绝对不可接受的退化）
- **绝不破坏现有手机竖屏（`< 600.dp`）已打磨好的界面、动效、悬浮胶囊底栏与交互流**。
- **绝不违反业务安全边界**：`UNKNOWN_REQUIRES_REVIEW` 全路径禁止自动重发；扫描对账弹窗在任何形态下都不得因内容区滑动误关闭；强制更新（`isMandatoryUpdate`）与下载校验期间不得因点遮罩或返回键误关闭。
- **绝不在代码、测试、文档或提交中泄露任何真实设备型号、序列号、账号名、好友昵称或本机绝对路径**。

---

## 3. 备选方案对比（Approaches Considered）

| 方案 | 核心思路 | 取舍 | 决策 |
| --- | --- | --- | --- |
| **方案 A（选定）** | 宽×高四档自适应底座 + 平板横屏 220dp 展开侧栏 + 顶栏去重 + 好友/模板/记录/设置原生双栏 + 极简模式横屏左右构图 + 大屏居中圆角浮层 | 改动覆盖完整 UI 层，但与手机竖屏复用同一套 ViewModel 与业务组件，不新增依赖；平板横屏彻底脱离“放大版手机”，竖屏与手机横屏同步达标 | **已采用** |
| **方案 B** | 保留 80dp 窄 Rail 与贴底弹窗/全屏设置跳转，仅拆断点、去重顶栏、修限宽 Bug 与加 2 列网格 | 改动小、风险低；但平板横屏在好友、设置、记录等高频页依然频繁弹层跳页，大屏质感提升有限 | 放弃 |
| **方案 C** | 对标 Web 桌面端，采用密集多列数据表格 + 顶栏全局命令条 + 三栏常驻 | 信息密度最高；但纯触控误触率高、与手机端 UI 代码分叉大、削弱 Android 端克制清透气质 | 放弃 |

---

## 4. 详细架构与界面设计（Scheme A）

### 4.1 统一窗口尺寸分级底座（`ui/components/FlameAdaptiveLayout.kt`）

新增纯 Kotlin 布局分级模型与 `CompositionLocal`，在 `MainActivity` 顶层（以及独立 `LoginWebViewActivity` 顶层）通过 `BoxWithConstraints` 实时测量**窗口可用宽高**（而非仅读物理屏幕尺寸，确保平板分屏/小窗模式精准降级）并提供 `LocalFlameWindowLayout`：

```kotlin
enum class FlameFormFactor {
    /** 手机竖屏或平板极窄分屏：width < 600.dp */
    COMPACT_PORTRAIT,
    /** 手机横屏或极矮小窗：width >= 600.dp && height < 480.dp */
    COMPACT_HEIGHT_LANDSCAPE,
    /** 平板竖屏 / 展开折叠屏 / 中等分屏：600.dp <= width < 960.dp && height >= 480.dp */
    TABLET_PORTRAIT,
    /** 平板横屏 / 桌面宽窗：width >= 960.dp && height >= 480.dp */
    TABLET_LANDSCAPE,
}

enum class FlameNavChromeKind {
    BOTTOM_DOCK,          // COMPACT_PORTRAIT
    COMPACT_RAIL,         // COMPACT_HEIGHT_LANDSCAPE (62.dp 可滚动纯图标 Rail)
    NAVIGATION_RAIL,      // TABLET_PORTRAIT (76.dp 精致 Rail)
    PERMANENT_SIDEBAR,    // TABLET_LANDSCAPE (216.dp 常驻展开侧边栏)
}

@Immutable
data class FlameWindowLayout(
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val formFactor: FlameFormFactor,
    val navChromeKind: FlameNavChromeKind,
    val globalActionsInNavChrome: Boolean, // 非 BOTTOM_DOCK 且非极简/隐藏导航时为 true
    val useCenterDialogForSheets: Boolean, // 非 COMPACT_PORTRAIT 时为 true
    val horizontalGutterDp: Int,           // 16 / 20 / 24
)
```

**关键派生规则（写纯函数 `resolveFlameWindowLayout(widthDp, heightDp)` 并全覆盖单测）**：
- `widthDp < 600` → `COMPACT_PORTRAIT`（`BOTTOM_DOCK`，`globalActionsInNavChrome = false`，`useCenterDialogForSheets = false`，`horizontalGutterDp = 16`）
- `widthDp >= 600 && heightDp < 480` → `COMPACT_HEIGHT_LANDSCAPE`（`COMPACT_RAIL`，`globalActionsInNavChrome = true`，`useCenterDialogForSheets = true`，`horizontalGutterDp = 16`）
- `widthDp in 600..959 && heightDp >= 480` → `TABLET_PORTRAIT`（`NAVIGATION_RAIL`，`globalActionsInNavChrome = true`，`useCenterDialogForSheets = true`，`horizontalGutterDp = 20`）
- `widthDp >= 960 && heightDp >= 480` → `TABLET_LANDSCAPE`（`PERMANENT_SIDEBAR`，`globalActionsInNavChrome = true`，`useCenterDialogForSheets = true`，`horizontalGutterDp = 20`）
  - *几何依据*：`960 dp` 扣除 `216 dp` 展开侧边栏后内容区仍有 `743 dp`（`1138 dp` 典型平板横屏内容区高达 `921 dp`），而 `840..959 dp` 区间保持 `76 dp` `NAVIGATION_RAIL`（内容区 `763..882 dp`），所有大横屏均可舒适撑起双栏，杜绝“既占宽侧栏又逼窄内容区”的几何死角。

**内容区自适配门禁（以各页面内 `BoxWithConstraints` 实际 `maxWidth` 为准，绝不按死窗口宽盲切）**：
- `hasBottomFloatingDock`: `formFactor == COMPACT_PORTRAIT`（彻底消灭 `TemplatesScreen` / `HistoryScreen` 在平板与横屏下的 `104.dp` 假底栏留白和 `86.dp` 悬浮 Snackbar 偏移；统一由 `flameContentBottomPadding(hasBottomDock: Boolean)` 计算）。
- `Today / Targets / History` 双栏内容门禁：**内容区 `maxWidth >= 700.dp`**（平板横屏 `921 dp` 与标准手机横屏 `~770 dp` 稳定命中；平板竖屏 `~635 dp` 与极窄手机横屏 `~500–640 dp` 自动降级为舒展单列，绝不挤压）。
- `Templates` 双栏画廊+预览门禁：**内容区 `maxWidth >= 760.dp`**（右侧预览栏固定 `332.dp`，左栏 `>= 480.dp` 时 2 列画廊、否则自动单列宽卡）。
- `Settings` Master-Detail 双栏门禁：**内容区 `maxWidth >= 680.dp`**（左目录 `256.dp` / 矮屏 `224.dp`，确保右子页至少 `400+ dp`；低于 `680.dp` 自动回退两级全屏导航）。
- `isShortLandscape`: `formFactor == COMPACT_HEIGHT_LANDSCAPE`。

### 4.1.1 全应用滚动铁律：固定页面框架与工具栏 + 局部内容独立滚动

所有大屏/横屏页面（平板横屏、平板竖屏、手机横屏）严格遵守**「外壳与控制条静止、仅内容视口独立上下滑动」**原则，杜绝拖动手势带着搜索框/筛选芯片整页滚动或卡片硬撞顶栏裁切：
1. **顶栏与页面级控制条永远钉死不动**：包括页面标题栏、搜索框、筛选芯片行、预览代入对象条、以及独立卡片的头部（如「今日执行动态」头 + 筛选、「执行明细」头 + 筛选 + 搜索）。
2. **仅数据内容在圆角卡片/独立视口内部滚动**：列表/卡片内容放在 `Modifier.weight(1f)` 的独立滚动容器（`LazyColumn` / `LazyVerticalGrid` / `verticalScroll`）中，滚动边界被清晰裁切在容器内部。
3. **手机横屏矮屏高度预算优化**：在 `COMPACT_HEIGHT_LANDSCAPE`（`~393 dp` 高）下，将好友页与记录页的「搜索框 + 筛选芯片」**合并压缩为同一行紧凑工具栏（`~44 dp`）**固定在超薄顶栏下方，下方保留尽可能充裕的独立内容滚动高度。

---

### 4.2 导航壳层与顶栏去重（`AdaptiveNavShell.kt` & `FlameChrome.kt`）

#### 四档导航壳层行为
1. **`BOTTOM_DOCK`（手机竖屏）**：完全维持现有 `FlameFloatingPillDock`（`widthIn(max = 460.dp).fillMaxWidth()`），一像素不改。
2. **`COMPACT_RAIL`（手机横屏 `width >= 600, height < 480`）**：
   - 宽度 `62.dp`，外层加 `verticalScroll(rememberScrollState())` 兜底，图标紧凑无文字标签（带 `contentDescription` 无障碍标签），顶/中/底三组清晰间距；应用 `WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)` 避让横向刘海/挖孔与系统手势条。
   - 底部常驻「反馈」与「设置」图标；无裁切、单手拇指可触。
3. **`NAVIGATION_RAIL`（平板竖屏 `600..839 dp`）**：
   - 宽度 `76.dp`，精致圆角选中态指示器，顶部为账号切换方块（带在线态圆点与他号异常红点），中部 4 个主 Tab（图标+文字），底部固定「极简模式切换 / 反馈 / 设置」。
4. **`PERMANENT_SIDEBAR`（平板横屏 `>= 840 dp`）**：
   - 宽度 `220.dp`，`palette.surface` 表面 + 右侧 `1.dp` `palette.hairline` 细线，避让左侧与上下 `safeDrawing` insets：
     - **顶部账号卡**：圆角 `16.dp` `surfaceSubtle` 容器，展示当前账号昵称、状态徽标（健康/登录失效/执行中）、绑定的抖音昵称或账号总数，右上角他号告警红点；整卡点击打开账号切换浮层。
     - **中部主导航分组**（标签「工作区」）：`今日 / 好友 / 模板 / 记录` 横向图文行（圆角 `12.dp`，选中项 `surfaceSubtle` 底色 + `hairline` 描边 + 图标高亮），右侧按需展示语义信息（`今日` 异常/待复核徽标、`好友` 启用数量）。
     - **底部工具区**（顶部分隔线）：
       - 「切换极简模式」轻行（直接切入 `UiDisplayMode.SIMPLE`，一键可回）；
       - 「反馈与建议」克制暖橙微底胶囊行（`emberContainer` + `emberBorder`）；
       - 「设置与维护」行（处于设置页时高亮选中，右侧附当前版本短标）。

#### 设置页与壳层的联动规则（重要！）
- **手机竖屏 & 平板竖屏**：保持现有交互——点击「设置」全屏滑入 `SettingsRoute`（隐藏底部 Dock / Rail），通过顶栏返回按钮或系统返回键退出设置。
- **平板横屏 & 手机横屏（非极简模式）**：
  - 点击侧边栏「设置与维护」或「反馈与建议」时，**保留左侧导航壳层不隐藏**，右侧主画布平滑切换为 `SettingsRoute`（Master-Detail 双栏），此时侧边栏的「设置与维护」呈选中态；点击左侧任意主 Tab（今日/好友/模板/记录）可直接切回该 Tab 并关闭设置，点击系统返回键则仍按 `MainNavState.navigateBack` 优雅退出设置！
  - **极简模式下进入设置**：极简模式本身无主导航侧栏，进入设置时仍以全宽呈现（平板横屏与手机横屏内部仍享有 Master-Detail 双栏设置，顶栏左侧显示返回按钮返回极简主页）。

#### 顶栏全局去重规范
- 新增环境信号 `LocalFlameChromeHandlesGlobalNav`（由 `AdaptiveNavShell` 提供：当使用侧边栏/Rail 且 `!hideNavigationChrome` 时为 `true`；手机竖屏或极简模式隐藏主导航时为 `false`）。
- 4 个主页面顶栏（`FlameTopBar`）遵循同一铁律：
  - 当 `LocalFlameChromeHandlesGlobalNav.current == true` 时：**隐藏**顶栏里的账号切换胶囊、反馈按钮、设置齿轮，只保留本页标题、可选轻副标题/状态胶囊及本页专属动作（「好友」的 `扫描对账` / `新增`、「模板」的 `新建`、「记录」的 `导出 CSV`）。
  - 手机横屏下 `FlameTopBar` 自动切换紧凑内边距（`vertical = 6.dp`，标题字号降为 `titleLarge`），把宝贵的垂直高度留给内容区。

---

### 4.3 极简模式专项深度适配（`SimpleTodayScreen.kt`）

极简模式是平板和日常轻度使用的高频入口，本次做重点精修：

1. **先彻底修复限宽反向 Bug 与横屏安全区**：
   - 将原 `.fillMaxWidth().widthIn(max = 520.dp)` 改为正确的 `.widthIn(max = ...).fillMaxWidth()`。
   - 顶栏与主体均应用 `WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)` 避让横向刘海、挖孔与侧边手势区。
2. **四形态差异化排版**：
   - **`COMPACT_PORTRAIT`（手机竖屏）**：100% 保持当前视觉与排版节奏（`widthIn(max = 460.dp)`、`68.sp` 主数字、`8–9.dp` 圆点、底部胶囊主按钮与轻底链）。
   - **`TABLET_PORTRAIT`（平板竖屏 `711×1138 dp`）**：
     - 保留极简纯粹的居中排版数字流，但按平板尺度升级比例：内容容器 `.widthIn(max = 560.dp).fillMaxWidth()`；顶栏内边距放大到 `horizontal = 28.dp, vertical = 16.dp`（增加账号状态说明微文字，右上增加轻量「反馈」图标与「设置」圆钮，不再空荡）；
     - 主数字放大至 `84.sp`（高字体缩放时降至 `64.sp`）、分母 `34.sp`；圆点尺寸升级为 `11.dp`（单行）/ `10.dp`（多行）、间距 `9.dp`；
     - 底部主行动按钮不再横跨整屏，使用 `.widthIn(max = 400.dp).fillMaxWidth().heightIn(min = 58.dp)` 优雅居中；底部次入口（「续火好友」「切换标准模式」）加大热区与可读字号（`bodyMedium`）。
   - **`TABLET_LANDSCAPE`（平板横屏 `1138×711 dp`）**：
     - 放弃垂直单列 `SpaceBetween`，采用**居中黄金左右双栏构图**（整体容器 `.widthIn(max = 960.dp).fillMaxWidth()`，左右间距 `40.dp`，垂直光学居中，支持内容超出时柔和滚动）：
       - **左栏（`weight(1.12f)`，左对齐/舒展展示）**：顶部语义眉标胶囊 + `92.sp` 超大等宽主数字与 `36.sp` 分母（或引导态大标题） + 状态副标题 + 升级版均衡圆点矩阵（`11–12.dp` 圆点，可点进好友列表） + 单行定时排程状态入口（点击直达定时设置）。
       - **右栏（`weight(0.88f)`，操作控制台卡）**：采用一张精致的 `FlameCard`（带微弱暖橙径向微光背景 `cornerRadius = 24.dp`），内含：
         1. 当前行动态简要说明（或异常/执行实时提示卡 `SimpleInlineCallout`，不再打散居中节奏）；
         2. 高对比大号主按钮（`SimpleBottomPrimaryButton`，高度 `58.dp`，铺满右卡内宽约 `320–360.dp` 刚刚好）；
         3. 底部并排两枚柔和胶囊快捷按钮：「续火好友 (N)」与「切回标准模式」（外加异常态按需露出的「重新登录」），点击面积充足，右手单手即可触达。
     - 顶栏增加轻克制的「反馈」图标按钮与「设置」圆钮，右上视觉平衡。
   - **`COMPACT_HEIGHT_LANDSCAPE`（手机横屏 `~873×393 dp`）**：
     - 采用**紧凑左右双栏构图**（容器 `.widthIn(max = 780.dp).fillMaxWidth()`，水平间距 `20.dp`，顶栏压薄至 `vertical = 6.dp`）：
       - 左栏（`weight(1.05f)`）：紧凑眉标 + `56.sp` 主数字 / `24.sp` 分母 + `8.dp` 紧凑圆点阵 + 排程行；
       - 右栏（`weight(0.95f)`）：精简操作卡（异常/执行提示 + `50.dp` 主按钮 + 紧凑双快捷入口）。
     - 目标：**在 `393 dp` 矮屏下首屏 100% 一眼看全数字、进度、主按钮与切换入口，无需滚动！**
3. **极简模式内二级流衔接**：
   - 从极简模式点「续火好友」进入 `TargetsScreen(onBack = ...)` 时，在平板横屏下同样享受 **List-Detail 双栏**，顶栏带清晰返回按钮回到极简主页；点「设置」进入 `SettingsRoute` 时，在平板横屏与手机横屏下同样享受 **Master-Detail 双栏**。

---

### 4.4 四个标准主页面重构

#### 1. 今日页（`TodayScreen.kt`）
- **先修复 `HeroLiveProgressStrip` 误红 Bug**：
  - 提取纯函数 `resolveHeroLiveStripTone(executionState: ExecutionLiveState): FlameTone`（并补单测）：
    - `running == true` → `FlameTone.EMBER`
    - 有明确 `lastErrorSummary`、或 `lastMessage` 包含非零失败数（正则 `失败\s*[1-9]\d*`）或明确异常关键词（`"异常"` / `"未明"` / `"超时"` / `"中断"` / `"拦截"`，且排除 `"失败 0"`）→ `FlameTone.DANGER`
    - 有明确 `lastSkipSummary`、或 `lastMessage` 包含非零跳过数（正则 `跳过\s*[1-9]\d*`）或 `"未绑定"` → `FlameTone.WARNING`
    - 其余（例如 `"今日续火完成：成功 15 / 跳过 0 / 失败 0"`）→ `FlameTone.HEALTHY`（清透翠绿轻摘要条，**不展开**大块诊断/反馈按钮；仅在 `DANGER` 或 `WARNING` 时才展开快捷诊断与一键带日志反馈按钮！）。
- **四形态布局**：
  - `COMPACT_PORTRAIT`：完全维持现状。
  - `TABLET_PORTRAIT`（`711×1138 dp`）：**解除 600dp 强切双窄列！** 遵循「固定框架 + 独立滚动」原则：上方固定 Hero 主卡 + **3 列等宽横向 Bento 指标卡**（「今日已完成」+「最高官方火花」+「排程与保活就绪」），下方「今日执行动态」整卡撑满剩余高度（`weight(1f)`），卡片头与轻量筛选胶囊固定不动，**仅动态条目在卡片内部上下滚动**（如屏幕高度不足 `780 dp` 时则优雅回退整页滚动兜底）。
  - `TABLET_LANDSCAPE` / 内容区 `>= 700.dp`：`44% : 56%` 左右双栏（间距 `20.dp`）：
    - **左栏**：Hero 主卡 + 2 列 Bento 指标卡 + **新增「今日排程与运行就绪」摘要卡**（展示权限就绪进度 `X/7`、下次自动续火时刻、自动扫描周期与「管理设置」快捷入口，彻底填实下半屏视觉重心，不再留 1/3 黑洞！）；有紧急告警时置顶，超出高度时仅左栏独立柔和滚动。
    - **右栏**：「今日执行动态」整卡占满右栏，卡片头自带轻量筛选胶囊（「全部 / 待处理 / 已确认」）固定不动，仅内部列表独立滚动，点击条目弹出居中时间线浮层。
  - `COMPACT_HEIGHT_LANDSCAPE`（矮屏）：内容区 `>= 700.dp` 时 `45% : 55%` 紧凑左右双栏（左栏独立微滚、右栏卡片头固定+内部列表独立滚动）；`< 700.dp` 回退紧凑单列。

#### 2. 好友页（`TargetsScreen.kt`）
- **固定工具栏规范**：在所有大屏/横屏形态下，顶栏 + 搜索框 + 筛选芯片行固定不动（`COMPACT_HEIGHT_LANDSCAPE` 下搜索框与筛选芯片合并为同一行紧凑工具栏 `~44.dp`），下方列表区使用 `Modifier.weight(1f)` 独立滚动，绝不带走筛选条！
- **`COMPACT_PORTRAIT`**：完全维持现状。
- **`TABLET_PORTRAIT`**（内容区 `< 700.dp`）：**解除 `GridCells.Fixed(2)` 挤压！** 改为舒展单列列表卡片（内部独立滚动），点击好友弹出居中圆角 `TargetActionDetailSheet` 浮层。
- **宽屏双栏（内容区 `>= 700.dp`，覆盖平板横屏与标准横屏）— List-Detail 双栏**：
  - 主区为 `44% : 56%` 双栏（间距 `16.dp`）：
    - **左栏**：圆角卡片容器包裹的好友与群聊独立滚动列表，选中项带 `surfaceSubtle` 底色与左侧 `3.dp` `ember` 细竖条指示；列表非空且当前选中 ID 不在筛选结果中时自动回落选中首项。
    - **右栏**：常驻**「好友详情与快捷操作面板」**——**严格守住 Non-Goal（不改 `TargetsViewModel` 状态机）**：抽取现有 `TargetActionDetailSheet` 的核心主体为共享 Composable `TargetActionDetailContent`，在右栏卡片内独立滚动展示稳定平台身份、抖音官方火花快照 vs 本机累计、绑定模板与发送时刻摘要、**即时启用开关**、**立即单发测试**、**编辑好友与模板**（点击后通过居中圆角 `TargetEditorSheet` 浮层完成草稿编辑与 `HH:mm` 校验保存）、**单点身份修复**与**移出续火列表**。点左侧谁、右侧秒切！
    - 列表为空时居中展示空态卡。
- **`COMPACT_HEIGHT_LANDSCAPE`**（若内容区 `< 700.dp`）：顶部单行合并工具栏固定，下方独立滚动单列/双列紧凑卡片，点击弹出居中详情浮层。

#### 3. 模板页（`TemplatesScreen.kt`）
- **固定工具栏与清除假底栏留白**：顶栏 + 「只读预览代入卡」固定不动，仅下方模板区独立滚动；`contentPadding.bottom` 与 `SnackbarHost` 改用 `flameContentBottomPadding(layout.hasBottomFloatingDock)`。
- **`COMPACT_PORTRAIT`**：维持现状。
- **`TABLET_PORTRAIT`**：顶部「只读预览代入卡」固定，下方独立滚动区中 4 类模板分组内卡片采用 **2 列等宽网格**，点击进入模板编辑器。
- **宽屏双栏（内容区 `>= 760.dp`，平板横屏）— 画廊 + 右侧实时预览面板**：
  - 主区 `1fr : 332.dp` 双栏（间距 `16.dp`）：
    - **左栏（独立滚动）**：缺图高优警告条（如有）+ 按 4 类分组的模板画廊（左栏可用宽 `>= 480.dp` 时 2 列卡片，否则自动单列宽卡；选中态高亮 `ember` 描边）。
    - **右栏（头部固定 + 内部独立滚动）**：常驻**「实时气泡预览与快捷操作面板」**——顶部可直接切换代入预览对象，中间模拟聊天气泡实时渲染选中模板代入 `{name}/{days}` / 图片缩略图 / 原生表情贴纸，文案池列出轮换条目摘要，底部固定「编辑模板」「启用开关」「删除」按钮。
- **`COMPACT_HEIGHT_LANDSCAPE`**：顶栏压缩 + 预览条固定，下方独立滚动 2 列紧凑画廊（`< 600.dp` 降为单列）。

#### 4. 记录页（`HistoryScreen.kt`）
- **清除假底栏留白**：改用 `flameContentBottomPadding(layout.hasBottomFloatingDock)`。
- **`COMPACT_PORTRAIT`**：维持现状。
- **`TABLET_PORTRAIT`**（内容区 `< 700.dp`）：遵循固定框架规范——上方固定「时间窗分段器 + 3 列 Bento KPI + 全宽 30 日堆叠柱状图」，下方「执行明细」卡片占满剩余高度（`weight(1f)`），卡片头部（标题 + 状态筛选 + 搜索）固定不动，**仅明细条目在卡片内独立滚动**（高屏 `>= 820.dp` 时固定上方图表区，矮屏回退整体滚动兜底）。
- **宽屏双栏（内容区 `>= 700.dp`，平板横屏 & 标准手机横屏）— Analytics 双栏仪表盘**：
  - 主区 `45% : 55%` 左右双栏（间距 `16.dp`）：
    - **左栏（独立微滚）**：时间窗切换芯片 + 区间说明 + 3 列 KPI Bento（左栏 `< 340.dp` 时自动收紧字号防折行）+ 每日发送趋势堆叠柱状图卡（手机横屏下柱图高度收紧至 `100.dp`）。**点击左侧任意一天柱子，右侧执行明细原地实时高亮过滤，图表永远在视野中！**
    - **右栏（卡片头固定 + 列表独立滚动）**：「执行明细」外框占满右栏，头部标题 + 展开控制 + 状态筛选芯片 + 搜索胶囊（手机横屏下状态筛选与搜索压缩合并单行）固定在卡顶不动，下方按日分组明细列表在卡片内独立滚动，点击弹出居中时间线与截图浮层。

---

### 4.5 设置中心 Master-Detail 双栏（`SettingsScreen.kt` & `SettingsHubScreen.kt`）

- **先修复限宽 Bug**：`SettingsPageScrollColumn` 改为 `.widthIn(max = 760.dp).fillMaxWidth()`（并在双栏右窗格中自动撑满右窗格宽裕空间，不二次限窄）。
- **单栏模式（`COMPACT_PORTRAIT` 或内容区 `< 680.dp`）**：维持两级导航（目录页 → 子页），平板竖屏下外边距扩至 `20.dp`、内容优雅限宽 `680.dp` 居中、外观页主题/小组件卡片采用 2 列整齐排布。
- **双栏 Master-Detail 模式（内容区 `>= 680.dp`，覆盖平板横屏与标准手机横屏）**：
  - 顶栏固定不动，下方左右双栏容器各自独立滚动（右窗格头部标题条固定、仅设置内容滚动）。
  - 当进入设置时，若 `activeSection == null`（例如点击侧栏「设置与维护」），双栏模式下自动选中高亮 `SettingsSection.DIAGNOSTICS`（若 `DOM_BROKEN`）或 `SettingsSection.SURVIVAL`（若核心权限未就绪）或 `SettingsSection.APPEARANCE`（常规默认），绝不出现空白右栏！
  - **左栏（目录窗格）**：平板横屏宽 `256.dp`（手机横屏 `224.dp`），展示紧凑分组目录（8 个 `SettingsSection` + 「新手引导」触发行 + 极简模式快捷切回卡 + `DOM_BROKEN` 告警条），当前选中项带柔和底色与 `ember` 标识；点击左侧任意一项，右侧瞬间切换（保留外层侧边栏不隐藏、无整页闪烁跳转）。
  - **右栏（详情窗格）**：直接嵌入 8 个既有设置子页的内容（为 `FlameSubpageScaffold` 增加 `embeddedInSplitPane: Boolean = false` 参数：为 `true` 时，隐藏系统返回箭头条、改用固定窗格头部，且不挂载覆盖外层导航的 `BackHandler`！）。
  - **特殊处理**：若从极简模式进设置（无主导航侧栏），双栏设置的顶栏左侧展示返回按钮，点击返回极简主页；标准模式下侧边栏点击任意主 Tab 直接关闭设置切回该 Tab，系统返回键关闭设置回到当前主 Tab。

---

### 4.6 统一大屏自适应弹层（`FlameModalSheet` 与专项 Sheet）

1. **`FlameModalSheet`（`FlameBadgesAndInputs.kt`）自动双形态**：
   - 读取 `LocalFlameWindowLayout.current.useCenterDialogForSheets`：
   - **`false`（手机竖屏）**：100% 保持现有 `ModalBottomSheet` 行为与样式。
   - **`true`（平板横/竖屏 & 手机横屏）**：自动切换为**圆角居中浮层 `Dialog`**（`DialogProperties(usePlatformDefaultWidth = false)`）：
     - 全屏居中，半透明遮罩（点遮罩关闭，受 `dismissOnClickOutside` 控制），卡片形态为 `RoundedCornerShape(24.dp)`、`palette.surface` 底色 + `1.dp` `palette.hairlineStrong` 细描边 + 轻阴影；
     - 宽度约束 `.padding(horizontal = 24.dp, vertical = 20.dp).widthIn(max = maxDialogWidth).fillMaxWidth()`（默认 `560.dp`，宽内容弹窗可传入 `680.dp`）；
     - 高度约束 `.heightIn(max = (screenHeightDp * 0.86f).dp)`，内容区支持垂直滚动 + `imePadding()`；
     - 顶部取消“底部拖拽短横条”，改为干净的标题行 + 右上角圆形 `×` 关闭按钮（当允许关闭时）。
   - **收益**：`AccountManagerSheet`、`AppUpdateSheet`、`TaskTimelineSheet`、`UnknownReviewSheet`、`ForceAllConfirmSheet`、`ReloginChoiceSheet`、`CooldownDetailSheet`、`TargetEditorSheet` 等所有复用 `FlameModalSheet` 的弹层**一次性全部升级为优雅的大屏居中浮层**，同时继续完整保留 `AppUpdateSheet` 的强制更新不可关闭拦截（补充 `dismissOnClickOutside` / `dismissOnBackPress` 参数给 `FlameModalSheet` 显式透传，彻底加固强制更新安全约束）！
2. **`ScanReconcileSheet`（自定义防误滑窗体）**：
   - 先修复 `.fillMaxWidth().widthIn(max = 760.dp)` 的反向顺序 Bug。
   - 手机竖屏：保持现有底部贴边弹窗 + 仅顶部横条可下拉关闭的防误滑逻辑完全不变。
   - 平板横/竖屏 & 手机横屏：切换为**居中圆角大浮层**（`.padding(20.dp).widthIn(max = 780.dp).fillMaxWidth()`，全四角 `24.dp` 圆角，无下拉关闭手势，仅通过标题右上角关闭按钮、底栏「关闭」或系统返回键关闭；导入进行中锁死防误关），彻底兼具防误滑与大屏优雅度！

---

### 4.7 独立全屏流程适配（登录页、新手向导、模板编辑器）

1. **原生扫码登录页（`LoginScreen.kt`）**：
   - 在 `LoginWebViewActivity` 顶层同样注入 `FlameWindowLayout`。
   - 平板横屏 & 手机横屏下的 `NativeQrWorkspace` 改为**居中左右双栏**（`.widthIn(max = 880.dp).fillMaxWidth()`）：左栏展示二维码舞台与倒计时/刷新状态卡，右栏展示「一键跳转抖音确认」「存码去扫一扫」「短信/网页登录」与后台保活提示（手机横屏适当缩小二维码边长至 `168.dp` 确保一屏可见无需滚屏）。
   - 平板竖屏：居中单列 `.widthIn(max = 520.dp).fillMaxWidth()`，不再拉爆全宽。
2. **7 步新手向导（`OnboardingWizardScreen.kt`）**：
   - 修复 `.fillMaxSize().widthIn(max = 640.dp)` 反向 Bug，改为外层居中 `Box` + 内层 `.widthIn(max = 680.dp).fillMaxWidth()`；顶栏步骤条与底栏操作按钮在大屏下同样采用对应 `widthIn` 居中对齐并避让横向 `safeDrawing`。
   - 平板横屏下，第 7 步「选择首页模式（极简模式 / 标准模式）」的两张模式对比卡改为**左右并排双列**对比展示，一目了然；手机横屏压缩顶部步骤头与底部按钮间距确保首屏不挤。
3. **全屏模板编辑器（`TemplateEditorScreen.kt`）**：
   - 已有正确顺序 `.widthIn(max = 680.dp).fillMaxWidth()`；平板横屏下可扩宽至 `.widthIn(max = 820.dp)` 并让「基本信息」与「内容/预览」在大屏下布局舒展，同时避让横向 `safeDrawing`。

---

## 5. 测试与真机验收策略

### 5.1 自动化单测（JVM Unit Tests）
1. **`FlameAdaptiveLayoutTest.kt`**：覆盖 `resolveFlameWindowLayout(widthDp, heightDp)` 全部边界值（`360×800`、`599×900`、`600×390`、`873×393`、`960×479`、`600×960`、`711×1138`、`959×1138`、`960×480`、`1138×711`、`1280×800`），验证四档 `formFactor`、`navChromeKind`、`globalActionsInNavChrome`、`useCenterDialogForSheets`、`hasBottomFloatingDock` 100% 精确。
2. **`TodayHeroStripToneTest.kt`**：覆盖 `resolveHeroLiveStripTone` 全部文案边界——特别是 `"今日续火完成：成功 15 / 跳过 0 / 失败 0"` 必须返回 `HEALTHY` 而非 `DANGER`/`WARNING`；`"成功 14 / 跳过 1 / 失败 0"` 返回 `WARNING`；`"成功 13 / 跳过 0 / 失败 2"` 返回 `DANGER`；含 `"异常"`/`"未明"`/`lastErrorSummary` 返回 `DANGER`。
3. **`SimpleTodayActionStateTest.kt` 扩展**：补充极简模式圆点与宽屏/矮屏尺度纯函数单测。
4. **`MainNavigationStateTest.kt` 扩展**：验证双栏设置模式下默认子页解析、切 Tab 自动关闭设置、以及返回栈行为不破坏手机端现有单测。
5. **全量回归门禁**：`gradlew verifySelectorBundleSync :app:testDebugUnitTest :app:lintDebug :app:assembleDemo`。

### 5.2 真机平板隔离 `demo` 变体视觉验收
- 使用仓库内置的隔离变体 **`:app:installDemo`**（包名 `io.github.oinzc.flamecontractor.demo`，虚构演示数据，零触碰正式版真实账号、登录态与闹钟）在已连接平板上实机安装并逐项截图自查：
  1. **平板横屏（深色 + 浅色）**：今日（标准）、好友（List-Detail 切换 + 扫描对账居中浮层）、模板（画廊+实时预览）、记录（双栏柱图联动）、设置（Master-Detail 切子页）、极简模式（左右黄金构图）、账号切换浮层。
  2. **平板竖屏（深色 + 浅色）**：今日（舒展单列 + 3 列指标）、好友（舒展单列 + 居中详情浮层）、模板、记录、设置、极简模式（大屏比例居中）。
  3. **手机横屏模拟**：临时通过 `adb shell wm size` / `wm density` 在 `demo` 变体下模拟手机横屏（如 `~873×393 dp`），核验紧凑可滚动 Rail、极简模式一屏不滚动、今日/好友/设置无裁切，验收后**立即 `wm size reset` & `wm density reset` 恢复平板原生参数**。
  4. **手机竖屏回归**：同样模拟手机竖屏（`~411×915 dp`）确认悬浮底栏、页面与 BottomSheet 零视觉退化后恢复。

---

## 6. 留给实施者的合理裁量项（What's Left to the Builder）

- 各卡片内部的细微 `dp` 间距、圆角微调与动画时长（保持遵循 `FlameMotionSpec` 与 `rememberReducedMotion()`）。
- `TargetsScreen`、`TemplatesScreen`、`SettingsScreen` 内部拆分私有 Composable 的文件组织方式（避免单文件继续过度膨胀，可按需提取 `*Pane` / `*Content` 辅助文件）。
- `TodayScreen` 新增的「今日排程与运行就绪」摘要卡具体复用哪些 `TodayUiState` + `SetupReadinessSnapshot` 字段组合，保证不增加额外数据库查询或后台副作用。
