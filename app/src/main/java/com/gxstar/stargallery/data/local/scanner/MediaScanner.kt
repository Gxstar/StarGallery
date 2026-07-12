package com.gxstar.stargallery.data.local.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.gxstar.stargallery.data.local.db.AppDatabase
import com.gxstar.stargallery.data.local.db.PhotoDao
import com.gxstar.stargallery.data.local.db.PhotoEntity
import com.gxstar.stargallery.data.local.ThumbnailManager
import com.gxstar.stargallery.data.local.exif.ExifExtractor
import com.gxstar.stargallery.data.local.preferences.ScanPreferences
import com.gxstar.stargallery.data.model.Photo
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体数据库扫描器
 * 负责将 MediaStore 中的媒体信息同步到 Room 数据库
 * 支持全量扫描和增量扫描
 * EXIF 信息在扫描完成后后台提取
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
    private val scanPreferences: ScanPreferences,
    private val exifExtractor: ExifExtractor,
    private val appDatabase: AppDatabase,
    private val thumbnailManager: ThumbnailManager
) {
    companion object {
        private const val TAG = "MediaScanner"
        private const val EXIF_BATCH_SIZE = 20
    }

    private val mutex = Mutex()

    // 扫描状态流
    private val _scanState = MutableSharedFlow<ScanState>(replay = 1)
    val scanState: SharedFlow<ScanState> = _scanState.asSharedFlow()

    sealed class ScanState {
        object Idle : ScanState()
        data class Scanning(val current: Int, val total: Int, val progress: Float) : ScanState()
        data class Completed(val totalScanned: Int, val durationMs: Long) : ScanState()
        data class Error(val message: String) : ScanState()
    }

    // 是否正在扫描
    @Volatile
    private var isScanning = false

    // EXIF 提取任务引用，用于取消/感知状态
    private var exifJob: Job? = null

    // EXIF 提取是否在进行中（独立状态流，避免 StateFlow conflate 导致 UI 感知延迟）
    private val _isExtractingExif = MutableStateFlow(false)
    val isExtractingExifFlow: StateFlow<Boolean> = _isExtractingExif.asStateFlow()

    // EXIF 提取进度 (0f ~ 1f)
    private val _exifProgress = MutableStateFlow(0f)
    val exifProgress: StateFlow<Float> = _exifProgress.asStateFlow()

    // 缩略图生成任务引用
    private var thumbnailJob: Job? = null

    // 缩略图生成是否在进行中
    private val _isGeneratingThumbnails = MutableStateFlow(false)
    val isGeneratingThumbnailsFlow: StateFlow<Boolean> = _isGeneratingThumbnails.asStateFlow()

    // 缩略图生成进度 (0f ~ 1f)
    private val _thumbnailProgress = MutableStateFlow(0f)
    val thumbnailProgress: StateFlow<Float> = _thumbnailProgress.asStateFlow()

    /**
     * 执行全量扫描
     * 扫描所有图片和视频，写入 Room 数据库
     */
    suspend fun performFullScan() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isScanning) {
                Log.w(TAG, "Scan already in progress")
                return@withContext
            }
            isScanning = true
        }

        val startTime = System.currentTimeMillis()

        try {
            _scanState.emit(ScanState.Scanning(0, 0, 0f))

            // 1. 查询所有媒体
            val allMedia = queryAllMediaFromMediaStore()
            val total = allMedia.size
            Log.i(TAG, "Found $total media files to scan")

            if (total == 0) {
                _scanState.emit(ScanState.Completed(0, 0))
                return@withContext
            }

            // 2. 批量写入 Room
            var processedCount = 0
            val batchSize = 100
            val batches = allMedia.chunked(batchSize)
            val hiddenIds = photoDao.getHiddenPhotoIds().toSet()
            val existingExif = photoDao.getExifSnapshots().associateBy { it.id }

            appDatabase.withTransaction {
                for (batch in batches) {
                    val entities = batch.map { item ->
                        val exif = existingExif[item.id]
                        PhotoEntity(
                            id = item.id,
                            uri = item.uri,
                            displayName = item.displayName,
                            dateTaken = item.dateTaken,
                            dateModified = item.dateModified,
                            dateAdded = item.dateAdded,
                            mimeType = item.mimeType,
                            width = item.width,
                            height = item.height,
                            size = item.size,
                            bucketId = item.bucketId,
                            bucketName = item.bucketName,
                            latitude = exif?.latitude,
                            longitude = exif?.longitude,
                            orientation = item.orientation,
                            isFavorite = item.isFavorite,
                            isHidden = item.id in hiddenIds,
                            cameraMake = exif?.cameraMake,
                            cameraModel = exif?.cameraModel,
                            lensModel = exif?.lensModel,
                            isoEquivalent = exif?.isoEquivalent,
                            focalLength = exif?.focalLength,
                            focalLength35mmEquiv = exif?.focalLength35mmEquiv,
                            fNumber = exif?.fNumber,
                            shutterSpeed = exif?.shutterSpeed,
                            exifImageWidth = exif?.exifImageWidth,
                            exifImageHeight = exif?.exifImageHeight,
                            lut1 = exif?.lut1,
                            lut2 = exif?.lut2,
                            flash = exif?.flash,
                            exposureCompensation = exif?.exposureCompensation,
                            meteringMode = exif?.meteringMode,
                            photoStyle = exif?.photoStyle,
                            isHdr = exif?.isHdr ?: false
                        )
                    }
                    photoDao.insertAll(entities)

                    processedCount += batch.size
                    val progress = processedCount.toFloat() / total
                    _scanState.emit(ScanState.Scanning(processedCount, total, progress))
                }

                // 全量扫描完成后，删除已不存在于 MediaStore 的记录
                // 先计算需要删除的 ID，再分批执行，避免 SQLite 参数上限（~999）
                val validIds = allMedia.map { it.id }.toSet()
                val roomIds = photoDao.getAllPhotoIds()
                val idsToRemove = roomIds - validIds
                if (idsToRemove.isNotEmpty()) {
                    idsToRemove.chunked(500).forEach { batch ->
                        thumbnailManager.deleteThumbnails(batch.toSet())
                        photoDao.deleteByIds(batch)
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Full scan completed: $total media in ${duration}ms")

            _scanState.emit(ScanState.Completed(total, duration))
            scanPreferences.lastScanTime = System.currentTimeMillis() / 1000
            scanPreferences.isScanCompleted = true
            scanPreferences.isExifExtractionCompleted = false

            // 全量扫描完成后，后台提取 EXIF 信息
            extractExifForAllPhotos()
        } catch (e: Exception) {
            Log.e(TAG, "Full scan failed", e)
            _scanState.emit(ScanState.Error(e.message ?: "Unknown error"))
        } finally {
            isScanning = false
        }
    }

    /**
     * 后台批量提取 EXIF 信息（优化版：批量查询+并行处理）
     * 在全量扫描完成后异步执行
     * 使用托管 Job 避免多次全量扫描并发，且不被 ViewModel 生命周期打断
     */
    private fun extractExifForAllPhotos() {
        exifJob?.cancel()
        _exifProgress.value = 0f
        _isExtractingExif.value = true
        exifJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val allPhotoIds = photoDao.getAllPhotoIds()
                if (allPhotoIds.isEmpty()) {
                    _exifProgress.value = 1f
                    _isExtractingExif.value = false
                    scanPreferences.isExifExtractionCompleted = true
                    generateThumbnailsForAllPhotos()
                    return@launch
                }

                Log.i(TAG, "Starting EXIF extraction for ${allPhotoIds.size} photos")

                // 批量预加载照片，过滤已有完整EXIF的照片（修复N+1查询）
                val photosNeedingExif = mutableListOf<PhotoEntity>()
                allPhotoIds.chunked(100).forEach { batchIds ->
                    val photos = photoDao.getPhotosByIds(batchIds)
                    photos.forEach { photo ->
                        val hasCameraData = photo.cameraMake != null || photo.cameraModel != null
                        val hasGpsData = photo.latitude != null || photo.longitude != null
                        if (!hasCameraData || !hasGpsData) {
                            photosNeedingExif.add(photo)
                        }
                    }
                }

                val totalToProcess = photosNeedingExif.size
                if (totalToProcess == 0) {
                    _exifProgress.value = 1f
                    _isExtractingExif.value = false
                    scanPreferences.isExifExtractionCompleted = true
                    generateThumbnailsForAllPhotos()
                    return@launch
                }

                Log.i(TAG, "Actually need EXIF extraction for $totalToProcess photos")

                // 并行处理，限制并发数为CPU核心数（最多4个）
                val processed = AtomicInteger(0)
                val concurrency = minOf(Runtime.getRuntime().availableProcessors(), 4)

                coroutineScope {
                    photosNeedingExif.chunked(EXIF_BATCH_SIZE)
                        .chunked(concurrency)
                        .forEach { batchGroup ->
                            val results = batchGroup.map { batch ->
                                async {
                                    processExifBatch(batch)
                                }
                            }.awaitAll()
                            val batchProcessed = results.sum()
                            val current = processed.addAndGet(batchProcessed)
                            _exifProgress.value = (current.toFloat() / totalToProcess).coerceIn(0f, 1f)
                        }
                }

                _exifProgress.value = 1f
                Log.i(TAG, "EXIF extraction completed")
            } catch (e: Exception) {
                Log.e(TAG, "EXIF extraction failed", e)
            } finally {
                _isExtractingExif.value = false
                scanPreferences.isExifExtractionCompleted = true
                generateThumbnailsForAllPhotos()
            }
        }
    }

    /**
     * 处理一批照片的EXIF提取
     * @return 本批处理的照片数量（含跳过的）
     */
    private suspend fun processExifBatch(batch: List<PhotoEntity>): Int {
        val batchUpdates = mutableListOf<PhotoEntity>()
        var processed = 0

        batch.forEach { photo ->
            val uri = try {
                android.net.Uri.parse(photo.uri)
            } catch (e: Exception) {
                processed++
                return@forEach
            }

            val exifData = exifExtractor.extractExif(uri)
            if (exifData != null) {
                batchUpdates.add(ExifExtractor.applyToEntity(photo, exifData))
            }
            processed++
        }

        if (batchUpdates.isNotEmpty()) {
            photoDao.updateAll(batchUpdates)
        }

        return processed
    }

    /**
     * 恢复被中断的 EXIF 提取
     * 当 isScanCompleted = true 但 isExifExtractionCompleted = false 时由 ViewModel 调用
     */
    fun recoverExifExtraction() {
        if (_isExtractingExif.value) {
            Log.i(TAG, "EXIF extraction already in progress, skipping recovery")
            return
        }
        exifJob?.cancel()
        exifJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                extractExifForAllPhotos()
            } catch (e: Exception) {
                Log.e(TAG, "EXIF recovery failed", e)
            }
        }
    }

    /**
     * 执行增量扫描
     * 只扫描新增或修改的媒体
     * @return 是否有数据变化
     */
    suspend fun performIncrementalScan(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isScanning) {
                Log.w(TAG, "Scan already in progress")
                return@withContext false
            }
            isScanning = true
        }

        val startTime = System.currentTimeMillis()

        try {
            val lastScanTime = scanPreferences.lastScanTime
            val changedMedia = queryMediaModifiedAfter(lastScanTime)

            scanPreferences.lastScanTime = System.currentTimeMillis() / 1000

            if (changedMedia.isNotEmpty()) {
                val hiddenIds = photoDao.getHiddenPhotoIds().toSet()
                val existingExif = photoDao.getExifSnapshots().associateBy { it.id }

                val entities = changedMedia.map { item ->
                    val exif = existingExif[item.id]
                    PhotoEntity(
                        id = item.id,
                        uri = item.uri,
                        displayName = item.displayName,
                        dateTaken = item.dateTaken,
                        dateModified = item.dateModified,
                        dateAdded = item.dateAdded,
                        mimeType = item.mimeType,
                        width = item.width,
                        height = item.height,
                        size = item.size,
                        bucketId = item.bucketId,
                        bucketName = item.bucketName,
                        latitude = exif?.latitude,
                        longitude = exif?.longitude,
                        orientation = item.orientation,
                        isFavorite = item.isFavorite,
                        isHidden = item.id in hiddenIds,
                        cameraMake = exif?.cameraMake,
                        cameraModel = exif?.cameraModel,
                        lensModel = exif?.lensModel,
                        isoEquivalent = exif?.isoEquivalent,
                        focalLength = exif?.focalLength,
                        focalLength35mmEquiv = exif?.focalLength35mmEquiv,
                        fNumber = exif?.fNumber,
                        shutterSpeed = exif?.shutterSpeed,
                        exifImageWidth = exif?.exifImageWidth,
                        exifImageHeight = exif?.exifImageHeight,
                        lut1 = exif?.lut1,
                        lut2 = exif?.lut2,
                        flash = exif?.flash,
                        exposureCompensation = exif?.exposureCompensation,
                        meteringMode = exif?.meteringMode,
                        photoStyle = exif?.photoStyle,
                        isHdr = exif?.isHdr ?: false
                    )
                }
                photoDao.insertAll(entities)

                CoroutineScope(Dispatchers.IO).launch {
                    extractExifForPhotos(changedMedia.map { it.id })
                }

                generateThumbnailsForPhotos(changedMedia.map { it.id })
            }

            val mediaStoreIds = queryAllMediaIdsFromMediaStore()
            val roomIds = photoDao.getAllPhotoIds()
            val mediaStoreIdSet = mediaStoreIds.toSet()
            val roomIdSet = roomIds.toSet()

            val removedIds = roomIdSet - mediaStoreIdSet
            if (removedIds.isNotEmpty()) {
                Log.i(TAG, "Removing ${removedIds.size} stale records (permanently deleted)")
                thumbnailManager.deleteThumbnails(removedIds)
                photoDao.deleteByIds(removedIds.toList())
            }

            val missingIds = mediaStoreIdSet - roomIdSet
            if (missingIds.isNotEmpty()) {
                Log.i(TAG, "Adding ${missingIds.size} missing records (restored from trash)")
                syncSpecificPhotos(missingIds.toList())
            }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Incremental scan completed: ${changedMedia.size} media found, ${removedIds.size} cleaned in ${duration}ms")

            _scanState.emit(ScanState.Completed(changedMedia.size, duration))
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Incremental scan failed", e)
            _scanState.emit(ScanState.Error(e.message ?: "Unknown error"))
            return@withContext false
        } finally {
            isScanning = false
        }
    }

    /**
     * 根据 ID 列表从 MediaStore 精确同步指定照片到 Room
     * 用于恢复/外部变更后精确回写，不依赖时间戳
     */
    suspend fun syncSpecificPhotos(photoIds: List<Long>) = withContext(Dispatchers.IO) {
        if (photoIds.isEmpty()) return@withContext

        val items = queryMediaByIds(photoIds)
        if (items.isEmpty()) return@withContext

        val hiddenIds = photoDao.getHiddenPhotoIds().toSet()
        val existingExif = photoDao.getExifSnapshots().associateBy { it.id }
        val entities = items.map { item ->
            val exif = existingExif[item.id]
            PhotoEntity(
                id = item.id,
                uri = item.uri,
                displayName = item.displayName,
                dateTaken = item.dateTaken,
                dateModified = item.dateModified,
                dateAdded = item.dateAdded,
                mimeType = item.mimeType,
                width = item.width,
                height = item.height,
                size = item.size,
                bucketId = item.bucketId,
                bucketName = item.bucketName,
                latitude = exif?.latitude,
                longitude = exif?.longitude,
                orientation = item.orientation,
                isFavorite = item.isFavorite,
                isHidden = item.id in hiddenIds,
                cameraMake = exif?.cameraMake,
                cameraModel = exif?.cameraModel,
                lensModel = exif?.lensModel,
                isoEquivalent = exif?.isoEquivalent,
                focalLength = exif?.focalLength,
                focalLength35mmEquiv = exif?.focalLength35mmEquiv,
                fNumber = exif?.fNumber,
                shutterSpeed = exif?.shutterSpeed,
                exifImageWidth = exif?.exifImageWidth,
                exifImageHeight = exif?.exifImageHeight,
                lut1 = exif?.lut1,
                lut2 = exif?.lut2,
                flash = exif?.flash,
                exposureCompensation = exif?.exposureCompensation,
                meteringMode = exif?.meteringMode,
                photoStyle = exif?.photoStyle,
                isHdr = exif?.isHdr ?: false
            )
        }
        photoDao.insertAll(entities)
        generateThumbnailsForPhotos(photoIds)
        CoroutineScope(Dispatchers.IO).launch {
            extractExifForPhotos(photoIds)
        }
    }

    /**
     * 删除单条媒体记录
     */
    suspend fun deletePhoto(photoId: Long) = withContext(Dispatchers.IO) {
        thumbnailManager.deleteThumbnail(photoId)
        mutex.withLock { photoDao.deleteById(photoId) }
    }

    /**
     * 批量删除媒体记录（单次事务，避免逐条删除触发多次 Room Flow 重新查询）
     */
    suspend fun deletePhotos(photoIds: List<Long>) = withContext(Dispatchers.IO) {
        if (photoIds.isEmpty()) return@withContext
        thumbnailManager.deleteThumbnails(photoIds)
        mutex.withLock { photoDao.deleteByIds(photoIds) }
    }

    /**
     * 批量更新收藏状态
     */
    suspend fun updateFavorite(photoIds: List<Long>, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { photoDao.updateFavoriteBatch(photoIds, isFavorite) }
    }

    /**
     * 为指定照片批量提取 EXIF 信息
     * 全量扫描仍在提取时不重复工作（最终会被全量覆盖）
     * 失败的照片 5 秒后重试一次（覆盖相机后处理未完成的场景）
     */
    private suspend fun extractExifForPhotos(photoIds: List<Long>) = withContext(Dispatchers.IO) {
        val batchUpdates = mutableListOf<PhotoEntity>()
        val failedIds = mutableListOf<Long>()

        photoIds.forEach { id ->
            val photo = photoDao.getPhotoById(id) ?: return@forEach
            val uri = android.net.Uri.parse(photo.uri)
            val exifData = exifExtractor.extractExif(uri)
            if (exifData != null) {
                batchUpdates.add(ExifExtractor.applyToEntity(photo, exifData))
            } else {
                failedIds.add(id)
            }
        }
        if (batchUpdates.isNotEmpty()) {
            photoDao.updateAll(batchUpdates)
        }

        // 失败的照片延迟重试，给相机后处理留出完成时间
        if (failedIds.isNotEmpty()) {
            Log.w(TAG, "EXIF extraction failed for ${failedIds.size} photos, retrying in 5s")
            delay(5000)
            retryExtractExif(failedIds)
        }
    }

    /**
     * 重试失败的 EXIF 提取
     * 通常发生在照片文件尚未完全写入或后处理未完成时
     */
    private suspend fun retryExtractExif(photoIds: List<Long>) = withContext(Dispatchers.IO) {
        val batchUpdates = mutableListOf<PhotoEntity>()
        photoIds.forEach { id ->
            val photo = photoDao.getPhotoById(id) ?: return@forEach
            val uri = android.net.Uri.parse(photo.uri)
            val exifData = exifExtractor.extractExif(uri)
            if (exifData != null) {
                batchUpdates.add(ExifExtractor.applyToEntity(photo, exifData))
            }
        }
        if (batchUpdates.isNotEmpty()) {
            Log.i(TAG, "Retry EXIF succeeded for ${batchUpdates.size}/${photoIds.size} photos")
            photoDao.updateAll(batchUpdates)
        } else {
            Log.w(TAG, "Retry EXIF still failed for all ${photoIds.size} photos")
        }
    }

    // ==================== 缩略图生成 ====================

    /**
     * 为所有照片生成缩略图（优化版：并行处理）
     * 在 EXIF 提取完成后异步执行
     */
    private fun generateThumbnailsForAllPhotos() {
        thumbnailJob?.cancel()
        _thumbnailProgress.value = 0f
        _isGeneratingThumbnails.value = true
        thumbnailJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val allPhotos = photoDao.getAllPhotos()
                if (allPhotos.isEmpty()) {
                    _thumbnailProgress.value = 1f
                    _isGeneratingThumbnails.value = false
                    return@launch
                }

                Log.i(TAG, "Starting thumbnail generation for ${allPhotos.size} photos")

                // 过滤出需要生成缩略图的照片
                val photosNeedingThumbnail = allPhotos.filter { photo ->
                    val expected = thumbnailManager.thumbnailFileFor(photo.id)
                    photo.thumbnailPath != expected.absolutePath || !expected.exists()
                }

                val totalToProcess = photosNeedingThumbnail.size

                if (totalToProcess == 0) {
                    _thumbnailProgress.value = 1f
                    _isGeneratingThumbnails.value = false
                    return@launch
                }

                Log.i(TAG, "Actually need thumbnail generation for $totalToProcess photos")

                // 并行处理，限制并发数为CPU核心数（最多4个）
                val processed = AtomicInteger(0)
                val thumbnailBatchSize = 50
                val concurrency = minOf(Runtime.getRuntime().availableProcessors(), 4)

                coroutineScope {
                    photosNeedingThumbnail.chunked(thumbnailBatchSize)
                        .chunked(concurrency)
                        .forEach { batchGroup ->
                            val results = batchGroup.map { batch ->
                                async {
                                    processThumbnailBatch(batch)
                                }
                            }.awaitAll()
                            val batchProcessed = results.sum()
                            val current = processed.addAndGet(batchProcessed)
                            _thumbnailProgress.value = (current.toFloat() / totalToProcess).coerceIn(0f, 1f)
                        }
                }

                _thumbnailProgress.value = 1f
                Log.i(TAG, "Thumbnail generation completed")
            } catch (e: Exception) {
                Log.e(TAG, "Thumbnail generation failed", e)
            } finally {
                _isGeneratingThumbnails.value = false
            }
        }
    }

    /**
     * 处理一批照片的缩略图生成
     * @return 本批处理的照片数量（含跳过的）
     */
    private suspend fun processThumbnailBatch(batch: List<PhotoEntity>): Int {
        val batchUpdates = mutableListOf<PhotoEntity>()
        var processed = 0

        batch.forEach { photo ->
            val uri = try {
                android.net.Uri.parse(photo.uri)
            } catch (e: Exception) {
                processed++
                return@forEach
            }

            val path = thumbnailManager.generateThumbnail(uri, photo.id, photo.mimeType)
            if (path != null) {
                batchUpdates.add(photo.copy(thumbnailPath = path))
            }
            processed++
        }

        // 批量更新数据库
        if (batchUpdates.isNotEmpty()) {
            photoDao.updateAll(batchUpdates)
        }

        return processed
    }

    /**
     * 为指定照片列表生成缩略图（优化版：批量查询+并行处理）
     * 用于增量扫描后为新照片生成缩略图
     */
    private fun generateThumbnailsForPhotos(photoIds: List<Long>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 批量查询照片（避免N+1查询）
                val allPhotos = photoDao.getPhotosByIds(photoIds)

                // 过滤出需要生成缩略图的照片
                val photosNeedingThumbnail = allPhotos.filter { photo ->
                    val expected = thumbnailManager.thumbnailFileFor(photo.id)
                    photo.thumbnailPath != expected.absolutePath || !expected.exists()
                }

                if (photosNeedingThumbnail.isEmpty()) {
                    Log.i(TAG, "Incremental thumbnail generation: all thumbnails exist")
                    return@launch
                }

                Log.i(TAG, "Incremental thumbnail generation for ${photosNeedingThumbnail.size} photos")

                // 并行处理
                coroutineScope {
                    photosNeedingThumbnail.chunked(10).map { batch ->
                        async {
                            processThumbnailBatch(batch)
                        }
                    }.awaitAll()
                }

                Log.i(TAG, "Incremental thumbnail generation completed")
            } catch (e: Exception) {
                Log.e(TAG, "Incremental thumbnail generation failed", e)
            }
        }
    }

    // ==================== MediaStore 查询 ====================

    private fun queryAllMediaFromMediaStore(): List<MediaStoreItem> {
        val items = mutableListOf<MediaStoreItem>()
        val uri = MediaStore.Files.getContentUri("external")

        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
        }

        context.contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: continue

                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                val photoUri: Uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
                val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))

                val finalDateTaken = when {
                    dateTaken > 0 -> dateTaken
                    dateModified > 0 -> dateModified * 1000L
                    dateAdded > 0 -> dateAdded * 1000L
                    else -> System.currentTimeMillis()
                }

                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri.toString(),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                        mimeType = mimeType,
                        dateTaken = finalDateTaken,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                        bucketId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
                        orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)),
                        isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
                        isVideo = isVideo
                    )
                )
            }
        }

        return items
    }

    private fun queryMediaModifiedAfter(modifiedAfter: Long): List<MediaStoreItem> {
        val items = mutableListOf<MediaStoreItem>()
        val uri = MediaStore.Files.getContentUri("external")

        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) " +
                "AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} >= $modifiedAfter"

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        }

        context.contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: continue

                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                val photoUri: Uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
                val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))

                val finalDateTaken = when {
                    dateTaken > 0 -> dateTaken
                    dateModified > 0 -> dateModified * 1000L
                    dateAdded > 0 -> dateAdded * 1000L
                    else -> System.currentTimeMillis()
                }

                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri.toString(),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                        mimeType = mimeType,
                        dateTaken = finalDateTaken,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                        bucketId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
                        orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)),
                        isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
                        isVideo = isVideo
                    )
                )
            }
        }

        return items
    }

    private fun queryMediaByIds(photoIds: List<Long>): List<MediaStoreItem> {
        if (photoIds.isEmpty()) return emptyList()
        val items = mutableListOf<MediaStoreItem>()
        val uri = MediaStore.Files.getContentUri("external")

        val idList = photoIds.joinToString(",")
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) " +
                "AND ${MediaStore.Files.FileColumns._ID} IN ($idList) " +
                "AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.ORIENTATION,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: continue

                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                val photoUri: Uri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN))
                val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED))

                val finalDateTaken = when {
                    dateTaken > 0 -> dateTaken
                    dateModified > 0 -> dateModified * 1000L
                    dateAdded > 0 -> dateAdded * 1000L
                    else -> System.currentTimeMillis()
                }

                items.add(
                    MediaStoreItem(
                        id = id,
                        uri = photoUri.toString(),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                        mimeType = mimeType,
                        dateTaken = finalDateTaken,
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)),
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)),
                        bucketId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)),
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)) ?: "Unknown",
                        orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)),
                        isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)) == 1,
                        isVideo = isVideo
                    )
                )
            }
        }

        return items
    }

    /**
     * 查询 MediaStore 中所有媒体文件的 _ID（用于清理孤立记录）
     * 只查单列，性能轻量
     */
    private fun queryAllMediaIdsFromMediaStore(): List<Long> {
        val ids = mutableListOf<Long>()
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        }
        context.contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0))
            }
        }
        return ids
    }

    /**
     * MediaStore 查询结果的内部数据类
     */
    private data class MediaStoreItem(
        val id: Long,
        val uri: String,
        val displayName: String?,
        val mimeType: String,
        val dateTaken: Long,
        val dateModified: Long,
        val dateAdded: Long,
        val width: Int,
        val height: Int,
        val size: Long,
        val bucketId: Long,
        val bucketName: String,
        val orientation: Int,
        val isFavorite: Boolean,
        val isVideo: Boolean
    )
}