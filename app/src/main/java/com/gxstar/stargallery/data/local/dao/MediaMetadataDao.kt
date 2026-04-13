package com.gxstar.stargallery.data.local.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.gxstar.stargallery.data.local.entity.MediaMetadata

/**
 * 媒体元数据数据访问对象
 */
@Dao
interface MediaMetadataDao {
    
    // ===== 查询操作 =====
    
    /**
     * 按拍摄时间降序分页查询（用于首页列表）
     */
    @Query("""
        SELECT * FROM media_metadata 
        WHERE is_video = 0 OR is_video = 1
        ORDER BY date_taken DESC
    """)
    fun getPagedByDateTaken(): PagingSource<Int, MediaMetadata>
    
    /**
     * 按添加时间降序分页查询
     */
    @Query("""
        SELECT * FROM media_metadata 
        ORDER BY date_added DESC
    """)
    fun getPagedByDateAdded(): PagingSource<Int, MediaMetadata>
    
    /**
     * 按拍摄时间降序分页查询（仅收藏）
     */
    @Query("""
        SELECT * FROM media_metadata 
        WHERE is_favorite = 1
        ORDER BY date_taken DESC
    """)
    fun getPagedFavoritesByDateTaken(): PagingSource<Int, MediaMetadata>
    
    /**
     * 按相册分页查询
     */
    @Query("""
        SELECT * FROM media_metadata 
        WHERE bucket_id = :bucketId
        ORDER BY date_taken DESC
    """)
    fun getPagedByBucket(bucketId: Long): PagingSource<Int, MediaMetadata>
    
    /**
     * 根据 ID 获取单个元数据
     */
    @Query("SELECT * FROM media_metadata WHERE id = :id")
    suspend fun getById(id: Long): MediaMetadata?
    
    /**
     * 根据 ID 列表批量获取
     */
    @Query("SELECT * FROM media_metadata WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaMetadata>
    
    /**
     * 获取所有元数据（用于全量扫描对比）
     */
    @Query("SELECT id, date_modified FROM media_metadata")
    suspend fun getAllIdsAndModifiedTimes(): List<MediaIdAndModifiedTime>
    
    /**
     * 获取元数据总数
     */
    @Query("SELECT COUNT(*) FROM media_metadata")
    suspend fun getCount(): Int
    
    /**
     * 获取收藏数量
     */
    @Query("SELECT COUNT(*) FROM media_metadata WHERE is_favorite = 1")
    suspend fun getFavoriteCount(): Int
    
    /**
     * 获取相册数量
     */
    @Query("SELECT COUNT(DISTINCT bucket_id) FROM media_metadata")
    suspend fun getAlbumCount(): Int
    
    /**
     * 获取所有相册信息
     */
    @Query("""
        SELECT bucket_id as bucketId, bucket_name as bucketName, COUNT(*) as photoCount
        FROM media_metadata 
        GROUP BY bucket_id
        ORDER BY photoCount DESC
    """)
    suspend fun getAlbums(): List<AlbumInfo>
    
    /**
     * 获取相册封面（最新照片的 ID）
     */
    @Query("""
        SELECT id FROM media_metadata 
        WHERE bucket_id = :bucketId 
        ORDER BY date_taken DESC 
        LIMIT 1
    """)
    suspend fun getAlbumCoverId(bucketId: Long): Long?
    
    /**
     * 获取最新拍摄时间
     */
    @Query("SELECT MAX(date_taken) FROM media_metadata")
    suspend fun getLatestDateTaken(): Long?
    
    /**
     * 检查是否存在指定 ID
     */
    @Query("SELECT EXISTS(SELECT 1 FROM media_metadata WHERE id = :id)")
    suspend fun exists(id: Long): Boolean
    
    /**
     * 检查数据库是否为空
     */
    @Query("SELECT COUNT(*) = 0 FROM media_metadata")
    suspend fun isEmpty(): Boolean
    
    // ===== 插入/更新操作 =====
    
    /**
     * 插入或替换单个元数据
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: MediaMetadata)
    
    /**
     * 批量插入或替换
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metadata: List<MediaMetadata>)
    
    /**
     * 更新单个元数据
     */
    @Update
    suspend fun update(metadata: MediaMetadata)
    
    /**
     * 更新收藏状态
     */
    @Query("UPDATE media_metadata SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)
    
    /**
     * 批量更新收藏状态
     */
    @Transaction
    suspend fun updateFavoriteBatch(ids: List<Long>, isFavorite: Boolean) {
        ids.forEach { updateFavorite(it, isFavorite) }
    }
    
    // ===== 删除操作 =====
    
    /**
     * 删除单个元数据
     */
    @Query("DELETE FROM media_metadata WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    /**
     * 批量删除
     */
    @Query("DELETE FROM media_metadata WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
    
    /**
     * 清空所有数据
     */
    @Query("DELETE FROM media_metadata")
    suspend fun deleteAll()
    
    /**
     * 删除不存在的 ID（已被系统删除的媒体）
     */
    @Query("DELETE FROM media_metadata WHERE id NOT IN (:existingIds)")
    suspend fun deleteNotIn(existingIds: List<Long>)
    
    // ===== 搜索操作 =====
    
    /**
     * 按相机型号搜索
     */
    @Query("""
        SELECT * FROM media_metadata 
        WHERE camera_model LIKE '%' || :keyword || '%'
        ORDER BY date_taken DESC
    """)
    fun searchByCameraModel(keyword: String): PagingSource<Int, MediaMetadata>
    
    /**
     * 按日期范围筛选
     */
    @Query("""
        SELECT * FROM media_metadata 
        WHERE date_taken >= :startTime AND date_taken <= :endTime
        ORDER BY date_taken DESC
    """)
    fun getByDateRange(startTime: Long, endTime: Long): PagingSource<Int, MediaMetadata>
    
    /**
     * 按拍摄日期（年-月-日）筛选
     */
    @Query("""
        SELECT * FROM media_metadata 
        WHERE date_taken >= :dayStart AND date_taken < :dayEnd
        ORDER BY date_taken DESC
    """)
    suspend fun getByDay(dayStart: Long, dayEnd: Long): List<MediaMetadata>
    
    /**
     * 获取所有拍摄日期（用于日历视图）
     */
    @Query("SELECT DISTINCT date_taken / 86400000 as day FROM media_metadata ORDER BY day DESC")
    suspend fun getAllShootDays(): List<Long>
}

/**
 * 用于全量扫描对比的简化数据
 */
data class MediaIdAndModifiedTime(
    val id: Long,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long
)

/**
 * 相册信息
 */
data class AlbumInfo(
    val bucketId: Long,
    val bucketName: String,
    val photoCount: Int
)
