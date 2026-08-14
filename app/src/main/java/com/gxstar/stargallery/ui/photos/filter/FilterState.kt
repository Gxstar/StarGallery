package com.gxstar.stargallery.ui.photos.filter

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.local.db.PhotoEntity

/**
 * 维度取值为空（null 或空串）时统一归一化到该 key，对应 UI 上的「未知」选项
 */
const val UNKNOWN_KEY = ""

/**
 * 筛选维度标识
 *
 * 新增维度时在此追加一个枚举值，并在 [FilterDimensions.ALL] 注册对应定义
 */
enum class FilterDimensionId {
    CAMERA_MAKE,
    CAMERA_MODEL,
    LENS_MODEL
}

/**
 * 筛选维度定义（注册表驱动的核心）
 *
 * 注册一个维度后，以下能力全部自动生效，无需改动其他文件：
 * 面板行渲染、选项列表、选项计数、生效条件 chip、过滤管道、层级联动。
 *
 * @param valueOf 从照片实体取出该维度取值；返回 null / 空串视为「未知」。
 *                连续量（光圈 / 快门 / ISO / 焦段）在此处直接分档成区间字符串即可，
 *                例如 `{ it.fNumber?.let(ApertureBucket::labelOf) }`，
 *                这样就不会出现 f/1.7、f/1.8、f/2.0 各自成为一个选项的情况。
 * @param parent  层级父维度。父维度选择变化时，子维度中已失效的选择会被自动裁剪，
 *                避免出现「品牌选了索尼、却残留一个松下镜头」导致结果空白。
 */
data class FilterDimension(
    val id: FilterDimensionId,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val parent: FilterDimensionId? = null,
    val valueOf: (PhotoEntity) -> String?
) {
    /** 取归一化后的 key，空值统一为 [UNKNOWN_KEY] */
    fun keyOf(entity: PhotoEntity): String =
        valueOf(entity)?.takeIf { it.isNotBlank() } ?: UNKNOWN_KEY
}

/**
 * 维度注册表
 *
 * 这是新增筛选维度的唯一改动点
 */
object FilterDimensions {

    val ALL: List<FilterDimension> = listOf(
        FilterDimension(
            id = FilterDimensionId.CAMERA_MAKE,
            titleRes = R.string.filter_camera_make,
            iconRes = R.drawable.ic_camera,
            valueOf = { it.cameraMake }
        ),
        FilterDimension(
            id = FilterDimensionId.CAMERA_MODEL,
            titleRes = R.string.filter_camera_model,
            iconRes = R.drawable.ic_camera,
            parent = FilterDimensionId.CAMERA_MAKE,
            valueOf = { it.cameraModel }
        ),
        FilterDimension(
            id = FilterDimensionId.LENS_MODEL,
            titleRes = R.string.filter_lens,
            iconRes = R.drawable.ic_lens,
            parent = FilterDimensionId.CAMERA_MODEL,
            valueOf = { it.lensModel }
        )
    )

    private val byId: Map<FilterDimensionId, FilterDimension> = ALL.associateBy { it.id }

    fun of(id: FilterDimensionId): FilterDimension = byId.getValue(id)

    /**
     * 返回以 [id] 为祖先的全部下游维度，按注册顺序
     * 用于父维度变化后裁剪子维度的失效选择
     */
    fun descendantsOf(id: FilterDimensionId): List<FilterDimension> {
        val result = mutableListOf<FilterDimension>()
        var frontier = setOf(id)
        while (true) {
            val next = ALL.filter { dimension ->
                dimension.parent?.let { it in frontier } == true
            }
            if (next.isEmpty()) break
            result += next
            frontier = next.map { it.id }.toSet()
        }
        return result
    }
}

/**
 * 全部筛选条件的单一数据源
 *
 * 不可变：所有变更返回新实例，StateFlow 可直接做等值去重。
 * 取代了原先每个维度两个 StateFlow（显式选择 + 级联写回结果）的写法，
 * 也因此不再存在「显式与隐式选择混杂、chip 视觉状态与实际过滤脱钩」的问题。
 */
data class FilterState(
    val favoritesOnly: Boolean = false,
    val searchQuery: String? = null,
    val selections: Map<FilterDimensionId, Set<String>> = emptyMap()
) {

    fun selectionOf(id: FilterDimensionId): Set<String> = selections[id] ?: emptySet()

    val hasDimensionSelection: Boolean
        get() = selections.values.any { it.isNotEmpty() }

    /** 是否没有任何生效条件（搜索不计入，搜索有独立的工具栏形态） */
    val hasNoCondition: Boolean
        get() = !favoritesOnly && !hasDimensionSelection

    fun withFavoritesOnly(enabled: Boolean): FilterState = copy(favoritesOnly = enabled)

    fun withSearchQuery(query: String?): FilterState =
        copy(searchQuery = query?.takeIf { it.isNotBlank() })

    fun toggle(id: FilterDimensionId, key: String): FilterState {
        val current = selectionOf(id)
        return withSelection(id, if (key in current) current - key else current + key)
    }

    fun withSelection(id: FilterDimensionId, keys: Set<String>): FilterState {
        val next = selections.toMutableMap()
        if (keys.isEmpty()) next.remove(id) else next[id] = keys
        return copy(selections = next)
    }

    fun clearDimension(id: FilterDimensionId): FilterState = withSelection(id, emptySet())

    fun clearDimensions(): FilterState = copy(selections = emptyMap())

    /**
     * 是否命中当前全部维度条件
     * 维度之间 AND，维度内部 OR
     */
    fun matches(entity: PhotoEntity): Boolean {
        if (favoritesOnly && !entity.isFavorite) return false
        for ((id, keys) in selections) {
            if (keys.isEmpty()) continue
            if (FilterDimensions.of(id).keyOf(entity) !in keys) return false
        }
        return true
    }

    /**
     * 仅应用除 [excluded] 以外的维度条件
     *
     * 用于 faceted 选项统计：统计某个维度的可选项时不能把它自己算进去，
     * 否则一旦选中某项，其他项的计数就会全部变成 0。
     */
    fun matchesExcept(entity: PhotoEntity, excluded: FilterDimensionId): Boolean {
        if (favoritesOnly && !entity.isFavorite) return false
        for ((id, keys) in selections) {
            if (id == excluded || keys.isEmpty()) continue
            if (FilterDimensions.of(id).keyOf(entity) !in keys) return false
        }
        return true
    }

    /** 文本搜索单独一层：匹配文件名与相册名，不参与选项统计 */
    fun matchesSearch(entity: PhotoEntity): Boolean {
        val query = searchQuery?.takeIf { it.isNotBlank() }?.lowercase() ?: return true
        return entity.displayName?.lowercase()?.contains(query) == true ||
            entity.bucketName.lowercase().contains(query)
    }

    /**
     * 裁剪掉在当前上游条件下已不可用的选择
     *
     * 按注册顺序逐维度检查：若某个选中 key 在「其他维度过滤后的照片集」中已不存在，
     * 则移除该 key。解决改了品牌之后子维度残留导致列表空白的问题。
     */
    fun pruneUnavailable(entities: List<PhotoEntity>): FilterState {
        if (!hasDimensionSelection) return this
        var state = this
        for (dimension in FilterDimensions.ALL) {
            val selected = state.selectionOf(dimension.id)
            if (selected.isEmpty()) continue
            val available = HashSet<String>()
            for (entity in entities) {
                if (state.matchesExcept(entity, dimension.id)) {
                    available.add(dimension.keyOf(entity))
                }
            }
            val retained = selected.intersect(available)
            if (retained.size != selected.size) {
                state = state.withSelection(dimension.id, retained)
            }
        }
        return state
    }
}

/**
 * 单个筛选选项（值 + 展示名 + 在当前上游条件下的命中张数）
 */
data class FilterOption(
    val key: String,
    val display: String,
    val count: Int
) {
    val isUnknown: Boolean get() = key == UNKNOWN_KEY
}
