# 无障碍能力丰富化调研：对标 AutoX.js（Autoxjs_v6_ozobi-65823）

> 调研日期：2026-11-02
> 目标：认真借鉴 autox 项目，全面对比咱们 Bridge 客户端现有的「简陋无障碍」（仅锁屏 + 静默截屏），
> 评估「如何丰富无障碍功能 / 集成-吞并 autox 能力」的可行路径与落地优先级。
> 权威源码参照：`references/Autoxjs_v6_ozobi-65823/`（AutoX v6.5.8 魔改版）。
>
> **决策更新（2026-11-02）**：① 不需要 APK 打包器；② **不考虑 GPL v2 / 侵权**（测试性质、只研究），
> **可直接借鉴/移植 autox 源码**；③ 已做耗电评估（见 §二）。⇒ **方案 B（直接移植 automator 模块）升为推荐**，
> 无需为规避 GPL 而重写。

---

## 一、结论先行（TL;DR）

| 维度 | 结论 |
|------|------|
| **差距本质** | autox 是「无障碍自动化**引擎**」，咱们 Bridge 只是「无障碍**两个动作**」。差距不在技巧，而在**架构层**：autox 有完整的「控件树读取 → 选择器 → 节点/手势/按键注入 → 事件监听」四层，Bridge 只用了最底层的两个 `performGlobalAction`。 |
| **能否吞并** | 不能也无必要「整体吞并」（autox = 脚本引擎 + IDE + 打包器 + OCR，体量巨大且 GPL v2 传染）。**正确姿势是「吞并其无障碍核心，而非吞并其产品」**：把 `automator` 模块的控件树/选择器/动作/手势能力吸收进 Bridge，复用咱们已有的命令通道暴露出去。 |
| **最高价值落点** | ① 开启 `canRetrieveWindowContent` + `canPerformGestures`（零代码，改一个 XML）；② 移植 automator 的 `UiObject`/`UiGlobalSelector`（约 2 千行，可裁剪）；③ 新命令：`ui_find`（读控件树/查找）、`ui_click`/`ui_set_text`（控件级操作）、`ui_gesture`（手势注入）。 |
| **最大风险** | ① **无障碍能力会「唤醒」隐私红线**：一旦 `canRetrieveWindowContent=true`，系统会向用户明示「此服务可读取屏幕内容」，授权门槛变高；② GPL 传染需隔离；③ 无障碍在部分 ROM 上易被杀、需结合咱们已有保活。 |

---

## 二、双方现状对比（逐层拆解）

### 2.1 咱们 Bridge 当前无障碍（`app/.../access/BridgeAccessibilityService.kt` + `res/xml/accessibility_service_config.xml`）

| 能力 | 状态 |
|------|------|
| 服务声明 | `BridgeAccessibilityService`（单服务） |
| **窗口内容读取** | `canRetrieveWindowContent = false`（**刻意关闭**，最小权限） |
| 手势注入 | 未声明 `canPerformGestures` |
| 按键过滤 | 未声明 `flagRequestFilterKeyEvents` |
| 事件订阅 | 仅 `typeWindowStateChanged`（占位，`onAccessibilityEvent = Unit`） |
| 截图 | `canTakeScreenshot=true`（takeScreenshot，API 30+） |
| 已实现功能 | 仅 2 个：`lockScreen()`（GLOBAL_ACTION_LOCK_SCREEN）、`takeScreenshotToFile()`（静默截屏） |
| 实例管理 | 静态 `instance` + `isReady()`，`onServiceConnected/onUnbind/onDestroy` 维护 |

**定位**：这是刻意的「纯动作型服务」，注释里明写「不订阅窗口内容，最小权限原则」。设计目标是无感、低门槛授权，只做了锁屏 + 截屏两个「顺手」能力。

### 2.2 autox 无障碍（`automator` 模块 + `core/accessibility` + `res/xml/accessibility_service_config.xml`）

DeepWiki 与源码实锤的 autox 无障碍服务配置：

```xml
<!-- autox accessibility_service_config.xml 关键项 -->
android:accessibilityEventTypes="typeAllMask"           <!-- 全事件 -->
android:accessibilityFlags="flagRequestFilterKeyEvents" <!-- 过滤按键 -->
android:canRetrieveWindowContent="true"                  <!-- 读控件树 ★ -->
android:canPerformGestures="true"                        <!-- 手势注入 ★ -->
```

| 层 | 关键类 | 职责 |
|----|--------|------|
| **服务层** | `automator/.../view/accessibility/AccessibilityService.kt`（170 行） | 绑定管理、事件分发（TreeMap 委托）、**按键观察 `onKeyEvent` + 手势 `onGesture`**、快速缓存 `fastRootInActiveWindow`、`waitForEnabled` 阻塞等待、`disableSelf` |
| **桥接层** | `core/accessibility/AccessibilityBridge.java`（150 行） | 抽象服务获取、`getRootInActiveWindow`/`windowRoots`（多窗口过滤）、MODE_NORMAL/FAST 切换、ActivityInfoProvider |
| **节点模型** | `automator/.../UiObject.kt`（414 行） | 包装 `AccessibilityNodeInfoCompat`：`parent/child/children` 遍历 + **30+ 动作**（click/longClick/setText/scroll*/setSelection/setProgress/expand/collapse/dismiss/copy/paste/...）+ 20+ 属性（text/desc/id/className/bounds/checkable/clickable/scrollable/...） |
| **选择器** | `automator/.../UiGlobalSelector.kt`（448 行）+ `core/accessibility/UiSelector.java`（430 行） | 流式 DSL：`id/text/desc/className/packageName/bounds`（各带 equals/contains/startsWith/matches 等）+ `-able` 布尔过滤 + int 过滤（depth/row/column）+ `findOne/findOnce/find/untilFind/exists`（阻塞/超时语义），BFS/DFS 算法 |
| **全局动作** | `automator/.../GlobalActionAutomator.kt`（300 行） | `performGlobalAction` 全集（back/home/recents/notifications/quickSettings/lockScreen/takeScreenshot/splitScreen/...）+ **手势 `dispatchGesture`**（click/swipe/press/longClick/gesture，多指 + Path + 阻塞/异步） |
| **事件系统** | `runtime/api/Events.java` | key 拦截（KeyInterceptor）、touch、toast、通知监听 |
| **脚本层** | `engine/RhinoJavaScriptEngine.kt` | Rhino 1.7.13 JS 引擎，把上面全部能力暴露为 JS API |

### 2.3 差距小结

autox 拥有而 Bridge 缺失的**无障碍能力**，按「对 Bridge 的价值」排序：

1. **控件树读取**（`canRetrieveWindowContent=true`）—— 所有 `find*` 的前提，也是价值最大的增量。
2. **节点级操作**（`UiObject` 的 click/setText/scroll 等）—— 精确、分辨率无关、比坐标注入可靠。
3. **选择器 DSL**（`UiGlobalSelector`）—— 让「远程找控件」成为可能（配合后台下发）。
4. **手势注入**（`dispatchGesture`）—— 点击/滑动/长按/多指，坐标级兜底。
5. **按键注入/拦截**（`onKeyEvent` + KeyInterceptor）—— 物理键模拟、监听（咱们已有蓝牙媒体键，可扩展）。
6. **事件监听**（窗口切换、toast、通知）—— 场景触发。

---

## 三、耗电评估（2026-11-02 补，用户关切）

**结论：无障碍能力本身「几乎不增加耗电」；耗电由「拉取频率」决定，不由「能力声明」决定。**

| 能力 | 耗电影响 | 说明 |
|------|---------|------|
| `canRetrieveWindowContent=true` | ≈0 | 仅权限声明，不主动 `getRootInActiveWindow()` 就一次树都不拉 |
| `canPerformGestures=true` | ≈0 | 仅声明，只有 `dispatchGesture()` 调用那一刻执行 |
| 订阅低频事件（`typeWindowStateChanged`） | 极低 | 窗口切换才触发，用于缓存根节点（autox `fastRootInActiveWindow`） |
| 按命令拉树（`ui_find`/`ui_dump`） | 按次可忽略 | 每次几十毫秒，一天几十次趋近 0 |
| ⚠️ 订阅高频事件（`typeViewScrolled`/`typeWindowContentChanged`） | 显著 | 别人 App 滚动会频繁唤醒 Bridge 进程，唯一隐性耗电源 |
| ⚠️ 常驻轮询控件树（`findOne` 无限每 50ms） | 显著 | autox 脚本耗电大户，但属脚本行为非能力本身 |

**红线：① 事件订阅仅限窗口切换级低频事件（不订阅滚动/内容变化）；② 一切控件树拉取「按命令触发」，零常驻轮询。** 在此约束下，无障碍增量耗电与现有 60s 心跳 / SilentPlayer 20ms 写音频 / PARTIAL_WAKE_LOCK 常驻相比，不在一个量级。

---

## 三、三个落地方案对比

### 方案 A：最小增量 —— 只开能力 + 移植动作层（推荐起步）

**做法**：
1. 改 `accessibility_service_config.xml`：`canRetrieveWindowContent=true`、`canPerformGestures=true`、补 `flagRequestFilterKeyEvents`（按需）。
2. 在 `BridgeAccessibilityService` 内新增：`getRootInActiveWindow()`（读根节点）、`dispatchGesture` 封装（click/swipe/press/longClick）、按键回调。
3. 移植/裁剪 `UiObject` + `UiGlobalSelector` 到 `core` 或 app 的 `access/` 包（去 `AccessibilityNodeInfoAllocator`、去 `UiObjectCollection` 可简化）。
4. 新增命令：`ui_find`（按选择器返回匹配节点 JSON）、`ui_click`（按选择器/坐标点击）、`ui_set_text`、`ui_gesture`（坐标滑动/手势）。

**优点**：零外部依赖、复用现有命令通道（`command_catalog.py` + `CommandModule`）、风险可控、GPL 不传染（代码是重写/裁剪）。
**缺点**：需要手动维护节点树解析与选择器；功能是「命令级」而非「脚本级」。

### 方案 B：移植 automator 模块（接近吞并）★ 当前推荐（在「不管协议、只研究」前提下）

**做法**：把整个 `automator`（`com.stardust.automator` + `com.stardust.view.accessibility`）作为独立 Gradle 模块或源码包搬进 Bridge，暴露 Kotlin API，再由命令层调用。

**优点**：
- 能力完整（选择器 + 全部动作 + 手势 + 事件），后续想加什么都有。
- 无需重写，直接复制源码（测试性质、不管 GPL）。
- 依赖的 `com.stardust.*` 工具类（`ScreenMetrics`、`AccessibilityNodeInfoAllocator`、`UiObjectCollection`、`EventDispatcher` 等）可一并移植或裁剪。

### 方案 C：整体集成 autox（吞并产品）

**做法**：内嵌 autox 的 Rhino 脚本引擎 + 运行时，让 Bridge 成为「远程可控的 AutoX.js」。

**缺点**（不推荐）：
- Rhino 引擎 + IDE + 打包器 + OCR（PaddleOCR/ML Kit/Tesseract）+ OpenCV，依赖链爆炸，APK 从 40MB 膨胀到 150MB+。
- GPL v2 强制传染整个 APK（开源），与 Bridge 企业级闭源定位冲突。
- 安全面：等于在设备上跑任意脚本，与「远程管控 App」的定位有根本性的安全张力。
- 咱们是「命令驱动」而非「脚本驱动」，脚本引擎是额外的复杂度。

**结论**：方案 C 仅当未来明确要「脚本化自动化」时才考虑，且建议用独立进程/独立 App 隔离，而非塞进 Bridge。

---

## 四、实施方案（已拍板：方案 B，直接移植 automator）

> 本节为**照做实施**的权威清单。决策前提：不管 GPL、可直接借鉴源码、只研究测试、不碰打包器。

### 4.0 实施总览（四步）

| 步骤 | 内容 | 产物 |
|------|------|------|
| S1 | 改 XML 开能力 | `res/xml/accessibility_service_config.xml` + 字符串 |
| S2 | 移植 automator 动作层（新建包 `my.bridge.com.access.automator`） | `UiObject` / `UiGlobalSelector` / `GlobalActionAutomator` / `filter/*` / `search/*` / `ActionArgument` / `UiObjectCollection` / `AccessibilityNodeInfoHelper` |
| S3 | 扩展 `BridgeAccessibilityService` | `getRootInActiveWindow()` + 根节点缓存 + 手势封装 |
| S4 | 命令通道接入 | `command_catalog.py` 7 条新命令 + `CommandModule` 执行器 + 文档 |

---

### 4.1 S1：改 XML 开能力

```xml
<!-- app/src/main/res/xml/accessibility_service_config.xml -->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"    <!-- false → true -->
    android:canPerformGestures="true"           <!-- 新增 -->
    android:canTakeScreenshot="true"
    android:summary="@string/accessibility_service_summary"
    android:description="@string/accessibility_service_desc" />
```

**要点**：
- 事件**只保留 `typeWindowStateChanged`**（窗口切换，低频），**坚决不加** `typeViewScrolled` / `typeWindowContentChanged`（高频，会频繁唤醒进程，违反耗电红线）。
- `flagReportViewIds` 必须开，否则 `getViewIdResourceName()` 返回 null，`id()` 选择器失效。
- `flagRetrieveInteractiveWindows` 用于多窗口（分屏/悬浮窗）场景，按需。
- 更新 `strings.xml` 的 `accessibility_service_desc`，如实说明「可读取屏幕控件信息用于远程辅助操作」。
- ⚠️ 用户需在系统无障碍里**重新开关一次**本服务，新配置才生效（改 XML 不会热更新已绑定的服务）。

### 4.2 S2：移植 automator 动作层（精确清单 + 裁剪点）

**目标包**：`app/src/main/java/my/bridge/com/access/automator/`（保留原包名 `com.stardust.*` 会与依赖冲突，统一改包名为 `my.bridge.com.access.automator.*`）。

**精确移植清单**（源码均已通读）：

| 文件 | 来源路径 | 行数 | 裁剪/改动 |
|------|---------|------|----------|
| `UiObject.kt` | `automator/.../UiObject.kt` | 414 | **去 `allocator` 参数与相关分支**（`AccessibilityNodeInfoAllocator` 不移植），构造器简化为 `(info: Any?, depth, indexInParent)`；保留全部动作 + 属性 |
| `UiGlobalSelector.kt` | `automator/.../UiGlobalSelector.kt` | 448 | **`findAndReturnList` 的 `isRefresh` 魔改剔除**（见下方 ⚠️ 编译坑），恢复为原始 `search(node, selector, max)` |
| `GlobalActionAutomator.kt` | `automator/.../GlobalActionAutomator.kt` | 300 | **裁剪**：去 `com.stardust.concurrent.VolatileBox/VolatileDispose`（改 `CountDownLatch`）；去 `ScreenMetrics`（直接用屏宽高）；保留 `performGlobalAction` 全集 + `dispatchGesture`/`click`/`swipe`/`press`/`longClick`/`gesture` |
| `ActionArgument.kt` | `automator/.../ActionArgument.kt` | 35 | 原样移植（无外部依赖） |
| `UiObjectCollection.kt` | `automator/.../UiObjectCollection.kt` | 258 | **裁剪**：去 `com.stardust.util.Consumer`（删 `each()`）；其余保留 |
| `filter/Filter.kt` | 同上 | 14 | 原样 |
| `filter/Selector.kt` | 同上 | 33 | 原样 |
| `filter/KeyGetter.kt` | 同上 | 12 | 原样 |
| `filter/StringEqualsFilter.kt` | 同上 | 21 | 原样 |
| `filter/StringContainsFilter.kt` | 同上 | 20 | 原样 |
| `filter/StringStartsWithFilter.kt` | 同上 | 20 | 原样 |
| `filter/StringEndsWithFilter.kt` | 同上 | 20 | 原样 |
| `filter/StringMatchesFilter.kt` | 同上 | 20 | 原样 |
| `filter/IdFilter.kt` | 同上 | 42 | 原样 |
| `filter/TextFilters.kt` | 同上 | 41 | 原样 |
| `filter/DescFilters.kt` | 同上 | 41 | 原样 |
| `filter/ClassNameFilters.kt` | 同上 | 45 | 原样 |
| `filter/PackageNameFilter.kt` | 同上 | 42 | 原样 |
| `filter/BoundsFilter.kt` | 同上 | 40 | 原样 |
| `filter/BooleanFilter.kt` | 同上 | 231 | 原样（注意 `CONTEXT_CLICKABLE` 需 `@RequiresApi(M)`） |
| `filter/IntFilter.kt` | 同上 | 106 | 原样 |
| `search/SearchAlgorithm.kt` | 同上 | 9 | **`isRefresh` 参数剔除**，签名 `search(root, filter, limit)` |
| `search/DFS.kt` | 同上 | 43 | **剔除 `isRefresh` 与 `root.refresh()`/`parent.refresh()` 调用**（见 ⚠️） |
| `search/BFS.kt` | 同上 | 39 | **同上剔除** |
| `view/accessibility/AccessibilityNodeInfoHelper.kt` | `automator/.../AccessibilityNodeInfoHelper.kt` | 62 | 原样（BoundsFilter 依赖） |

**不移植**（裁剪掉）：
- `AccessibilityNodeInfoAllocator.kt`（节点池优化，非必需；UiObject 的 allocator 分支剔除）
- `AccessibilityService.kt` / `AccessibilityDelegate.kt` / `KeyInterceptor.kt` / `OnKeyListener.kt` / `NotificationListener*` / `LayoutInspector*` / `AccessibilityNotificationObserver.kt`（事件/通知/按键拦截，S4 阶段用不到，后续按需）
- `simple_action/*`（ScrollAction 等复合动作，属脚本层，暂不需要）
- `test/TestUiObject.kt`、`notification/*`（测试/通知）

> **⚠️ 移植必踩坑（ozobi 魔改 bug）**：DFS.kt/BFS.kt 里 ozobi 加的 `root.refresh()` / `parent.refresh()` **编译不过**——`UiObject` 及父类 `AccessibilityNodeInfoCompat` 均无 `refresh()` 方法（全仓 grep 无定义）。移植时**必须剔除 `isRefresh` 参数及所有 `.refresh()` 调用**，恢复 autox 原始稳定版搜索逻辑。这是「直接照抄魔改版会编译失败」的实锤，勿踩。

**依赖确认**：`androidx.core:core-ktx` 已包含 `androidx.core.view.accessibility.AccessibilityNodeInfoCompat`（`UiObject` 父类），app 模块已有该依赖，无需新增。

### 4.3 S3：扩展 `BridgeAccessibilityService`

在现有服务内新增（保持纯动作 + 按需读树，无常驻轮询）：

```kotlin
// 1. 根节点缓存（autox fastRootInActiveWindow 思路）
@Volatile private var cachedRoot: AccessibilityNodeInfo? = null

override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        // 换页/切窗口才刷新缓存；旧节点 recycle 防止内存泄漏
        cachedRoot?.let { runCatching { it.recycle() } }
        cachedRoot = runCatching { rootInActiveWindow }.getOrNull()
    }
}

// 2. 读根节点（供 UiGlobalSelector 搜索入口）
fun currentRoot(): AccessibilityNodeInfo? =
    runCatching { rootInActiveWindow }.getOrNull() ?: cachedRoot

// 3. 手势封装（转发到 GlobalActionAutomator）
fun click(x: Int, y: Int): Boolean = automator.click(x, y)
fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean = automator.swipe(...)
fun gesture(points: Array<IntArray>, duration: Long): Boolean = automator.gesture(...)
```

**要点**：
- `cachedRoot` 在 `onUnbind`/`onDestroy` 时 recycle 置空，防泄漏。
- `rootInActiveWindow` 每次都是**跨进程 IPC**，故用缓存 + 窗口变化事件刷新，避免命令频繁时反复 IPC。

### 4.4 S4：命令通道接入

**4.4.1 命令定义（`server/app/core/command_catalog.py`）**

新增 category `ui`（「界面自动化」），7 条命令：

| 命令 | label | params | 说明 |
|------|-------|--------|------|
| `ui_dump` | 导出界面结构 | — | 导出当前窗口完整控件树 JSON（限制深度/数量） |
| `ui_find` | 查找控件 | `selector`(text,必填)、`timeout_ms`(number,默认 0) | 按选择器 DSL 查找，返回匹配节点 JSON 列表 |
| `ui_click` | 点击控件 | `selector`(text) 或 `x`/`y`(number) | 优先按选择器，否则按坐标 |
| `ui_set_text` | 输入文本 | `selector`(text,必填)、`text`(text,必填) | 对匹配的可编辑节点 setText |
| `ui_gesture` | 手势操作 | `x1`/`y1`/`x2`/`y2`(number)、`duration_ms`(number) | 坐标滑动/长按/点击 |
| `ui_back` | 返回 | — | performGlobalAction(BACK) |
| `ui_home` | 回桌面 | — | performGlobalAction(HOME) |

**选择器 DSL 语法**（与 autox 对齐，文本形式，客户端解析为 `UiGlobalSelector` 链式调用）：
```
text("登录")                            # 文本精确匹配
textContains("登录").clickable()        # 文本包含 + 可点击
id("com.xx:id/btn").findOne            # 按 id
desc("搜索").className("android.widget.Button")
textMatches(".*确定.*")
```
> 简化实现：S4 先支持 `text` / `textContains` / `id` / `desc` / `className` / `clickable`，正则与 bounds 等后续按需。参数用一个 `selector` 字符串下发，客户端写一个极简 parser 映射到 `UiGlobalSelector`。

**4.4.2 客户端执行器（`CommandModule.kt`）**

新增 `executeUiDump` / `executeUiFind` / `executeUiClick` / `executeUiSetText` / `executeUiGesture` / `executeUiBack` / `executeUiHome` 七个执行器，统一模式：

1. `BridgeAccessibilityService.isReady()` 判否 → 返回 `success=false`「无障碍服务未开启」。
2. 取 `currentRoot()` → `UiObject.createRoot(root)`。
3. 构造 `UiGlobalSelector` → `findOne` / `find` → 执行动作。
4. **动作返回 false 时如实上报**（沿用「失败不崩、如实回执」约定，参考 VNT 状态联动与幂等守卫范式）。
5. 结果用结构化 JSON 回执（后台按命令单独设计详情弹窗，参考 `VntResultDetailDialog` 分治思路）。

**4.4.3 危险命令防护**（沿用 VNT 那套「状态联动 + 二次确认 + 幂等守卫」范式）

- `ui_click` / `ui_set_text` / `ui_gesture` 属**主动写操作**，后台 UI 需「二次确认」。
- 防抖：同一命令短时间内重复下发做幂等处理（参考 `VNT_STATE_RULES`）。

**4.4.4 文档与索引**

- `docs/api/commands/` 新增 `ui.md`（每条命令的参数/返回/示例/选择器 DSL 语法）。
- `docs/api/command-catalog.md` 补类别索引。
- README 命令清单补一行。

### 4.5 验收标准

1. **编译通过**：`JAVA_HOME="D:/Tool/Java/jdk-17.0.20.1+1" ./gradlew :app:compileDebugKotlin`。
2. **能力开启**：`adb shell dumpsys accessibility` 确认服务 `canRetrieveWindowContent=true`、`canPerformGestures=true`。
3. **真机冒烟**（用户在设备端配合）：
   - `ui_dump` 能导出某 App 界面结构且非空。
   - `ui_find` 按 `text("xx")` 能找到目标控件并返回 bounds。
   - `ui_click` 能点中控件（页面跳转/状态变化可见）。
   - `ui_gesture` 坐标滑动生效。
   - `ui_back` / `ui_home` 生效。
4. **耗电红线回归**：确认未订阅高频事件、无常驻轮询（code review 层面 + 观察日志无周期拉树）。

### 4.6 关键实现要点（吸收 autox 的工程智慧）

1. **快照缓存 `fastRootInActiveWindow`**：见 S3，窗口变化才刷新，命令走缓存，避免频繁 IPC。
2. **`isReady()` 语义**：Bridge 现有静态 `instance` + `isReady()` 已够用，无需引入 autox 的 `ReentrantLock + Condition`（咱们是命令驱动、一次性调用，非脚本常驻等待）。
3. **节点回收**：`AccessibilityNodeInfo` 用完 `recycle()`；DFS/BFS 已内置「非目标节点 recycle」，移植保留。
4. **`IllegalStateException` 兜底**：autox 所有 `performAction` catch 返回 false，**移植必须保留**（`UiObject.kt:143-160` 的 try-catch）。
5. **手势坐标**：直接用屏幕物理像素（`GlobalActionAutomator` 的 `scaleX/Y` 传入 identity 即可，因我们不下发逻辑坐标）。

---

## 五、风险与权衡（务必先看再动手）

1. **隐私/授权门槛上升**：`canRetrieveWindowContent=true` 是「读屏」能力，系统授权文案会变敏感；且部分银行/支付 App 对「可读屏的无障碍服务」会弹风险提示甚至限制运行。→ 建议：能力按需声明，向用户透明说明，保留「纯动作模式」作为可选降级。
2. **GPL v2 传染**：任何直接复制 autox 源码的行为都要隔离（独立模块 + 独立命名 + 不静态链接进闭源代码），否则污染 Bridge。方案 A 的「重写/裁剪」天然规避。
3. **无障碍服务易被杀**：部分国产 ROM（尤其华为/小米激进省电）会杀无障碍服务。Bridge 已有保活体系（core-keepalive + 电池白名单 + 前台服务），可复用；但要新增「无障碍服务掉线检测 + 引导重开」。
4. **安全面**：`ui_click`/`ui_set_text`/`ui_gesture` 本质是「远程操控屏幕」，与锁屏/截屏不同，是**主动写操作**。需在后台做「危险命令二次确认」+ 幂等/防抖（沿用 VNT 状态联动那套范式），并考虑审计日志。
5. **性能**：`ui_dump` 导出全控件树在复杂页面可能产生大 JSON，需限制深度/大小，避免 WS 通道拥塞。
6. **合规**：远程 UI 自动化若用于「抢单/外挂/批量操作」，可能触碰目标 App 的用户协议。工具能力本身中立，但需在文档明确「仅限自有设备/合法场景」。

---

## 六、参考资料

- 源码：`references/Autoxjs_v6_ozobi-65823/`
  - `automator/src/main/java/com/stardust/view/accessibility/AccessibilityService.kt`
  - `automator/src/main/java/com/stardust/automator/{UiObject,UiGlobalSelector,GlobalActionAutomator}.kt`
  - `autojs/src/main/java/com/stardust/autojs/core/accessibility/{AccessibilityBridge.java,UiSelector.java}`
  - `autojs/src/main/java/com/stardust/autojs/engine/RhinoJavaScriptEngine.kt`
- 外部：
  - AutoX.js 官方文档（autoxjs.dayudada.com）：Rhino 1.7.13、基于控件 vs 基于坐标操作
  - DeepWiki：`autox-community/AutoX`、`openautojs/openautojs` 的 UI Automation 章节
  - FlowBot 无障碍核心报告（zread.ai）：findOne/findOnce/untilFind 阻塞语义、FLAG_FIND_ON_UI_THREAD
