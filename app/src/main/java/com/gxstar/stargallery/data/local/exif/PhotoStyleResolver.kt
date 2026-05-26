package com.gxstar.stargallery.data.local.exif

import android.util.Log
import com.drew.metadata.Metadata
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory.CameraSettings
import com.drew.metadata.exif.makernotes.FujifilmMakernoteDirectory
import com.drew.metadata.exif.makernotes.NikonPictureControl1Directory
import com.drew.metadata.exif.makernotes.NikonPictureControl2Directory
import com.drew.metadata.exif.makernotes.OlympusCameraSettingsMakernoteDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.drew.metadata.exif.makernotes.SonyType1MakernoteDirectory

object PhotoStyleResolver {

    private const val TAG = "PhotoStyleResolver"

    fun resolve(
        cameraMake: String?,
        cameraModel: String?,
        metadata: Metadata
    ): String? {
        val make = (cameraMake?.lowercase() ?: "") + " " + (cameraModel?.lowercase() ?: "")
        val result = when {
            make.contains("panasonic") || make.contains("lumix") ->
                readPanasonic(metadata)
            make.contains("sony") || make.contains("ilce") || make.contains("ilca") ->
                readSony(metadata)
            make.contains("canon") ->
                readCanon(metadata)
            make.contains("nikon") ->
                readNikon(metadata)
            make.contains("fujifilm") || make.contains("fuji") ->
                readFujifilm(metadata)
            make.contains("olympus") || make.contains("om system") ->
                readOlympus(metadata)
            else -> null
        }
        if (result != null) {
            Log.d(TAG, "Resolved photo style for $make: $result")
        }
        return result
    }

    // ==================== 松下 ====================

    private fun readPanasonic(metadata: Metadata): String? {
        val dir = metadata.getFirstDirectoryOfType(PanasonicMakernoteDirectory::class.java) ?: return null
        return dir.getInteger(PanasonicMakernoteDirectory.TAG_PHOTO_STYLE)?.let { PANASONIC[it] }
    }

    // ==================== 索尼 ====================

    private fun readSony(metadata: Metadata): String? {
        val dir = metadata.getFirstDirectoryOfType(SonyType1MakernoteDirectory::class.java) ?: return null
        return dir.getInteger(SonyType1MakernoteDirectory.TAG_COLOR_MODE)?.let { SONY[it] }
    }

    // ==================== 佳能 ====================

    private fun readCanon(metadata: Metadata): String? {
        val dir = metadata.getFirstDirectoryOfType(CanonMakernoteDirectory::class.java) ?: return null

        // 1. 优先 CameraSettings → TAG_PHOTO_EFFECT (CanonMakernoteDescriptor.getPhotoEffectDescription)
        dir.getInteger(CameraSettings.TAG_PHOTO_EFFECT)?.let { value ->
            if (value != 0) { // 0 = Off，跳过
                CANON[value]?.let { return it }
            }
        }
        // 2. 降级到 PictureStylePC (raw tag 0x4009)，现代 EOS/R 系列相机
        dir.getIntArray(0x4009)?.firstOrNull()?.let { value ->
            CANON_PICTURE_STYLE[value]?.let { return it }
        }
        // 3. 降级到 PictureStyleUserDef (raw tag 0x4008)
        dir.getIntArray(0x4008)?.firstOrNull()?.let { value ->
            CANON_PICTURE_STYLE[value]?.let { return it }
        }
        return null
    }

    // ==================== 尼康 ====================

    private fun readNikon(metadata: Metadata): String? {
        // 1. NikonPictureControl1Directory (58 bytes, TAG_PICTURE_CONTROL = 0x0023)
        metadata.getFirstDirectoryOfType(NikonPictureControl1Directory::class.java)?.let { dir ->
            val name1 = dir.getString(NikonPictureControl1Directory.TAG_PICTURE_CONTROL_NAME)
            if (!name1.isNullOrBlank()) return name1.trim()
            val name2 = dir.getString(NikonPictureControl1Directory.TAG_PICTURE_CONTROL_BASE)
            if (!name2.isNullOrBlank()) return name2.trim()
        }
        // 2. NikonPictureControl2Directory (68 bytes, TAG_PICTURE_CONTROL_2 = 0x00BD)
        metadata.getFirstDirectoryOfType(NikonPictureControl2Directory::class.java)?.let { dir ->
            val name1 = dir.getString(NikonPictureControl2Directory.TAG_PICTURE_CONTROL_NAME)
            if (!name1.isNullOrBlank()) return name1.trim()
            val name2 = dir.getString(NikonPictureControl2Directory.TAG_PICTURE_CONTROL_BASE)
            if (!name2.isNullOrBlank()) return name2.trim()
        }
        return null
    }

    // ==================== 富士 ====================

    private fun readFujifilm(metadata: Metadata): String? {
        val dir = metadata.getFirstDirectoryOfType(FujifilmMakernoteDirectory::class.java) ?: return null
        return dir.getInteger(FujifilmMakernoteDirectory.TAG_FILM_MODE)?.let { FUJIFILM[it] }
    }

    // ==================== 奥林巴斯 ====================

    private fun readOlympus(metadata: Metadata): String? {
        metadata.getFirstDirectoryOfType(OlympusCameraSettingsMakernoteDirectory::class.java)?.let { dir ->
            dir.getInteger(OlympusCameraSettingsMakernoteDirectory.TagPictureMode)?.let { value ->
                OLYMPUS[value]?.let { return it }
            }
        }
        return null
    }

    // ==================== 值映射表（来自 metadata-extractor 各品牌 Descriptor 源码） ====================

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

    // CanonMakernoteDescriptor.getPhotoEffectDescription 的实际值
    private val CANON = mapOf(
        0 to null,  // Off → return null
        1 to "Vivid",
        2 to "Neutral",
        3 to "Smooth",
        4 to "Sepia",
        5 to "B&W",
        6 to "Custom",
        100 to "My Color Data"
    )

    // Canon PictureStyle (tag 0x4008/0x4009) — ExifTool Canon PictureStyle values
    // 含两个范围：0x01-0x07（早期机型）和 0x81-0x88（现代 EOS/R 系列）
    private val CANON_PICTURE_STYLE = mapOf(
        0x01 to "Standard",
        0x02 to "Portrait",
        0x03 to "High Saturation",
        0x04 to "Adobe RGB",
        0x05 to "Low Saturation",
        0x06 to "CM Set 1",
        0x07 to "CM Set 2",
        0x21 to "User Def. 1",
        0x22 to "User Def. 2",
        0x23 to "User Def. 3",
        0x41 to "PC 1",
        0x42 to "PC 2",
        0x43 to "PC 3",
        0x81 to "Standard",
        0x82 to "Portrait",
        0x83 to "Landscape",
        0x84 to "Neutral",
        0x85 to "Faithful",
        0x86 to "Monochrome",
        0x87 to "Auto",
        0x88 to "Fine Detail"
    )

    // FujifilmMakernoteDescriptor.getFilmModeDescription 的实际值
    private val FUJIFILM = mapOf(
        0x000 to "Provia (Standard)",
        0x100 to "Studio Portrait",
        0x110 to "Portrait Enhanced",
        0x120 to "Portrait Smooth (Astia)",
        0x130 to "Portrait Sharp",
        0x200 to "Velvia (Vivid)",
        0x300 to "Studio Portrait Ex",
        0x400 to "F4/Velvia",
        0x500 to "Pro Neg. Std",
        0x501 to "Pro Neg. Hi",
        0x600 to "Classic Chrome",
        0x700 to "Eterna",
        0x800 to "Classic Negative",
        0x900 to "Bleach Bypass",
        0xa00 to "Nostalgic Neg"
    )

    // Olympus 常见 PictureMode 值（来自 ExifTool 和 descriptor 分析）
    private val OLYMPUS = mapOf(
        0 to "Standard (sRGB)",
        1 to "Vivid (sRGB)",
        2 to "Natural (sRGB)",
        3 to "Muted (sRGB)",
        4 to "Portrait (sRGB)",
        5 to "i-Enhance",
        6 to "Monotone (sRGB)",
        7 to "Sepia (sRGB)",
        8 to "i-Finish",
        16 to "Art Filter",
        17 to "Color Creator",
        256 to "Standard (Adobe RGB)",
        257 to "Vivid (Adobe RGB)",
        258 to "Natural (Adobe RGB)",
        259 to "Muted (Adobe RGB)",
        260 to "Portrait (Adobe RGB)",
        261 to "Monotone (Adobe RGB)",
        262 to "Sepia (Adobe RGB)"
    )
}
