package my.bridge.com.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.wechat.WechatAttachment
import my.bridge.com.wechat.WechatController
import my.bridge.com.wechat.WechatEmoji
import my.bridge.com.wechat.WechatDownloader
import my.bridge.com.wechat.WechatImageLoader
import my.bridge.com.wechat.WechatMessage
import my.bridge.com.wechat.WechatMsgType
import my.bridge.com.wechat.WechatVoicePlayer

// ============ 远程图片 / 头像 ============

/**
 * 远程图片（附件或头像）：内存缓存命中直接显示，否则 IO 线程下载后解码。
 *
 * 不引入 Coil/Glide：项目无该依赖，且附件需带 `?token=` 或 Bearer 头，自实现更可控。
 */
@Composable
fun WechatRemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    bearer: String = "",
    maxEdge: Int = 720,
    contentScale: ContentScale = ContentScale.Crop,
    loading: (@Composable () -> Unit)? = null,
) {
    var bmp by remember(url) { mutableStateOf<Bitmap?>(WechatImageLoader.cached(url)) }
    if (bmp == null && url.isNotBlank()) {
        LaunchedEffect(url) {
            withContext(Dispatchers.IO) {
                val loaded = WechatImageLoader.load(url, bearer = bearer, maxEdge = maxEdge)
                if (loaded != null) bmp = loaded
            }
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else if (loading != null) {
        loading()
    }
}

/** 会话/联系人头像：远程头像优先，失败回落首字母色块（与参考前端 avatarManager 一致）。 */
@Composable
fun WechatAvatar(wxid: String, name: String, size: Dp = 40.dp, radius: Dp = 5.dp) {
    val context = LocalContext.current
    val url = remember(wxid) { WechatController.avatarUrl(context, wxid) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(wechatAvatarColor(wxid)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            WechatRemoteImage(
                url = url,
                bearer = WechatController.apiKey(context),
                maxEdge = 160,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 头像加载中/失败时显示首字母（图片加载完成后会盖在上面）
        val initial = name.take(1).ifBlank { "微" }
        if (WechatImageLoader.cached(url) == null) {
            Text(initial, color = Color.White, fontSize = (size.value / 2.6).sp)
        }
    }
}

private fun wechatAvatarColor(wxid: String): Color {
    val palette = listOf(
        Color(0xFFE6A23C), Color(0xFF6C8CFF), Color(0xFF5AB86C),
        Color(0xFF8A7BE6), Color(0xFFE57373), Color(0xFF4CB3A5),
    )
    return palette[(wxid.hashCode() and Int.MAX_VALUE) % palette.size]
}

// ============ 消息内容（按 msgType 渲染） ============

/**
 * 富消息内容渲染 —— 类型与字段名严格照参考项目：
 * 3 图片 / 34 语音 / 43 视频 / 47 表情 / 48 位置 / 49 应用消息（含微信支付卡）/ 10000·10002 系统。
 */
@Composable
fun WechatMessageContent(
    msg: WechatMessage,
    onOpenImage: (String, String) -> Unit,
    onPlayVoice: (WechatMessage, WechatAttachment) -> Unit,
    onOpenFile: (WechatMessage, WechatAttachment) -> Unit,
) {
    val att = remember(msg.msgId + msg.attachmentJson) { WechatAttachment.parse(msg.attachmentJson) }
    val context = LocalContext.current
    val playing = WechatVoicePlayer.playingMsgId == msg.msgId

    when (msg.msgType) {
        WechatMsgType.IMAGE -> {
            val obj = att.imageObject
            if (obj.isBlank()) {
                Text("[图片]", fontSize = 13.sp)
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = 180.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenImage(att.imageOrigObject, "image_${msg.msgId.ifBlank { System.currentTimeMillis() }}.jpg") },
                ) {
                    WechatRemoteImage(
                        url = WechatController.attachmentUrl(context, obj),
                        modifier = Modifier.widthIn(max = 180.dp).heightIn(max = 240.dp),
                        loading = { Box(modifier = Modifier.size(120.dp).background(Color(0xFFE9EBEF))) },
                    )
                }
            }
        }

        WechatMsgType.VIDEO -> {
            val cover = att.movieCover
            Box(
                modifier = Modifier
                    .widthIn(max = 180.dp)
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2B2F36))
                    .clickable { onOpenFile(msg, att) },
                contentAlignment = Alignment.Center,
            ) {
                if (cover.isNotBlank()) {
                    WechatRemoteImage(
                        url = WechatController.attachmentUrl(context, cover),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }

        WechatMsgType.VOICE -> {
            Row(
                modifier = Modifier
                    .widthIn(min = 70.dp, max = 190.dp)
                    .clickable { onPlayVoice(msg, att) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (playing) "⏸" else "▶", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (att.duration > 0) "${att.duration}\"" else "[语音]",
                    fontSize = 13.sp,
                )
            }
        }

        WechatMsgType.EMOJI -> {
            val obj = att.emojiObject
            if (obj.isBlank()) {
                // v0.2.23：`[微笑]` 这类文本表情代码要渲染成 emoji（照前端 replaceEmojiCodes）
                Text(WechatEmoji.replaceCodes(msg.content).ifBlank { "[表情]" }, fontSize = 13.sp)
            } else {
                WechatRemoteImage(
                    url = WechatController.attachmentUrl(context, obj),
                    modifier = Modifier.size(96.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        WechatMsgType.LOCATION -> {
            Column(modifier = Modifier.widthIn(max = 200.dp)) {
                val map = att.mapObject
                if (map.isNotBlank()) {
                    WechatRemoteImage(
                        url = WechatController.attachmentUrl(context, map),
                        modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    att.poiName.ifBlank { att.label }.ifBlank { msg.content }.ifBlank { "位置消息" },
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (att.lat != 0.0 && att.lng != 0.0) {
                    Text("%.5f, %.5f".format(att.lat, att.lng), fontSize = 10.sp, color = Color(0xFF8A919F))
                }
            }
        }

        WechatMsgType.XML -> {
            if (att.subType == WechatMsgType.SUB_WECHAT_PAY) {
                WechatPayCard(att)
            } else {
                Text(msg.content.replace(Regex("<[^>]+>"), "").trim().ifBlank { "[应用消息]" }, fontSize = 13.sp)
            }
        }

        WechatMsgType.VOIP -> Text("[语音/视频通话]", fontSize = 13.sp)

        WechatMsgType.TEXT -> Text(WechatEmoji.replaceCodes(msg.content), fontSize = 14.sp)

        else -> {
            // 文件（attachment.path）与其它未覆盖类型
            if (att.filePath.isNotBlank() || att.fileName.isNotBlank()) {
                Row(
                    modifier = Modifier.widthIn(max = 200.dp).clickable { onOpenFile(msg, att) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = Color(0xFF8A919F), modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            att.fileName.ifBlank { att.filePath.substringAfterLast('/') }.ifBlank { "文件" },
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text("点击下载", fontSize = 10.sp, color = Color(0xFF8A919F))
                    }
                }
            } else {
                Text(WechatEmoji.replaceCodes(msg.content).ifBlank { "[消息]" }, fontSize = 13.sp)
            }
        }
    }
}

/** 微信支付卡片（XML 49 / sub_type=wechat_pay，照 ChatPanel.vue 第 66~80 行）。 */
@Composable
private fun WechatPayCard(att: WechatAttachment) {
    val isReceive = att.payDirection.contains("收款")
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp,
        modifier = Modifier.widthIn(max = 220.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description, contentDescription = null,
                    tint = if (isReceive) Color(0xFF07C160) else Color(0xFFE5484D),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(att.payHeaderTitle.ifBlank { "微信支付" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (att.payTitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(att.payTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            att.payLines.forEach {
                Text(it, fontSize = 11.sp, color = Color(0xFF6B7280))
            }
        }
    }
}

// ============ 大图查看器 ============

/**
 * 图片查看器（照参考前端 ImageViewer）：支持保存到 `/sdcard/bridge/wechat/images`。
 */
@Composable
fun WechatImageViewer(
    objectName: String,
    fileName: String,
    onDismiss: () -> Unit,
    onSave: suspend (String, String) -> Result<android.net.Uri>,
) {
    val context = LocalContext.current
    val url = remember(objectName) { WechatController.attachmentUrl(context, objectName) }
    var saving by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xCC000000))) {
            var bmp by remember(url) { mutableStateOf<Bitmap?>(WechatImageLoader.cached(url)) }
            LaunchedEffect(url) {
                withContext(Dispatchers.IO) {
                    val loaded = WechatImageLoader.load(url, maxEdge = 1600)
                    if (loaded != null) bmp = loaded
                }
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            // 底部操作条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("保存中...", color = Color.White, fontSize = 13.sp)
                } else {
                    Text(
                        "保存",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x33000000))
                            .clickable {
                                saving = true
                                err = ""
                                kotlinx.coroutines.MainScope().launch {
                                    val r = onSave(objectName, fileName)
                                    saving = false
                                    if (r.isSuccess) {
                                        savedPath = WechatDownloader.logicalPath(WechatDownloader.Kind.IMAGE, fileName)
                                    } else {
                                        err = r.exceptionOrNull()?.message ?: "保存失败"
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "关闭",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x33000000))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            if (savedPath.isNotBlank() || err.isNotBlank()) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (err.isNotBlank()) err else "已保存到 $savedPath",
                        color = if (err.isNotBlank()) Color(0xFFFF8A80) else Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** 位置消息里的地图缩略图用（与消息气泡共用 [WechatRemoteImage]）。 */
@Composable
fun WechatLocationIcon() {
    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color(0xFFE5484D), modifier = Modifier.size(18.dp))
}
