package my.bridge.com.immich

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Immich 主题调色板（复刻 Immich 安卓版 constants/colors.dart 品牌色）。
 *
 * 品牌色实锤（Immich mobile 源码）：
 * - 亮色主色 #4150AF（indigo）；暗色主色 #ACCBFA（浅蓝）。
 * - 界面用色从原型 assets/design/ui-mockup-immich.html 的 CSS 变量 1:1 迁移。
 *
 * 本阶段（纯 UI 复刻）主题跟随系统深浅色；后续接 API 阶段可按决策 D6 扩展手动切换。
 */
data class ImmichPalette(
    /** 页面背景（时间线 / 搜索 / 相册页底色） */
    val bg: Color,
    /** 一级表面色（卡片 / 药丸底） */
    val surface: Color,
    /** 二级表面色（输入框 / 选中药丸 / 相册封面空态） */
    val surface2: Color,
    /** 三级表面色（拖动条浮层 / 角标） */
    val surface3: Color,
    /** 主文字 */
    val text: Color,
    /** 次要文字（计数 / 说明） */
    val sub: Color,
    /** 分隔线 / 描边 */
    val divider: Color,
    /** 品牌强调色（图标 / 选中态）：亮 #4150AF / 暗 #ACCBFA */
    val accent: Color,
    /** 品牌深色（logo 圆徽底色）：两套主题均为 #4150AF */
    val accentDeep: Color,
    /** 底部导航底色 */
    val navBg: Color,
    /** 危险色（删除动作） */
    val danger: Color,
    /** 收藏心形色 */
    val heart: Color,
)

/** 暗色调色板（原型 CSS 暗色变量）。 */
val ImmichDarkPalette = ImmichPalette(
    bg = Color(0xFF000000),
    surface = Color(0xFF0F0F10),
    surface2 = Color(0xFF1C1C1E),
    surface3 = Color(0xFF2A2A2D),
    text = Color(0xFFE8EAED),
    sub = Color(0xFF9AA0A6),
    divider = Color(0xFF2A2A2D),
    accent = Color(0xFFACCBFA),
    accentDeep = Color(0xFF4150AF),
    navBg = Color(0xFF101012),
    danger = Color(0xFFF2B8B5),
    heart = Color(0xFFFF6072),
)

/** 亮色调色板（原型 CSS 亮色变量）。 */
val ImmichLightPalette = ImmichPalette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F5F7),
    surface2 = Color(0xFFECECF0),
    surface3 = Color(0xFFDCDCE2),
    text = Color(0xFF1A1B1F),
    sub = Color(0xFF5D6672),
    divider = Color(0xFFE0E4E9),
    accent = Color(0xFF4150AF),
    accentDeep = Color(0xFF4150AF),
    navBg = Color(0xFFF2F2F6),
    danger = Color(0xFFB3261E),
    heart = Color(0xFFE5526A),
)

/** Immich 调色板组合局部：ImmichPage 根部提供，子组件随处读取。 */
val LocalImmichPalette = staticCompositionLocalOf { ImmichDarkPalette }

/**
 * 生成网格缩略图占位渐变（对齐原型 tileHtml 的色块公式）。
 *
 * 原型公式：135° 线性渐变，主色 hsl(hue,45%,28%+i*13%14) → 邻色 hsl(hue+40,40%,16%+i*7%10)。
 * 真机接 API 后由 Coil 加载缩略图替换，占位公式保持不变以便视觉回归对比。
 *
 * :param index: 网格内索引，驱动明度抖动产生相邻色块差异
 * :param hue: 基础色相（月份 / 相册各给一色）
 * :return: 缩略图占位渐变画刷
 */
fun immichTileBrush(index: Int, hue: Int): Brush {
    val l1 = 28f + (index * 13) % 14
    val l2 = 16f + (index * 7) % 10
    return Brush.linearGradient(
        listOf(
            Color.hsl(hue.toFloat(), 0.45f, l1 / 100f),
            Color.hsl(((hue + 40) % 360).toFloat(), 0.40f, l2 / 100f),
        ),
    )
}

/**
 * 生成大图查看器占位渐变（对齐原型 openViewer 的 v-img 公式）。
 *
 * 原型公式：135° 渐变 hsl(200+i*23,45%,30%) → hsl((240+i*17)%360,40%,14%)。
 *
 * :param index: 资产索引，驱动色相偏移使连续查看有明显区分
 * :return: 查看器大图占位渐变画刷
 */
fun immichViewerBrush(index: Int): Brush = Brush.linearGradient(
    listOf(
        Color.hsl((200 + index * 23) % 360f, 0.45f, 0.30f),
        Color.hsl((240 + index * 17) % 360f, 0.40f, 0.14f),
    ),
)
