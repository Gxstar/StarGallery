package com.gxstar.stargallery.data.local.db

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

    @Update
    suspend fun updateAll(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE id = :photoId")
    suspend fun deleteById(photoId: Long)

    @Query("DELETE FROM photos WHERE id IN (:photoIds)")
    suspend fun deleteByIds(photoIds: List<Long>)

    @Query("DELETE FROM photos")
    suspend fun deleteAll()

    // ==================== 单条查询 ====================

    @Query("SELECT * FROM photos WHERE id = :photoId")
    suspend fun getPhotoById(photoId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :photoId")
    fun getPhotoByIdFlow(photoId: Long): Flow<PhotoEntity?>

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

    // ==================== 全量 Flow 查询 ====================

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun getAllPhotosFlow(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotos(): List<PhotoEntity>

    // ==================== 手动分页查询 ====================

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC LIMIT :limit OFFSET :offset")
    suspend fun getPhotosByDateTakenPaged(offset: Int, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos ORDER BY dateAdded DESC LIMIT :limit OFFSET :offset")
    suspend fun getPhotosByDateAddedPaged(offset: Int, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE isFavorite = 1 ORDER BY dateTaken DESC LIMIT :limit OFFSET :offset")
    suspend fun getFavoritePhotosByDateTakenPaged(offset: Int, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE isFavorite = 1 ORDER BY dateAdded DESC LIMIT :limit OFFSET :offset")
    suspend fun getFavoritePhotosByDateAddedPaged(offset: Int, limit: Int): List<PhotoEntity>

    // ==================== 相册/bucket 查询 ====================

    // ==================== 隐藏照片 ====================

    @Query("SELECT id FROM photos WHERE isHidden = 1")
    suspend fun getHiddenPhotoIds(): List<Long>

    @Query("SELECT COUNT(*) FROM photos WHERE isHidden = 1")
    fun getHiddenCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE isHidden = 1")
    suspend fun getHiddenCount(): Int

    @Query("UPDATE photos SET isHidden = :isHidden WHERE id = :photoId")
    suspend fun updateHidden(photoId: Long, isHidden: Boolean)

    @Query("UPDATE photos SET isHidden = :isHidden WHERE id IN (:photoIds)")
    suspend fun updateHiddenBatch(photoIds: List<Long>, isHidden: Boolean)

    // ==================== 批量操作 ====================

    @Query("UPDATE photos SET isFavorite = :isFavorite WHERE id = :photoId")
    suspend fun updateFavorite(photoId: Long, isFavorite: Boolean)

    @Query("UPDATE photos SET isFavorite = :isFavorite WHERE id IN (:photoIds)")
    suspend fun updateFavoriteBatch(photoIds: List<Long>, isFavorite: Boolean)

    // ==================== EXIF 筛选选项计数 ====================

    @Query("SELECT cameraMake AS value, COUNT(*) AS count FROM photos WHERE cameraMake IS NOT NULL AND cameraMake != '' AND isHidden = 0 GROUP BY cameraMake ORDER BY count DESC")
    fun getCameraMakeCountsFlow(): Flow<List<ExifCount>>

    @Query("SELECT cameraModel AS value, COUNT(*) AS count FROM photos WHERE cameraModel IS NOT NULL AND cameraModel != '' AND isHidden = 0 GROUP BY cameraModel ORDER BY count DESC")
    fun getCameraModelCountsFlow(): Flow<List<ExifCount>>

    @Query("SELECT lensModel AS value, COUNT(*) AS count FROM photos WHERE lensModel IS NOT NULL AND lensModel != '' AND isHidden = 0 GROUP BY lensModel ORDER BY count DESC")
    fun getLensModelCountsFlow(): Flow<List<ExifCount>>

    @Query("SELECT COUNT(*) FROM photos WHERE isHidden = 0")
    fun getVisiblePhotoCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE cameraMake IS NOT NULL OR cameraModel IS NOT NULL")
    fun getExifCompletedCountFlow(): Flow<Int>

    // ==================== 级联筛选关联查询 ====================

    @Query("SELECT DISTINCT cameraMake FROM photos WHERE lensModel IN (:lensModels) AND cameraMake IS NOT NULL AND cameraMake != ''")
    suspend fun getMakesForLenses(lensModels: List<String>): List<String>

    @Query("SELECT DISTINCT cameraModel FROM photos WHERE lensModel IN (:lensModels) AND cameraModel IS NOT NULL AND cameraModel != ''")
    suspend fun getModelsForLenses(lensModels: List<String>): List<String>

    @Query("SELECT DISTINCT cameraMake FROM photos WHERE cameraModel IN (:cameraModels) AND cameraMake IS NOT NULL AND cameraMake != ''")
    suspend fun getMakesForModels(cameraModels: List<String>): List<String>

    data class ExifCount(val value: String, val count: Int)

    data class ExifSnapshot(
        val id: Long,
        val cameraMake: String?,
        val cameraModel: String?,
        val lensModel: String?,
        val isoEquivalent: Int?,
        val focalLength: Float?,
        val focalLength35mmEquiv: Int?,
        val fNumber: Float?,
        val shutterSpeed: Float?,
        val exifImageWidth: Int?,
        val exifImageHeight: Int?,
        val lut1: String?,
        val lut1opacity: Int?,
        val lut2: String?,
        val lut2opacity: Int?,
        val latitude: Double?,
        val longitude: Double?,
        val flash: Boolean?,
        val exposureCompensation: Float?,
        val meteringMode: String?,
        val photoStyle: String?
    )

    @Query("SELECT id, cameraMake, cameraModel, lensModel, isoEquivalent, focalLength, focalLength35mmEquiv, fNumber, shutterSpeed, exifImageWidth, exifImageHeight, lut1, lut1opacity, lut2, lut2opacity, latitude, longitude, flash, exposureCompensation, meteringMode, photoStyle FROM photos")
    suspend fun getExifSnapshots(): List<ExifSnapshot>

    // ==================== 缩略图 ====================

    @Query("UPDATE photos SET thumbnailPath = :path WHERE id = :photoId")
    suspend fun updateThumbnailPath(photoId: Long, path: String?)

    @Query("UPDATE photos SET thumbnailPath = NULL WHERE id IN (:photoIds)")
    suspend fun clearThumbnailPaths(photoIds: List<Long>)

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