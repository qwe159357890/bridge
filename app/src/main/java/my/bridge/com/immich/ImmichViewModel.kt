package my.bridge.com.immich

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import my.bridge.com.core.common.DiagLog

private const val NTAG = "相册"

/**
 * Immich 相册功能视图模型（接 API 阶段，2026-11-16；2026-11-16 二轮反馈修复）。
 *
 * 承载跨 Tab 共享状态：时间线（分页加载 + 三级分组 + 本地未上传资产合并）、
 * 相册列表、人物列表、资产统计、分类结果网格（收藏/最近/视频/回收站/归档/人物/文件名共用）。
 * 网络调用统一切 IO 线程，UI 经 StateFlow 订阅（同 MonitorViewModel 范式）。
 *
 * 本地资产合并（用户反馈第 5 条，借鉴 Immich 原版）：MediaStore 本地照片/视频与
 * 云端资产按文件名匹配，未匹配到的（最近 40 天内）作为「未上传」资产并入时间线，
 * 网格块带角标区分（☁ 已备份 / ⬆ 未上传）。
 */
class ImmichViewModel : ViewModel() {

    /** 分页大小（search/metadata 单页；1000 太重 500 折中）。 */
    private val pageSize = 500

    /** 本地候选资产仅取最近 N 天（云端分页只加载了最近部分，窗口外匹配会误报未上传）。 */
    private val localWindowDays = 40

    // ============ 时间线（照片 Tab） ============

    /** 时间线云端资产（降序累计；分组由 UI 用 ImmichTimeline.group 现算）。 */
    private val _timelineAssets = MutableStateFlow<List<ImmichAsset>>(emptyList())
    val timelineAssets: StateFlow<List<ImmichAsset>> = _timelineAssets

    /** 本机媒体原始候选（最近 40 天窗口；未与云端匹配的原始集）。 */
    private val _rawLocalAssets = MutableStateFlow<List<ImmichAsset>>(emptyList())

    /** 本地未上传资产（重组时按当前已加载云端文件名现算）。 */
    private val _localOnlyAssets = MutableStateFlow<List<ImmichAsset>>(emptyList())
    val localOnlyAssets: StateFlow<List<ImmichAsset>> = _localOnlyAssets

    /** 时间线三级分组结果（云端 + 本地合并后；缓存避免每次重组重算）。 */
    private val _timelineGroups = MutableStateFlow<List<ImmichTimeline.ImmichGroup>>(emptyList())
    val timelineGroups: StateFlow<List<ImmichTimeline.ImmichGroup>> = _timelineGroups

    private val _timelineLoading = MutableStateFlow(false)
    val timelineLoading: StateFlow<Boolean> = _timelineLoading

    /** 时间线加载更多中（底部 loading 条）。 */
    private val _timelineLoadingMore = MutableStateFlow(false)
    val timelineLoadingMore: StateFlow<Boolean> = _timelineLoadingMore

    /** 服务端检索是否已无下一页。 */
    private val _timelineExhausted = MutableStateFlow(false)
    val timelineExhausted: StateFlow<Boolean> = _timelineExhausted

    /** 时间线刷新序号（每次 loadTimelinePage 自增；UI 监听复位滚动到顶部）。 */
    private val _refreshSeq = MutableStateFlow(0)
    val refreshSeq: StateFlow<Int> = _refreshSeq

    private var timelinePage = 0

    // ============ 相册 / 人物 / 统计 ============

    private val _albums = MutableStateFlow<List<ImmichAlbumInfo>>(emptyList())
    val albums: StateFlow<List<ImmichAlbumInfo>> = _albums

    private val _people = MutableStateFlow<List<ImmichPerson>>(emptyList())
    val people: StateFlow<List<ImmichPerson>> = _people

    private val _statistics = MutableStateFlow<ImmichStatistics?>(null)
    val statistics: StateFlow<ImmichStatistics?> = _statistics

    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading: StateFlow<Boolean> = _albumsLoading

    // ============ 分类结果网格（搜索/资料/人物页共用覆盖层） ============

    /** 当前结果网格标题。 */
    private val _resultsTitle = MutableStateFlow("")
    val resultsTitle: StateFlow<String> = _resultsTitle

    /** 结果资产（降序累计）。 */
    private val _resultsAssets = MutableStateFlow<List<ImmichAsset>>(emptyList())
    val resultsAssets: StateFlow<List<ImmichAsset>> = _resultsAssets

    private val _resultsLoading = MutableStateFlow(false)
    val resultsLoading: StateFlow<Boolean> = _resultsLoading

    private val _resultsLoadingMore = MutableStateFlow(false)
    val resultsLoadingMore: StateFlow<Boolean> = _resultsLoadingMore

    private val _resultsExhausted = MutableStateFlow(false)
    val resultsExhausted: StateFlow<Boolean> = _resultsExhausted

    /** 当前结果检索条件（加载更多续页用）。 */
    private var resultsQuery = ImmichQuery()

    private var resultsPage = 0

    // ============ 错误与配置态 ============

    /** 最近一次加载错误文案（null = 无错）。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 是否已配置账号（未配置时 UI 引导去设置页填写）。 */
    val configured = MutableStateFlow(true)

    /** 全局资产收藏态覆盖表（assetId → 最新收藏态，收藏切换后立即反映到各网格）。 */
    private val _favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val favoriteOverrides: StateFlow<Map<String, Boolean>> = _favoriteOverrides

    /**
     * 检索条件（分类结果网格的参数载体）。
     *
     * :param type: IMAGE / VIDEO
     * :param personId: 人物 ID
     * :param albumId: 相册 ID（截图分类）
     * :param favorite: 收藏过滤
     * :param archived: 归档过滤
     * :param trashed: 回收站过滤
     * :param filename: 文件名模糊搜索
     * :param title: 结果页标题
     */
    data class ImmichQuery(
        val type: String = "",
        val personId: String = "",
        val albumId: String = "",
        val favorite: Boolean? = null,
        val archived: Boolean? = null,
        val trashed: Boolean? = null,
        val filename: String = "",
        val title: String = "",
    )

    /**
     * 进入相册页拉全量基础数据（时间线首页刷新 + 元数据 + 本地资产扫描）。
     * 时间线每次进入都刷新（拍照备份后回相册页能看到新照片——用户反馈第 4 条）。
     *
     * :param context: 任意 Context（ApiClient 用 applicationContext）
     */
    fun loadAll(context: Context) {
        configured.value = ImmichPrefs.isConfigured(context)
        if (!configured.value) {
            DiagLog.i(NTAG, "未配置服务端账号，跳过加载（引导用户去设置页）")
            return
        }
        loadTimelinePage(context)
        loadMeta(context)
        loadLocalMedia(context)
    }

    /**
     * 拉取时间线下一页（首页 loadTimelinePage 或无限滚动 loadMoreTimeline 调用）。
     *
     * :param context: 任意 Context
     */
    private fun fetchTimelinePage(context: Context) {
        viewModelScope.launch {
            val isFirst = timelinePage == 0
            if (isFirst) _timelineLoading.value = true else _timelineLoadingMore.value = true
            _error.value = null
            try {
                val api = ImmichApiClient(context.applicationContext)
                val target = timelinePage + 1
                val page = withContext(Dispatchers.IO) { api.searchAssets(target, pageSize) }
                timelinePage += 1
                _timelineAssets.value = _timelineAssets.value + page.items
                recomputeTimeline()
                _timelineExhausted.value = !page.hasNext
                DiagLog.i(NTAG, "时间线累计 ${_timelineAssets.value.size} 条（p$target exhausted=${page.hasNext.not()}）")
            } catch (e: Exception) {
                _error.value = "时间线加载失败：${e.message}"
                DiagLog.e(NTAG, "时间线加载失败（p${timelinePage + 1}）：${_error.value}", e)
            } finally {
                _timelineLoading.value = false
                _timelineLoadingMore.value = false
            }
        }
    }

    /**
     * 加载时间线首页（重置分页游标，本地合并组重算）。
     *
     * :param context: 任意 Context
     */
    fun loadTimelinePage(context: Context) {
        timelinePage = 0
        _timelineAssets.value = emptyList()
        _timelineExhausted.value = false
        _refreshSeq.value += 1
        fetchTimelinePage(context)
    }

    /**
     * 无限滚动加载时间线下一页（网格接近底部时调用；带跳过原因日志便于真机诊断）。
     *
     * :param context: 任意 Context
     */
    fun loadMoreTimeline(context: Context) {
        if (_timelineLoading.value) {
            DiagLog.i(NTAG, "无限滚动跳过：首页加载中")
            return
        }
        if (_timelineLoadingMore.value) {
            DiagLog.i(NTAG, "无限滚动跳过：正在加载下一页")
            return
        }
        if (_timelineExhausted.value) {
            DiagLog.i(NTAG, "无限滚动跳过：已到最后一页")
            return
        }
        DiagLog.i(NTAG, "无限滚动触发：拉取 p${timelinePage + 1}")
        fetchTimelinePage(context)
    }

    /**
     * 重算时间线三级分组：本地候选按「当前已加载云端文件名」现算匹配后与云端资产
     * 合并降序分组（修复：扫描早于分页返回时云端名单为空 → 全部误标未上传导致重复显示）。
     */
    private fun recomputeTimeline() {
        val cloudNames = _timelineAssets.value.mapTo(HashSet()) { it.fileName }
        val localOnly = _rawLocalAssets.value.filter { it.fileName !in cloudNames }
        _localOnlyAssets.value = localOnly
        val merged = ImmichLocalMedia.merge(_timelineAssets.value, localOnly)
        _timelineGroups.value = ImmichTimeline.group(merged)
        DiagLog.i(NTAG, "时间线重组：云端 ${_timelineAssets.value.size} / 本地候选 ${_rawLocalAssets.value.size} → 未上传 ${localOnly.size}")
    }

    /**
     * 扫描本机 MediaStore 全部候选（最近窗口），存原始集后重组（匹配在重组时现算）。
     *
     * :param context: 任意 Context
     */
    fun loadLocalMedia(context: Context) {
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    ImmichLocalMedia.scanLocal(context, localWindowDays)
                }
                _rawLocalAssets.value = raw
                recomputeTimeline()
            } catch (e: Exception) {
                DiagLog.w(NTAG, "本地资产扫描失败：${e.message}")
            }
        }
    }

    /**
     * 手动上传单个本机资产到 Immich 服务端（查看器「上传」动作）。
     * 上传成功后从本地未上传集合移除并刷新时间线。
     *
     * :param context: 任意 Context
     * :param asset: 本地资产（isLocal = true）
     */
    fun uploadLocalAsset(context: Context, asset: ImmichAsset) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ImmichLocalMedia.uploadLocal(context, asset) }
                _rawLocalAssets.value = _rawLocalAssets.value.filterNot { it.id == asset.id }
                recomputeTimeline()
                loadTimelinePage(context)
            } catch (e: Exception) {
                DiagLog.e(NTAG, "手动上传失败（${asset.fileName}）：${e.message}", e)
                _error.value = "上传失败：${e.message}"
            }
        }
    }

    /**
     * 拉取相册列表 + 人物列表 + 资产统计（三路独立容错：一路失败不拖垮其余，
     * 修复 2026-11-16 反馈「资料页相册一直加载中、统计 0」——原实现顺序执行一损俱损）。
     *
     * :param context: 任意 Context
     */
    fun loadMeta(context: Context) {
        if (!ImmichPrefs.isConfigured(context)) return
        viewModelScope.launch {
            _albumsLoading.value = true
            try {
                val api = ImmichApiClient(context.applicationContext)
                try {
                    val albums = withContext(Dispatchers.IO) { api.fetchAlbums() }
                    _albums.value = albums
                } catch (e: Exception) {
                    DiagLog.e(NTAG, "相册列表加载失败：${e.message}", e)
                }
                try {
                    val people = withContext(Dispatchers.IO) { api.fetchPeople() }
                    _people.value = people
                } catch (e: Exception) {
                    DiagLog.e(NTAG, "人物列表加载失败：${e.message}", e)
                }
                try {
                    val stats = withContext(Dispatchers.IO) { api.fetchStatistics() }
                    _statistics.value = stats
                } catch (e: Exception) {
                    DiagLog.e(NTAG, "资产统计加载失败：${e.message}", e)
                }
            } finally {
                _albumsLoading.value = false
            }
        }
    }

    /**
     * 打开分类结果网格（搜索/资料/人物页共用）。
     *
     * :param context: 任意 Context
     * :param query: 检索条件（title/人物/收藏/视频/文件名等）
     */
    fun openResults(context: Context, query: ImmichQuery) {
        resultsQuery = query
        resultsPage = 0
        _resultsTitle.value = query.title
        _resultsAssets.value = emptyList()
        _resultsExhausted.value = false
        fetchResultsPage(context)
    }

    /**
     * 拉取结果网格下一页。
     *
     * :param context: 任意 Context
     */
    private fun fetchResultsPage(context: Context) {
        viewModelScope.launch {
            val isFirst = resultsPage == 0
            if (isFirst) _resultsLoading.value = true else _resultsLoadingMore.value = true
            try {
                val api = ImmichApiClient(context.applicationContext)
                val page = withContext(Dispatchers.IO) {
                    api.searchAssets(
                        page = resultsPage + 1,
                        size = pageSize,
                        type = resultsQuery.type,
                        personIds = if (resultsQuery.personId.isNotBlank()) listOf(resultsQuery.personId) else emptyList(),
                        albumId = resultsQuery.albumId,
                        isFavorite = resultsQuery.favorite,
                        isArchived = resultsQuery.archived,
                        isTrashed = resultsQuery.trashed,
                        filename = resultsQuery.filename,
                    )
                }
                resultsPage += 1
                _resultsAssets.value = _resultsAssets.value + page.items
                _resultsExhausted.value = !page.hasNext
            } catch (e: Exception) {
                _error.value = "结果加载失败：${e.message}"
                DiagLog.e(NTAG, "结果网格加载失败：${_error.value}", e)
            } finally {
                _resultsLoading.value = false
                _resultsLoadingMore.value = false
            }
        }
    }

    /**
     * 结果网格无限滚动加载下一页。
     *
     * :param context: 任意 Context
     */
    fun loadMoreResults(context: Context) {
        if (_resultsLoading.value || _resultsLoadingMore.value || _resultsExhausted.value) return
        fetchResultsPage(context)
    }

    /**
     * 切换收藏并立即更新本地覆盖表（网格/查看器即时反映，无需重拉）。
     *
     * :param context: 任意 Context
     * :param assetId: 资产 ID
     * :param favorite: 目标收藏态
     */
    fun toggleFavorite(context: Context, assetId: String, favorite: Boolean) {
        _favoriteOverrides.value = _favoriteOverrides.value + (assetId to favorite)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ImmichApiClient(context.applicationContext).setFavorite(assetId, favorite) }
                // 时间线与结果里的同 id 资产同步覆盖
                _timelineAssets.value = _timelineAssets.value.map {
                    if (it.id == assetId) it.copy(isFavorite = favorite) else it
                }
                _resultsAssets.value = _resultsAssets.value.map {
                    if (it.id == assetId) it.copy(isFavorite = favorite) else it
                }
            } catch (e: Exception) {
                DiagLog.e(NTAG, "收藏切换失败 $assetId：${e.message}", e)
            }
        }
    }

    /**
     * 删除资产（入回收站）并从本地列表移除。
     *
     * :param context: 任意 Context
     * :param assetId: 资产 ID
     */
    fun trashAsset(context: Context, assetId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ImmichApiClient(context.applicationContext).trashAssets(listOf(assetId)) }
                _timelineAssets.value = _timelineAssets.value.filterNot { it.id == assetId }
                recomputeTimeline()
                _resultsAssets.value = _resultsAssets.value.filterNot { it.id == assetId }
            } catch (e: Exception) {
                DiagLog.e(NTAG, "删除失败 $assetId：${e.message}", e)
            }
        }
    }

    /**
     * 相册详情资产（点击相册卡片时拉取，一次性全量）。
     *
     * :param context: 任意 Context
     * :param albumId: 相册 ID
     * :param onLoaded: 加载完成回调（信息 + 分组结果）
     */
    fun loadAlbumDetail(context: Context, albumId: String, onLoaded: (ImmichAlbumInfo, List<ImmichTimeline.ImmichGroup>) -> Unit) {
        viewModelScope.launch {
            try {
                val api = ImmichApiClient(context.applicationContext)
                val (info, assets) = withContext(Dispatchers.IO) { api.fetchAlbumDetail(albumId) }
                onLoaded(info, ImmichTimeline.group(assets))
            } catch (e: Exception) {
                _error.value = "相册详情加载失败：${e.message}"
                DiagLog.e(NTAG, "相册详情加载失败：${_error.value}", e)
            }
        }
    }

    /**
     * 资产详情（查看器 EXIF 面板打开时拉取，列表条目缺 people/exif）。
     *
     * :param context: 任意 Context
     * :param assetId: 资产 ID
     * :param onLoaded: 加载完成回调
     */
    fun loadAssetDetail(context: Context, assetId: String, onLoaded: (ImmichAsset) -> Unit) {
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { ImmichApiClient(context.applicationContext).fetchAsset(assetId) }
                onLoaded(detail)
            } catch (e: Exception) {
                DiagLog.w(NTAG, "资产详情加载失败 $assetId：${e.message}")
            }
        }
    }

    /**
     * 清除错误提示。
     */
    fun clearError() {
        _error.value = null
    }
}
