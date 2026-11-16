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
 * Immich 相册功能视图模型（接 API 阶段，2026-11-16）。
 *
 * 承载跨 Tab 共享状态：时间线（分页加载 + 三级分组）、相册列表、人物列表、资产统计、
 * 分类结果网格（收藏/最近/视频/回收站/归档/人物/文件名搜索共用）。
 * 网络调用统一切 IO 线程，UI 经 StateFlow 订阅（同 MonitorViewModel 范式）。
 *
 * 未配置账号时进入「待配置」态（UI 显示配置引导，真实数据为空）。
 */
class ImmichViewModel : ViewModel() {

    /** 分页大小（search/metadata 单页；1000 太重 500 折中）。 */
    private val pageSize = 500

    // ============ 时间线（照片 Tab） ============

    /** 时间线全部资产（降序累计；分组由 UI 用 ImmichTimeline.group 现算）。 */
    private val _timelineAssets = MutableStateFlow<List<ImmichAsset>>(emptyList())
    val timelineAssets: StateFlow<List<ImmichAsset>> = _timelineAssets

    /** 时间线三级分组结果（缓存避免每次重组重算）。 */
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
     * 首次进入拉全量基础数据（时间线首页 + 相册 + 人物 + 统计）。
     * 时间线分组按 today 现算（跨天进入自动重算）。
     *
     * :param context: 任意 Context（ApiClient 用 applicationContext）
     */
    fun loadAll(context: Context) {
        configured.value = ImmichPrefs.isConfigured(context)
        if (!configured.value) {
            DiagLog.i(NTAG, "未配置服务端账号，跳过加载（引导用户去设置页）")
            return
        }
        if (timelinePage == 0) loadTimelinePage(context)
        if (_albums.value.isEmpty()) loadMeta(context)
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
                val page = withContext(Dispatchers.IO) { api.searchAssets(timelinePage + 1, pageSize) }
                timelinePage += 1
                _timelineAssets.value = _timelineAssets.value + page.items
                _timelineGroups.value = ImmichTimeline.group(_timelineAssets.value)
                _timelineExhausted.value = !page.hasNext
                DiagLog.i(NTAG, "时间线累计 ${_timelineAssets.value.size} 条（p$timelinePage exhausted=${page.hasNext.not()}）")
            } catch (e: Exception) {
                _error.value = e.message ?: "加载时间线失败"
                DiagLog.e(NTAG, "时间线加载失败：${_error.value}", e)
            } finally {
                _timelineLoading.value = false
                _timelineLoadingMore.value = false
            }
        }
    }

    /**
     * 加载时间线首页（重置分页游标）。
     *
     * :param context: 任意 Context
     */
    fun loadTimelinePage(context: Context) {
        timelinePage = 0
        _timelineAssets.value = emptyList()
        _timelineExhausted.value = false
        fetchTimelinePage(context)
    }

    /**
     * 无限滚动加载时间线下一页（网格接近底部时调用）。
     *
     * :param context: 任意 Context
     */
    fun loadMoreTimeline(context: Context) {
        if (_timelineLoading.value || _timelineLoadingMore.value || _timelineExhausted.value) return
        fetchTimelinePage(context)
    }

    /**
     * 拉取相册列表 + 人物列表 + 资产统计（进相册/搜索/资料 Tab 时调用，空才拉）。
     *
     * :param context: 任意 Context
     */
    fun loadMeta(context: Context) {
        if (!ImmichPrefs.isConfigured(context)) return
        viewModelScope.launch {
            _albumsLoading.value = true
            try {
                val api = ImmichApiClient(context.applicationContext)
                val albums = withContext(Dispatchers.IO) { api.fetchAlbums() }
                _albums.value = albums
                val people = withContext(Dispatchers.IO) { api.fetchPeople() }
                _people.value = people
                val stats = withContext(Dispatchers.IO) { api.fetchStatistics() }
                _statistics.value = stats
            } catch (e: Exception) {
                _error.value = e.message ?: "加载相册数据失败"
                DiagLog.e(NTAG, "相册元数据加载失败：${_error.value}", e)
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
                _error.value = e.message ?: "加载结果失败"
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
                _timelineGroups.value = ImmichTimeline.group(_timelineAssets.value)
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
                _error.value = e.message ?: "加载相册详情失败"
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
