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

### v0.2.115 播报音量提升重构：所有播报都调 + 智能检测其他程序 + 强制开关（2026-11-08 19:05 实施）

**背景（用户 11-08 18:48 反馈）**：用户实测发现「锁屏/熄屏时没调音量」，且明确纠正核心诉求——**所有播报都调节音量，不是只有连接成功首次收到数据才调**。日志实证：旧逻辑「锁定/熄屏/极空页」三条件下音量提升其实**部分生效**（6 次提升），但 21 次播报里有 15 次没提升（亮屏已解锁、不在极空页场景），体感像「只有首次才调」。用户拍板：
- **默认智能检测**：播报前查有无**其他程序**在放声音，没有才拉满（避免打断听歌/看视频/导航）。
- **智能 + 强制两个开关**：主开关「播报时提升音量」默认开；子开关「强制提升」默认关（无条件拉满）。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交 `7faa9a1` 存全文）**：
1. `SpeakVolumeBoost.kt`（改前 v0.2.114 版）：
   ```kotlin
   @Volatile
   private var extraBoostCondition: (() -> Boolean)? = null
   fun setExtraBoostCondition(cond: (() -> Boolean)?) { extraBoostCondition = cond }

   fun shouldBoost(context: Context): Boolean {
       val km = context.getSystemService(KeyguardManager::class.java)
       val pm = context.getSystemService(PowerManager::class.java)
       val locked = runCatching { km?.isDeviceLocked == true }.getOrDefault(false)
       val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
       val extra = runCatching { extraBoostCondition?.invoke() == true }.getOrDefault(false)
       return locked || !screenOn || extra
   }
   ```
2. `JikongController.kt` init 块尾部（改前注入极空页可见）：
   ```kotlin
   SpeakVolumeBoost.setExtraBoostCondition { pageVisible }
   ```
3. `app/build.gradle.kts`：`versionName = "0.2.114"`。

**改动清单**：
- `SpeakVolumeBoost.kt`：删 `extraBoostCondition`/`setExtraBoostCondition` 与「锁定/熄屏/极空页」三条件判断；新增 `shouldBoostProvider` 注入字段 + `setShouldBoostProvider`；`shouldBoost` 改为委托注入函数（未注入恒 false 安全默认）；新增 `isOtherAppPlaying(context)`（Android 8.0+ 用 `getActivePlaybackConfigurations` 遍历 usage ∈ {MEDIA/GAME/NAVIGATION_GUIDANCE/ASSISTANT/UNKNOWN}，8.0 以下降级 `isMusicActive`）。`begin`/`end`/`describeLock`/`describeScreen` 不变。
- `JikongController.kt`：注入改为完整决策函数 `{ ctx -> 主开关关→false；强制开关开→true；否则 !isOtherAppPlaying(ctx) }`；`reportStatus` app 块新增 `volume_boost_enabled`/`volume_boost_force` 两属性（后台镜像）。
- `JikongPrefs.kt`：新增 `volumeBoostEnabled`（默认 true）/`setVolumeBoostEnabled`、`volumeBoostForce`（默认 false）/`setVolumeBoostForce`，key `jikong_volume_boost_enabled`/`jikong_volume_boost_force`。
- `JikongPages.kt`：设置页「语音播报」卡新增两个开关（「播报时提升音量」主开关 + 「强制提升」子开关，子开关 `enabled = volumeBoostEnabled`）；状态声明 + `remoteSpeakTick` 重读同步。
- `versionName` 0.2.114→0.2.115。
- **不动**：JikongSpeaker（`begin`/`end` 调用点原样）、JikongBleClient、JikongProtocol、握手实验④（12s 阈值、0x96、反射 7.5ms）全部原样——本次只改「数据到手之后的播报音量」环节，与握手零交集。

**回滚配方**：`git checkout 7faa9a1 -- app/src/main/java/my/bridge/com/data/SpeakVolumeBoost.kt app/src/main/java/my/bridge/com/jikong/JikongController.kt app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt app/build.gradle.kts`；或 revert 本轮提交。

**触发信号**：无（纯功能重构，非 bug 修复）。

**实施结果（2026-11-08 19:15 回填）**：`compileDebugKotlin` **BUILD SUCCESSFUL**（5m52s，仅存量 deprecation 警告，无害）。首轮编译踩坑 1 个：`AudioPlaybackConfiguration` 无 `isActive` 属性（`getActivePlaybackConfigurations()` 返回的本身就是「活跃播放」列表），删掉 `cfg.isActive &&` 后通过。**未打包分发**（待用户确认打包时机）。

**背景（用户 11-08 17:29 反馈）**：两块诉求，同版本合并打包（用户拍板）：
- **A｜音量增补**：v0.2.112 的「锁定或熄屏」音量提升已够后台静默场景，但用户还要求**客户端前台且处于极空页**时也临时拉满音量播报（主动打开极空页看数据时播报也要清晰）。
- **B｜半死链实验④**：用户反馈「靠近几分钟不播报 + 切极空页重连几次才有数据」，日志实证实验②a（20s 阈值）**未命中且更差**（5 次连接 97s），拍板「改回 12s + 强制 7.5ms 连接参数」。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交 `7faa9a1` 存全文）**：
1. `SpeakVolumeBoost.kt` `shouldBoost`（改前只有两条件）：
   ```kotlin
   fun shouldBoost(context: Context): Boolean {
       val km = context.getSystemService(KeyguardManager::class.java)
       val pm = context.getSystemService(PowerManager::class.java)
       val locked = runCatching { km?.isDeviceLocked == true }.getOrDefault(false)
       val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
       return locked || !screenOn
   }
   ```
2. `JikongController.kt` init 块尾部（改前无注入）：
   ```kotlin
   JikongBleClient.onDataReceived = { bytes ->
       handleData(bytes)
   }
   ```
3. `JikongController.kt` startPolling 重建阈值（改前 20s）：
   ```kotlin
   if (now - connectAt > 20_000) {
   ```
4. `JikongBleClient.kt` STATE_CONNECTED（改前只有 HIGH 请求）：
   ```kotlin
   runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
   ```
5. `app/build.gradle.kts`：`versionName = "0.2.113"`。

**改动清单**：
- **A｜音量**：`SpeakVolumeBoost.kt` 加 `extraBoostCondition` 字段 + `setExtraBoostCondition(cond)` setter，`shouldBoost` 加 `|| (extraBoostCondition?.invoke() == true)`；`JikongController.kt` 加 import + init 注入 `SpeakVolumeBoost.setExtraBoostCondition { pageVisible }`。
- **B｜半死链实验④**：`JikongController.kt` startPolling 重建阈值 20_000→12_000（回退实验②a 的失败改动，恢复实验①基线）；`JikongBleClient.kt` 加 `forceLeConnectionParams(g)`——反射调用 AOSP 隐藏 API `requestLeConnectionUpdate(6, 6, 0, 200, 0, 0)`（min=max=7.5ms、latency=0、timeout=2s），失败静默降级 HIGH（公开 API 只有 requestConnectionPriority，HIGH 在 Android 6+ 仅 11.25~15ms，真 7.5ms 只能反射 @hide 方法）。
- `versionName` 0.2.113→0.2.114。

**回滚配方**：`git checkout 7faa9a1 -- app/src/main/java/my/bridge/com/data/SpeakVolumeBoost.kt app/src/main/java/my/bridge/com/jikong/JikongController.kt app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt app/build.gradle.kts`；或 revert 本轮提交。

**触发信号**：B 实验④归因看日志——`实验④强制连接参数：requestLeConnectionUpdate 反射成功` vs `实验④反射 requestLeConnectionUpdate 失败（降级 HIGH）`。

**实施结果（2026-11-08 18:18 回填）**：`compileDebugKotlin` **BUILD SUCCESSFUL**（1m28s，仅存量 deprecation 警告）。打包环节：CI #271 打包成功、Release 已建，但 gh-proxy 全镜像 504（release-assets CDN 又波动）+ artifact 通道 Azure blob 直连限速（47KB/s 下 35MB 需 12 分钟）→ 用户拍板**改本地 assembleDebug 打包**（3m42s BUILD SUCCESSFUL，APK 46,054,402 字节，aapt 实证 versionName=0.2.114），落地 `server/data/updates/bridge-v0.2.114.apk` + update.json（versionCode 314 / sha256 `1ff37770…`）。唯一留存：旧版本 + 编译产物已清理。**已分发**。

---

### v0.2.113 锁屏/息屏回执文案去歧义 + 管理后台「查看详情」弹窗（2026-11-08 16:35 实施）

**背景（用户 11-08 16:31 反馈，v0.2.112 真机验证后）**：①回执文案 `screen_on=false（屏幕点亮）` 自相矛盾——括号里「屏幕点亮」是固定字段说明，与 `false` 读起来冲突；②希望命令历史回执列加「查看详情」按钮，点开格式化展示状态。两条诉求分别落在两端：**回执文案在客户端**（手机端产生，需重打包 APK）；**详情弹窗在管理后台**（admin 前端，构建部署即生效，不依赖 APK）。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交 `7faa9a1` 存全文）**：
1. `SpeakVolumeBoost.kt` `describeLock` / `describeScreen`：
   ```kotlin
   fun describeLock(context: Context): String {
       val km = context.getSystemService(KeyguardManager::class.java)
       val locked = runCatching { km?.isDeviceLocked == true }.getOrDefault(false)
       val showing = runCatching { km?.inKeyguardRestrictedInputMode() == true }.getOrDefault(false)
       return "锁屏状态：device_locked=$locked（是否需要解锁），" +
           "keyguard_showing=$showing（锁屏界面显示中）"
   }
   fun describeScreen(context: Context): String {
       val pm = context.getSystemService(PowerManager::class.java)
       val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
       return "屏幕状态：screen_on=$screenOn（屏幕点亮）"
   }
   ```
2. `admin/src/views/command/index.vue`「查看详情」按钮 `v-if`：
   `canViewVntDetail(row) || canViewSshDetail(row) || canViewUiDetail(row) || canViewDeviceStatsDetail(row)`
   及 `openDetail` 分流仅 4 分支（无 deviceState）。

**改动清单**：
- `SpeakVolumeBoost.kt`：`describeLock`/`describeScreen` 括号中文改为**按值动态结论**——`device_locked=true（已锁定）/false（未锁定）`、`keyguard_showing=true（锁屏界面显示中）/false（锁屏界面未显示）`、`screen_on=true（亮屏中）/false（息屏中）`。字段名 + `key=bool` 结构不变（详情弹窗仍按 `key=true|false` 正则解析）。
- 新增 `admin/src/components/DeviceStateResultDialog.vue`（`get_lock_state`/`get_screen_state` 的「查看详情」弹窗，沿用 VNT/SSH/UI/电流量同款「按命令分治」约定）：元信息条 + 核心结论卡（该维度对 `shouldBoost = 锁定 || 熄屏` 的贡献：锁定/息屏 → 触发提升，未锁定/亮屏 → 不触发并提示查另一维度）+ 结构化字段网格 + 回执原文（复制）。
- `admin/src/views/command/index.vue`：import + 组件引用 + `DEVICE_STATE_DETAIL_COMMANDS`/`canViewDeviceStateDetail` + `openDetail` 加第 5 分支 + 按钮 `v-if` 追加 `|| canViewDeviceStateDetail(row)`。
- `docs/api/commands/control.md`：两命令回执示例同步为新文案。
- `versionName` 0.2.112→0.2.113（客户端文案需重打包才在手机端生效）。
- **不动**：JikongController / JikongBleClient / JikongProtocol（握手实验②a 的 20s 阈值、0x96 频率、3 次重建保底等全部原样保留）。

**回滚配方**：`git revert` 本轮提交；或 `git checkout 7faa9a1 -- app/src/main/java/my/bridge/com/data/SpeakVolumeBoost.kt app/build.gradle.kts admin/src/views/command/index.vue docs/api/commands/control.md`，另**手动删除**新增文件 `admin/src/components/DeviceStateResultDialog.vue`。
**触发信号**：①回执仍显示「（屏幕点亮）」说明客户端未打包生效；②锁屏/息屏命令「查看详情」按钮不出现或弹窗解析不出字段（key 格式变动）；③音量提升结论方向相反（锁定/息屏应「会触发」）。

**实施结果（2026-11-08 17:19 回填，已打包分发）**：客户端 `compileDebugKotlin` **BUILD SUCCESSFUL**（1m24s，仅 `inKeyguardRestrictedInputMode` 一处 deprecation，无害）；admin 前端 `npx vue-tsc -b` 类型检查通过（0 错误）+ `npm run build` **built in 21.54s**（新组件 DeviceStateResultDialog 已打进 dist）。`versionName` 已 bump 0.2.112→0.2.113。**admin 前端交付方式**：用户本地 `npm run dev`（开发态，改动即时生效，无需部署 dist）。**客户端打包分发**（用户 16:54 拍板）：本地 commit `b800841` → `api_push.py` 推送（远端 main `cecec1dfad`）+ 建 tag `v0.2.113`（**本次 tag 自动触发 Actions #270**，无需手动 dispatch）→ CI 打包 success（Build Debug APK + Create Release 均 success）→ 下载 **踩坑**：铁律镜像 `v4.gh-proxy.org` 连续 504（Release 资产限速，直连 release-assets 仅 27KB/s），探测 8 个镜像后换 `gh-proxy.com`（Range 0-0 返回 206 存活）成功下载 46,054,402 字节 → aapt 实证 versionName=0.2.113 → 落地 `server/data/updates/bridge-v0.2.113.apk` + update.json（versionCode=313=200+113，sha256 `d844c888…`）→ 唯一留存（删 bridge-v0.2.112/111.apk + releases 副本 + app/build 残留）。**待用户真机验证**：①客户端「检查更新」装 v0.2.113；②锁屏/熄屏回执文案是否显示动态中文结论；③管理后台「查看详情」弹窗。

---

### v0.2.112 锁屏播报音量临时提升（2026-11-08 15:40 实施）

**背景（用户 11-08 15:35 穿插需求，与实验②a 并行，互不影响）**：合成侧已把响度拉满（服务端 -8 LUFS），但用户若把手机媒体音量调得很小，锁屏（未解锁）状态下播报（如靠近自动连接连上后的电量播报）依然很小听不清。需求三步：①播报前若处于锁屏状态，记录当前媒体音量；②临时拉到最大；③播报完成后恢复原值。**仅媒体流**（STREAM_MUSIC），不动铃声/闹钟/通知/系统流。**用户明确此需求与握手实验无交集**（只动「数据到手后的播报」环节，不碰 BLE/握手/收数据），不影响实验②a 判定（首建链是否 20s 内无重建直接出数据、蜂鸣次数）。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交 `e3ed523` 存全文）**：
1. `JikongSpeaker.kt` `playMp3`（在线 mp3 播放）：
   ```kotlin
   private fun playMp3(file: File): Boolean = runCatching {
       player?.let { p -> runCatching { p.stop(); p.release() } }
       val p = MediaPlayer().apply {
           setAudioAttributes(
               AudioAttributes.Builder()
                   .setUsage(AudioAttributes.USAGE_MEDIA)          // STREAM_MUSIC（用户拍板）
                   .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                   .build(),
           )
           setDataSource(file.absolutePath)
           setOnCompletionListener {
               runCatching { it.release() }
               if (player === it) player = null
           }
           prepare()   // 本地文件，IO 线程同步 prepare 可接受
           start()
       }
       player = p
       Timber.tag(TAG).i("在线语音播放：%s", file.name)
       true
   }.onFailure { e -> Timber.tag(TAG).w(e, "在线语音播放失败，将回退本地引擎") }.getOrDefault(false)
   ```
2. `JikongSpeaker.kt` `doSpeak`（本地 TTS 回退）：
   ```kotlin
   private fun doSpeak(engine: TextToSpeech, text: String) {
       runCatching {
           engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
               override fun onStart(utteranceId: String?) {}
               override fun onDone(utteranceId: String?) {
                   Timber.tag(TAG).i("播报完成：%s", utteranceId)
               }
               @Deprecated("Deprecated in Java")
               override fun onError(utteranceId: String?) {
                   Timber.tag(TAG).w("播报出错：%s", utteranceId)
               }
           })
           val accepted = engine.speak(
               text, TextToSpeech.QUEUE_FLUSH, null, "jk_speak_${System.currentTimeMillis()}",
           )
           if (accepted != TextToSpeech.SUCCESS) {
               Timber.tag(TAG).w("TTS 引擎拒绝播报请求（可能引擎未就绪）")
           }
       }.onFailure { Timber.tag(TAG).w(it, "播报异常（已忽略）") }
   }
   ```
3. `localSpeak` 调用点：`if (ready) doSpeak(cur, text) else pending = text`、`if (text0 != null && tts != null) doSpeak(tts!!, text0)`；`speak`/`speakSegs` 调用点：`playMp3(file)`、`playMp3(out)`。
4. `app/build.gradle.kts`：`versionName = "0.2.111"`。

**改动清单**：
- 新增 `app/src/main/java/my/bridge/com/data/SpeakVolumeBoost.kt`（object 单例）：`shouldBoost(context)`=「设备锁定 **或** 屏幕熄灭」（`KeyguardManager.isDeviceLocked() || !PowerManager.isInteractive()`，**用户拍板「锁定或熄屏」**——覆盖有锁/无锁 + 熄屏及亮屏锁屏界面，仅亮屏已解锁不动）；`begin(context)` 命中时记录媒体音量并 `setStreamVolume(STREAM_MUSIC, max, 0)`；`end(context)`=恢复原档。幂等（`boosted`+`original` 双字段，重叠/连续播报只记录一次、最后一次 end 才恢复）；媒体流不受 DND 限制，无 SecurityException 风险（runCatching 兜底）。
- `JikongSpeaker.kt`：`playMp3`/`doSpeak` 签名加 `app: Context`，播放前 `begin`，`onCompletion`/`onError`/`onDone`/拒绝播报/异常统一 `end`；两处调用点（speak、speakSegs、localSpeak 两处）传 `applicationContext`。
- `versionName` 0.2.111→0.2.112。
- **测试验证命令（两个，用户要求分开测「是否锁屏」「是否息屏」）**：
  - `get_lock_state`（查询锁屏状态）：`SpeakVolumeBoost.describeLock()` 回执 `device_locked`（是否需要解锁）+`keyguard_showing`（锁屏界面显示中）；`Command.kt` 加 `GET_LOCK_STATE`；`CommandModule.kt` 加 `executeGetLockState`；`command_catalog.py` 加 `_cmd("get_lock_state", "查询锁屏状态", "control", "🔐", …)`。
  - `get_screen_state`（查询息屏状态）：`SpeakVolumeBoost.describeScreen()` 回执 `screen_on`（屏幕是否点亮）；`Command.kt` 加 `GET_SCREEN_STATE`；`CommandModule.kt` 加 `executeGetScreenState`；`command_catalog.py` 加 `_cmd("get_screen_state", "查询息屏状态", "control", "📴", …)`。
  - 管理后台命令页动态读 catalog，两命令自动出现，无需改前端；文档 `control.md` 加 §get_lock_state + §get_screen_state + `README.md` 加索引。
- **不动**：JikongController / JikongBleClient / JikongProtocol（握手实验②a 的 20s 阈值、0x96 频率、3 次重建保底等全部原样保留）。

**回滚配方**：`git revert` 本轮提交；或 `git checkout e3ed523 -- app/src/main/java/my/bridge/com/jikong/JikongSpeaker.kt app/build.gradle.kts core/core-network/src/main/java/my/bridge/com/core/network/ws/Command.kt app/src/main/java/my/bridge/com/di/CommandModule.kt server/app/core/command_catalog.py docs/api/commands/control.md docs/api/commands/README.md`，另**手动删除**新增文件 `app/src/main/java/my/bridge/com/data/SpeakVolumeBoost.kt`。
**触发信号**：①锁屏播报后音量未恢复（永久停在最大）；②亮屏解锁播报被误调音量（应只在锁屏生效）；③播报无声/破音（音量提升逻辑干扰播放）；④实验②a 判定被干扰（理论上不可能——本改动不碰握手，但作为观察项）。

**实施结果（2026-11-08 16:18 回填）**：`compileDebugKotlin` **BUILD SUCCESSFUL**。首轮编译踩坑 1 个：`setOnErrorListener` 用 `_, _, _` 三个通配符导致 lambda 无隐式 `it`（`it.release()` 无法解析，`Cannot infer type`），改显式参数 `mp, _, _` 修复。随后按用户拍板把判断改为「锁定或熄屏」、拆成 `get_lock_state`+`get_screen_state` 双命令（catalog + Command + CommandModule + SpeakVolumeBoost.describeLock/describeScreen + 文档），客户端二次编译 **BUILD SUCCESSFUL**（2m6s，仅 `inKeyguardRestrictedInputMode` 一处 deprecation 提示，无害）；服务端 `command_catalog.py` py_compile 通过。**已打包分发（用户 16:06 拍板）**：本地 commit `a065cd8` → API 通道 push（api_push.py，远端 main `1d433c28`）→ 建 tag `v0.2.112` + `dispatch release.yml` 触发 CI → CI 打包成功（Create Release success）→ gh-proxy 下载 APK 46,054,402 字节 → aapt 实证 versionName=0.2.112 → 落地 `server/data/updates/bridge-v0.2.112.apk` + update.json（versionCode=312，sha256 `96555f6a…`）→ 删 app/build 编译产物 + releases 副本；update.json 单独 commit `882ca32` + API push（远端 main `735bfe8c`）。**待用户真机验证**：①装 0.2.112 后在管理后台分别下发 `get_lock_state`/`get_screen_state` 验证锁屏/息屏判断；②锁屏/熄屏状态播报是否拉满音量且播完恢复。

---

### v0.2.111 握手修复实验②a：12s 重建阈值放宽到 20s（2026-11-08 15:20 实施）

**背景（实验①半命中后，用户 15:15 拍板）**：实验①（requestConnectionPriority HIGH）已把直连重建从 3~4 轮→1 轮（14s 出数据），但**首建链仍半死**：日志实证 BMS 苏醒延迟约 13~14s，而 12s 超时恰好卡在苏醒前断开重建。实验②a 单变量验证「放宽 12s 阈值」能否让首建链直接等到 BMS 苏醒出数据（= 官方一次成功，无需断开重建）。**用户明确：不能完全移除重建（要保底），故仅放宽阈值、3 次重建保底保留、0x96 补发频率保持 1.2s 不动**。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交存全文）**：
1. `JikongController.kt` `startPolling()` 启动 ramp 的 12s 重建阈值块（约 645-662 行）：
   ```kotlin
   // v0.2.80 首连半死链自愈（用户真机实证）：蓝牙闲置 ~10 分钟后首连，
   // 链路建立但数据通道打不通（BMS 蓝牙模块休眠未醒），旧流程要干等
   // ~1min 直到 status=8 断开；而自动重连建的第 2 条链路立即有数据。
   // ⇒ 12s 内仍无任何 0x02 就主动断开重建（dataRecyclePending → 快档
   // 重连），把 1 分钟缩短到 ~15s。连续重建限 3 次，防「休眠↔重建」死循环。
   if (now - connectAt > 12_000) {
       if (datalessRecycles < 3) {
           datalessRecycles++
           dataRecyclePending = true
           Timber.tag(TAG).w(
               "连接 %ds 仍无 0x02（疑似首连半死链），主动重建链路（第 %d/3 次）",
               (now - connectAt) / 1000, datalessRecycles,
           )
           JikongBleClient.disconnect()  // → 快档自动重连 → startPolling
           return@launch
       }
       Timber.tag(TAG).w("已连续重建 3 次仍无 0x02，保持当前链路继续 ramp（等模块自行苏醒）")
   }
   ```
2. 0x96 补发频率 `now - lastTriggerAt > 1200`（1.2s）——**本次保持不动**（实验②b 再单独测）。
3. `app/build.gradle.kts`：`versionName = "0.2.110"`。

**改动清单（严格单变量：只改 12s 阈值）**：
- `JikongController.kt`：`if (now - connectAt > 12_000)` → `20_000`（12s → 20s）；同步改注释「12s 内」→「20s 内」、「~15s」→「~22s」。
- versionName 0.2.110→0.2.111。
- **不动**：0x96 补发频率（1.2s）、3 次重建保底（`datalessRecycles < 3`）、`dataRecyclePending` 快档重连逻辑。

**回滚配方**：`git revert` 本轮提交；或 `git checkout 9ea241a -- app/src/main/java/my/bridge/com/jikong/JikongController.kt app/build.gradle.kts`。
**触发信号**：首建链在 20s 内直接出数据（无重建）→ 命中；若首建链 20s 后仍重建才出数据 → 未命中（12s 非元凶，改测实验②b 0x96 频率）。

**实施结果（2026-11-08 15:28 回填）**：`compileDebugKotlin` BUILD SUCCESSFUL（3m3s）。提交 `e9312d7`（rebase 对齐后 `82cdd83`）清代理直连 push（b1312e6..82cdd83）。`release.py v0.2.111` 建 tag 触发 CI 打包成功（gh-proxy 代理下载）。APK 46,054,398 字节，aapt 实证 versionName=0.2.111（versionCode=1，update.json 自定义 versionCode=311），分发 updates/bridge-v0.2.111.apk + update.json（sha256 `cc95715d…`）。**服务端零改动、无需重启**。**待用户真机验证**（重点：首建链是否无重建、~14~20s 内直接出数据、蜂鸣 ≤1 次）。

---

### v0.2.110 握手修复实验①：连接后 requestConnectionPriority(HIGH)（2026-11-08 14:25 实施）

**背景（用户 11-08 13:49 真机反馈 + 专档追踪）**：装 v0.2.109 后，靠近「能立马发现并建链」，但收到数据（0x02）要 3~4 轮 12s 超时直连重建（近 1 分钟）才播报；保护板 3~4 次「滴滴滴」。官方 APP 一次交互即成功。详见 `docs/research/jikong-handshake-fix.md`（专档）+ 调研文档 §四。

**根因假设**：官方在 STATE_CONNECTED 后立即 `requestConnectionPriority(HIGH)`（7.5ms 连接间隔，v0.2.82 dexdump 逆向实证），靠「高频连接事件」持续唤醒休眠的 MCU；而我们走系统默认 30~50ms 连接间隔，唤醒不够 → 半死链反复。实验①单变量验证：只加这一句，其余不动。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交存全文）**：
1. `JikongBleClient.kt` `onConnectionStateChange` STATE_CONNECTED 分支（约 336-348 行）——**当前无任何 requestConnectionPriority 调用**（grep 全库证实），连接建立后直接 `requestMtu(256)`：
   ```kotlin
   override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
       if (isStale(g)) return
       if (newState == BluetoothProfile.STATE_CONNECTED) {
           Timber.tag(TAG).i("GATT 已连接，先协商 MTU 再发现服务")
           servicesRequested = false
           cccdRetries = 0
           notifyLogBudget = 3
           // 请求更大 MTU（极空单帧超 20B，增大 MTU 减少分包，提升稳定性）。
           // discoverServices 移到 onMtuChanged 里串行执行。
           val requested = runCatching { g.requestMtu(256) }.getOrDefault(false)
   ```
2. `app/build.gradle.kts`：`versionName = "0.2.109"`。

**改动清单**：
- `JikongBleClient.kt`：STATE_CONNECTED 分支、发 `requestMtu(256)` 之前，加一句 `runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }`（runCatching 包裹防个别 ROM 抛异常打断后续流程）。
- versionName 0.2.109→0.2.110。

**回滚配方**：`git revert` 本轮提交；或 `git checkout 0f16419 -- app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt app/build.gradle.kts`。
**触发信号**：闲置 ≥10min 后靠近仍 3~4 轮 12s 才出数据（说明 HIGH 优先级不解决）/ 出现连接失败率升高（HIGH 优先级兼容性问题）。

**实施结果（2026-11-08 14:32 回填）**：`compileDebugKotlin` BUILD SUCCESSFUL（2m42s，仅存量 BLE deprecated 告警）。提交 `eb6e8cc`（rebase 对齐后 `9ea241a`）清代理直连 push（98a4edf..9ea241a）。`release.py v0.2.110` 建 tag 触发 CI 打包成功（gh-proxy 代理下载）。APK 46,054,402 字节，aapt 实证 versionName=0.2.110（versionCode=1，update.json 自定义 versionCode=310），分发 updates/bridge-v0.2.110.apk + update.json（sha256 `9568d9b5…`）。**服务端零改动、无需重启**。

**真机验证结论（2026-11-08 15:05 回填，用户多轮多情况测试 + 日志实证）**：**半命中**。日志 14:52:38 靠近 → 14:52:52 收到 0x02 播报 = **14 秒**，「主动重建链路」只到**第 1/3 次**即成功（基线 v0.2.109 是 3~4 轮 ≈ 1 分钟），改善约 3~4 倍；MTU 协商到 303（此前 256）。但**首建链仍半死**：14:52:38 建链后 0x96 连发 11 次（14:52:40~50）无 0x02，14:52:51 触 12s 超时断开、14:52:52 直连重建立即出数据。**关键线索**：BMS 苏醒延迟约 13~14s，12s 超时恰好卡在苏醒前断开 → 指向实验②（放宽/移除 12s 耐心等，有望首建链直接等到数据 = 官方一次成功）。详见专档 `jikong-handshake-fix.md` 实验①。

---

### v0.2.109 靠近自动连接「半死链自愈失效」修复：proximity 模式立即直连重建（2026-11-08 12:35 实施）

**问题（用户 11-08 12:32 真机反馈 + 上传日志，设备 f2c38ebee251a196）**：装 v0.2.108 后，靠近自动连接「连上了但一直收不到数据、不播报」。

**根因（日志实证，tag=Jikong / JikongBle，2026-11-08 12:18~12:23）**：
1. autoConnect 建链成功后 BMS 处于**半死链**（只回 `AT\r\n`/0x01 单体帧，进不了 0x02 实时流）——BMS 蓝牙模块休眠的固有行为，任何方式首连都可能遇到（v0.2.80 已实证）。
2. 半死链自愈靠「12s 无 0x02 → 主动断开 → **立即直连重建**（connectGatt autoConnect=false）」——手动连接路径（11:12 日志）实证：第 2 条直连链路立即有 0x02；直连路径（12:23:37 进页触发的 connect）也立即有 0x02。
3. 但 v0.2.107 触点②的断开分流把 `dataRecyclePending = false` 清掉、改走 `evaluateInPlace`（**重新挂起 autoConnect**）——重挂起唤醒不了休眠的 BMS，于是 12:18~12:23 反复「挂起→建链→半死（只 AT）→12s 断开→再挂起」恶性循环（日志：连续 3 次重建 + 3 次后「保持当前链路继续 ramp」仍无 0x02），永远拿不到 0x02 → 不播报。

**改前原文（关键块；改动文件 git 跟踪中，锚点提交存全文）**：
1. `JikongController.kt` 触点②断开分流（约 293-298 行）：
   ```kotlin
   if (autoReconnectEnabled && (pageVisible || keepConnection) && !proximityModeActive) {
       startReconnect(fast = dataRecyclePending)
   } else if (proximityModeActive && !manualSuppressed) {
       dataRecyclePending = false
       appContext?.let { JikongProximityService.evaluateInPlace(it) }
   }
   ```
2. `startReconnect()` 方法后直接 `private fun stopPolling()`，无 `startProximityReconnect`。
3. `app/build.gradle.kts`：`versionName = "0.2.108"`。

**改动清单**：
- `JikongController.kt`：触点②断开分流 proximity 分支内，`dataRecyclePending=true`（半死链自愈）时改走 `startProximityReconnect()`（立即直连重建）而非清标志+evaluateInPlace；`false`（走远自然断开）时保持 evaluateInPlace 重挂起不变。
- `JikongController.kt`：+`startProximityReconnect()`——对默认设备做一次 `JikongBleClient.connect(ctx, mac)`（autoConnect=false 直连），成功 → `prevAnnounceSoc=-1` + `clearScanCache()` + `startPolling()` + `proximityAnnouncePending=true`（首帧强制播）；失败 → `evaluateInPlace` 恢复 autoConnect 挂起（等下次靠近）。
- versionName 0.2.108→0.2.109。

**回滚配方**：`git revert` 本轮提交；或 `git checkout <锚点提交> -- app/src/main/java/my/bridge/com/jikong/JikongController.kt app/build.gradle.kts`。
**触发信号**：开关开后靠近保护板仍反复「连上→只 AT→断开」拿不到 0x02 / 语音始终不播报 / 半死链自愈后仍无数据流。

**实施结果（2026-11-08 13:02 回填）**：`compileDebugKotlin` BUILD SUCCESSFUL（2m50s）。提交 `0f16419` 清代理直连 push（7501333..0f16419）。`release.py v0.2.109` 建 tag 触发 CI 打包成功（期间 GitHub API 读 actions/runs 列表两次 IncompleteRead 网络抖动，重跑 + 最终用 v4.gh-proxy.org 代理下载 APK）。APK 46,054,402 字节，aapt 实证 versionName=0.2.109，分发 updates/bridge-v0.2.109.apk + update.json（versionCode 309 / sha256 `d9ab9e73…`）。**客户端改动生效，服务端零改动、无需重启**。真机验证：用户 check_update 升级到 0.2.109 后，靠近保护板应能直连重建唤醒半死链 → 收到 0x02 → 播报电量。

---

### v0.2.108 极空「靠近自动连接」后台适配：镜像开关 + 状态展示（2026-11-08 12:15 实施）

**需求（用户 11-08 12:12 拍板「档一档二都做」）**：v0.2.107 客户端已上报 `proximity_auto_connect`/`proximity_suppressed` 两字段（服务端原样透传），但管理后台未消费。档一=只读展示（模式开关状态 + 手动断开抑制态）；档二=可编辑镜像开关（后台下发 `jikong_set_proximity` 远程开/关，与手机设置页开关同语义）。

**改前原文（关键块；改动文件均 git 跟踪，锚点提交存全文）**：
1. `server/app/api/v1/jikong.py` 第 80 行：`elif body.action in ("speak", "set_theme", "set_speak", "set_config"):`；action 描述止于 `demo_refresh`；params 描述止于 `set_config`。
2. `server/app/core/command_catalog.py`：`jikong_set_config` 条目止于 `_param("odo_discount", …)]),`，其后直接 `_cmd("jikong_demo_refresh", …)`。
3. `core/core-network/…/ws/Command.kt`：`JIKONG_SET_CONFIG` 常量后直接 `JIKONG_DEMO_REFRESH`。
4. `app/…/di/CommandModule.kt`：分发表 `JIKONG_SET_CONFIG` 后直接 `JIKONG_DEMO_REFRESH`；`executeJikongSetConfig` 方法后直接 `jikong_demo_refresh` 注释块。
5. `app/…/jikong/JikongController.kt`：`attachContext()` 方法后直接 `/** 轮询间隔（毫秒）。 */`，无 setProximityAutoConnect。
6. `admin/src/api/jikong.ts`：`JikongStatus.app` 止于 `odo_discount`；`JikongControlParams.action` 类型止于 `'demo_refresh'`。
7. `admin/src/views/jikong/index.vue`：Tab3 开头 `<template v-else><div class="set-title">仪表盘</div>`；`cfgDiscount` 声明后直接 `let cfgLastSendAt = 0`；watch 内 `odo_discount` 回写后直接 `}`；`sendConfig` 后直接 `onMuteToggle`；`dashEnabled` computed 后直接 `// ---------- v0.2.101 …` 注释。

**改动清单**：
- `jikong.py`：action 描述 +`set_proximity`；params 描述同步；`elif body.action in (…, "set_proximity")` 纳入透传。
- `command_catalog.py`：+`jikong_set_proximity` 命令（📡，param `auto_connect` PARAM_TYPE_SWITCH）。
- `Command.kt`：+`JIKONG_SET_PROXIMITY = "jikong_set_proximity"`。
- `CommandModule.kt`：分发表 +`JIKONG_SET_PROXIMITY → executeJikongSetProximity`；+`executeJikongSetProximity()`（读 `auto_connect` 布尔 → `JikongController.setProximityAutoConnect`）。
- `JikongController.kt`：+`setProximityAutoConnect(context, enabled)`（写 Prefs + evaluate/stop + forceReport，不碰 manualSuppressed，与手机设置页开关同语义）。
- `jikong.ts`：`JikongStatus.app` +`proximity_auto_connect`/`proximity_suppressed`；action 类型 +`'set_proximity'`。
- `index.vue`：Tab3 顶部 +「靠近自动连接」卡（可编辑开关 `cfgProximity` + 抑制态只读提示）；+`cfgProximity` 状态、watch 回写、`sendProximity()`、`proximitySuppressed` computed。
- versionName 0.2.107→0.2.108。

**回滚配方**：`git revert` 本轮提交；或 `git checkout <锚点提交> -- <上述 7 文件> + app/build.gradle.kts`。
**触发信号**：后台开关下发后手机不启停靠近服务 / 手机设置页与后台开关状态不一致（回声不同步）/ 后台看不到抑制态提示 / 命令 `jikong_set_proximity` 回执失败。

**实施结果（2026-11-08 12:31 回填）**：`compileDebugKotlin` BUILD SUCCESSFUL（2m）；admin `npm run build`（vue-tsc + vite）成功（仅 chunk 体积警告）；服务端 `py_compile` OK。提交 `2f97316` 清代理直连推送（0d8ad05..2f97316）；`release.py v0.2.108` 建 tag 触发 CI 打包成功，APK 经 gh-proxy 下载（46,054,402 字节，aapt 实证 versionName=0.2.108），分发 updates/bridge-v0.2.108.apk + update.json（versionCode 308 / sha256 `51cb014f…`）。**⚠ 服务端 jikong.py + command_catalog.py 有改动，需用户手动重启服务端才生效**（客户端/后台无需重启，后台 dist 重新构建后由用户部署）。

---

### v0.2.107 极空「靠近自动连接」：GATT autoConnect 挂起 + 前台服务承载（2026-11-08 11:50 实施）

**需求（2026-11-08 拍板，需求/调研/设计全文见 `docs/research/jikong-proximity-autoconnect.md`）**：设置页新增「靠近自动连接」开关（默认关）。开后对默认设备挂 `connectGatt(autoConnect=true)`，手机靠近保护板 ≤30s 自动连上（前台/后台/被杀重启均覆盖），连上播报电量（**后台也播**，§2.8 拍板）+后台持续收数据；走远断开不抑制（系统自动持续重试）、回来照常重连+播报；仅用户在极空页点断开才抑制（直到重进极空页/进程重启）。省电第一：控制器级 3.75% 占空比监听，估 <0.5%/天、零流量。一期只做方案 A（B=PendingIntent 扫描保留二期，见调研 §2.9）。

**改前原文（逐字抄录，节选关键块；改动文件均 git 跟踪中，锚点提交存全文）**：
1. `JikongPrefs.kt`：无靠近连接相关键（对象止于 theme/dash/统计/播报/昵称/默认设备/单位/静音各段）。
2. `JikongBleClient.kt`：connect() 直连 `dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)`；disconnect() = `gatt?.disconnect()` + `gatt?.close()` + `gatt=null` + `setConnected(false)`；cancelPendingConnect() 同样 close；无 attachAutoConnect/detachAutoConnect/autoConnectAttached。
3. `JikongController.kt`：init 回调断开分支 `if (autoReconnectEnabled && (pageVisible || keepConnection)) { startReconnect(fast = dataRecyclePending) }`（无 proximity 分流）；connected 分支无初始化逻辑（成功初始化只在 connect()/startReconnect() 内）；setPageVisible(false) `if (!keepConnection) { autoReconnectEnabled=false; …; JikongBleClient.disconnect() } else { stopPolling() }`；disconnect() 无抑制概念（`autoReconnectEnabled=false; dataRecyclePending=false; …`）；maybeAnnounce `if (demoMode || !pageVisible) return`；appContext 仅 connect() 内缓存；buildReportStatus app 块止于 `addProperty("odo_discount", …)`。
4. `JikongPages.kt` SettingsView(controller, dashEnabled, onDashToggle)：设置页无「靠近自动连接」开关。
5. `app/src/main/AndroidManifest.xml`：无 `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限、无 JikongProximityService 声明。
6. `BridgeApplication.kt` onCreate：极空挂载止于 `JikongSpeaker.attach(apiClient, deviceStore)`。
7. `app/build.gradle.kts`：`versionName = "0.2.106"`。
8. **新建** `app/src/main/java/my/bridge/com/jikong/JikongProximityService.kt`（新文件，无改前原文）。

**改动清单**（按调研文档 §三 设计实施）：
- `JikongPrefs.kt`：+KEY_PROXIMITY_AUTO_CONNECT（默认 false）+读写函数。
- `JikongProximityService.kt`（新）：connectedDevice 四件套前台服务（NOTIFICATION_ID 2104 / 渠道 jikong_proximity_channel IMPORTANCE_LOW / START_STICKY）；核心 `evaluateInPlace()`（开关开+默认设备+未抑制+非演示+未连接+未挂起+无在飞连接 → attach；抑制/演示 → detach）；ACTION_STOP=detach+自停；蓝牙 STATE_CHANGED receiver 重挂；startForeground 失败即自停防 FGS 超时崩溃；`evaluate()`（start 前台服务）与 `evaluateInPlace()`（进程内直接执行，避开后台 startService 限制）双入口。
- `JikongBleClient.kt`：+autoConnectAttached 状态；+attachAutoConnect()（connectGatt(autoConnect=true, TRANSPORT_LE)，先清陈旧 GATT 防泄漏）；+detachAutoConnect()（未连接才 disconnect+close；已连接不动链路）；disconnect()/cancelPendingConnect() 均清 autoConnectAttached。
- `JikongController.kt`：+manualSuppressed（内存，不持久化=进程重启自然解除）+proximityAnnouncePending+proximityModeActive+attachContext()；触点① connected 分支识别 proximity 建链（非 connectInFlight、非 reconnectJob）执行与 connect() 成功同款初始化+置播报待发；触点② 断开分流——proximity 模式不走 startReconnect，改 evaluateInPlace 重挂（幂等：挂起仍在则系统自己在试）；触点③ disconnect() 演示退出不抑制（wasDemo 区分），真手动断开置抑制+停服务；触点④ setPageVisible(true) 解除抑制+evaluate，false 时 proximity 模式不断开不停轮询；maybeAnnounce gate 放开 `(!pageVisible && !proximityModeActive)` + forcedProximity 强制播一次（受小喇叭静音拦截）；connect() finally 重评估挂起；buildReportStatus app 块 +proximity_auto_connect/proximity_suppressed。
- `JikongPages.kt`：SettingsView 仪表盘段后 +「靠近自动连接」开关卡（无默认设备回弹+Toast；切换 evaluate/stop + forceReport）。
- `AndroidManifest.xml`：+FOREGROUND_SERVICE_CONNECTED_DEVICE 权限 +service 声明（connectedDevice 类型）。
- `BridgeApplication.kt`：onCreate +evaluate（覆盖开开关/进程重建/开机三链路，内部查开关幂等）。
- versionName 0.2.106→0.2.107。

**回滚配方**：`git checkout <锚点提交> -- app/src/main/java/my/bridge/com/jikong/JikongPrefs.kt app/src/main/java/my/bridge/com/jikong/JikongBleClient.kt app/src/main/java/my/bridge/com/jikong/JikongController.kt app/src/main/java/my/bridge/com/ui/JikongPages.kt app/src/main/AndroidManifest.xml app/src/main/java/my/bridge/com/BridgeApplication.kt app/build.gradle.kts && rm app/src/main/java/my/bridge/com/jikong/JikongProximityService.kt`；或 `git revert` 本轮提交。
**触发信号**：开关打开后靠近保护板 >60s 仍不自动连 / 口袋里连上不播报 / 极空页点断开后仍自动重连（抑制失效）/ 走远回来不再重连 / 后台 jikong_state 断流 / 耗电异常增大（>1%/天增量）。

**实施结果（2026-11-08 12:06 回填）**：`compileDebugKotlin` BUILD SUCCESSFUL（3m19s，仅存量 BLE deprecated 告警）。提交 `a729f52` 清代理直连推送（5ca7e94..a729f52）；`release.py v0.2.107` 建 tag 触发 CI 打包成功，APK 经 gh-proxy 下载（46,054,402 字节，aapt 实证 versionName=0.2.107），分发 updates/bridge-v0.2.107.apk + update.json（version 0.2.107 / versionCode 307 / sha256 `47aeda75…`）。纯客户端改动，服务端无改动无需重启。真机验证走 check_update 升级（§3.10 十项清单，尤其 ⑤ autoConnect 首建链×12s 自愈组合、③ 手动断开抑制、⑨ 耗电 24h 对照）。

---

### v0.2.106 时速卡两行「平均」列左对齐对齐后台（2026-11-08 10:10 实施）

**需求（用户 11-08 10:07 反馈 1 项）**：客户端「平均时速(km/h)」「平均(kWh/100km)」没有左对齐，应与后台一致左对齐。

**改前原文（逐字抄录）**：`JikongPages.kt` 行 1（1190 行）：`Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("平均时速(km/h)", ...) }`——中列整体居中；行 2 中列 `Column(Modifier.weight(1f))` 默认 Start。后台 `.dash-col { flex: 1 }` 无 text-align（默认左对齐）。

**改动清单**：行 1 中列去掉 `horizontalAlignment = Alignment.CenterHorizontally`，改默认 Start。根因：后台三列全部左对齐，客户端仅中列居中 → 两行「平均」标签起点错位。行 2 本就左对齐无需改。

**回滚配方**：`git revert` 本轮提交；或手改回 `Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally)`。
**触发信号**：平均时速标签又回到列中间 / 两行平均标签起点错位复发。

**实施结果（2026-11-08 10:22 回填）**：assembleDebug BUILD SUCCESSFUL；本地 commit `b9d2343`。github.com:443 直连推送仍 Connection reset → api_push.py 通道成功（远端 main=74f0a77eaf 为 parent，API commit `dc54d92dee`）+ tag v0.2.106 触发 CI 打包成功，APK 0.2.106（aapt 实证，sha256 `b4f57444…`，46,037,958 字节）分发 updates+Downloads（删 0.2.105 旧包）+update.json。本地与远端 commit 链仍不同源（API 建父提交），待 git 直连恢复后 fetch 对齐。纯客户端 UI 改动，服务端无改动无需重启。

### v0.2.105 后台可远程刷新演示数据 + 播报时速单位修正（2026-11-08 09:45 实施）

**需求（用户 11-08 09:45 反馈 2 项）**：①后台演示横幅「刷新模拟数据（手机端）」不可用——应可点击并真实触发客户端刷新、刷新后立刻同步；②双击播报时速读作「时速 6公里」，单位应为「6公里每小时」（速度类单位错误）。

**改前原文（逐字抄录）**：
1. `JikongController.kt` speakDashboardNow（695-718 行）：`segs.add("时速"); segs.add(数字); segs.add("公里")`；fallback `sb.append("，时速 %.0f 公里".format(speed))`。
2. `JikongSpeaker.kt` prefetchAll phrases（124 行）：`"当前电量", "百分之", "时速", "公里", "剩余续航", "电量低，请注意充电"`。
3. `Command.kt`（163 行前）：JIKONG_* 十常量（无 demo_refresh）；`CommandModule.kt` 映射至 JIKONG_SET_CONFIG 止。
4. `server/app/api/v1/jikong.py` control：action 白名单 `("speak", "set_theme", "set_speak", "set_config")`（demo_refresh 未放行）。
5. `command_catalog.py`：无 jikong_demo_refresh 条目。
6. `admin/index.vue` 演示横幅：`<span class="demo-refresh" style="cursor: default">刷新模拟数据（手机端）</span>`（纯占位不可点）；`.demo-refresh { font-size: 12px; color: gray; opacity: 0.7; }`。

**改动清单**：
- 播报单位：speakDashboardNow 时速段「公里」→「公里每小时」（续航「公里」保持距离语义）；fallback 整句同步；JikongSpeaker 预取词组表 +「公里每小时」。★ 缓存键判定：新文本 md5(text|voice|v6seg) 独立，已缓存旧段零变化 → **无需升 v7seg**，进首页 prefetchAll 幂等补拉新段即可（与 0.2.100 升键场景不同——那次是旧段内容坏了必须重拉）。
- 远程刷新：`Command.kt` +JIKONG_DEMO_REFRESH；`CommandModule.kt` +executeJikongDemoRefresh（非演示模式回执失败说明；refreshDemoData 自带 statusVersion 自增+reportStatus，执行器再 forceReport 双保险）；`jikong.py` 放行 action=demo_refresh（无参）；`command_catalog.py` +条目。
- 后台：横幅按钮改可点击（amber 加粗 cursor:pointer，:active 反馈，文案去「（手机端）」与手机横幅一致）；onDemoRefresh → jikongControl(demo_refresh) → 成功后立即 loadState()（客户端已 forceReport，点了立刻变）。
- versionName 0.2.104→0.2.105。

**回滚配方**：`git revert` 本轮提交；或手改：speakDashboardNow/词组表回「公里」、Command.kt/CommandModule 删 demo_refresh、jikong.py 回四 action 白名单、command_catalog 删条目、admin 回退横幅与 onDemoRefresh→admin npm run build；versionName 回 0.2.104。
**触发信号**：后台演示横幅按钮点不动 / 点击后手机数据不变 / 双击播报时速仍读「公里」/ 手机端升级前播报「公里每小时」缺段走整句回退（正常，进首页预取后恢复分段连读）。

**实施结果（2026-11-08 10:02 回填）**：编译/构建全过（admin vue-tsc+vite、Kotlin assembleDebug、server py_compile；首轮 Kotlin daemon 意外重启疑似假成功，重跑 assembleDebug 实证 BUILD SUCCESSFUL）。提交 `2834b0b` 直连推送；tag v0.2.105 CI 打包成功，APK 0.2.105（aapt 实证，sha256 `d03dca79…`，46,037,962 字节）分发 updates+Downloads（删 0.2.104 旧包）+update.json。服务端 jikong.py/command_catalog.py 有改动（demo_refresh 放行），由用户自行重启后生效。播报新段「公里每小时」随手机升级 0.2.105 进首页预取补拉；升级前的旧 APK 遇「公里每小时」缺段自动走整句回退，不哑不卡。

### v0.2.104 后台小喇叭移至首页连接栏 + 设置页镜像全面可编辑（jikong_set_config）（2026-11-08 09:10 实施）

**需求（用户 11-08 09:10 反馈 2 项）**：①删侧栏「语音播报（同步到手机）」卡，小喇叭改为后台首页右上角图标（与手机客户端同位置同交互，双向同步）；②后台设置页此前只能改「语音播报」，仪表盘/单位与花费/里程折扣也应可设置并下发客户端；客户端设置改动后后台刷新应同步。

**改前原文（逐字抄录，节选关键块）**：
1. `Command.kt`（152-162 行）：JIKONG_* 九个常量，无 set_config。
2. `CommandModule.kt`：jikong 执行器映射至 JIKONG_SET_SPEAK 止；executeJikongSetSpeak 末尾 `remoteSpeakTick++ + forceReport`。
3. `JikongPages.kt` JikongScreen：`toggleDash = { on -> dashEnabled = on; JikongPrefs.setDashEnabled(context, on); if (on) SpeedDash.start(context) else SpeedDash.stop() }`（无上报，无远程 tick 订阅）；SettingsView remoteSpeakTick Effect 只重读 4 个播报字段；SettingsView 各控件（播报开关/三 stepper/单位 chip/电费/折扣 stepper）写 Prefs 无 forceReport；StatsView 只订阅 statusVersion（折扣/电费读 Prefs 快照）。
4. `server/app/api/v1/jikong.py` control：action 白名单 `("speak", "set_theme", "set_speak")`。
5. `command_catalog.py`：jikong_* 十一条目，无 jikong_set_config。
6. `admin/index.vue`：侧栏「语音播报（同步到手机）」卡（spk-row 五行，改动 sendSpeakSettings）；Tab3 仪表盘开关 `dashMirror` disabled、电耗单位 chip 只读、电费/折扣只读 span、语音播报卡含「小喇叭」行（手机设置页无此行）；connbar 仅 dot+conn-text+theme-btn；watch(app) 只回写 spk* 五字段（spkLastSendAt 回声窗口）。

**改动清单**：
- 客户端：`Command.kt` +JIKONG_SET_CONFIG；`CommandModule.kt` +映射与 executeJikongSetConfig（dash_enabled 同步启停 SpeedDash / unit_kwh100 / elec_price 0.05~10 / odo_discount 0.1~1.0，成功后 remoteSpeakTick++ + forceReport）；`JikongPages.kt` JikongScreen +remoteSpeakTick 订阅重读 dashEnabled、toggleDash +forceReport，SettingsView tick Effect 扩展重读单位/电费/折扣、播报开关/三间隔/单位/电费/折扣共 9 处 +forceReport，StatsView +JkUiState.remoteSpeakTick 订阅（折扣/花费即时重算）。
- 服务端：`jikong.py` action 白名单 +set_config（params 透传）；`command_catalog.py` +jikong_set_config 条目。
- 后台：connbar 加 🔊/🔇（.spk-btn，onMuteToggle→sendSpeakSettings）；删侧栏语音播报卡与 .spk-row CSS；Tab3 删「小喇叭」行（手机设置页无此行，保 1:1）、时速仪表盘开关改可编辑（cfgDash）、单位 chip 可点击（cfgUnit）、电费单价 el-input-number(step 0.05)、里程折扣 el-input-number(step 0.1)，统一 sendConfig('set_config' 全量四字段)；app watch 扩展回写 cfg* 四字段（cfgLastSendAt 独立 6s 回声窗口）；★ cfg* 声明置于 watch 之前（TDZ 位置铁律）。
- 双向同步闭环：后台改 → jikong_set_speak/set_config 下发 → 手机 Prefs+tick 即时生效+forceReport 回报 → 后台 6s 回声窗口过后随刷新跟随；手机改 → 各控件 forceReport → app 块入报 → 后台刷新即同步。
- versionName 0.2.103→0.2.104。

**回滚配方**：`git revert` 本轮提交；或手改：Command.kt/CommandModule 删 set_config 条目、JikongPages 删两处 tick 订阅与 9 处 forceReport、jikong.py 回三 action 白名单、command_catalog 删条目、admin 回退 connbar/Tab3/侧栏卡/watch 四处→admin npm run build；versionName 回 0.2.103。
**触发信号**：后台首页看不到小喇叭 / 后台改仪表盘·单位·电费·折扣手机不生效 / 手机改设置后台刷新不同步 / 后台极空页白屏（TDZ）。

**实施结果（2026-11-08 09:40 回填）**：编译/构建全过（admin vue-tsc+vite、客户端 compileDebugKotlin、server py_compile、assembleDebug 46,037,962B）。中途两次声明顺序报错（admin cfg* TDZ、SettingsView unit/price/discount 在 Effect 后声明 Unresolved），均前置声明后过。提交 `ff1c8cb` 直连推送；tag v0.2.104 推送触发 CI 打包成功，APK 0.2.104（aapt 实证，sha256 `828b748f…`，46,037,962 字节）resume_release 下载分发 updates+Downloads（Downloads 删 0.2.103 旧包）+update.json。**服务端 jikong.py/command_catalog.py 有改动，用户明确不需代启——服务端重启由用户自行完成**（重启前 set_config 会 400 未知 action，重启后即通）。⚠ 新坑：release.py 不支持 --help，传参会被当 tag 执行（本轮 `git tag -f --help` 恰好显示帮助未建误 tag，侥幸）；看用法直接读脚本头注释。admin npm run build 已含本改动，用户 dev/build 后即可见。

### v0.2.103 后台/客户端八项修复（2026-11-08 08:47 实施）

**改前原文（逐字抄录，节选关键块）**：
1. JikongPages HomeView：`DisposableEffect(dashEnabled) { if (dashEnabled) SpeedDash.start(context); onDispose { SpeedDash.stop(); if (controller.demoMode) controller.disconnect() } }`
2. 根层：`DisposableEffect(Unit) { JkUiState.immersive = true; onDispose { JkUiState.immersive = false } }`
3. cycleTheme：`JikongPrefs.setTheme(context, next)`（无上报）；onToggleMute：`JikongPrefs.setMuted(context, muted)`（无上报）
4. admin index.vue：`@dblclick="onRemoteSpeak"`；远程控制卡含 mos-actions 三按钮；Tab3 语音播报只读镜像（无小喇叭行）；`onMounted { loadDeviceNames(); restartTimer() }`；`statsBase: const totalKmRaw = list[list.length - 1].odo_km - list[0].odo_km`
5. reportStatus 去重：`val json = status.toJson().toString(); if (json == lastReportedJson) return`（app 块不参与）

**需求（用户 08:46 反馈 8 项）**：①后台双击表盘选中文字 ②删「关充电/关放电/开均衡」按钮 ③手机改主题后台上不同步 ④后台看不到小喇叭 ⑤后台刷新网页先显示未连接 ⑥手机切内部 Tab 退出演示 ⑦刷新模拟数据疑未刷新实时/统计页 ⑧后台统计「记录区间里程」恒 --。

**根因与修复**：
- ⑥ HomeView 的 DisposableEffect onDispose 挂着演示退出——切内部 Tab 卸载 HomeView 误退出 → 上移根层 DisposableEffect(Unit)（仅离开极空页整体触发；后台退出仍走 ON_PAUSE）。
- ③ reportStatus 按 status JSON 去重，app 块（theme/muted）变化不触发重报 → cycleTheme/onToggleMute 加 forceReport()。
- ⑦ 是 ⑥ 的下游：切实时/统计页即退出演示回真实数据；refreshDemoData 本身 bump statusVersion（实时页订阅），修 ⑥ 后即正常。统计页台账独立于刷新模拟数据（设计如此）。
- ⑧ statsBase 的 totalKmRaw = list[last]-list[0]，而台账「新在上」倒序 → 负数恒 -- → 改按 ts 升序后相减。
- ① dblclick.prevent + .gauge-wrap user-select:none。② 删三按钮与 toggleMos/toggleBalance，卡片改「MOS / 均衡状态（只读）」。④ Tab3 语音播报镜像整体改可操作（小喇叭/播报开关/间隔/低电阈值/提醒间隔，绑 spk* 走 sendSpeakSettings）。⑤ onMounted 立即 loadState()。

**回滚配方**：`git revert` 本轮提交；或手改：HomeView onDispose 加回演示退出并删根层 onDispose 的、cycleTheme/onToggleMute 删 forceReport、admin 四处回退→admin npm run build；versionName 回 0.2.102。
**触发信号**：手机切内部 Tab 又退出演示 / 后台主题或小喇叭不同步 / 后台进页闪「未连接」/ 记录区间里程恒 -- / 双击表盘选中文字。

**实施结果（2026-11-08 09:05 回填）**：admin build（vue-tsc+vite）与客户端 compileDebugKotlin/assembleDebug 全过；提交 `4722b9a` 直连推送；tag v0.2.103 直连推送成功，CI 打包成功，APK 0.2.103（aapt 实证，sha256 `77fdd0b6…`，46,037,962 字节）分发 updates+Downloads+update.json（Downloads 删 0.2.102 旧包）。台账本轮用 python 插入条目（0 次吞标题，Edit 工具对「### 标题」行高风险）。服务端本轮无改动，无需重启；后台/手机两端升级 0.2.103 后 8 项全部生效。

### v0.2.102 管理后台↔客户端双向对齐第二轮（远程播报/主题/小喇叭/演示统计/刷新频率/压差3位）（2026-11-08 08:10 实施）

**需求（用户 08:05 反馈 7 项）**：①后台双击仪表盘→下发手机播报；②最大压差 3 位小数（后台+客户端核对）；③远程控制卡删「关充电 关放电 开均衡…保留在手机壳外」旧说明；④后台自动刷新频率可设置；⑤后台深/浅色切换必须同步客户端；⑥后台加小喇叭并可同步客户端；⑦客户端演示模式时后台统计页须与客户端一致（当前后台用的是 MySQL 真实台账）。总纲：后台必须和客户端一模一样，客户端能操作的后台也能操作（进入演示模式除外）。

**改前原文**：
- `JikongPages.kt` JkUiState（169-172 行）：仅 `immersive`、`exitToken` 两字段。
- `JikongPages.kt` 主题状态（399 行）：`var themeMode by remember { mutableIntStateOf(JikongPrefs.theme(context)) }`，cycleTheme=(themeMode+1)%3。
- `JikongPages.kt` onDashDoubleTap（926-949 行）：播报组合逻辑内联在 HomeView Composable 里。
- `JikongPages.kt` 演示台账（2158 行）：`var demoList by remember { mutableStateOf(jkDemoRecords()) }`；`val records = if (demoMode) demoList else remember(recordsVersion) { JikongStatsStore.records(context) }`（演示台账在 Composable 内存，控制器不可见→无法上报后台）。
- `JikongPages.kt` 压差显示（994/1587 行）：`fmt(s.diffVoltage)`=≥100 一位小数否则两位。
- `JikongController.kt` buildReportStatus app 块（1044-1057 行）：theme/dash_enabled/demo_mode/unit_kwh100/speak_enabled/speak_interval_min/speak_low_soc/speak_warn_repeat_min/elec_price/odo_discount，**无 speak_muted、无 demo_stats**。
- `Command.kt`（152-158 行）：JIKONG_SCAN~JIKONG_WRITE_PARAM 七个常量。
- `CommandModule.kt`（317-338 行）：jikong 七个执行器映射。
- `server/app/api/v1/jikong.py` control（37-84 行）：action 仅 mos/balance/write_param，params 白名单构造。
- `admin/index.vue`：autoRefresh 固定 2000ms（1087-1089 行）；theme-btn 仅本地翻转（45 行）；远程控制卡说明文字（581-583 行）；压差 `fmt(s.diff_voltage)` 两位（256/743 行）；统计页数据源 `odo.value`（MySQL 台账）。

**改动清单**：JkUiState+remoteThemeTick/remoteSpeakTick；新增 `JikongController.speakDashboardNow(ctx)`（onDashDoubleTap 与 jikong_speak 命令共用）；演示台账迁入控制器 `demoOdoRecords`（可上报）；buildReportStatus+`speak_muted`+`demo_stats`；Command.kt+3 常量、CommandModule+3 执行器（jikong_speak/jikong_set_theme/jikong_set_speak）；jikong.py control 放行新 action（params 透传）；command_catalog.py+3 条目；后台：刷新频率下拉、主题切换下发、双击表盘下发播报、语音播报卡（静音/开关/间隔/低电阈值/提醒间隔，改即下发）、删旧说明、压差 toFixed(3)、统计页演示数据源（demo_stats 前端算段）。versionName 0.2.100→0.2.102。

**回滚配方**：`git revert` 本轮提交；或手改：JkUiState 删两 tick 字段、JikongController 删 speakDashboardNow/demoOdoRecords、buildReportStatus 删 speak_muted/demo_stats、Command.kt/CommandModule 删三条目、jikong.py 回白名单、admin 五处回退→admin npm run build；versionName 回 0.2.101。
**触发信号**：后台点主题/小喇叭手机不生效 / 后台双击表盘手机不播 / 演示模式后台统计仍是真实台账 / 压差仍两位。

**实施结果（2026-11-08 08:35 回填）**：编译/构建全过（admin vue-tsc+vite、客户端 compileDebugKotlin、server py_compile）。提交 `e7b8b3a` 直连推送；tag v0.2.102 直连推送成功（14c98d4），CI 打包成功，APK 0.2.102（aapt 实证，sha256 `6c339a9a…`，46,037,962 字节）分发 updates+Downloads+update.json。台账本轮 Edit 又吞了 v0.2.101 标题（同款事故第 4 次），已补回并核对了区块归属。
**⚠ 依赖关系**：①后台新交互（播报/主题/小喇叭下发）需**服务端更新重启**（jikong.py 放行新 action）+ 手机升 0.2.102（新命令执行器）两端就绪才生效；②演示统计同步需手机 0.2.102 上报 demo_stats，旧 APK 时后台统计回 MySQL 真实台账；③主题/播报回读依赖 forceReport，命令执行后 ~1s 内后台刷新可见。

**★ 热修复（2026-11-08 08:38，admin-only，无发版）——极空页白屏**：用户 `npm run dev` 实证极空页白屏（路由记忆落在 /jikong 属正常，白屏是真问题）。**根因**：v0.2.102 在 `<script setup>` 里把两个 `watch(() => s.value…)` 放在 `const s = ref(…)` 声明之前——watch 注册即同步执行一次 getter，命中 const TDZ 抛 `Cannot access 's' before initialization`，setup 整体失败 → 组件白屏。vue-tsc 检不出闭包内后置引用，故编译通过仍会白屏。**修复**：双向同步块整体移到 `s`/`selectedDevice` 声明之后，并留「位置铁律」注释。dist 已重出。**教训**：script setup 中 watch/computed 引用响应式状态必须放在声明之后，勿按「功能分区」把块前置。
**触发信号（白屏）**：dev 或 build 后极空页空白、控制台 `Cannot access 's' before initialization`。

### v0.2.101 管理后台极空页与客户端 v0.2.100 对齐（2026-11-08 07:33 实施）

**需求（用户 11-08 07:33）**：客户端多轮迭代后，管理后台「极空保护板」页未跟进——检查并确保与客户端 APP 极空页面百分百一致（只读镜像，交互类动作不搬运）。

**差异清单（逐项核对结论）**：
| # | 位置 | 客户端现状 | 后台现状 | 处置 |
|---|---|---|---|---|
| 1 | 时速卡行1 | **三列**：时速/平均时速/剩余续航（v0.2.91） | 只有时速/剩余续航两列 | 后台补平均时速列 |
| 2 | 时速卡本次里程 | GPS 口径 gpsTripKm（v0.2.95） | 服务端 dash.trip_km 报的是 BMS 口径 tripKm → 与手机不一致 | 客户端上报改 gpsTripKm（BMS 口径另存 bms_trip_km） |
| 3 | 时速卡电耗单位 | 跟随 unit_kwh100 切换 Wh/km ↔ kWh/100km（v0.2.90） | 写死 Wh/km | 上报 unit_kwh100 + 后台跟随换算 |
| 4 | 首页演示横幅 | demoMode 时 amber 横幅（v0.2.92/98） | 无 | 上报 demo_mode + 后台只读横幅 |
| 5 | 统计页概要 | 五卡 3+2（区间里程[折扣]/总耗电/总花费/平均电耗[折扣]/平均花费，v0.2.90/91） | 三卡（里程/耗电/平均电耗），无花费无折扣 | 后台补齐（需 elec_price/odo_discount 上报） |
| 6 | 统计页阶段统计 | 一周/一月/一季度/一年/自定义(DateRangePicker)+按记录时间归属（v0.2.92） | 无 | 后台补（el-date-picker daterange） |
| 7 | 能耗曲线标题 | 「平均 X Wh/km ≈ Y 元/100km」双刻度（v0.2.90） | 「均值 X」 | 后台对齐文案 |
| 8 | 设置页累计统计 | 第三格=「生命周期电耗」=totalWh/totalKm（≥1km）else hist（v0.2.90） | 标签「历史电耗」且服务端 hist_avg_wh_km 报的是行程平均优先 | 客户端上报改生命周期口径 + 后台改标签 |
| 9 | 设置页结构 | 仪表盘开关/语音播报/单位与花费/里程折扣/累计统计/说明（v0.2.89/90）；**保护板参数已删（v0.2.83）** | 只有仪表盘开关/累计统计/**保护板参数（只读）**/说明 | 后台删保护板参数块，补三个只读镜像块，说明文案对齐 |
| 10 | 实时页/首页四格/电流功率卡/仪表盘 SVG | — | 逐字段核对一致 | 不动 |

**改前原文（逐字抄录，节选关键块）**：

1. JikongController.buildReportStatus（dash 块 + app 块，见 v0.2.86 版全文）：
```kotlin
                add("dash", JsonObject().apply {
                    addProperty("speed_kmh", d.speedKmh)
                    addProperty("gps_fresh", d.gpsFresh)
                    addProperty("range_km", d.rangeKm)
                    addProperty("inst_wh_km", d.instWhKm)
                    addProperty("avg_wh_km", d.avgWhKm)
                    addProperty("trip_km", d.tripKm)
                    addProperty("trip_wh", d.tripWh)
                    addProperty("trip_seconds", d.tripSeconds)
                    addProperty("bms_stale", d.bmsStale)
                    if (ctx != null) {
                        addProperty("total_km", JikongPrefs.totalKm(ctx) + d.tripKm)
                        addProperty("total_wh", JikongPrefs.totalWh(ctx) + d.tripWh)
                        addProperty("hist_avg_wh_km",
                            if (d.avgWhKm > 0f) d.avgWhKm else JikongPrefs.histAvgWhKm(ctx))
                    }
                    addProperty("updated_at", System.currentTimeMillis())
                })
            ...
                    add("app", JsonObject().apply {
                        addProperty("theme", JikongPrefs.theme(ctx))
                        addProperty("dash_enabled", JikongPrefs.dashEnabled(ctx))
                    })
```

2. admin/src/views/jikong/index.vue 时速卡行1（两列）：
```html
                    <div class="dash-row1">
                      <div class="dash-col">
                        <div class="mini-label">时速(km/h)</div>
                        <div class="dash-big" :style="{ color: c.green }">{{ s.dash.gps_fresh ? n1(s.dash.speed_kmh) : '--' }}</div>
                      </div>
                      <div class="dash-col">
                        <div class="mini-label">剩余续航(km)</div>
```

3. index.vue 统计概要三卡：`区间里程(km)/总耗电(kWh)/平均电耗(Wh/km)`（odo.summary 直出，无花费/折扣）。
4. index.vue 设置页 Tab3：仪表盘开关镜像 + 累计统计（km/wh/hist 标签「历史电耗」）+ **保护板参数（只读）卡 + 说明卡**。
5. admin/src/api/jikong.ts dash/app 类型同客户端旧字段。

**回滚配方**：`git revert` 本轮客户端提交（buildReportStatus 恢复）+ admin 提交回退（index.vue/jikong.ts）→ admin 重新 build。
**触发信号**：后台时速卡与手机列数不一致 / 里程口径差 / 设置页多出保护板参数 / 统计页无花费。

**实施结果（v0.2.101，2026-11-08 07:55 发版）**：
1. 客户端 buildReportStatus：dash +avg_speed_kmh、trip_km 改 GPS 口径（BMS 口径保留 bms_trip_km）、hist_avg_wh_km 改生命周期口径（totalWh/totalKm≥1km）；app +demo_mode/unit_kwh100/speak_*/elec_price/odo_discount。
2. admin jikong.ts 类型同步扩展；index.vue：时速卡行 1 三列+行 2 单位跟随（fmtCons 同公式）、首页演示横幅（只读）、统计页概要五卡 3+2（折扣+花费）、阶段统计 chips+el-date-picker 自定义区间+阶段三卡、曲线双轴文案+折扣 X 轴、台账行折扣显示+31/38 着色+花费小字、口径说明对齐；设置页删保护板参数镜像，新增语音播报/单位与花费/里程折扣三个只读镜像块，累计统计第三格改「生命周期电耗」，说明文案对齐 v0.2.90。
3. 逐字段核对一致不动：首页四格×2、实时页双列+单体/内阻网格、电流功率卡、仪表盘 SVG 几何、设备信息/保护状态/MOS 控制侧板。
4. 构建：admin `npm run build` 通过（vue-tsc+vite）；客户端编译通过。
5. 发版：提交 `9090fd7`；git 直连 github.com:443 中断（Connection reset/连不上），按发布网络策略切 **API 通道**（REST 创建 annotated tag a061533 → refs/tags/v0.2.101 → CI 触发成功）；resume_release.py 等待+下载成功。APK 0.2.101（aapt 实证 versionName=0.2.101，sha256 `808890c7…`，46,037,962 B）分发 updates+Downloads+update.json（Downloads 删 0.2.100）。
**注意**：管理后台新字段依赖 v0.2.101 客户端上报——手机未升级到 0.2.101 前，后台新增块显示 --/默认值（优雅降级）；admin dist 已重新构建，若服务端托管 admin 静态资源需确认其读取的是 admin/dist 最新产物。
**待真机验证**：升级 0.2.101 后后台四 Tab 与手机逐屏对照。

### v0.2.100 演示GPS覆盖时速/退出演示不清仪表盘（262.8km/h真因）/手机旧滤链段缓存（2026-11-07 21:58 实施）

**需求（用户 21:58 反馈 3 项）**：①演示模式刷新后其它值稳定了，但时速 1~2s 后仍消失；②真机连接平均时速 262.8（时速 11.3，截图 21:46）；③「公里」段前仍有停顿，疑段首尾静音未裁。

**根因（证据链）**：
- **① = 演示模式 GPS 仍订阅**：dashEnabled 且 demo 把 connected=true → SpeedDash.start 订阅真实 GPS；onFix 回调把注入的模拟时速覆盖成真实值（静止≈0.0）→「消失」。v0.2.99 只挡了 tickLoop，没挡 onFix。
- **② = 退出演示不清仪表盘（演示值混入真机统计）**：截图铁证——本次时长 6:58=418s、本次里程 1.47km，隐含均速 12.66km/h = 演示 refreshDemoData 的「时长=里程÷均速」自洽注入值；真机重连后 gpsTripKm=1.47（演示残留）+ movingSecondsAvg≈20.1s（真机新累计）→ avg=1.47×3600÷20.1=262.8。injectDemo 污染 gpsTripKm/tripSeconds，退出演示无清理路径。
- **③ = 手机本地旧滤链段缓存**：服务端进程 21:01:16 重启（晚于 19:05 双向裁静音改动），服务端缓存实证干净（「公里」0.528s、-40dB 无 ≥50ms 静音、ffprobe start=23ms 为 mp3 编码器延迟属正常）；但 v0.2.98 客户端（19:22 发，键 v5seg）在 19:22~21:01 服务端仍跑旧滤链（裁首+40ms 尾垫）期间拉取的段已带尾静音缓存在手机，缓存命中永不重拉 → 停顿持续。修法=客户端键 v5seg→v6seg 强制全量重拉。

**改前原文（逐字抄录）**：

1. SpeedDash.onFix（无演示守卫）：
```kotlin
    private fun onFix(loc: Location) {
        val v = loc.speed * 3.6f
        if (v < 0f || v > SPEED_MAX_KMH) return  // 毛刺丢弃
        speedKmh = v
```

2. JikongController 三处退出演示（均无 SpeedDash 清理）：
- connect 成功（L245）：`demoMode = false`（前文 `if (ok) {`＋注释「真机连接成功即退出演示模式」）
- disconnect（L276）：`demoMode = false`（if (demoMode) 分支内，后接 `status = JikongStatus()`）
- 自动重连成功（L572）：`demoMode = false`（if (ok) 分支内）

3. SpeedDash 无 clearDemoInjection（injectDemo 是唯一演示写入口，退出演示不清理）。

4. JikongSpeaker 缓存键：`val key = md5("$text|${JikongPrefs.TTS_VOICE}|${if (seg == 1) "v5seg" else "v3"}")`

5. versionName：`versionName = "0.2.99"`

**回滚配方**：`git revert` 本轮提交；或手改：onFix 删 demoMode 守卫、删 SpeedDash.clearDemoInjection 及三处调用、键 v6seg→v5seg（服务端 SEG_SUFFIX 不动）、versionName 回 0.2.99。
**触发信号**：演示模式时速变 0.0/真实值 / 真机平均时速再次出现数百 km/h / 播报段间停顿复现（确认服务端音频干净后即手机缓存问题）。

**实施结果（v0.2.100，22:26 发版）**：
1. SpeedDash.onFix 开头加 `if (JikongController.instance.demoMode) return`——演示期间真实 GPS 不再覆盖注入时速。
2. SpeedDash 新增 `clearDemoInjection()`：清 speedKmh/gpsFresh/avgSpeedKmh/rangeKm/instWhKm/avgWhKm/gpsTripKm/tripSeconds/movingSecondsAvg，bmsStale=true 等真机数据；不动 tripKm/tripWh/movingSeconds/ema/累计基线（演示值绝不入账）。JikongController 三处退出演示路径（connect 成功 L247 / disconnect L279 / 自动重连成功 L575）挂 `if (demoMode) SpeedDash.clearDemoInjection()`。
3. JikongSpeaker seg 键 v5seg→v6seg（整句 v3 不动）；服务端 SEG_SUFFIX/滤链均不动（音频已实证干净）。
4. 交付：提交 `dec5eb1`；release.py 轮询仍 IncompleteRead（直连大响应稳定截断），resume_release.py 接续成功；APK 0.2.100（sha256 `a903e15f…`，46,037,962 B）分发 updates+Downloads+update.json（Downloads 删 0.2.99）。
**待真机验证**：①演示模式刷新后时速稳定不再被真实 GPS 覆盖；②真机连接平均时速从 0 起算、数值合理（清零行程后更干净）；③升级后首次双击播报重拉 106 段（~2~5s），之后「公里」前无停顿。

### v0.2.99 演示值被 tickLoop 覆盖（4993km/h + 一闪即消）/ TTS 预取并行化（2026-11-07 21:06 实施）

**需求（用户 21:06 反馈 2 项）**：①演示模式点「刷新模拟数据」后平均时速显示 4993.0km/h，且剩余续航/瞬时/平均等数值**一闪就消失**（演示模式不应消失）；②刷新数据后双击仪表盘播报仍有延时——问预取是「一次性拉全部」还是「按需拉」。

**根因（证据链）**：
- **① = `SpeedDash.tickLoop` 在演示模式下照跑**：`SpeedDashCard` 的 DisposableEffect 在 `dashEnabled && status.connected` 时调 `start()`，而 enterDemoMode 把 `connected=true` → 积分协程每秒重算，把 injectDemo 注入值全部覆盖——`bmsOk=false` → rangeKm/instWhKm/avgWhKm 置 -1（一闪即消）；`movingSecondsAvg` 只在前 5s（gpsFresh 窗口）累积 1~5 秒，`avgSpeedKmh = 注入里程×3600÷移动秒数` → 天文数字（8.6km÷3s×3600≈10320，用户实测 4993.0 正落此区间）。injectDemo 注释「积分协程不跑」是错误假设。
- **② = 预取存在但串行且窗口期按需补拉**：`prefetchAll`（0..100+词组 106 段）确实已实现，但 v0.2.98 键 v4seg→v5seg 使本地缓存整批失效，首轮要串行重拉 106 段（每段 1 次网络请求，约 10~30s）；窗口内双击 → speakSegs 对缺段逐段同步补拉（每段一次网络往返）→ 可闻延时。窗口过后所有 ≤100 数值全命中缓存零网络，演示刷新生成的值（时速 6~42/续航 25~95/SOC 22~91）全在预取范围内。

**改前原文（逐字抄录）**：

1. SpeedDash.tickLoop 循环头：
```kotlin
        while (isActive) {
            delay(1_000)
            val nowElapsed = SystemClock.elapsedRealtime()
```

2. JikongSpeaker.prefetchAll 主体（串行逐段）：
```kotlin
            var pulled = 0
            for (t in texts) {
                // fetchOnline 内部：命中缓存直接返回（不触网），只有缺段才请求服务端
                if (fetchOnline(app, t, seg = 1) != null) pulled++
            }
            Timber.tag(TAG).i("TTS 分段预取完成：%d/%d 段就绪", pulled, texts.size)
```

3. fetchOnline 落盘临时文件（同名 tmp 有并发竞写风险）：
```kotlin
            val tmp = File(dir, "$key.tmp")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
```

4. versionName：`versionName = "0.2.98"`

**回滚配方**：`git revert` 本轮提交；或手改 3 处：tickLoop 循环头删 `if (controller.demoMode) continue`、prefetchAll 回串行、fetchOnline 回固定 `$key.tmp`；versionName 回 0.2.98。
**触发信号**：演示模式数值 1~5s 后被 --/异常值覆盖 / 平均时速出现数千 km/h / 预取窗口内双击播报仍慢。

**实施结果（v0.2.99，21:20 发版）**：
1. SpeedDash.tickLoop 循环头加 `if (controller.demoMode) continue`——演示期间整个积分循环跳过，注入值不被覆盖，点「刷新」重灌才变。
2. JikongSpeaker.prefetchAll 串行改每批 6 路 async/awaitAll 并发（106 段窗口 10~30s→约 2~5s）；fetchOnline tmp 名加线程+时间戳唯一化 + renameTo 前复查目标缓存（防预取与双击补拉并发竞写同一段）。
3. 提交 `dbe275a`（含 2 个新 tts_cache 段缓存文件），直连推送 main 成功；release.py v0.2.99 发版走远程 CI。
**问题解答（用户第 2 问）**：预取早已实现「一次性拉全部」（0..100 + 词组 106 段，幂等）；本次延时的原因是 v0.2.98 缓存键 v4seg→v5seg 让旧缓存整批失效，首轮串行重拉窗口 10~30s，窗口内双击对缺段按需补拉→可闻延时；窗口过后全部零网络秒播（演示刷新生成的数值全在预取范围内）。>100 的数值仍按需补拉后进缓存。
**待真机验证**：①演示模式点刷新后数值稳定不闪消、平均时速合理；②进演示约 5s 后双击播报零延时。

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

### v0.2.98 语音连读化/演示模拟数据刷新/演示自动退出（2026-11-07 19:05 实施）

**改前原文（逐字抄录）**：

1. tts.py `_SEG_AF`（v0.2.97 版，40ms 尾垫 + 只裁段首——edge-tts 每段天然尾静音叠加后停顿仍可闻）：
```python
_SEG_AF = (
    "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.02,"
    "loudnorm=I=-8:TP=-1.5:LRA=7,"
    "apad=pad_dur=0.04"
)

# 分段缓存键后缀（预热脚本 tts_prewarm.py 从这里 import，保证两端键推导永不漂移）
SEG_SUFFIX = "v3seg"
```

2. 段序含「百分之」（两处，3 段 2 停顿）：Controller.maybeAnnounce `val segs = mutableListOf("当前电量", "百分之", soc.toString())`；Pages.onDashDoubleTap `val segs = mutableListOf("当前电量", "百分之", soc.toString())`。

3. tts_prewarm.py：`PHRASES = ["当前电量", "百分之", "时速", "公里", "剩余续航", "电量低，请注意充电"]`

4. JikongSpeaker 客户端键：`val key = md5("$text|${JikongPrefs.TTS_VOICE}|${if (seg == 1) "v4seg" else "v3"}")`

5. SpeedDash 无演示注入接口（demoMode 时积分全停，speedKmh=-1 等）；JikongController.enterDemoMode 灌固定值、无 refreshDemoData；HomeView 断开仅在用户点击。

**回滚配方**：4 处键回退（v4seg/v5seg→v3seg/v4seg）、_SEG_AF 回 40ms apad、「百分之」段恢复、删 SpeedDash.injectDemo/JikongController.refreshDemoData、HomeView/ON_PAUSE 演示退出分支删除 → 删 tts_cache 重预热 → 重启服务端。
**触发信号**：播报恢复三段两停顿 / 演示模式无刷新按钮 / 后台后演示仍挂着。

**实施结果（v0.2.98，19:22 发版）**：
1. tts.py `_SEG_AF` 改双向裁静音（areverse→silenceremove→areverse）零尾垫；`SEG_SUFFIX` v3seg→v4seg；旧 v3seg 段删 57 个重预热（106 段，PHRASES 去「百分之」）。
2. 段序：Controller.maybeAnnounce 与 Pages.onDashDoubleTap 去「百分之」（3段2停顿→2段1停顿）；JikongSpeaker seg 键 v4seg→v5seg。
3. SpeedDash 新增 `injectDemo(speed,avgSpeed,range,instWh,avgWh,tripKm,seconds)`；Controller 新增 `refreshDemoData()`（Random：SOC 22~91、电压 62.8~68.0、单体 ±6mV 微扰、25% 概率充电、时速 6~42 或 15% 静止、平均=瞬时×0.55~0.85、时长=里程÷均速自洽、瞬时静止时 --），enterDemoMode 末尾注入默认值（18.4/15.2/58/18.6/19.8/8.6/2050s）。
4. HomeView 连接栏下演示横幅（amber + 「刷新模拟数据」clickable）。
5. 演示自动退出：HomeView DisposableEffect onDispose + JikongPage ON_PAUSE 均加 `if (demoMode) disconnect()`（真机连接不受影响）。
6. enterDemoMode 开头掐在飞尝试（cancelPendingConnect + reconnectJob.cancel）——修「尝试成功踢出演示」连带 bug；doConnect 失败路径 demoMode 时静默收尾。
7. 交付：APK 0.2.98（sha256 `6ef49405…`）三件套（删 v0.2.97）。**服务端需再重启一次**。
**待真机验证**：①「当前电量 72」两段近乎连读；②演示横幅点刷新全部数值合理重生成；③切主页/后台演示即退；④设默认设备后进演示不被踢出。

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
