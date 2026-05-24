package com.gxstar.stargallery.data.local.exif

import com.drew.metadata.Metadata
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.drew.metadata.exif.makernotes.SonyType1MakernoteDirectory

object PhotoStyleResolver {

    fun resolve(
        cameraMake: String?,
        cameraModel: String?,
        metadata: Metadata
    ): String? {
        val make = (cameraMake?.lowercase() ?: "") + " " + (cameraModel?.lowercase() ?: "")
        return when {
            make.contains("panasonic") || make.contains("lumix") ->
                metadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java)
                    ?.getInteger(PanasonicMakernoteDirectory.TAG_PHOTO_STYLE)
                    ?.let { PANASONIC[it] }
            make.contains("sony") || make.contains("ilce") || make.contains("ilca") ->
                readSonyColorMode(metadata)
            else -> null
        }
    }

    private fun readSonyColorMode(metadata: Metadata): String? {
        val dir = metadata.getFirstDirectoryOfType(SonyType1MakernoteDirectory::class.java) ?: return null
        val raw = dir.getInteger(SonyType1MakernoteDirectory.TAG_COLOR_MODE) ?: return null
        return SONY[raw]
    }

    private val PANASONIC = mapOf(
        0 to "Auto",
        1 to "Standard",
        2 to "Vivid",
        3 to "Natural",
        4 to "Monochrome",
        5 to "Scenery",
        6 to "Portrait",
        8 to "Cinelike D",
        9 to "Cinelike V",
        11 to "L. Monochrome",
        12 to "Like709",
        15 to "L. Monochrome D",
        17 to "V-Log",
        18 to "Cinelike D2"
    )

    private val SONY = mapOf(
        0 to "Standard",
        1 to "Vivid",
        2 to "Portrait",
        3 to "Landscape",
        4 to "Sunset",
        5 to "Night View/Portrait",
        6 to "B&W",
        7 to "Adobe RGB",
        12 to "Neutral",
        13 to "Clear",
        14 to "Deep",
        15 to "Light",
        16 to "Autumn Leaves",
        17 to "Sepia",
        18 to "FL",
        19 to "Vivid 2",
        20 to "IN",
        21 to "SH",
        22 to "FL2",
        23 to "FL3",
        100 to "Neutral",
        101 to "Clear",
        102 to "Deep",
        103 to "Light",
        104 to "Night View",
        105 to "Autumn Leaves",
        255 to "Off"
    )
}
