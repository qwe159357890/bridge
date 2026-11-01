package my.bridge.com.wechat

import android.content.Context

/**
 * 微信页**页面侧**控制器 —— 只做「订阅/退订」与用户动作，不持有连接。
 *
 * 与销售页 [my.bridge.com.sales.SalesController] 的关键区别：
 * - 销售页：`enterPage()` 建连、`exitPage()` 断连（连接随页面生死）；
 * - 微信页：`onEnter()` 只是订阅 Runtime 的数据、`onLeave()` 只是退订，**连接一直在**
 *   （实时性是硬需求：离开微信页仍要收到消息并通知）。
 *
 * 退订清理的是「观察关系」（正在查看的会话、为 UI 服务的刷新），不是连接。
 */
object WechatController {

    @Volatile
    private var viewingSessionWxid: String? = null

    /** 用户是否正在看这个会话（用于避免正在看的会话还弹提示）。 */
    fun isViewing(sessionWxid: String): Boolean = viewingSessionWxid == sessionWxid

    /** 页面进入 → 订阅。 */
    fun onEnter(context: Context) {
        WechatRuntime.onPageEnter(context)
    }

    /** 页面离开 → 退订（连接不断）。 */
    fun onLeave() {
        viewingSessionWxid = null
        WechatRuntime.onPageLeave()
    }

    fun openSession(context: Context, sessionWxid: String) {
        viewingSessionWxid = sessionWxid
        WechatRuntime.openSession(context, sessionWxid)
    }

    fun closeSession() {
        viewingSessionWxid = null
    }

    fun selectAccount(context: Context, wxid: String) = WechatRuntime.selectAccount(context, wxid)

    fun setSessionMode(context: Context, mode: String) = WechatRuntime.setSessionMode(context, mode)

    fun setContactsFilter(context: Context, filter: String) = WechatRuntime.setContactsFilter(context, filter)

    fun setNotifyEnabled(context: Context, enabled: Boolean) = WechatRuntime.setNotifyEnabled(context, enabled)

    fun setSoundEnabled(context: Context, enabled: Boolean) = WechatRuntime.setSoundEnabled(context, enabled)

    fun setMuteAll(context: Context, muted: Boolean) = WechatRuntime.setMuteAll(context, muted)

    fun togglePin(context: Context, wxid: String, pinned: Boolean) = WechatRuntime.togglePin(context, wxid, pinned)

    fun toggleMute(context: Context, wxid: String, muted: Boolean) = WechatRuntime.toggleMute(context, wxid, muted)

    fun clearSession(context: Context, wxid: String) = WechatRuntime.clearSession(context, wxid)

    /** 删除单条消息。 */
    suspend fun deleteMessage(context: Context, sessionWxid: String, msgId: String): Result<Unit> =
        WechatRuntime.deleteMessage(context, sessionWxid, msgId)

    fun refreshSessions() = WechatRuntime.refreshSessions()

    /** 加载下一页会话（列表滑到底调用，v0.2.23）。 */
    fun loadMoreSessions() = WechatRuntime.loadMoreSessions()

    /** 加载下一页联系人（列表滑到底调用，v0.2.23）。 */
    fun loadMoreContacts() = WechatRuntime.loadMoreContacts()

    /** 最新一条未读会话（双击「聊天」按钮跳过去），没有未读返回 null。 */
    fun latestUnreadSession(): WechatSession? = WechatRuntime.latestUnreadSession()

    /** 设置页展示用：实际 WS 端点。 */
    fun wsEndpoint(context: Context): String = WechatRuntime.wsEndpoint(context)

    /**
     * 主动退出微信：释放微信相关全部资源（WS、HTTP 连接池与线程池、语音、内存数据）。
     * 再次进入微信页会自动重连。
     */
    fun logout(context: Context) {
        WechatRuntime.logout(context)
    }

    /** 「离开微信页立即释放」开关（保活设置里）。 */
    fun releaseOnLeave(context: Context): Boolean = WechatPrefs.releaseOnLeave(context)

    fun setReleaseOnLeave(context: Context, on: Boolean) {
        WechatPrefs.setReleaseOnLeave(context, on)
    }

    fun refreshContacts() = WechatRuntime.refreshContacts()

    suspend fun sendText(context: Context, targetWxid: String, text: String): Result<Unit> =
        WechatRuntime.sendText(context, targetWxid, text)

    suspend fun sendImage(context: Context, targetWxid: String, fileName: String, mime: String, bytes: ByteArray): Result<Unit> =
        WechatRuntime.sendImage(context, targetWxid, fileName, mime, bytes)

    suspend fun loadMessages(context: Context, sessionWxid: String): List<WechatMessage> =
        WechatRuntime.loadMessages(context, sessionWxid)

    /** 加载更早的消息（分页 offset）。 */
    suspend fun loadMoreMessages(context: Context, sessionWxid: String, offset: Int): List<WechatMessage> =
        WechatRuntime.loadMessages(context, sessionWxid, limit = 30, offset = offset)

    /** 发送图片/视频/文件（multipart upload_and_send）。 */
    suspend fun sendFile(context: Context, targetWxid: String, fileName: String, mime: String, bytes: ByteArray): Result<Unit> =
        WechatRuntime.sendFile(context, targetWxid, fileName, mime, bytes)

    /** 附件 URL（图片/视频封面/表情/语音等）。 */
    fun attachmentUrl(context: Context, objectName: String): String =
        WechatRuntime.attachmentUrl(context, objectName)

    /** 头像 URL（Bearer 鉴权）。 */
    fun avatarUrl(context: Context, wxid: String): String = WechatRuntime.avatarUrl(context, wxid)

    fun apiKey(context: Context): String = WechatRuntime.apiKey(context)

    /** 保存附件到 `/sdcard/bridge/wechat/...`。 */
    suspend fun saveAttachment(
        context: Context,
        objectName: String,
        fileName: String,
        kind: WechatDownloader.Kind,
    ): Result<android.net.Uri> = WechatRuntime.saveAttachment(context, objectName, fileName, kind)

    /** 语音：点一下播、再点停。 */
    suspend fun toggleVoice(context: Context, msgId: String, objectName: String) =
        WechatRuntime.toggleVoice(context, msgId, objectName)

    fun stopVoice() = WechatRuntime.stopVoice()

    /** 页面关闭时释放播放器（页面级资源，连接不动）。 */
    fun releaseMedia() = WechatRuntime.stopVoice()

    /** 修改服务器配置后重连（设置页「修改服务器」用）。 */
    fun reconfigure(context: Context, url: String, apiKey: String) {
        my.bridge.com.wechat.WechatPrefs.setServerUrl(context, url)
        my.bridge.com.wechat.WechatPrefs.setApiKey(context, apiKey)
        WechatRuntime.onConfigChanged(context)
    }

    /** 断开当前连接（保留配置）：Runtime 会自动 2s 重连。 */
    fun reconnectNow(context: Context) = WechatRuntime.onConfigChanged(context)

    /** 清空本地图片缓存与语音缓存。 */
    fun clearCache() {
        WechatImageLoader.clear()
        WechatVoicePlayer.stop()
    }

    /** 测试并保存：先测输入值，通过才落库并重连。 */
    suspend fun saveConfig(context: Context, url: String, apiKey: String): Boolean =
        saveConfigInternal(context, url, apiKey)

    /** 设置页「修改服务器」语义同 [saveConfig]（测试通过才保存）。 */
    suspend fun testConfig(context: Context, url: String, apiKey: String): Boolean =
        saveConfigInternal(context, url, apiKey)

    private suspend fun saveConfigInternal(context: Context, url: String, apiKey: String): Boolean {
        val ok = WechatRuntime.testConnection(context, url, apiKey)
        if (ok) WechatRuntime.saveConfig(context, url, apiKey)
        return ok
    }
}
