package my.bridge.com.wechat

import org.json.JSONObject

/**
 * 附件（attachment）解析 —— **字段严格照参考项目源码，不臆造**。
 *
 * 实锤来源：
 * - 图片 `thumb_path` / `image_path`：`agent/core/agent_client.py` 1634~1635
 * - 文件 `path`：`agent/core/agent_client.py` 1653
 * - 视频 `movie_cover` / `movie_path`：`agent/core/agent_client.py` 1704~1719
 * - 语音 `voice_path` + `duration`：`agent/core/agent_client.py` 1753
 * - 表情 `emoji_object`：`web-frontend/src/components/chat/ChatPanel.vue` 874
 * - 位置 `map_object` / `poiname` / `label` / `coordinate.{lng,lat}`：ChatPanel.vue 1837~1855
 * - 微信支付 `sub_type=wechat_pay` / `direction` / `header_title` / `title` / `lines`：ChatPanel.vue 66~73
 * - 文件 URL：`{server}/api/files/{object}?token={api_key}`：`web-frontend/src/utils/apiUrl.ts` 36
 */
data class WechatAttachment(
    /** 图片缩略图对象名（优先用它渲染，省流量）。 */
    val thumbPath: String = "",
    /** 图片原图对象名（大图查看用）。 */
    val imagePath: String = "",
    /** 视频封面对象名。 */
    val movieCover: String = "",
    /** 视频本体对象名。 */
    val moviePath: String = "",
    /** 语音本体对象名。 */
    val voicePath: String = "",
    /** 普通文件对象名（attachment.path）。 */
    val filePath: String = "",
    /** 表情对象名。 */
    val emojiObject: String = "",
    /** 位置地图对象名。 */
    val mapObject: String = "",
    /** 位置名（poiname）。 */
    val poiName: String = "",
    /** 位置标签（label）。 */
    val label: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 语音时长（秒）。 */
    val duration: Int = 0,
    val fileName: String = "",
    /** XML(49) 子类型，如 `wechat_pay`。 */
    val subType: String = "",
    val payDirection: String = "",
    val payHeaderTitle: String = "",
    val payTitle: String = "",
    val payLines: List<String> = emptyList(),
) {

    /** 图片：优先缩略图，无则原图（照 ChatPanel.vue getImageThumbSrc）。 */
    val imageObject: String get() = thumbPath.ifBlank { imagePath }

    /** 图片原图：优先 image_path，无则缩略图（照 getImageOrigSrc）。 */
    val imageOrigObject: String get() = imagePath.ifBlank { thumbPath }

    companion object {

        fun parse(raw: String): WechatAttachment {
            if (raw.isBlank()) return WechatAttachment()
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return WechatAttachment()
            val coord = o.optJSONObject("coordinate")
            val lines = o.optJSONArray("lines")
            return WechatAttachment(
                thumbPath = o.optString("thumb_path"),
                imagePath = o.optString("image_path"),
                movieCover = o.optString("movie_cover"),
                moviePath = o.optString("movie_path"),
                voicePath = o.optString("voice_path"),
                filePath = o.optString("path"),
                emojiObject = o.optString("emoji_object"),
                mapObject = o.optString("map_object"),
                poiName = o.optString("poiname"),
                label = o.optString("label"),
                lat = coord?.optDouble("lat", 0.0) ?: 0.0,
                lng = coord?.optDouble("lng", 0.0) ?: 0.0,
                duration = o.optInt("duration", 0),
                fileName = o.optString("file_name").ifBlank { o.optString("filename") },
                subType = o.optString("sub_type"),
                payDirection = o.optString("direction"),
                payHeaderTitle = o.optString("header_title"),
                payTitle = o.optString("title"),
                payLines = if (lines == null) emptyList()
                else (0 until lines.length()).mapNotNull { i -> lines.optString(i).takeIf { it.isNotBlank() } },
            )
        }
    }
}
