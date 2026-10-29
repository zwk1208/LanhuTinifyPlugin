package com.lanhu.tinify

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Locale
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import javax.imageio.ImageIO
import kotlin.math.roundToInt

enum class Density(val suffix: String, val scale: Float) {
    MDPI("mdpi", 1f), HDPI("hdpi", 1.5f), XHDPI("xhdpi", 2f), XXHDPI("xxhdpi", 3f), XXXHDPI("xxxhdpi", 4f);

    val resourceDirectory: String get() = "drawable-$suffix"

    override fun toString(): String = suffix

    companion object {
        private val outputDirectories = entries.associateBy { it.resourceDirectory }

        fun fromOutputDirectoryName(directory: String?): Density? =
            directory?.trim()?.lowercase()?.let(outputDirectories::get)

        fun fromDirectoryName(directory: String?): Density? {
            val normalized = directory?.trim()?.lowercase() ?: return null
            val densityPart = when {
                normalized.startsWith("drawable-") -> normalized.removePrefix("drawable-")
                normalized.startsWith("mipmap-") -> normalized.removePrefix("mipmap-")
                entries.any { normalized == it.suffix || normalized.startsWith("${it.suffix}-") } -> normalized
                else -> return null
            }
            return entries.firstOrNull { density ->
                densityPart.split('-').contains(density.suffix)
            }
        }
    }
}

data class ImportedImage(
    val originalName: String,
    val bytes: ByteArray,
    val format: String,
    val width: Int,
    val height: Int,
    val density: Density?
) {
    var targetName: String = ResourceNameGenerator.generate(originalName)
    private var targetResourceFolderValue: String = (density ?: Density.XXHDPI).resourceDirectory
    var targetDensity: Density = density ?: Density.XXHDPI
        set(value) {
            field = value
            targetResourceFolderValue = value.resourceDirectory
        }
    var importToProject: Boolean = true
    var compress: Boolean = true

    var targetResourceFolder: String
        get() = targetResourceFolderValue
        set(value) {
            val normalized = value.trim().lowercase()
            if (!normalized.startsWith("drawable-") && !normalized.startsWith("mipmap-")) {
                throw IllegalArgumentException("Unsupported Android resource folder: $value")
            }
            targetDensity = Density.fromDirectoryName(normalized)
                ?: throw IllegalArgumentException("Unsupported Android density folder: $value")
            targetResourceFolderValue = normalized
        }

    var targetFolder: String
        get() = targetResourceFolder
        set(value) {
            targetResourceFolder = value
        }

    fun extension(): String = format.lowercase().let { if (it == "jpg") "jpg" else it }

    /**
     * Imported rows are intentionally independent.  In particular, do not use data class
     * copy() here: targetName, targetDensity, and row import/compression flags are mutable state.
     */
    fun freshImport(): ImportedImage =
        ImportedImage(originalName, bytes.copyOf(), format, width, height, density)
}

data class ImageProcessingPlan(
    val imported: List<ImportedImage>,
    val compressed: List<ImportedImage>,
    val originals: List<ImportedImage>
) {
    companion object {
        fun from(images: List<ImportedImage>): ImageProcessingPlan {
            val imported = images.filter { it.importToProject }
            return ImageProcessingPlan(
                imported = imported,
                compressed = imported.filter { it.compress },
                originals = imported.filterNot { it.compress }
            )
        }
    }
}

object ImageSizeFormatter {
    fun format(bytes: Long): String {
        require(bytes >= 0) { "bytes must not be negative" }
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

object ResourceNameValidator {
    private val valid = Regex("^[a-z0-9_]+$")
    private val supported = setOf("png", "jpg", "jpeg", "webp")

    fun validate(baseName: String, extension: String, targetFolder: String? = null): String? {
        if (baseName.isBlank()) return "必须填写目标名称"
        if (!valid.matches(baseName)) return "只能使用小写字母、数字和下划线"
        if (extension.lowercase() !in supported) return "仅支持 png、jpg、jpeg 和 webp 文件"
        targetFolder?.let { validateTargetFolder(it)?.let { error -> return error } }
        return null
    }

    fun isValid(baseName: String, extension: String): Boolean = validate(baseName, extension) == null

    fun validateTargetFolder(folder: String): String? {
        val normalized = folder.trim().lowercase()
        return if ((!normalized.startsWith("drawable-") && !normalized.startsWith("mipmap-")) ||
            Density.fromDirectoryName(normalized) == null
        ) {
            "目标目录必须是 drawable-*/mipmap-* density 目录，也可以带语言或版本限定符"
        } else null
    }

    fun validateFolder(folder: String): String? = validateTargetFolder(folder)

    fun isValidTargetFolder(folder: String): Boolean = validateTargetFolder(folder) == null
}

object ResourceNameGenerator {
    const val DEFAULT_PREFIX = "ic_"

    private val englishTranslations = linkedMapOf(
        "警告横幅" to "warning_banner",
        "错误提示" to "error_hint",
        "成功提示" to "success_hint",
        "登录页" to "login_page",
        "首页" to "home",
        "设置" to "settings",
        "搜索" to "search",
        "图标" to "icon_asset",
        "按钮" to "button",
        "头像" to "avatar",
        "返回" to "back",
        "删除" to "delete",
        "添加" to "add",
        "编辑" to "edit",
        "保存" to "save",
        "取消" to "cancel",
        "确认" to "confirm",
        "播放" to "play",
        "暂停" to "pause",
        "分享" to "share",
        "刷新" to "refresh",
        "关闭" to "close",
        "菜单" to "menu",
        "通知" to "notification"
    )

    private val pinyin = mapOf(
        '图' to "tu", '标' to "biao", '点' to "dian", '歌' to "ge",
        '警' to "jing", '告' to "gao", '横' to "heng", '幅' to "fu",
        '片' to "pian", '像' to "xiang",
        '按' to "an", '钮' to "niu", '首' to "shou", '页' to "ye",
        '设' to "she", '置' to "zhi", '搜' to "sou", '索' to "suo",
        '删' to "shan", '除' to "chu", '加' to "jia", '添' to "tian",
        '编' to "bian", '辑' to "ji", '保' to "bao", '存' to "cun",
        '返' to "fan", '回' to "hui", '确' to "que", '认' to "ren",
        '取' to "qu", '消' to "xiao", '提' to "ti", '示' to "shi",
        '成' to "cheng", '功' to "gong", '失' to "shi", '败' to "bai",
        '播' to "bo", '放' to "fang", '音' to "yin", '乐' to "yue",
        '视' to "shi", '频' to "pin", '相' to "xiang", '册' to "ce",
        '用' to "yong", '户' to "hu", '人' to "ren", '登' to "deng",
        '录' to "lu", '密' to "mi", '码' to "ma", '账' to "zhang",
        '号' to "hao", '网' to "wang", '络' to "luo", '云' to "yun",
        '新' to "xin", '闻' to "wen", '消' to "xiao", '息' to "xi",
        '大' to "da", '小' to "xiao", '中' to "zhong", '上' to "shang",
        '下' to "xia", '左' to "zuo", '右' to "you", '前' to "qian",
        '后' to "hou", '开' to "kai", '关' to "guan", '亮' to "liang",
        '暗' to "an", '红' to "hong", '黄' to "huang", '蓝' to "lan",
        '绿' to "lv", '黑' to "hei", '白' to "bai", '灰' to "hui",
        '一' to "yi", '二' to "er", '三' to "san", '四' to "si",
        '五' to "wu", '六' to "liu", '七' to "qi", '八' to "ba",
        '九' to "jiu", '十' to "shi", '空' to "kong", '格' to "ge",
        '界' to "jie", '面' to "mian", '导' to "dao", '航' to "hang"
    )
    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    fun generate(originalName: String, prefix: String = DEFAULT_PREFIX): String {
        val rawBase = originalName.substringBeforeLast('.', originalName)
        val translatedBase = englishTranslations.entries.fold(rawBase) { value, (source, target) ->
            value.replace(source, target)
        }
        val base = abbreviateIconPrefix(slug(translatedBase))
        val normalizedPrefix = normalizePrefix(prefix)
        if (base.isBlank()) return normalizedPrefix
        val prefixWithoutSeparator = normalizedPrefix.removeSuffix("_")
        val combined = if (base == prefixWithoutSeparator || base.startsWith(normalizedPrefix)) base
        else normalizedPrefix + base
        return ensureResourceStart(slug(combined))
    }

    fun normalize(originalName: String, prefix: String = DEFAULT_PREFIX): String =
        generate(originalName, prefix)

    fun applyPrefix(targetName: String, prefix: String = DEFAULT_PREFIX): String =
        slug(targetName.substringBeforeLast('.', targetName)).let { base ->
            val normalizedPrefix = normalizePrefix(prefix)
            if (base.isBlank()) return@let normalizedPrefix
            val withPrefix = if (base.startsWith(normalizedPrefix)) base else normalizedPrefix + base
            ensureResourceStart(slug(withPrefix))
        }

    private fun normalizePrefix(prefix: String): String {
        val normalized = slug(prefix).ifBlank { DEFAULT_PREFIX.removeSuffix("_") }
        val safe = ensureResourceStart(normalized)
        return if (safe.endsWith("_")) safe else "${safe}_"
    }

    private fun ensureResourceStart(value: String): String =
        if (value.firstOrNull()?.let { it in 'a'..'z' } == true) value else "resource_$value"

    private fun abbreviateIconPrefix(value: String): String =
        when {
            value == "icon" -> "ic"
            value.startsWith("icon_") -> "ic_${value.removePrefix("icon_")}"
            else -> value
        }

    private fun slug(value: String): String {
        val result = StringBuilder()
        value.forEach { character ->
            when {
                character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '_' ->
                    result.append(character.lowercaseChar())
                pinyinFor(character) != null -> {
                    if (result.isNotEmpty() && result.last() != '_') result.append('_')
                    result.append(pinyinFor(character))
                    result.append('_')
                }
                else -> {
                    if (character in '\u3400'..'\u9fff') {
                        if (result.isNotEmpty() && result.last() != '_') result.append('_')
                        result.append("u${character.code.toString(16)}")
                        result.append('_')
                    } else if (result.isNotEmpty() && result.last() != '_') {
                        result.append('_')
                    }
                }
            }
        }
        return result.toString().trim('_').replace(Regex("_+"), "_")
    }

    private fun pinyinFor(character: Char): String? =
        pinyin[character] ?: runCatching {
            PinyinHelper.toHanyuPinyinStringArray(character)
                ?.firstOrNull()
                ?.replace(Regex("[^a-zA-Z]"), "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

object BatchSplitter {
    fun <T> split(items: List<T>, maxBatchSize: Int = 20): List<List<T>> {
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
        return items.chunked(maxBatchSize)
    }
}

object ImageMetadata {
    fun read(bytes: ByteArray, fileName: String, density: Density?): ImportedImage? {
        val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: return null
        val format = fileName.substringAfterLast('.', "").lowercase()
        if (format !in setOf("png", "jpg", "jpeg", "webp")) return null
        return ImportedImage(fileName.substringAfterLast('/'), bytes, format, image.width, image.height, density)
    }

}

object ImageDensityScaler {
    fun inferSourceDensity(bytes: ByteArray, logicalWidth: Int, logicalHeight: Int): Density? {
        if (logicalWidth <= 0 || logicalHeight <= 0) return null
        val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: return null
        val widthScale = image.width.toFloat() / logicalWidth
        val heightScale = image.height.toFloat() / logicalHeight
        val scale = maxOf(widthScale, heightScale)
        return when {
            scale <= 1.25f -> Density.MDPI
            scale <= 1.75f -> Density.HDPI
            scale <= 2.5f -> Density.XHDPI
            scale <= 3.5f -> Density.XXHDPI
            scale <= 4.75f -> Density.XXXHDPI
            else -> null
        }
    }

    fun scale(
        bytes: ByteArray,
        format: String,
        sourceDensity: Density?,
        targetDensity: Density
    ): ByteArray {
        if (sourceDensity == null || sourceDensity == targetDensity) return bytes
        val source = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: return bytes
        val ratio = targetDensity.scale / sourceDensity.scale
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        if (width == source.width && height == source.height) return bytes

        val normalizedFormat = if (format.lowercase() == "jpeg") "jpg" else format.lowercase()
        val imageType = if (normalizedFormat == "jpg" || normalizedFormat == "jpeg") {
            BufferedImage.TYPE_INT_RGB
        } else if (source.colorModel.hasAlpha()) {
            BufferedImage.TYPE_INT_ARGB
        } else {
            BufferedImage.TYPE_INT_RGB
        }
        val resized = BufferedImage(width, height, imageType)
        val graphics = resized.createGraphics()
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC
        )
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_RENDERING,
            java.awt.RenderingHints.VALUE_RENDER_QUALITY
        )
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()

        return ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(resized, normalizedFormat, output)) return bytes
            output.toByteArray()
        }
    }
}

object ResourceDirectoryResolver {
    fun resourceRoot(selected: Path): Path {
        val normalized = selected.toAbsolutePath().normalize()
        val name = normalized.fileName?.toString()?.lowercase()
        return when {
            name == "res" -> normalized
            name == "drawable" || name == "mipmap" ||
                name?.startsWith("drawable-") == true || name?.startsWith("mipmap-") == true ->
                normalized.parent ?: normalized
            java.nio.file.Files.isDirectory(normalized.resolve("src/main/res")) ->
                normalized.resolve("src/main/res")
            java.nio.file.Files.isDirectory(normalized.resolve("res")) ->
                normalized.resolve("res")
            else -> normalized
        }
    }

    fun targetFolders(selected: Path): List<String> {
        val existing = existingTargetFolders(selected)
        return (
            existing +
                Density.entries.map { it.resourceDirectory } +
                Density.entries.map { "mipmap-${it.suffix}" }
            ).distinct()
    }

    private fun existingTargetFolders(selected: Path): List<String> {
        val root = resourceRoot(selected)
        return if (java.nio.file.Files.isDirectory(root)) {
            java.nio.file.Files.newDirectoryStream(root).use { children ->
                children
                    .filter { java.nio.file.Files.isDirectory(it) }
                    .mapNotNull { it.fileName?.toString()?.lowercase() }
                    .filter {
                        (it.startsWith("drawable-") || it.startsWith("mipmap-")) &&
                            Density.fromDirectoryName(it) != null
                    }
                    .sorted()
            }
        } else {
            emptyList()
        }
    }

    fun targetFolderForDensity(selected: Path?, density: Density): String {
        val existing = selected?.let(::existingTargetFolders).orEmpty()
        return existing.firstOrNull { it == density.resourceDirectory }
            ?: existing.firstOrNull { Density.fromDirectoryName(it) == density }
            ?: density.resourceDirectory
    }

    fun resolve(
        selected: Path,
        density: Density,
        create: Boolean = true,
        targetFolder: String? = null
    ): Path {
        val normalized = selected.toAbsolutePath().normalize()
        val name = normalized.fileName?.toString()?.lowercase()
        val resourceDir = targetFolder
            ?.trim()
            ?.lowercase()
            ?.takeIf {
                (it.startsWith("drawable-") || it.startsWith("mipmap-")) &&
                    Density.fromDirectoryName(it) == density
            }
            ?: density.resourceDirectory
        val target = when {
            name == resourceDir -> normalized
            name == "drawable" || name == "mipmap" ||
                name?.startsWith("drawable-") == true || name?.startsWith("mipmap-") == true ->
                normalized.parent?.resolve(resourceDir) ?: normalized.resolve(resourceDir)
            name == "res" -> normalized.resolve(resourceDir)
            java.nio.file.Files.isDirectory(normalized.resolve("src/main/res")) -> normalized.resolve("src/main/res").resolve(resourceDir)
            java.nio.file.Files.isDirectory(normalized.resolve("res")) -> normalized.resolve("res").resolve(resourceDir)
            java.nio.file.Files.exists(normalized.resolve("build.gradle")) ||
                java.nio.file.Files.exists(normalized.resolve("build.gradle.kts")) ||
                java.nio.file.Files.isDirectory(normalized.resolve("src")) ->
                normalized.resolve("src/main/res").resolve(resourceDir)
            else -> normalized.resolve(resourceDir)
        }
        if (create) java.nio.file.Files.createDirectories(target)
        return target
    }
}
