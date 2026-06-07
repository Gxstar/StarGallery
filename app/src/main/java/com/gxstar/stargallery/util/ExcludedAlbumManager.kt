package com.gxstar.stargallery.util

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExcludedAlbumManager @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val KEY_EXCLUDED_BUCKET_IDS = "excluded_bucket_ids"
    }

    private val _excludedBucketIds = MutableStateFlow(loadExcludedIds())
    val excludedBucketIds: StateFlow<Set<Long>> = _excludedBucketIds.asStateFlow()

    private fun loadExcludedIds(): Set<Long> {
        return sharedPreferences.getStringSet(KEY_EXCLUDED_BUCKET_IDS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()
    }

    fun isExcluded(bucketId: Long): Boolean = bucketId in _excludedBucketIds.value

    fun setExcluded(bucketId: Long, excluded: Boolean) {
        val current = _excludedBucketIds.value.toMutableSet()
        if (excluded) current.add(bucketId) else current.remove(bucketId)
        saveIds(current)
    }

    fun setExcludedBatch(bucketIds: Collection<Long>, excluded: Boolean) {
        val current = _excludedBucketIds.value.toMutableSet()
        if (excluded) current.addAll(bucketIds) else current.removeAll(bucketIds)
        saveIds(current)
    }

    fun setAllExcluded(bucketIds: Set<Long>) {
        saveIds(bucketIds)
    }

    private fun saveIds(ids: Set<Long>) {
        _excludedBucketIds.value = ids
        sharedPreferences.edit().putStringSet(
            KEY_EXCLUDED_BUCKET_IDS,
            ids.map { it.toString() }.toSet()
        ).apply()
    }
}
