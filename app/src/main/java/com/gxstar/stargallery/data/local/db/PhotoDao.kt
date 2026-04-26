package com.gxstar.stargallery.data.local.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Photo 数据访问对象
 */
@Dao
interface PhotoDao {

    // ==================== 基础 CRUD ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PhotoEntity>)

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id = :photoId")
    suspend fun deleteById(photoId: Long)

    @Query("DELETE FROM photos WHERE id IN (:photoIds)")
    suspend fun deleteByIds(photoIds: List<Long>)

    @Query("DELETE FROM photos")
    suspend fun deleteAll()

    // ==================== 单条查询 ====================

    @Query("SELECT * FROM photos WHERE id = :photoId")
    suspend fun getPhotoById(photoId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id IN (:photoIds)")
    suspend fun getPhotosByIds(photoIds: List<Long>): List<PhotoEntity>

    // ==================== 统计 ====================

    @Query("SELECT COUNT(*) FROM photos")
    fun getPhotoCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE isFavorite = 1")
    fun getFavoriteCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun getPhotoCount(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE isFavorite = 1")
    suspend fun getFavoriteCount(): Int

    // ==================== Paging 3 分页查询 ====================

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun pagingPhotosByDateTaken(): PagingSource<Int, PhotoEntity>

    @Query("SELECT * FROM photos ORDER BY dateAdded DESC")
    fun pagingPhotosByDateAdded(): PagingSource<Int, PhotoEntity>

    @Query("SELECT * FROM photos WHERE isFavorite = 1 ORDER BY dateTaken DESC")
    fun pagingFavoritePhotosByDateTaken(): PagingSource<Int, PhotoEntity>

    @Query("SELECT * FROM photos WHERE isFavorite = 1 ORDER BY dateAdded DESC")
    fun pagingFavoritePhotosByDateAdded(): PagingSource<Int, PhotoEntity>

    // ==================== 手动分页查询（RoomPagingSource 使用） ====================

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC LIMIT :limit OFFSET :offset")
    suspend fun getPhotosByDateTakenPaged(offset: Int, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos ORDER BY dateAdded DESC LIMIT :limit OFFSET :offset")
    suspend fun getPhotosByDateAddedPaged(offset: Int, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE isFavorite = 1 ORDER BY dateTaken DESC LIMIT :limit OFFSET :offset")
    suspend fun getFavoritePhotosByDateTakenPaged(offset: Int, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE isFavorite = 1 ORDER BY dateAdded DESC LIMIT :limit OFFSET :offset")
    suspend fun getFavoritePhotosByDateAddedPaged(offset: Int, limit: Int): List<PhotoEntity>

    // ==================== 相册/bucket 查询 ====================

    @Query("SELECT * FROM photos WHERE bucketId = :bucketId ORDER BY dateTaken DESC")
    fun pagingPhotosByBucket(bucketId: Long): PagingSource<Int, PhotoEntity>

    @Query("SELECT * FROM photos WHERE bucketId = :bucketId ORDER BY dateAdded DESC")
    fun pagingPhotosByBucketDateAdded(bucketId: Long): PagingSource<Int, PhotoEntity>

    // ==================== 批量操作 ====================

    @Query("UPDATE photos SET isFavorite = :isFavorite WHERE id = :photoId")
    suspend fun updateFavorite(photoId: Long, isFavorite: Boolean)

    @Query("UPDATE photos SET isFavorite = :isFavorite WHERE id IN (:photoIds)")
    suspend fun updateFavoriteBatch(photoIds: List<Long>, isFavorite: Boolean)

    // ==================== 完整性检查 ====================

    @Query("SELECT id FROM photos")
    suspend fun getAllPhotoIds(): List<Long>

    /**
     * 检查指定 ID 列表中哪些在数据库中存在
     */
    @Query("SELECT id FROM photos WHERE id IN (:photoIds)")
    suspend fun getExistingIds(photoIds: List<Long>): List<Long>

    /**
     * 删除所有不在指定 ID 列表中的记录（用于同步后清理已删除的媒体）
     */
    @Query("DELETE FROM photos WHERE id NOT IN (:validIds)")
    suspend fun deleteRemovedPhotos(validIds: List<Long>)
}