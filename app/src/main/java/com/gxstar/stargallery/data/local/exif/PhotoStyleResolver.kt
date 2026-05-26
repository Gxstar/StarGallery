package com.gxstar.stargallery.data.local.exif

import android.util.Log
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory.CameraSettings
import com.drew.metadata.exif.makernotes.FujifilmMakernoteDirectory
import com.drew.metadata.exif.makernotes.NikonPictureControl1Directory
import com.drew.metadata.exif.makernotes.NikonPictureControl2Directory
import com.drew.metadata.exif.makernotes.OlympusCameraSettingsMakernoteDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.drew.metadata.exif.makernotes.PentaxMakernoteDirectory
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
            make.contains("pentax") || make.contains("ricoh") || make.contains("asahi") ->
                readPentax(metadata)
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
        val dir = metadata.getFirstDirectoryOfType(CanonMakernoteDirectory::class.java)

        if (dir != null) {
            // 1. CameraSettings → TAG_PHOTO_EFFECT
            dir.getInteger(CameraSettings.TAG_PHOTO_EFFECT)?.let { value ->
                if (value != 0) {
                    CANON[value]?.let { return it }
                }
            }

            // 2. PictureStylePC (0x4009) — int16u[3]
            resolveCanonPictureStyle(dir, 0x4009)?.let { return it }

            // 3. PictureStyleUserDef (0x4008) — 同上
            resolveCanonPictureStyle(dir, 0x4008)?.let { return it }
        }

        // 4. 兜底：直接从 ExifIFD0 的 MakerNote 原始字节解析 0x4008/0x4009
        val exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val makernoteBytes = exifDir?.getByteArray(0x927C)
        if (makernoteBytes != null) {
            parseCanonMakernote(makernoteBytes)?.let { return it }
        }

        return null
    }

    /**
     * 从 Canon MakerNote 原始字节中直接解析 PictureStyle。
     * Canon MakerNote 结构：前 8 字节为 "Canon\0\0\0" 头，后续为标准 TIFF IFD。
     * IFD 条目格式：12 字节/条目 (tag:2, type:2, count:4, value/offset:4)
     * 遍历 IFD 查找 tag 0x4008/0x4009（int16u[3]），取第一个值。
     */
    private fun parseCanonMakernote(bytes: ByteArray): String? {
        if (bytes.size < 10) return null

        // 跳过 "Canon\0\0\0"（8 字节魔数头）
        val ifdStart = 8
        if (ifdStart + 2 > bytes.size) return null

        val entryCount = ((bytes[ifdStart + 1].toInt() and 0xFF) shl 8) or (bytes[ifdStart].toInt() and 0xFF)

        for (i in 0 until entryCount) {
            val pos = ifdStart + 2 + i * 12
            if (pos + 12 > bytes.size) break

            val tag = ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos].toInt() and 0xFF)
            val type = ((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 2].toInt() and 0xFF)
            val count = ((bytes[pos + 7].toInt() and 0xFF) shl 24) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 4].toInt() and 0xFF)

            if (tag != 0x4008 && tag != 0x4009) continue
            if (type != 3 || count < 1) continue // type 3 = unsigned short (2 bytes)

            // value 区域包含第一个 int16u 值（little-endian）
            val valPos = pos + 8
            if (valPos + 2 > bytes.size) continue
            val value = ((bytes[valPos + 1].toInt() and 0xFF) shl 8) or (bytes[valPos].toInt() and 0xFF)
            val result = CANON_PICTURE_STYLE[value]
            if (result != null) Log.d(TAG, "Canon makernote raw parse: tag=0x${tag.toString(16)}, value=$value → $result")
            return result
        }
        return null
    }

    /**
     * 从 CanonMakernoteDirectory 中取 PictureStyle 值。
     * metadata-extractor 可能把 0x4008/0x4009 存为 intArray 或 Integer 或 byteArray。
     */
    private fun resolveCanonPictureStyle(dir: CanonMakernoteDirectory, tag: Int): String? {
        val value = dir.getIntArray(tag)?.firstOrNull()
            ?: dir.getInteger(tag)
            ?: dir.getByteArray(tag)?.let { bytes ->
                if (bytes.size >= 2) ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
                else null
            }
            ?: return null
        return CANON_PICTURE_STYLE[value]
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

    // ==================== 宾得 ====================

    private fun readPentax(metadata: Metadata): String? {
        // Pentax 的照片风格是 ImageTone（0x004f），非 PictureMode（0x000b，后者是拍摄模式）
        val dir = metadata.getFirstDirectoryOfType(PentaxMakernoteDirectory::class.java) ?: return null
        return dir.getInteger(0x004f)?.let { PENTAX[it] }
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

    // Pentax ImageTone（0x004f）— ExifTool Pentax ImageTone Values
    private val PENTAX = mapOf(
        0 to "Natural",
        1 to "Bright",
        2 to "Portrait",
        3 to "Landscape",
        4 to "Vibrant",
        5 to "Monochrome",
        6 to "Muted",
        7 to "Reversal Film",
        8 to "Bleach Bypass",
        9 to "Radiant",
        10 to "Cross Processing",
        11 to "Flat",
        256 to "Standard",
        257 to "Vivid",
        258 to "Monotone",
        259 to "Soft Monotone",
        260 to "Hard Monotone",
        261 to "Hi-contrast B&W",
        262 to "Positive Film",
        263 to "Bleach Bypass 2",
        264 to "Retro",
        265 to "HDR Tone",
        266 to "Cross Processing 2",
        267 to "Negative Film",
        32768 to "Standard",
        32769 to "Hard",
        32770 to "Soft",
        33024 to "Monochrome"
    )
}
