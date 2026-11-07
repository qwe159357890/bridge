# 极空（JK BMS）集成 · 改前留档台账

> **约定来源**：`MEMORY.md` ★ 改前留档约定（2026-11-01 用户确立，优先级最高）——
> 任何修改前**先逐字固化「改之前」的状态**，改完再回填「改之后」，目标是出错时能 **100% 完美恢复**。
> 极空相关改动一律先在本台账登记（改前快照 / 回滚配方 / 触发信号），再动手。
>
> 权威调研文档：`docs/research/jikong-bms-integration.md`；
> 官方 APK 逆向时序记录：`.workbuddy/memory/2026-11-06.md`（2026-11-06 17:30 段）。

> **★ 2026-11-06 22:15 git 历史重写公告（锚点 SHA 已全部换新）**：
> 同步远程时发现 v0.2.81 快照误收了 dexdump 调试产物 `tools/dd1/2/3.txt`（最大 140 MB，超 GitHub 100 MB 单文件上限，堵死推送）。
> 经用户拍板，用 `git filter-branch` 从 v0.2.81 快照起的本地历史中剔除该 3 个文件后强推远程（`cb56583`→`5606d05`）。
> **重写只删了这 3 个文件，其余内容逐字节不变**；此前所有锚点 SHA 全部失效，新旧对照：
> `0b03027`→`cacc8f5`（v0.2.81 快照）、`3342e91`→`a4f5404`（v0.2.82 快照）、`ae48fa9`→`3d09eb7`（v0.2.84 快照）、`6305354`→`3fd7bb0`（v0.2.85）、`ab58680`→`9352a06`（里程碑 v0.2.76→87）。
> `3778aca`（里程碑 v0.2.51→75）等更早提交不受影响。旧 SHA 对象仍在本机 `backup/pre-ddsync-ab58680` 保险分支可达。
> 同批清理：tools/ 下 dexdump 输出（blemessage/gattcb/jk_official_strings）、抽取的 classes*.dex、重复的官方 APK（原件存 `references/极空保护板资料/05_应用软件/JK_BMS_6.4.04.apk`）。

---

### v0.2.96 时速卡间距真因/播报无声排查/平均时速门槛（2026-11-07 17:58 实施）

**需求**：用户 17:54 反馈四项——①卡片行距仍太大（时速/瞬时之间）；②平均时速仍 0；③**完全没有语音播报**；④断连回连接页时 GPS 等资源是否释放。

**排查结论（证据链，勿忘）**：
- **无声根因 = 服务端进程从未重启**（Win32_Process 实证：`server/.venv python main.py` 启动于 11:39:20，早于 13:30 的 -8 LUFS 与 17:00 的 seg 改动）。手机 17:29-17:30 拉取的 9 个文件哈希全部匹配**无后缀键** `md5(text|voice)`（v0.2.90 服务端格式）且文本=分段（当前电量/百分之/时速/公里/剩余续航/17/18/36/56）→ 证明手机已是 0.2.95、服务端是旧代码：-12 LUFS、无尾静音、键错位导致每次重新合成；且手机本地把旧服务端产物缓存进了 v2seg 键。**处置：本轮重启服务端 + 客户端缓存键升 v3/v3seg 强制重拉**。
- **间距真因 = `DividerV()` 是竖线**（width 1dp × height 16dp），被当作行 1/行 2 的分隔线放在卡片 Column 里，实际撑出 4+16+4=24dp 空隙。
- 平均时速代码（GPS 口径）数学上不可能恒 0；用户观察疑为升级前印象/起步 10s 内 "--"。门槛 10s→5s 让数值更快出现。

| 项 | 改前原文 |
|---|---|
| **SpeedDashCard 行间分隔** | `Spacer(Modifier.height(4.dp))`⏎`DividerV()`⏎`Spacer(Modifier.height(4.dp))`（DividerV 定义：`Box(modifier = Modifier.width(1.dp).height(height.dp).background(c.divider))`，默认 height=16） |
| **SpeedDash AVG_SPEED_MIN_S** | `private const val AVG_SPEED_MIN_S = 10f` |
| **JikongSpeaker 缓存键** | `val key = md5("$text|${JikongPrefs.TTS_VOICE}|${if (seg == 1) "v2seg" else "v2"}")` |
| **versionName** | `versionName = "0.2.95"` |

**改后**：①SpeedDashCard 分隔改横向 `Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))`；②门槛 10→5s；③客户端键升 v3/v3seg（服务端键不动）；④0.2.96。服务端已重启（15160 端口）。

**回滚配方**：`git revert` 本轮提交即可（四处改动均独立小改）；服务端无需回滚（重启只会更新键错位缓存）。
**触发信号**：行间 24dp 大空隙复现 / 播报无声且服务端日志出现无后缀键合成 / 平均时速起步 5s 后仍 "--"。

### v0.2.94 返回键方案 A 统一梯子（2026-11-07 15:00 实施）

**需求**：用户 14:58 反馈返回键在极空页仍不理想并核对示意图后**拍板方案 A（统一梯子）**：无论极空排第几位，back①弹提示→back②显示工具栏（退沉浸）→back③单击跳第一个非极空页面→之后双击退出 APP（微信式逐级上退）。修复 v0.2.92 遗留死循环（极空不排第 1 位时，显示工具栏后再按 back 又回到起点弹提示）。

| 项 | 改前原文 |
|---|---|
| **MainScreen exitToken 处理（改前全文）** | `val firstNonJk = homeTabOrder.indexOfFirst { it != 5 }`⏎`if (firstNonJk == 0) {`⏎`JkUiState.immersive = false`⏎`} else {`⏎`homeTab = if (firstNonJk > 0) firstNonJk else 0`⏎`}`（LaunchedEffect(jkExitToken) 内，含 v0.2.92 注释 5 行） |
| **JikongPages back① Toast** | `Toast.makeText(context, "再按一次返回主页", Toast.LENGTH_SHORT).show()` |
| **JikongPages BackHandler 双击分支** | `if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) { JkUiState.exitToken++ } else { lastBackAt = now; Toast… }`（本轮不改此结构，exitToken 语义移交 MainScreen 解释） |

**改后**：MainScreen 以 `JkUiState.immersive` 为梯级标志——true→置 false（显示顶部 Tab 栏+底部工具栏）；false→`homeTab = homeTabOrder.indexOfFirst { it != 5 }.coerceAtLeast(0)`（跳第一个非极空页面）。Toast 文案改「再按一次显示导航栏」。JikongPages BackHandler 结构不变。

**回滚配方**：`git checkout e006070 -- app/src/main/java/my/bridge/com/ui/MainScreen.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt`
**触发信号**：极空页返回键不逐级上退 / 工具栏显示后再按 back 弹提示死循环复现 / 双击 back 直接跳页。

**实施结果（16:20 发版）**：MainScreen exitToken LaunchedEffect 改为 `if (JkUiState.immersive) { JkUiState.immersive = false } else { homeTab = homeTabOrder.indexOfFirst { it != 5 }.coerceAtLeast(0) }`；JikongPages Toast 改「再按一次显示导航栏」，BackHandler 结构未动。用户追问两点已确认：①极空子页 back→回仪表盘（分支保留未动）；②未连接的扫描页同样走 back②工具栏→back③跳走（BackHandler 页面级，与连接状态无关）。编译通过，aapt 实证 versionName=0.2.94（sha256 `828f985c…`，46,021,570 B），分发 updates+Downloads+update.json（删 v0.2.93 APK）。真机未连 adb，走 check_update 升级。

### v0.2.95 四项：时速卡行距/平均时速 GPS 解耦/分段 TTS 本地组合（2026-11-07 16:45 实施）

**需求（用户 16:38 提出 4 项）**：①时速卡每行之间间隙太大（时速行/瞬时行/本次时长行/保护板提示行之间都有大空隙）；②平均时速一直显示 0（时速有数据）；③问缩略图/头像/TTS 缓存存放位置（纯解答不改码）；④edge-tts 整句组合难命中缓存——预合成 0-100 数字+词组段，客户端本地字节拼接。

**问题②根因**：`avgSpeedKmh = tripKm / movingSecondsAvg`，而 `tripKm` 只在 **BMS 放电功率>1W** 时积分（tripWh/tripKm 电耗口径）；用户截图时正处「保护板数据未到」态（页脚提示），tripKm 冻结极小值、movingSecondsAvg 持续累计 → 平均值被稀释成 0.0x 显示 0.0。

| 项 | 改前原文 |
|---|---|
| **SpeedDash avgSpeed（改前全文）** | `avgSpeedKmh =⏎if (tripKm > 0.05f && movingSecondsAvg >= AVG_SPEED_MIN_S) tripKm / movingSecondsAvg else -1f`；tripKm 积分条件 `if (bmsOk && v >= MOVING_KMH) { … if (p > 1f) { … tripKm += v * dt / 3600f … } }` |
| **SpeedDashCard 行3 清零（改前）** | `TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("清零行程", fontSize = 11.sp, color = c.gray) }`（48dp 最小触控目标把行3 撑出大量空白） |
| **SpeedDashCard 行距（改前）** | 行间 `Spacer(Modifier.height(6.dp))` ×3；行1 三个 28sp 值无 includeFontPadding 设置 |
| **tts.py（改前）** | `def synth_tts(request, text, voice=DEFAULT_VOICE)` 无 seg 参数；`_loudnorm(src, dst)` 固定 `-af _LOUDNORM`；键 `md5(f"{text}|{voice}|v2")` |
| **maybeAnnounce 播报（改前）** | `val text = "当前电量 $soc%" + if (low) "，电量低，请注意充电" else ""` → `JikongSpeaker.speak(ctx, text)` |
| **双击播报（改前）** | onDashDoubleTap 内 StringBuilder 拼 `"当前电量 $soc%"`（+低电/时速/续航）→ `JikongSpeaker.speak(context, sb.toString())` |
| **BridgeApi.synthTts（改前）** | 参数 `(text, voice, deviceId, token)` 无 seg |

**改后**：①SpeedDash 新增 `gpsTripKm`（v≥0.5 且 GPS 新鲜即积分，与 BMS 解耦）——avgSpeed 与卡片「本次里程」行都改用它，电耗口径 tripKm 不动；②行3 TextButton→clickable Text，Spacer 6→4dp，28sp 值 includeFontPadding=false；③tts.py 加 `seg=1`（追加 120ms 尾静音 `apad=pad_dur=0.12`，键升 `v2seg`）+ `server/tts_prewarm.py` 预热 0..100 数字+6 词组；④BridgeApi.synthTts 加 `seg` 参数；JikongSpeaker.fetchOnline 加 seg 参数、新增 `speakSegs`（本地段全命中→字节拼接 mp3 播放，缺段补拉，失败回退整句）；maybeAnnounce 与双击播报改 speakSegs。

**回滚配方**：`git checkout a7852e8 -- app/src/main/java/my/bridge/com core/core-network server/app/api/v1/tts.py`
**触发信号**：平均时速回到恒 0/里程不走/时速卡行距回归/播报回退整句或无声。

**实施结果（17:10 发版）**：全部落地。预热脚本两轮跑完 107 段（首轮后台任务 ~2min 被截停于 49 段，幂等补跑补齐 50-100+6 词组；⚠️ 首轮可能仍存活并发写过同名文件，均为完整 ffmpeg 产物无损坏）。aapt 实证 0.2.95（sha256 `34339555…`），分发 updates+Downloads+update.json（删 v0.2.94）。**服务端需重启**（tts.py 新 seg 参数才生效；旧服务端会忽略 seg 返回无尾静音整句音频，客户端不崩、只是段间无停顿）。缓存位置问答（不改码）：微信头像/缩略图/封面=`getExternalFilesDir/bridge/wechat/cache/{avatars,media}`（外部私有，7 天清理）；TTS 客户端=`cacheDir/tts/`；微信语音=`cacheDir/wechat/`；更新包=`getExternalFilesDir/updates/`；音乐=`getExternalFilesDir/music/`；服务端 TTS=`server/data/tts_cache/`。

## §一 当前生效的回退锚点（★ 最高优先查看）

### v0.2.97 平均时速单位错+段间隔收紧+启动预取（2026-11-07 18:35 实施）

**改前原文（全部逐字抄录）**：

1. SpeedDash.kt 平均时速公式（**真因：km÷秒 单位错误，少乘 3600**——10km/h 算出 0.003，`%.1f` 显示 0.0，v0.2.90 起一直如此）：
```kotlin
            // ---- 平均时速（v0.2.95 修恒 0）：GPS 里程 ÷ 移动时间，不再依赖 BMS 放电口径 tripKm ----
            avgSpeedKmh =
                if (gpsTripKm > 0.05f && movingSecondsAvg >= AVG_SPEED_MIN_S) gpsTripKm / movingSecondsAvg else -1f
```

2. tts.py seg 滤链与键（v0.2.95 版，120ms apad 是段间大间隔元凶）：
```python
    # v2 = 响度档版本（v0.2.92 由 -12 提到 -8 LUFS）；v2seg = 分段档（含尾静音）
    suffix = "v2seg" if seg else "v2"
    key = hashlib.md5(f"{text}|{voice}|{suffix}".encode("utf-8")).hexdigest()
...
        af = _LOUDNORM + (",apad=pad_dur=0.12" if seg else "")
```

3. tts_prewarm.py 键推导：`return hashlib.md5(f"{text}|{voice}|v2seg".encode("utf-8")).hexdigest()`

4. JikongSpeaker.kt 客户端键：`val key = md5("$text|${JikongPrefs.TTS_VOICE}|${if (seg == 1) "v3seg" else "v3"}")`

**回滚配方**：上述 4 处逆向还原（键回 v2seg/v3seg、apad 回 0.12、公式去掉 ×3600）→ 删 server/data/tts_cache 全部重预热 → 重启服务端。
**实施结果（v0.2.97，18:52 发版）**：
1. SpeedDash 平均时速公式 `gpsTripKm / movingSecondsAvg` → `gpsTripKm * 3600f / movingSecondsAvg`（真因修复，用户实证时速 10 显示 0.0 与公式推导吻合），门槛 `>0.05f` → `>0.001f`（起步快出数）。
2. tts.py：新增 `_SEG_AF`（silenceremove 段首 -40dB + loudnorm + apad 0.04）替代旧 120ms apad；键 v2seg→v3seg 并抽出 `SEG_SUFFIX` 常量供预热脚本 import（两端键永不漂移）。
3. tts_prewarm.py：键/滤链改从 tts.py import；旧 v2seg 段删除（58 个）后全量重预热，107/107 就绪。
4. JikongSpeaker：seg 键 v3seg→v4seg（本地 120ms 旧段作废重拉）；新增 `prefetchAll`（0..100+6 词组幂等预取）；HomeView 进页 `LaunchedEffect(Unit)` 触发——拉全后播报完全本地组合零网络。
5. 交付：APK 0.2.97（sha256 `5a9db5bd…`）分发三件套（删 v0.2.96）。**服务端需再重启一次**（v3seg+_SEG_AF 生效）。
**待真机验证**：①骑行时速 10 左右平均时速≈10（不再是 0.0）；②「当前电量→50」段间停顿明显变短；③进首页后日志「TTS 分段预取完成：107/107」，之后双击播报第二次起零网络。

**触发信号**：平均时速回归 0.0 / 段间隔回到 120ms 明显停顿 / 预取后播报不发声。

### v0.2.93 五项：chip 防竖排/连接竞态治理（陈旧 GATT 回调守卫+连接抢占+数据帧硬保证）（2026-11-07 14:05 实施）

| 项 | 内容 |
|---|---|
| **需求（用户 14:05 提出 2 项）** | ①阶段统计 chip「自定义」三字竖排了（按钮太多放不下）→ 拍板去掉「近」字减宽；②连接竞态：设默认设备后点扫描延时 10~50s 才变「扫描中」；自动连接+手动操作交叉后，最终 BLE 已连上且在收数据，但页面停在扫描页。硬要求：**成功连接且读到数据时必须显示数据页** |
| **回退标记（git 快照）** | 提交 **`8c272db`**（`feat: v0.2.92 四项优化+返回键修复…`）——本次改动前全部代码原文以此为准 |
| **回滚配方** | `git checkout 8c272db -- app/src/main/java/my/bridge/com app/build.gradle.kts` |
| **触发回滚的信号** | ①正常断开/重连流程异常（连不上、断不净、重连风暴）；②扫描列表不刷新；③播报/统计行为异常（数据帧强制恢复连接误触发） |
| **根因（代码层确认，与用户现象逐条对应）** | ①**陈旧 GATT 回调无守卫**：gattCallback 所有回调不检查 `g === gatt`——被 disconnect()/close() 掉的旧 GATT 迟到回调（自动连接 A 与手动连接 C 交叉时必然产生）会 resume 错误的 waiter、`setConnected(true/false)` 污染当前状态；**迟到的 STATE_DISCONNECTED 把正在流数据的新连接打成未连接 → 页面回扫描页而数据网格仍在刷新**（用户观察的最终态）。②**disconnect() 丢弃 waiter 不 resume**：旧 connect() 的 withTimeoutOrNull 只能等满 10s → 新请求在 controller.mutex 排队 → A(10s)+B(10s)+… = 扫描延时 10~50s。③**connect 不能抢占**：controller.connect 排队等 mutex，不终结在飞尝试。④**无数据帧兜底**：状态与链路真值脱节时无自愈 |
| **改前原文（逐字，BleClient.disconnect L473-485）** | `fun disconnect() {\n        writeWaiters.values.forEach { it.complete(false) }\n        writeWaiters.clear()\n        // 清空挂起的连接等待，避免后续 GATT 回调 resume 一个已取消的 continuation\n        connectWaiter = null\n        rxBuffer.reset()\n        runCatching { gatt?.disconnect() }\n        runCatching { gatt?.close() }\n        gatt = null\n        writeChar = null\n        notifyChar = null\n        setConnected(false)\n    }` |
| **改前原文（逐字，BleClient.onConnectionStateChange 断开分支 L282-287）** | `} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {\n                Timber.tag(TAG).i("GATT 已断开 status=$status")\n                lastDisconnectStatus = status\n                setConnected(false)\n                connectWaiter?.let { w -> connectWaiter = null; w.resume(false) }\n            }` |
| **改前原文（逐字，Controller.connect L221-227）** | `suspend fun connect(context: Context, address: String): Boolean = mutex.withLock {\n        appContext = context.applicationContext\n        lastConnectAddress = address\n        // 用户手动连接优先：终止可能正在跑的自动重连循环\n        reconnectJob?.cancel()\n        reconnectJob = null\n        val ok = JikongBleClient.connect(context, address)` |
| **改前原文（逐字，mergeFrame FRAME_DATA 分支 L638-644）** | `JikongProtocol.FRAME_DATA -> {\n                parseDataFrame(s, frame.data)          // 0x02\n                lastDataFrameAt = System.currentTimeMillis()\n                hasDataFrame = true\n                datalessRecycles = 0   // v0.2.80：数据通了，重建计数归零\n                maybeAnnounce(s)       // v0.2.89：每帧判定播报（变化/间隔/预警，规则见函数注释）\n            }` |
| **改前原文（其余触点）** | ①Pages doConnect 无 attempt token（陈旧 fallback 扫描会误触发）；②自动连接 LaunchedEffect 只查 connected/demoMode 不查在飞；③doScan 无在飞直连跳过；④阶段统计 ranges=`listOf("近一周" to 7L, "近一月" to 30L, "一季度" to 90L, "近一年" to 365L)` 无 maxLines |
| **实施结果（改后回填，2026-11-07 14:18）** | ①**BleClient 陈旧守卫**：gattCallback 加 `isStale(g)`（g !== gatt → 忽略+close），挂到 onConnectionStateChange/onMtuChanged/onServicesDiscovered/onDescriptorWrite 全部与 onCharacteristicChanged/onCharacteristicWrite 的 `g !== gatt` 早退——旧链路迟到回调不再污染状态/不再幽灵 resume 新 waiter/旧链路数据不进会话；②**disconnect() resume waiter**（runCatching 包裹防重复 resume）+ 新增 `cancelPendingConnect()`（抢占终结在飞尝试，**不触发 setConnected** 防误拉自动重连）；③**Controller.connect 抢占**：mutex 外先 cancelPendingConnect，全程 `connectInFlight`（@Volatile，finally 复位）；④**数据帧硬保证**（用户拍板硬要求）：mergeFrame FRAME_DATA 收到帧但 `!s.connected && !demoMode` → 强制恢复已连接+补设备名+autoReconnectEnabled=true（链路活着的最高证据）；⑤**UI**：doConnect 加 attempt token（被抢占的旧 attempt 不动 connecting/不 Toast/不回退扫描）；doScan 在飞时跳过默认直连直接正常扫描；自动连接 LaunchedEffect 加 `connecting || connectInFlight` 跳过；阶段统计 chip 去「近」字+`maxLines=1, softWrap=false`。编译一次通过；APK 实证 0.2.93；分发 updates（46,021,570 B，sha256 03b80911…）+Downloads+update.json（删 v0.2.92 APK） |
| **修复后行为（对应原现象）** | ①点扫描：在飞尝试被毫秒级抢占（不再等 10~50s），立即「扫描中」；②自动连接+手动交叉：旧 GATT 全部被守卫忽略，最终状态=真实链路状态；③即使状态被意外污染，第一帧数据到达即强制回到数据页（双保险） |

### v0.2.92 四项：阶段统计自定义时间段/演示模式清列表/双击仪表盘修复/播报提响（2026-11-07 13:20 实施）

| 项 | 内容 |
|---|---|
| **需求（用户 13:20 提出 4 项）** | ①统计页阶段统计加「自定义」chip，用户自由选时间段；②蓝牙防僵尸：进入演示模式也算、同样清空扫描列表；③双击仪表盘没有任何反应（修复）；④播报声音太小（提响） |
| **回退标记（git 快照）** | 提交 **`d6a6ef6`**（`feat: v0.2.91 第二轮六项…`）——本次改动前全部代码原文以此为准 |
| **回滚配方** | `git checkout d6a6ef6 -- app/src/main/java/my/bridge/com app/build.gradle.kts server/app/api/v1/tts.py` |
| **触发回滚的信号** | ①自定义日期选择器崩溃/选不了日期/统计错乱；②进演示模式误清了不该清的状态；③双击播报误触发频繁刷语音；④-8 LUFS 出现削波/破音 |
| **改前原文（逐字，Controller.enterDemoMode L271-274）** | `fun enterDemoMode() {\n        stopPolling()\n        demoMode = true\n        val s = status` |
| **改前原文（逐字，HomeView 仪表盘 Box L858-861）** | `// 中央仪表盘（300°弧 + 0~100 刻度带 + 外围数字 + SOC + 三胶囊）\n        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {\n            JkGauge(s.soc, s.totalVoltage, s.current, s.totalVoltage * s.current)\n        }` |
| **改前原文（逐字，阶段统计 chip 行 + cutoff L2174-2196）** | `val ranges = remember {\n                    listOf("近一周" to 7L, "近一月" to 30L, "一季度" to 90L, "近一年" to 365L)\n                }\n                var stageIdx by remember { mutableStateOf(1) }   // 默认近一月\n                Row {\n                    ranges.forEachIndexed { i, (label, _) ->\n                        val sel = i == stageIdx\n                        Text(\n                            label,\n                            fontSize = 12.sp,\n                            color = if (sel) c.onfill else c.gray,\n                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,\n                            modifier = Modifier\n                                .background(if (sel) c.green else c.bg, RoundedCornerShape(14.dp))\n                                .clickable { stageIdx = i }\n                                .padding(horizontal = 12.dp, vertical = 5.dp),\n                        )\n                        if (i < ranges.lastIndex) Spacer(Modifier.width(6.dp))\n                    }\n                }\n                Spacer(Modifier.height(8.dp))\n                val cutoff = System.currentTimeMillis() - ranges[stageIdx].second * 24 * 3600_000L\n                val stageSegs = validSegs.filter { it.rec.ts >= cutoff }` |
| **改前原文（逐字，JikongSpeaker 缓存键 L86）** | `val key = md5("$text\|${JikongPrefs.TTS_VOICE}")` |
| **改前原文（逐字，server/app/api/v1/tts.py L36-37 + L80）** | `# 响度归一化目标：语音类内容 -12 LUFS 偏响且留 1.5dB 真峰余量（用户拍板「越大越好但不削波」）。\n_LOUDNORM = "loudnorm=I=-12:TP=-1.5:LRA=7"`；`key = hashlib.md5(f"{text}\|{voice}".encode("utf-8")).hexdigest()` |
| **双击无反应根因判断** | v0.2.91 双击手势只挂在 SpeedDashCard（时速仪表盘卡片）上；用户说的「仪表盘」是**圆形大表盘**（JkGauge），未挂手势 → 无反应（时速卡开关关闭时更无处可点）。本轮把同一播报处理同时挂到圆形表盘外层 Box |
| **实施结果（改后回填，2026-11-07 13:55）** | ①阶段统计加「自定义」chip（点选弹 Material3 DateRangePicker，起止两次点选；选中后 chip 行下方显示「自定义区间：MM-dd ~ MM-dd」；区间口径 [start 本地零点, end+1天)，`jkLocalDayStart` 把 picker 的 UTC 零点转本地零点防 UTC+8 错日；未选日期时提示「请先选择起止日期」；弹窗内套 `MaterialTheme(light/darkColorScheme())` 按 c.bg 亮度跟随主题）；②`enterDemoMode()` 加 `clearScanCache()`（进演示也清扫描列表，用户拍板）；③双击播报抽成 `onDashDoubleTap` 共用 lambda，圆形大表盘外层 Box 挂 `detectTapGestures(onDoubleTap)` + SpeedDashCard 保留；演示模式同样生效（demo SOC=72，时速 0 只播电量）；④响度：服务端 `_LOUDNORM` -12→**-8 LUFS**（TP=-1.5 不变），服务端缓存键升 `v2`（`md5(text\|voice\|v2)`），客户端 JikongSpeaker 缓存键同步升 v2，旧低响度缓存两端自然失效；server/data/tts_cache 已清空。编译 3 轮过（踩坑：material3 1.3.1 无 `DateRangePickerDefaults.colors/dateRangePickerColors`，改省略 colors 参数 + MaterialTheme 包装；`luminance` 补 import） |
| **★ 附加 bug：双击返回键回不了主页（用户 13:31-13:41 实证 + 根因确认）** | **根因**：用户把「极空」重排到主页第 1 位（设置→通用可调 Tab 顺序），`exitToken++ → homeTab=0` 仍停在极空页 → 永远退不出去（与主线程卡顿无关；扫描慢另因：v0.2.88 设计——点扫描先直连默认设备，最长 10s 超时才回退正常扫描）。**拍板行为（用户 13:41）**：极空在第 1 位时双击返回**不切 Tab，改为退出沉浸态**（`JkUiState.immersive=false`，顶部 Tab 栏 + APP 底部工具栏恢复显示）；否则跳第一个非极空 Tab。已按此实现（MainScreen.kt exitToken LaunchedEffect） |
| **改前原文（逐字，MainScreen exitToken 效果）** | `LaunchedEffect(jkExitToken) {\n        if (jkExitToken == consumedJkExit) return@LaunchedEffect\n        consumedJkExit = jkExitToken\n        homeTab = 0\n    }` |

### v0.2.91 第二轮六项：同级平均时速/演示台账/双击播报/返回键回首页/阶段统计/蓝牙防僵尸（2026-11-07 11:55 实施）

| 项 | 内容 |
|---|---|
| **需求（用户 11:55 提出 6 项）** | 详见 `todolist.md` 任务十~十五：①平均时速与时速/续航视觉同级；②演示模式带内存演示台账（不碰真实存储）；③双击仪表盘播报电量(+预警)+时速+续航；④极空页 tab≠0 时返回键先回首页；⑤统计页阶段统计 chip（周/月/季/年）；⑥连接成功清空蓝牙列表+距上次扫描>10min 进页清空 |
| **回退标记（git 快照）** | 提交 **`c93ca0d`**（`feat: v0.2.90 九项优化…`）——本次改动前全部客户端代码原文以此为准 |
| **回滚配方** | `git checkout c93ca0d -- app/src/main/java/my/bridge/com app/build.gradle.kts` |
| **触发回滚的信号** | ①双击播报误触发/无声；②演示台账混入真实存储或退出演示不还原；③阶段统计数值错乱；④返回键行为异常（回不了首页/直接退主页）；⑤连接后扫描列表仍显示旧设备 |
| **实施结果（改后回填，2026-11-07 12:10）** | ①平均时速 20sp SemiBold→**28sp Bold**（c.text）；②演示台账：`jkDemoRecords()` 内存 7 条（30 天跨度、段电耗 31~40 梯度、真机样例差值），StatsView `demoMode` 分支——records=demoList、pending=0、记一笔/删除只动内存、同步按钮换「演示 · 不同步」、标题行琥珀「演示数据」、enterDemoMode 置 `hasDataFrame=true` 放行记一笔门控；③双击播报：SpeedDashCard 加 `pointerInput+detectTapGestures(onDoubleTap)`，HomeView 组文案「当前电量 N%」(+低电预警)+时速 N 公里+剩余续航 N 公里（速度取整减缓存键，**绕过静音**，显式手势）；④BackHandler 前置 `connected && tab!=0 → tab=0`；⑤阶段统计卡：4 chip（周/月/季/年默认月）+阶段里程(折扣口径)/阶段花费/平均花费 元每100km，段按记录时间归属（validSegs.ts≥cutoff）；⑥防僵尸：Controller 加 `clearScanCache()`（connect 成功+重连成功调用）+ `SCAN_STALE_MS=10min`（进页 LaunchedEffect 判断 lastScanAt 超时清理）；顺带修 v0.2.90 bug：JkSumCell sub 重复「原 」前缀；版本 0.2.91；编译踩坑 1 个（JikongSpeaker import 缺失）已修；APK 实证 0.2.91+新符号；分发 updates(46,021,570 B, sha256 40ed1663…)+Downloads+update.json，删 v0.2.90 APK |
| **改前原文（逐字，SpeedDashCard L1051-1056 平均时速列）** | `Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {\n                Text("平均时速(km/h)", fontSize = 11.sp, color = c.gray)\n                Text(\n                    if (avgSpeed > 0f) "%.1f".format(avgSpeed) else "--",\n                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text,\n                )\n            }` |
| **改前原文（逐字，BackHandler L380-388）** | `BackHandler {\n        val now = System.currentTimeMillis()\n        if (now - lastBackAt < BackPressPrefs.homeWindowMs(context)) {\n            JkUiState.exitToken++\n        } else {\n            lastBackAt = now\n            Toast.makeText(context, "再按一次返回主页", Toast.LENGTH_SHORT).show()\n        }\n    }` |
| **改前原文（逐字，Controller.cacheScanResult L162-165）** | `fun cacheScanResult(devices: List<BleDevice>) {\n        cachedDevices = devices\n        lastScanAt = System.currentTimeMillis()\n    }` |
| **改前原文（其余触点）** | ①connect()/重连成功处无清列表；②SpeedDashCard 无双击手势；③StatsView 全量读写真实存储、无演示分支、无阶段统计卡 |

### v0.2.90 九项优化：单位切换/估算链/平均时速/静音/edge-tts/音量/记一笔门控/里程折扣/花费（2026-11-07 09:30 实施）

| 项 | 内容 |
|---|---|
| **需求（用户 08:51 提出 6 项 + 09:11 追加 3 项并拍板，09:35 原型审核确认）** | 详见 `todolist.md` 九项条目与三份原型（assets/design/ui-mockup-jikong-{home,stats,settings}.html）。拍板：平均时速=本次行程（内存）；静音持久化；服务器 uv 管理 edge-tts、音色+缓存方案同意；服务端响度方案同意 |
| **回退标记（git 快照）** | 提交 **`20ef571`**（`feat: v0.2.89 语音播报规则调整…`）+ 工作区仅原型文件改动——本次改动前全部客户端/服务端代码原文以此为准 |
| **回滚配方** | `git checkout 20ef571 -- app/src/main/java/my/bridge/com core/core-network/src/main/java/my/bridge/com/core/network/api/BridgeApi.kt server/app/api/v1 server/app/pyproject.toml server/app/uv.lock app/build.gradle.kts server/data/updates/update.json`，另**手动删除**新增文件 `server/app/api/v1/tts.py` |
| **触发回滚的信号** | ①edge-tts 播报失败但本地回退也异常（无声）；②缓存目录膨胀超 50MB 上限；③/api/tts 鉴权放行异常；④统计页折扣/花费数值错乱；⑤记一笔永久置灰；⑥平均时速数值离谱（除零/ NaN）；⑦静音后仍播报 |
| **实施结果（改后回填，2026-11-07 10:05）** | ①SpeedDash：估算链改「行程平均 > 生命周期累计(totalWh÷totalKm, ≥1km) > EMA > hist > 20」（hist 停止落盘）；新增 avgSpeedKmh（tripKm÷movingSecondsAvg，v≥0.5 计时 GPS 口径独立，≥10s 生效）；②JikongPrefs 新增 unitKwh100/odoDiscount(0.1~1)/elecPrice(0.05~10,默认0.6)/muted + TTS_VOICE=zh-CN-XiaoxiaoNeural；③Controller：firstAnnouncePending（进程启动首播强制，播出才消耗），maybeAnnounce 加静音拦截（forced 例外）；④JikongSpeaker 重写：在线优先（BridgeApiClient.synthTts → cacheDir/tts/{md5}.mp3 缓存 LRU 50MB → MediaPlayer STREAM_MUSIC 播放）失败回退长驻本地 TTS；BridgeApi+BridgeApiClient 加 synthTts（@Streaming GET api/tts，设备头鉴权），Application 注入 attach；⑤服务端：新增 api/v1/tts.py（GET /api/tts，edge-tts volume=+0% + ffmpeg loudnorm I=-12 LUFS，缓存 server/data/tts_cache，uv add edge-tts>=7.3.1 已装），router 挂载，py_compile 通过，合成+归一化端到端实测通过（raw 28080B → final 28460B）；⑥UI：SpeedDashCard 行1 三列（时速\|平均时速\|续航）+行2 单位切换显示；ConnectionBar 🔊/🔇；设置页新增「单位与花费」（单位分段+电费步进0.05）与「里程折扣」（步进0.1）两卡+说明更新（估算链）；StatsView：记一笔门控（hasDataFrame&&cycleCapacity>0，禁用+amber 提示）、概要 3→5 卡（区间总花费/平均花费 元/100km+原值小字）、曲线双轴（左 Wh/km 右 元/100km 琥珀，X 轴折扣里程）、台账行折扣显示+原值+该段花费；⑦版本 0.2.90；编译踩坑 3 个（mutableFloatStateOf/RowScope import 缺失；BridgeApiClient 是逐方法包装类需加 synthTts 委托）全部修复；APK 实证 0.2.90+7 符号；分发 updates(46,005,186 B, sha256 eb283cc3…)+Downloads+update.json，删 v0.2.89 APK |
| **上线前提** | 服务端需重启（加载 /api/tts 路由与 edge-tts 依赖）；客户端「在线人声」需服务器可达，离线自动回退本地引擎 |
| **改前原文（逐字，SpeedDash.kt L297-304 预测电耗链）** | `// ---- 预测电耗：行程平均 > EMA(移动>30s) > 上次历史平均 > 参考值；钳位防乐观/发散 ----\n            val hist = appContextRef?.let { JikongPrefs.histAvgWhKm(it) } ?: -1f\n            val ePred = when {\n                avgWhKm > 0f -> avgWhKm\n                emaWhKm > 0f && movingSeconds >= EMA_MIN_MOVING_S -> emaWhKm\n                hist > 0f -> hist\n                else -> REFERENCE_WH_PER_KM\n            }.coerceIn(6f, 100f)` |
| **改前原文（逐字，JikongSpeaker.kt L28-34 speak 入口）** | `/** 播报一段文字（异步，立即返回）。引擎未就绪时暂存待播。 */\n    @Synchronized\n    fun speak(context: Context, text: String) {\n        val cur = tts\n        if (cur != null) {\n            if (ready) doSpeak(cur, text) else pending = text\n            return\n        }\n        pending = text` |
| **改前原文（逐字，StatsView L1877-1899 概要三卡）** | `// ---- 概要三卡 ----\n        Card(...) {\n            Row(Modifier.padding(12.dp)) {\n                listOf(\n                    "记录区间里程" to (if (totalKm > 0f) "%.1f".format(totalKm) else "--"),\n                    "区间总耗电" to (if (totalWh > 0f) "%.2f".format(totalWh / 1000f) else "--"),\n                    "平均电耗" to (if (avgWhKm > 0f) "%.1f".format(avgWhKm) else "--"),\n                ).forEachIndexed { i, (label, value) -> …三卡渲染…`（完整原文见 git 快照） |
| **改前原文（其余触点）** | ①Controller.maybeAnnounce 无静音/强制首播逻辑；②SpeedDashCard 行1 双列（时速\|剩余续航）；③StatsView 记一笔 Button 无 enabled 门控；④JkStatsCurve 无右轴；⑤SettingsView 无单位/电费/里程折扣卡；⑥JikongPrefs 无 unit/mute/discount/price 键；⑦BridgeApi 无 TTS 端点；⑧服务端路由无 tts.py |

### v0.2.89 语音播报规则调整：每帧判定 + 预警重复提醒（2026-11-07 07:30 实施）

| 项 | 内容 |
|---|---|
| **需求（用户 07:22 提出、07:27 拍板）** | ①播报从「每次连接成功后首帧判一次、连接内不再播」改为**每帧持续判定**：电量 ≠ 上一帧 或 ≠ 上次播报值 → 播；②预警阈值要考虑：**首次跌破立即播一次带提醒（1A）**；低于阈值期间**按预警提醒间隔重复提醒（间隔设置页可调，默认 5 分钟）**；③「电量相同且距上次播报 ≥ 间隔 → 重复播」**保留** |
| **回退标记（git 快照）** | 提交 **`fe9ded4`**（`feat: v0.2.88 极空语音播报+设备昵称+默认设备…`，工作区干净即此提交）——本次改动前全部文件原文以此为准 |
| **回滚配方** | `git checkout fe9ded4 -- app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/src/main/java/my/bridge/com/jikong/JikongController.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt app/build.gradle.kts server/data/updates/update.json`（无新增/删除文件） |
| **触发回滚的信号** | ①播报刷屏（SOC 每帧都播）；②预警提醒不响或过于频繁；③连接后首帧不播（回归 v0.2.88 行为） |
| **实施结果（改后回填，2026-11-07 07:40）** | ①`JikongPrefs` 加 speakWarnRepeatMin(1~60,默认5)/KEY_LAST_WARN_AT+lastWarnAt/setLastWarnAt；②Controller：`speakPendingThisConnection` 删除 → `prevAnnounceSoc`（每帧推进、连接/重连成功重置 -1）；`maybeAnnounce` 重写为每帧判定：changed=(prev!=-1&&soc!=prev)\|\|soc!=lastSoc、due 保留、warnDue=low&&距上次带提醒≥预警间隔，任一满足即播，low 时追加提醒并刷 lastWarnAt；③设置页：开关说明改新规则、新增「预警提醒间隔」步进行（1~60 默认5）、卡底说明更新；④版本 0.2.89（versionCode 保持 1，与历史一致）；编译一次通过，APK 实证 0.2.89 + jikong_speak_warn_repeat_min/lastWarnAt/prevAnnounceSoc 在 classes11.dex；分发 updates(45,972,418 B, sha256 7d1ea362…)+Downloads+update.json，删 v0.2.88 APK |
| **改前原文（逐字，JikongController.kt L98-104 字段）** | `/**\n     * 语音播报机会标记（v0.2.88）：每次连接成功置位，**首个「页面可见时的含电量帧」判定一次**，\n     * 判定后（无论播与不播）即复位——连接存续期内不再播报。跨连接的播/不播规则见 [maybeAnnounce]。\n     */\n    @Volatile\n    private var speakPendingThisConnection = false\n        private set` |
| **改前原文（逐字，JikongController.kt L486-507 判定函数）** | `private fun maybeAnnounce(s: JikongStatus) {\n        if (!speakPendingThisConnection) return\n        if (demoMode \|\| !pageVisible) return   // 页面不可见/演示：机会保留，下一帧再判\n        val ctx = appContext ?: return\n        if (!JikongPrefs.speakEnabled(ctx)) return   // 开关未开：不消耗机会（开着了下一帧即判）\n        val soc = s.soc\n        if (soc !in 0..100) return\n        speakPendingThisConnection = false           // 正式判定，本连接不再播\n        val lastSoc = JikongPrefs.lastSpokenSoc(ctx)\n        val changed = soc != lastSoc\n        val due = System.currentTimeMillis() - JikongPrefs.lastSpokenAt(ctx) >=\n            JikongPrefs.speakIntervalMin(ctx) * 60_000L\n        if (!(changed \|\| due)) {\n            Timber.tag(TAG).i("语音播报判定：不播（SOC=%d 未变且间隔未到，上次播报 SOC=%d）", soc, lastSoc)\n            return\n        }\n        val text = "当前电量 $soc%" +\n            if (soc < JikongPrefs.speakLowSoc(ctx)) "，电量低，请注意充电" else ""\n        JikongPrefs.setLastSpoken(ctx, soc)\n        JikongSpeaker.speak(ctx, text)\n        Timber.tag(TAG).i("语音播报：%s（上次 SOC=%d，changed=%s due=%s）", text, lastSoc, changed, due)\n    }` |
| **改前原文（其余触点）** | connect 成功 L210-211：`// v0.2.88：本次连接获得一次播报机会（首个页面可见的含电量帧判定）\n            speakPendingThisConnection = true`；自动重连成功 L461-462：`// v0.2.88：重连也是一次「连接成功」，重新获得播报机会\n                    speakPendingThisConnection = true`；mergeFrame 调用 L596：`maybeAnnounce(s)       // v0.2.88：连接后首个页面可见的含电量帧判定播报`；设置页状态 L1572-1575（speakEnabled/speakIntervalMin/speakLowSoc 三个 remember）；开关说明 L1628：`"连接成功并获取到电量后语音播报一次（仅本页打开时）。电量与上次播报相同且未超间隔则不播."`；低阈值步进行后 L1654 仅 `Spacer(Modifier.height(2.dp))` + 下一行即低电量阈值 JkStepperRow（label="低电量阈值", 范围 1~50）；卡底说明 L1669：`"低于阈值时播报追加「电量低，请注意充电」。需要设备装有系统文字转语音（TTS）引擎。"`；JikongPrefs 无 speakWarnRepeatMin 相关键 |

### v0.2.88 播报/昵称/默认设备三功能（2026-11-06 23:15 实施）

| 项 | 内容 |
|---|---|
| **需求（用户 23:12 逐条拍板）** | ①语音播报：设置页开关+间隔+低电量阈值；**每次连接成功后首个含电量帧判定一次，连接存续期内不再播**（判定规则「电量≠上次播报值→播；相同且距上次播报≥间隔→播」跨连接生效）；话术「当前电量 N%」，低于阈值（默认 20，设置可调）追加「电量低，请注意充电」；仅极空页可见时播（页面不可见机会保留到可见后首帧）；演示模式不播。②昵称：连接栏「改名」入口（点设备名弹输入框，留空清除）；扫描列表昵称主标题+原名/MAC 小字。③默认设备：连接栏「设为默认」开关（关=取消）；扫描时默认设备直连、失败自动回退正常扫描；扫描按钮下显示「默认：昵称」；**进入极空页自动连默认设备** |
| **回退标记（git 快照）** | 提交 **`74af59b`**（`docs: 里程碑补脉络5…`，工作区干净即此提交）——本次改动前全部文件原文以此为准 |
| **回滚配方** | `git checkout 74af59b -- app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/src/main/java/my/bridge/com/jikong/JikongController.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt app/build.gradle.kts server/data/updates/update.json`，另**手动删除**新增文件 `app/src/main/java/my/bridge/com/jikong/JikongSpeaker.kt` |
| **触发回滚的信号** | ①TTS 播报异常刷屏/不播；②改昵称后扫描列表/连接栏显示错乱；③默认设备直连失败后不回退扫描或死循环重连；④进页面自动连与自动重连互相打架（双连接竞争） |
| **实施结果（改后回填，2026-11-06 23:35）** | ①新增 `JikongSpeaker.kt`（长驻 TTS 单例，惰性初始化+pending 补播，缺引擎静默失败）；`JikongPrefs` 加 speakEnabled/speakIntervalMin(1~120,默认10)/speakLowSoc(1~50,默认20)/lastSpokenSoc/lastSpokenAt/nicknames(JSON)/defaultMac；Controller 加 `speakPendingThisConnection`（connect 与自动重连成功置位，`maybeAnnounce` 在 0x02 帧判定后复位——页面不可见/演示/开关未开不消耗机会）；②UI：设置页「语音播报」卡（开关+两个步进行 JkStepperRow）；ConnectionBar 点设备名弹改名框（留空清除）+「设默认/取消默认」文字开关+名称后缀「· 默认」；ScanConnectView 昵称主标题/原名小字+默认提示行；doScan 有默认→直连(fallbackScan=true)，失败 Main 线程回退 doNormalScan（局部函数改单向引用避免 doScan↔doConnect 互调）；JikongPage 进入时 LaunchedEffect 自动连默认设备（未连接/非演示/有连接权限/蓝牙开，失败仅 Toast）；③编译两次过（首错=局部函数前向引用，重构修复）；assembleDebug 成功，APK 实证 0.2.88 + JikongSpeaker/maybeAnnounce 在 classes11.dex；分发 updates+Downloads+update.json（sha256 d4aaddd1…），删 v0.2.87 APK |
| **踩坑** | Kotlin **局部函数不允许前向引用**——doScan 与 doConnect 互相调用编译错 `Unresolved reference 'doConnect'`；解法=抽出 `doNormalScan()` 打断循环（doConnect 回退直调它，不再回 doScan） |

### v0.2.86 后台极空页 1:1 复刻 + 客户端上报扩展（2026-11-06 21:00 实施，已构建分发，待验证）

| 项 | 内容 |
|---|---|
| **实施结果（改后回填，2026-11-06 21:16）** | ①客户端：reportStatus 扩展编译一次通过，assembleDebug 成功，APK 实证 versionName=0.2.86 + buildReportStatus 在 dex，分发 `server/data/updates/bridge-v0.2.87.apk` + Downloads + update.json（0.2.87；0.2.86 同版本号覆盖无法触发手机端升级，已重发）；②服务端：odometer 查询端点 py_compile 通过 + 段电耗公式单测通过（73.55V 均压口径与设备端一致，Δ容量≤0 判无效）；③后台：index.vue 重写 + jikong.ts 扩展，`npm run build`（vue-tsc+vite）通过。**待验证**：真机升级 0.2.87 后后台手机壳四 Tab 数据实时性、统计曲线、MOS 控制面板 |
| **补丁（21:26，用户反馈两点）** | ①后台目标设备接全局 `useTargetDeviceStore`（与命令下发/自动化台一致：localStorage 记忆、切换自动保存、跨页面保持，无记忆才回退第一台）；②`enterDemoMode` 末尾补 `reportStatus()`——演示数据（connected=true）上报服务端，后台显示「已连接 · 演示设备」可用于联调；演示退出路径本就上报复位。同版本未装机，APK 直接覆盖分发（46,123,195 B），提交 `27390e6` |
| **复刻范围说明** | 手机壳内四 Tab 与客户端 1:1（首页含仪表盘/健康横幅/时速卡/双四格/电流功率卡；实时页双列+单体电压/内阻网格；统计页只读台账+曲线；设置页镜像含开关只读）。**管理台专属面板放在手机壳外**：远程控制（MOS/均衡，手机端已按官方 UI 移除控制入口）+ 保护/告警状态 + 设备信息 |

| 项 | 内容 |
|---|---|
| **需求** | 管理后台「极空保护板」页从 Element Plus 通用仪表盘风格改为**手机壳 1:1 复刻**（390px 居中一台，四 Tab 与手机 APP 完全一致）；补齐客户端一直没上报的数据（时速仪表盘快照、本地设置），统计台账走 MySQL 查询 |
| **回退目标版本** | v0.2.85（统计页，未真机验证但功能完整） |
| **回退标记（git 快照）** | 提交 **`3fd7bb0`**（`feat: v0.2.85 极空统计页…`，工作区干净即此提交）——本次改动前全部文件原文以此为准 |
| **回滚配方** | ①客户端：`git checkout 3fd7bb0 -- app/src/main/java/my/bridge/com/jikong/JikongController.kt app/build.gradle.kts` 重新打包（恢复 0.2.85 上报内容）；②服务端：`git checkout 3fd7bb0 -- server/app/api/v1/jikong.py`（去掉台账查询端点）；③后台：`git checkout 3fd7bb0 -- admin/src/views/jikong/index.vue admin/src/api/jikong.ts` 重新 build |
| **触发回滚的信号** | ①上报扩展导致 jikong_state 主链路异常（状态不更新/WS 报错）；②后台页构建失败或极空页白屏；③台账查询接口拖垮服务端 |
| **改动内容** | ①客户端 v0.2.86：`JikongController.reportStatus` 的 jikong_state 上报 status 对象内附带 `dash`（speed_kmh/gps_fresh/range_km/inst_wh_km/avg_wh_km/trip_km/trip_wh/trip_seconds/bms_stale/updated_at）与 `app`（theme/dash_enabled）两块（SpeedDash 单例 + JikongPrefs）；②服务端：jikong_state 原样透传（store 存原始 dict，零改动）+ `GET /api/jikong/{device_id}/odometer`（require_admin，odometer_store.list + 服务端算段电耗）；③后台：重写 `admin/src/views/jikong/index.vue`（手机壳四 Tab）+ `admin/src/api/jikong.ts` 补字段 |

### v0.2.85 极空统计页（里程台账+能耗曲线+MySQL 留档）（2026-11-06 20:20 实施，已打包分发，待真机验证）

| 项 | 内容 |
|---|---|
| **实施结果（改后回填，2026-11-06 20:45）** | 编译一次通过（compileDebugKotlin + assembleDebug 增量）；APK 实证 versionName=0.2.85（UTF-16 编码）+ JikongStatsStore/JikongStatsSync/StatsView 均在 dex；服务端 py_compile 通过；分发完成 `server/data/updates/bridge-v0.2.85.apk`（45,956,038 B）+ Downloads + update.json（0.2.85）。**真机未连 adb，待用户更新后验证**：①统计 Tab 位置/图标/亮暗主题；②录入→列表/概要/曲线联动；③同步成功与失败（可停服务端 MySQL 演示）Toast 与标记状态机；④删行联动 |

| 项 | 内容 |
|---|---|
| **回退目标版本** | v0.2.84（已真机确认基线：无握手 + 时速仪表盘） |
| **回退标记（git 快照）** | 提交 **`3d09eb7`**（`chore: 快照 v0.2.84（极空统计页功能实施前的回退锚点）`）——首次把 v0.2.84 全量工作区状态入库（含此前未跟踪的 SpeedDash.kt / 统计.svg / 原型 HTML），**本次改动前全部文件的原文以此提交为准** |
| **回滚配方** | `git checkout 3d09eb7 -- app/src/main/java/my/bridge/com/ui/JikongPages.kt app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/build.gradle.kts server/app/api/v1/router.py server/app/main.py server/data/updates/update.json`，另**手动删除**新增文件：`app/src/main/java/my/bridge/com/jikong/JikongStatsStore.kt`、`JikongStatsSync.kt`、`app/src/main/res/drawable/ic_jk_stats.xml`、`server/app/core/odometer_store.py`、`server/app/api/v1/odometer.py`（MySQL 表 jk_mileage_records 可留可删，无副作用） |
| **触发回滚的信号** | ①统计页导致极空主流程异常（连接/状态上报/时速仪表盘被干扰）；②同步逻辑拖垮主 WS 或产生大量重复请求；③服务端 503 频发且 MySQL 正常（新表/SQL 问题）；④编译/打包失败 30 分钟内无法定位 |
| **改动内容** | 客户端：底部导航第 3 位新增「统计」Tab（图标 `assets/icons/统计.svg` → `ic_jk_stats`，顺序 首页/实时状态/**统计**/参数设置）；`JikongStatsStore`（SharedPreferences JSON 数组：id/ts/odoKm/cycleAh/volt/synced + 待删除队列，org.json 范式）；统计页 UI（概要三卡=区间里程/总耗电/平均电耗、录入卡=里程输入+自动读取循环容量/总压、Canvas 能耗曲线、固定高度倒序台账含删除/未同步徽标/同步按钮+Toast、口径说明）。同步：录入成功后**静默自动同步一次** + 手动按钮兜底，失败保「待同步」标记可重试；服务端：`server/app/core/odometer_store.py`（MySQL 表 `jk_mileage_records`，按 device_id+record_id 幂等 upsert，删除=tombstone deleted=1）+ REST `POST /api/v1/odometer/sync`（X-Device-Id/X-Device-Token 鉴权，复用 locations.mysql 配置，无新配置项） |
| **口径** | 段电耗 Wh/km = Δ循环容量(Ah) × 两记录平均电压(V) ÷ Δ里程(km)；循环容量只增不减，充电不影响；Δ容量≤0（BMS 重置/换电池）→ 该段标记无效不进曲线并重设基准；新里程须 > 上一条 |

### v0.2.84 回退握手改动（2026-11-06 18:55 实施；19:47 用户真机确认，**定为当前基线**）

| 项 | 内容 |
|---|---|
| **当前版本（基线）** | **v0.2.84 = v0.2.81 连接时序 + v0.2.83 时速仪表盘（握手改动已完全移除）**。2026-11-06 19:47 用户真机验证通过，明确拍板「暂时将当前版本链路定为当前版本」 |
| **基线验证结论（用户观察）** | ①官方 APP 链路**也时通时不通**（「官方链路有时能通，有时不能」）→ 半死链路是官方同样存在的现象，并非我们实现独有缺陷；②v0.2.84 回退版：偶发退出重连（有时间隔约 1 分钟）**但至少能通** → 可用性达标，作为后续迭代的对照基线 |
| **握手链路处置** | **暂存**（用户拍板 2026-11-06 19:47）：21B 握手帧 / 官方时序对齐 / 半死链路根因（快档 800ms 重连嫌疑等）整体搁置，留待以后具体分析和剖析；逆向成果保留在本文档与 2026-11-06 工作日志，随时可复用 |
| **实施方式** | `git checkout cacc8f5 -- app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt JikongController.kt JikongProtocol.kt`（仅回滚握手 3 文件；`SpeedDash.kt`/`JikongPrefs.kt`/`JikongPages.kt` 保留） |
| **验证** | 编译 3m27s 通过（时速仪表盘与 v0.2.81 Controller API 完全兼容）；APK 实证 0.2.84 + `buildOfficialHandshake` 0 残留 + SpeedDash 在 dex；真机可用（见上） |
| **回退背景（历史，已证伪）** | ① v0.2.83 真机实证：官方时序完全生效（21B 握手帧发送成功+CCCD+MTU303）但 BMS 休眠时 0 帧数据；② 二轮逆向定论：官方 APP 无独立激活帧（第 3 声蜂鸣=0x6C 时间同步帧，收到数据后才发；0xC8=OTA 帧；监控平台协议 0x01 激活指令是 4E57 帧，不适用 55AAEB 蓝牙直连）；③ 官方 CONNECT_TIMEOUT 10s 报错不重连 |
| **遗留疑点（暂存，待以后剖析）** | 官方 APP 同场景十几秒连接时长但**链路不断**，我们曾 13s 必 status=8 → 候选原因：①800ms 快档重连循环（BMS 未清完旧链路→新链路质量差→supervision timeout）；②requestConnectionPriority(HIGH) 弱信号副作用。**恢复剖析时一次只测一个变量，先以 0.2.84 基线对照** |
| **若未来要再调链路** | 候选微调：快档重连 800ms→2s（给 BMS 清链时间）；去掉启动 ramp 的 0x96 轰炸（官方只发一次后等待）。**改前先在本台账登记改前快照** |

### v0.2.83 时速仪表盘（2026-11-06 18:35 实施，用户已确认，待真机验证）

| 项 | 内容 |
|---|---|
| **回退目标版本** | v0.2.82（官方握手对齐，功能完整但仪表盘尚未验证） |
| **回退标记（git 快照）** | 提交 **`a4f5404`**（`chore: 快照 v0.2.82（时速仪表盘功能实施前的回退锚点）`） |
| **回滚配方** | `git checkout a4f5404 -- app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt app/build.gradle.kts server/data/updates/update.json`，另**手动删除**新增文件 `app/src/main/java/my/bridge/com/jikong/SpeedDash.kt`（git 未跟踪） |
| **触发回滚的信号** | ①仪表盘开关打开后 GPS 异常耗电/发热；②首页卡片数值明显错误（如续航乱跳、里程虚增）；③仪表盘逻辑干扰既有极空连接/上报 |
| **上一轮锚点** | v0.2.82 官方握手对齐（回退标记 `cacc8f5` = v0.2.81，见下节） |

### v0.2.82 对齐官方握手序列（2026-11-06 17:40 实施，待真机验证）

| 项 | 内容 |
|---|---|
| **回退目标版本** | v0.2.81（功能正常、偏慢但可用的最后版本） |
| **回退标记（git 快照）** | 提交 **`cacc8f5`**（`chore: 快照 v0.2.81（极空官方握手帧对齐前的回退锚点）`），含 v0.2.76~81 全部工作区状态 |
| **回滚配方** | 见下方命令块 |
| **当前版本** | v0.2.82（改动已落地、打包、分发，**用户尚未真机测试**） |

**回滚配方**（在仓库根目录执行，一条命令退回 v0.2.81 全部相关文件）：

```bash
git checkout cacc8f5 -- \
  app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt \
  app/src/main/java/my/bridge/com/jikong/JikongController.kt \
  app/src/main/java/my/bridge/com/jikong/JikongProtocol.kt \
  app/build.gradle.kts \
  server/data/updates/update.json
```

回滚后重新本地打包即可恢复 0.2.81 行为（注意 `app/build.gradle.kts` 里 versionName 会随文件一起退回 0.2.81，如需保持新版本号再手动改回）。

**触发回滚的信号**（任一出现即考虑回滚）：
1. **健康链路回归失败**：正常近距离连接（非闲置首连）也长时间无数据 / 0x02 不来——说明官方时序反而干扰了本来能用的链路；
2. **BMS 对 21B 握手帧行为异常**：如 ACK 帧循环、0x02 爆发消失、设备信息帧（0x03）不再返回；
3. **连接成功率下降**：自动重连频繁失败、或 12s 自愈反而更频繁触发；
4. 编译/打包失败且 30 分钟内无法定位。

**本次改动内容**（改了什么，便于回滚前判断问题归属）：
- `JikongProtocol.kt`：新增 `buildOfficialHandshake()`——官方 21B 握手帧 `AA 55 90 EB 97 + 15×00 + 11`（CRC=0x311 低 8 位=0x11，与 20B 0x97 帧 CRC 同值，0 不参与累加）。
- `JikongBleClient.kt`：
  - STATE_CONNECTED：`requestConnectionPriority(HIGH)` → **80ms** 后 `discoverServices`（官方时序；移除旧的先 `requestMtu(256)` 后发现服务 + 1.5s 兜底）；
  - `onDescriptorWrite` 成功（CCCD 订阅完成）→ **40ms** 后发官方 21B 握手帧 → `requestMtu(517)`（官方时序：MTU 在 CCCD 之后）；`handshakeSent` 每链路一次，STATE_CONNECTED / disconnect() 复位。
- `JikongController.kt`：`startPolling` 删除首发 0x97（让位给握手帧）；启动 ramp 的 0x97 重发改用 21B 握手帧；`requestRealtimeData()` 同步替换。
- **保留未动**（v0.2.77~80 的保险层）：0x96 每 1.2s 催流、4s 断流自愈、12s 半死链主动重建、180s 自动重连（status=8 快档 800ms）。

**背景**（为什么改）：真机实证「蓝牙闲置 ~10 分钟后首连 → 链路建立但无数据 → 干等 ~1min 至 status=8 断开 → 第 2 条链路立即出数」。官方 APP 无任何重连机制却总能一次连接出数，逆向 `JK_BMS_6.4.04.apk`（dexdump 反汇编 `com.jktech.bms.ble.BleMessage`）找到三处差异：①HIGH 连接优先级 ②CCCD 后 40ms 的 21B 握手帧（推测为 BMS 应用层会话激活信号）③MTU 517 时序。

---

## §二 历史改动登记（按时间倒序）

| 日期 | 版本 | 改动 | 快照/回退方式 |
|---|---|---|---|
| 2026-11-06 | v0.2.76~81 | 0x02 断流自愈、demo 重置、内阻@80/平均重算、自动重连（180s/双档）、GATT 串行化（MTU→发现服务→CCCD）、强跳过冻结修复（基元传参）、12s 半死链自愈、日志降噪 | 均含于快照 `cacc8f5`；更早回退点见 git 历史（`3778aca` 之前） |
| 2026-11-06 | v0.2.82 | 对齐官方握手序列（见 §一） | 已回退到 `cacc8f5` = v0.2.81 |
| 2026-11-06 | v0.2.83 | 时速仪表盘（SpeedDash + 首页卡片 + 设置页清空） | 回退到 `a4f5404` = v0.2.82（已验证，无需回退） |
| 2026-11-06 | v0.2.84 | 回退握手 3 文件至 v0.2.81 + 保留时速仪表盘；**用户真机确认定为当前基线**，握手链路暂存 | 基线本身即回退产物；再回退可用 `a4f5404`（含握手）或 `cacc8f5`（无握手无仪表盘） |
| 2026-11-06 | v0.2.85 | 统计页：里程台账/能耗曲线/同步按钮（本地为准+MySQL 留档），见 §一 | 回退到 `3d09eb7` = v0.2.84 |

> 登记规则：今后极空相关每次改版，先在 §一 更新「当前生效的回退锚点」，把旧锚点移入 §二。
