package com.lanhu.tinify

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

object ImageImportService {
    private val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")

    data class ImportResult(
        val images: List<ImportedImage>,
        val skippedEntries: List<String>
    )

    fun importFiles(paths: List<Path>, fallbackDensity: Density = Density.XXHDPI): List<ImportedImage> =
        importFilesDetailed(paths, fallbackDensity).images

    fun importFilesDetailed(
        paths: List<Path>,
        fallbackDensity: Density = Density.XXHDPI,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): ImportResult {
        val images = mutableListOf<ImportedImage>()
        val skipped = mutableListOf<String>()
        paths.forEachIndexed { index, path ->
            if (isCancelled()) return@forEachIndexed
            val result = if (Files.isRegularFile(path) && path.fileName.toString().lowercase().endsWith(".zip")) {
                importZipDetailed(path, fallbackDensity, onProgress, isCancelled)
            } else {
                importFileDetailed(path, fallbackDensity)
            }
            images += result.images
            skipped += result.skippedEntries
            if (!path.fileName.toString().lowercase().endsWith(".zip")) {
                onProgress(index + 1, paths.size)
            }
        }
        return ImportResult(uniqueTargetNames(images), skipped)
    }

    fun importFile(path: Path, fallbackDensity: Density = Density.XXHDPI): List<ImportedImage> {
        return importFileDetailed(path, fallbackDensity).images
    }

    private fun importFileDetailed(path: Path, fallbackDensity: Density): ImportResult {
        val name = path.fileName.toString()
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in supportedExtensions) return ImportResult(emptyList(), listOf(name))
        val bytes = Files.readAllBytes(path)
        val image = ImageMetadata.read(bytes, name, fallbackDensity)
        return if (image != null) ImportResult(listOf(image), emptyList())
        else ImportResult(emptyList(), listOf(name))
    }

    fun importZip(path: Path, fallbackDensity: Density = Density.XXHDPI): List<ImportedImage> {
        return uniqueTargetNames(importZipDetailed(path, fallbackDensity).images)
    }

    private fun uniqueTargetNames(images: List<ImportedImage>): List<ImportedImage> {
        val used = mutableSetOf<String>()
        return images.map { image ->
            val base = image.targetName
            var candidate = base
            var suffix = 2
            while (!used.add(candidate)) {
                candidate = "${base}_$suffix"
                suffix++
            }
            if (candidate != base) image.targetName = candidate
            image
        }
    }

    private fun importZipDetailed(
        path: Path,
        fallbackDensity: Density,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): ImportResult {
        val images = mutableListOf<ImportedImage>()
        val skipped = mutableListOf<String>()
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries()
            val total = zip.size()
            var completed = 0
            while (entries.hasMoreElements()) {
                if (isCancelled()) break
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val extension = entry.name.substringAfterLast('.', "").lowercase()
                    if (extension !in supportedExtensions) {
                        if (extension == "avif") skipped += entry.name
                        completed++
                        onProgress(completed, total)
                        continue
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val density = entry.name.split('/').dropLast(1).asReversed()
                        .asSequence().mapNotNull(Density::fromDirectoryName).firstOrNull() ?: fallbackDensity
                    val image = ImageMetadata.read(bytes, entry.name, density)
                    if (image == null) skipped += entry.name else images += image
                }
                completed++
                onProgress(completed, total)
            }
        }
        return ImportResult(images, skipped)
    }
}
