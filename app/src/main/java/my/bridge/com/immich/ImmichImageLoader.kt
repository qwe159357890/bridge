package my.bridge.com.immich

import android.content.Context
import coil.ImageLoader
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Immich 专用 Coil ImageLoader 单例（接 API 阶段，2026-11-16）。
 *
 * 缩略图 / 人物头像 / 大图预览加载统一走此 ImageLoader：
 * - OkHttp 拦截器为所有图片请求补 x-immich-user-token 头（Coil 无法在 URL 上带鉴权，
 *   query 参数有泄漏进日志的风险，调研报告 §3.4 结论：头方式更稳）；
 * - 内存缓存配额放大（时间线 3 列网格快速回滚）。
 */
object ImmichImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    /**
     * 获取全局 Immich ImageLoader（懒加载单例）。
     *
     * :param context: 任意 Context（取 applicationContext）
     * :return: 带 token 拦截器的 ImageLoader
     */
    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    /**
     * 构造带 token 拦截器的 ImageLoader。
     *
     * :param appContext: 应用上下文（拦截器内读 ImmichPrefs.token）
     * :return: ImageLoader
     */
    private fun build(appContext: Context): ImageLoader = ImageLoader.Builder(appContext)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor(ImmichTokenInterceptor(appContext))
                .build()
        }
        .crossfade(true)
        .build()
}

/**
 * 图片请求令牌拦截器：为所有 Immich 图片请求补 x-immich-user-token 头。
 *
 * :param appContext: 应用上下文（读 ImmichPrefs.token）
 */
private class ImmichTokenInterceptor(private val appContext: Context) : Interceptor {

    /**
     * 拦截图片请求并注入令牌头。
     *
     * :param chain: OkHttp 拦截链
     * :return: 补头后的响应
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = ImmichPrefs.token(appContext)
        val request = if (token.isNotEmpty()) {
            chain.request().newBuilder().header("x-immich-user-token", token).build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
