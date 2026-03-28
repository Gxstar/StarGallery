package com.gxstar.stargallery.data.model

data class Album(
    val id: Long,
    val name: String,
    val coverUri: android.net.Uri?,
    val photoCount: Int
)
