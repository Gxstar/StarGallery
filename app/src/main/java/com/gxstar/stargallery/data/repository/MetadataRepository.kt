package com.gxstar.stargallery.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.gxstar.stargallery.data.local.dao.MediaMetadataDao
import com.gxstar.stargallery.data.local.entity.MediaMetadata
import com.gxstar.stargallery.data.local.scanner.MetadataScanner
import com.gxstar.stargallery.data.model.Album
import com.gxstar.stargallery.data.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 元数据仓库
 * 从本地数据库读取媒体元数据，并提供分页数据
 */
@Singleton
class MetadataRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val dao: MediaMetadataDao,
    private val scanner: MetadataScanner
) {
    
    /**
     * 排序方式枚举
     */
    enum class SortType {
        DATE_TAKEN,      // 拍摄时间
        DATE_ADDED       // 创建时间
    }
    
    companion object {
        private const val PAGE_SIZE = 20
        private const val PREFETCH_DISTANCE = 10
    }
    
    /**
     * 获取分页照片数据流
     * 从数据库读取，支持排序
     */
    fun getPhotosPaging(sortType: SortType): Flow<PagingData<MediaMetadata>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE * 2,
                prefetchDistance = PREFETCH_DISTANCE
            ),
            pagingSourceFactory = {
                when (sortType) {
                    SortType.DATE_TAKEN -> dao.getPagedByDateTaken()
                    SortType.DATE_ADDED -> dao.getPagedByDateAdded()
                }
            }
        ).flow
    }
    
    /**
     * 获取收藏照片分页数据流
     */
    fun getFavoritesPaging(sortType: SortType): Flow<PagingData<MediaMetadata>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE * 2,
                prefetchDistance = PREFETCH_DISTANCE
            ),
            pagingSourceFactory = {
                when (sortType) {
                    SortType.DATE_TAKEN -> dao.getPagedFavoritesByDateTaken()
                    SortType.DATE_ADDED -> dao.getPagedByDateAdded() // TODO: 添加按添加时间排序的收藏查询
                }
            }
        ).flow
    }
    
    /**
     * 获取相册照片分页数据流
     */
    fun getPhotosByBucketPaging(bucketId: Long): Flow<PagingData<MediaMetadata>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE * 2,
                prefetchDistance = PREFETCH_DISTANCE
            ),
            pagingSourceFactory = {
                dao.getPagedByBucket(bucketId)
            }
        ).flow
    }
    
    /**
     * 根据 ID 获取元数据
     */
    suspend fun getMetadataById(id: Long): MediaMetadata? {
        return dao.getById(id)
    }
    
    /**
     * 批量获取元数据
     */
    suspend fun getMetadataByIds(ids: List<Long>): List<MediaMetadata> {
        return dao.getByIds(ids)
    }
    
    /**
     * 获取照片总数
     */
    suspend fun getPhotoCount(): Int {
        return dao.getCount()
    }
    
    /**
     * 获取收藏数量
     */
    suspend fun getFavoriteCount(): Int {
        return dao.getFavoriteCount()
    }
    
    /**
     * 获取相册数量
     */
    suspend fun getAlbumCount(): Int {
        return dao.getAlbumCount()
    }
    
    /**
     * 获取所有相册
     */
    suspend fun getAlbums(): List<Album> {
        val albumInfos = dao.getAlbums()
        return albumInfos.map { info ->
            val coverId = dao.getAlbumCoverId(info.bucketId) ?: 0
            val coverUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                coverId
            )
            Album(info.bucketId, info.bucketName, coverUri, info.photoCount)
        }
    }
    
    /**
     * 检查是否需要扫描
     */
    suspend fun needsScan(): Boolean {
        return scanner.needsScan()
    }
    
    /**
     * 获取扫描状态流
     */
    fun getScanState() = scanner.scanState
    
    /**
     * 执行全量扫描
     */
    suspend fun performFullScan() {
        scanner.performFullScan()
    }
    
    /**
     * 执行增量扫描
     */
    suspend fun performIncrementalScan() {
        scanner.performIncrementalScan()
    }
    
    /**
     * 更新收藏状态
     */
    suspend fun updateFavorite(id: Long, isFavorite: Boolean) {
        dao.updateFavorite(id, isFavorite)
    }
    
    /**
     * 批量更新收藏状态
     */
    suspend fun updateFavoriteBatch(ids: List<Long>, isFavorite: Boolean) {
        dao.updateFavoriteBatch(ids, isFavorite)
    }
    
    /**
     * 删除元数据
     */
    suspend fun deleteMetadata(id: Long) {
        dao.deleteById(id)
    }
    
    /**
     * 批量删除元数据
     */
    suspend fun deleteMetadataBatch(ids: List<Long>) {
        dao.deleteByIds(ids)
    }
    
    /**
     * 更新单个媒体的元数据
     */
    suspend fun updateSingleMedia(id: Long) {
        scanner.updateSingleMedia(id)
    }
    
    /**
     * 数据库是否为空
     */
    suspend fun isEmpty(): Boolean {
        return dao.isEmpty()
    }
    
    /**
     * 将 MediaMetadata 转换为 Photo 模型
     */
    fun toPhoto(metadata: MediaMetadata): Photo {
        return Photo(
            id = metadata.id,
            uri = metadata.getUri(),
            dateTaken = metadata.dateTaken,
            dateModified = metadata.dateModified,
            dateAdded = metadata.dateAdded,
            mimeType = metadata.mimeType,
            width = metadata.width,
            height = metadata.height,
            size = metadata.fileSize,
            bucketId = metadata.bucketId,
            bucketName = metadata.bucketName,
            latitude = metadata.latitude,
            longitude = metadata.longitude,
            orientation = metadata.orientation,
            isFavorite = metadata.isFavorite
        )
    }
    
    /**
     * 获取最新拍摄时间
     */
    suspend fun getLatestDateTaken(): Long? {
        return dao.getLatestDateTaken()
    }
}
