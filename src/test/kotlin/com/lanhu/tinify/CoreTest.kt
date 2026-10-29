package com.lanhu.tinify

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ResourceNameValidatorTest {
    @Test fun acceptsAndroidResourceNamesAndSupportedFormats() {
        assertNull(ResourceNameValidator.validate("ic_launcher_2", "png"))
        assertTrue(ResourceNameValidator.isValid("photo01", "jpeg"))
        assertTrue(ResourceNameValidator.isValid("icon", "webp"))
    }

    @Test fun rejectsInvalidNamesAndFormats() {
        assertFalse(ResourceNameValidator.isValid("Icon", "png"))
        assertFalse(ResourceNameValidator.isValid("has-dash", "png"))
        assertFalse(ResourceNameValidator.isValid("empty", "avif"))
        assertTrue(ResourceNameValidator.isValidTargetFolder("mipmap-xxhdpi"))
        assertFalse(ResourceNameValidator.isValidTargetFolder("drawable-any"))
        assertTrue(ResourceNameValidator.isValidTargetFolder("drawable-xxhdpi"))
        assertTrue(ResourceNameValidator.isValidTargetFolder("drawable-ar-xxhdpi"))
    }
}

class ResourceNameGeneratorTest {
    @Test
    fun generatesSafePinyinNamesWithDefaultPrefix() {
        assertEquals("ic_asset", ResourceNameGenerator.generate("图标.png"))
        assertEquals("ic_dian_ge", ResourceNameGenerator.generate("点歌.PNG"))
        assertEquals("ic_warning_banner", ResourceNameGenerator.generate("警告横幅.jpg"))
        assertEquals("ic_existing_name", ResourceNameGenerator.generate("Existing Name.png"))
    }

    @Test
    fun abbreviatesIconPrefixInOriginalNames() {
        assertEquals("ic_home", ResourceNameGenerator.generate("icon_home.png"))
        assertEquals("ic_warning_banner", ResourceNameGenerator.generate("ICON-Warning-Banner.webp"))
        assertEquals("ic", ResourceNameGenerator.generate("icon.png"))
    }

    @Test
    fun doesNotDuplicatePrefixWhenAppliedRepeatedly() {
        val renamed = ResourceNameGenerator.applyPrefix("ic_tu_biao", "ic_")
        assertEquals("ic_tu_biao", ResourceNameGenerator.applyPrefix(renamed, "ic_"))
        assertEquals("asset_ic_tu_biao", ResourceNameGenerator.applyPrefix(renamed, "asset_"))
    }
}

class DensityMappingTest {
    @Test fun displaysDensityNamesInLowercase() {
        assertEquals("mdpi", Density.MDPI.toString())
        assertEquals("xxhdpi", Density.XXHDPI.toString())
    }

    @Test fun mapsAndroidDrawableDirectories() {
        assertEquals(Density.MDPI, Density.fromDirectoryName("drawable-mdpi"))
        assertEquals(Density.XXHDPI, Density.fromDirectoryName("DRAWABLE-XXHDPI"))
        assertEquals(Density.XXHDPI, Density.fromDirectoryName("mipmap-xxhdpi"))
        assertEquals(Density.XXHDPI, Density.fromDirectoryName("xxhdpi"))
        assertEquals(Density.XXHDPI, Density.fromDirectoryName("drawable-night-xxhdpi-v21"))
        assertEquals(null, Density.fromDirectoryName("drawable-any"))
    }
}

class ImageImportTest {
    @Test
    fun importsMultipleImagesFromBareDensityDirectory() {
        val zip = Files.createTempFile("lanhu-import", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                repeat(2) { index ->
                    val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
                    val bytes = java.io.ByteArrayOutputStream().also {
                        ImageIO.write(image, "png", it)
                    }.toByteArray()
                    output.putNextEntry(ZipEntry("xxhdpi/image_$index.png"))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            val result = ImageImportService.importFilesDetailed(listOf(zip), Density.MDPI)
            assertEquals(2, result.images.size)
            assertTrue(result.images.all { it.density == Density.XXHDPI })
            assertTrue(result.skippedEntries.isEmpty())
        } finally {
            Files.deleteIfExists(zip)
        }
    }
}

class ImageImportFallbackTest {
    @Test
    fun usesXxhdpiForZipEntriesWithoutRecognizedDensity() {
        val zip = Files.createTempFile("lanhu-import-fallback", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
                val bytes = java.io.ByteArrayOutputStream().also {
                    ImageIO.write(image, "png", it)
                }.toByteArray()
                output.putNextEntry(ZipEntry("assets/icon.png"))
                output.write(bytes)
                output.closeEntry()
            }
            val image = ImageImportService.importZip(zip).single()
            assertEquals(Density.XXHDPI, image.density)
            assertEquals(Density.XXHDPI, image.targetDensity)
            assertEquals("drawable-xxhdpi", image.targetResourceFolder)
        } finally {
            Files.deleteIfExists(zip)
        }
    }
}

class ResourceDirectoryResolverTest {
    @Test
    fun mapsResAndDrawableDirectoriesToSelectedOutputFolder() {
        val module = Files.createTempDirectory("lanhu-module")
        try {
            val res = Files.createDirectories(module.resolve("src/main/res"))
            val drawable = Files.createDirectories(res.resolve("drawable-xxhdpi"))
            val localizedDrawable = Files.createDirectories(res.resolve("drawable-ar-xxhdpi"))
            Files.createDirectories(res.resolve("mipmap-hdpi"))
            assertEquals(res.resolve("drawable-mdpi"), ResourceDirectoryResolver.resolve(res, Density.MDPI, false))
            assertEquals(res.resolve("drawable-hdpi"), ResourceDirectoryResolver.resolve(drawable, Density.HDPI, false))
            assertEquals(res.resolve("drawable-xhdpi"), ResourceDirectoryResolver.resolve(module, Density.XHDPI, false))
            assertEquals(
                localizedDrawable,
                ResourceDirectoryResolver.resolve(
                    localizedDrawable,
                    Density.XXHDPI,
                    create = false,
                    targetFolder = "drawable-ar-xxhdpi"
                )
            )
            assertEquals(
                localizedDrawable,
                ResourceDirectoryResolver.resolve(
                    res,
                    Density.XXHDPI,
                    create = false,
                    targetFolder = "drawable-ar-xxhdpi"
                )
            )
            assertEquals(
                res.resolve("mipmap-xxhdpi"),
                ResourceDirectoryResolver.resolve(
                    res,
                    Density.XXHDPI,
                    create = false,
                    targetFolder = "mipmap-xxhdpi"
                )
            )
            assertEquals(
                "mipmap-hdpi",
                ResourceDirectoryResolver.targetFolderForDensity(res, Density.HDPI)
            )
        } finally {
            module.toFile().deleteRecursively()
        }
    }
}

class BatchSplitterTest {
    @Test fun splitsIntoAtMostTwentyItems() {
        val batches = BatchSplitter.split((1..45).toList())
        assertEquals(listOf(20, 20, 5), batches.map { it.size })
        assertEquals((1..45).toList(), batches.flatten())
    }
}

class ImageSizeFormatterTest {
    @Test
    fun formatsBytesWithHumanReadableUnits() {
        assertEquals("512 B", ImageSizeFormatter.format(512))
        assertEquals("1.0 KB", ImageSizeFormatter.format(1024))
        assertEquals("1.0 MB", ImageSizeFormatter.format(1024L * 1024))
    }

}

class ImageDensityScalerTest {
    @Test
    fun infersSourceDensityFromLogicalAndDownloadedPixels() {
        val source = BufferedImage(96, 72, BufferedImage.TYPE_INT_ARGB)
        val encoded = ByteArrayOutputStream().use {
            assertTrue(ImageIO.write(source, "png", it))
            it.toByteArray()
        }

        assertEquals(
            Density.XXHDPI,
            ImageDensityScaler.inferSourceDensity(encoded, 32, 24)
        )
    }

    @Test
    fun scalesXxxhdpiExportToMdpiPixels() {
        val source = BufferedImage(40, 24, BufferedImage.TYPE_INT_ARGB)
        val encoded = ByteArrayOutputStream().use {
            assertTrue(ImageIO.write(source, "png", it))
            it.toByteArray()
        }

        val scaled = ImageDensityScaler.scale(
            encoded,
            "png",
            Density.XXXHDPI,
            Density.MDPI
        )
        val result = ByteArrayInputStream(scaled).use { ImageIO.read(it) }

        assertEquals(10, result.width)
        assertEquals(6, result.height)
    }
}

class ImportedImageStateTest {
    @Test
    fun freshImportResetsMutableRowState() {
        val image = ImportedImage("图标.png", byteArrayOf(1, 2), "png", 1, 2, Density.XXHDPI)
        image.targetName = "edited_name"
        image.targetDensity = Density.MDPI
        image.importToProject = false
        image.compress = false

        val fresh = image.freshImport()

        assertEquals("ic_asset", fresh.targetName)
        assertEquals(Density.XXHDPI, fresh.targetDensity)
        assertTrue(fresh.importToProject)
        assertTrue(fresh.compress)
        assertTrue(fresh.bytes !== image.bytes)
    }
}

class ImageTableModelTest {
    @Test
    fun keepsManualRowFolderWhenTheSameResourceRootIsRefreshed() {
        val root = Files.createTempDirectory("lanhu-res-root")
        try {
            Files.createDirectories(root.resolve("drawable-xxhdpi"))
            Files.createDirectories(root.resolve("mipmap-xxhdpi"))
            val model = TinifyPanel.ImageTableModel()
            model.addAll(
                listOf(ImportedImage("icon.png", byteArrayOf(1), "png", 1, 1, Density.XXHDPI))
            )
            model.setTargetRoot(root)
            model.setValueAt("mipmap-xxhdpi", 0, 6)
            model.setTargetRoot(root)
            assertEquals("mipmap-xxhdpi", model.items.single().targetResourceFolder)
            model.setTargetResourceFolder("drawable-xxhdpi")
            assertEquals("drawable-xxhdpi", model.items.single().targetResourceFolder)
            assertEquals(Density.XXHDPI, model.items.single().targetDensity)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun booleanColumnsAreEditableAndRemainIndependent() {
        val model = TinifyPanel.ImageTableModel()
        val image = ImportedImage("icon.png", byteArrayOf(1), "png", 1, 1, Density.MDPI)
        model.addAll(listOf(image))
        val editor = TinifyPanel.SingleClickBooleanCellEditor()

        assertEquals(
            listOf(
                "预览",
                "原文件名",
                "原始大小",
                "尺寸",
                "格式",
                "目标名称（可编辑）",
                "目标资源目录（可编辑）",
                "导入工程",
                "需要压缩"
            ),
            (0 until model.columnCount).map(model::getColumnName)
        )
        assertEquals(java.lang.Boolean::class.java, model.getColumnClass(7))
        assertEquals(java.lang.Boolean::class.java, model.getColumnClass(8))
        assertEquals(1, editor.clickCountToStart)
        assertFalse(model.isCellEditable(0, 0))
        assertTrue(model.isCellEditable(0, 5))
        assertTrue(model.isCellEditable(0, 6))
        assertTrue(model.isCellEditable(0, 7))
        assertTrue(model.isCellEditable(0, 8))

        model.setValueAt("renamed_icon", 0, 5)
        assertEquals("renamed_icon", model.items[0].targetName)

        model.setValueAt(false, 0, 7)
        assertFalse(model.items[0].importToProject)
        assertFalse(model.items[0].compress)
        assertFalse(model.isCellEditable(0, 8))
        assertEquals(false, model.getValueAt(0, 8))
        model.setValueAt(true, 0, 7)
        assertTrue(model.isCellEditable(0, 8))
        model.setValueAt(false, 0, 8)
        assertFalse(model.items[0].compress)
    }
}

class ImageProcessingPlanTest {
    @Test
    fun routesImportedRowsToCompressionOrOriginalBytes() {
        val compressed = ImportedImage("compressed.png", byteArrayOf(1), "png", 1, 1, Density.MDPI)
        val original = ImportedImage("original.png", byteArrayOf(2), "png", 1, 1, Density.MDPI).apply {
            compress = false
        }
        val excluded = ImportedImage("excluded.png", byteArrayOf(3), "png", 1, 1, Density.MDPI).apply {
            importToProject = false
        }

        val plan = ImageProcessingPlan.from(listOf(compressed, original, excluded))

        assertEquals(listOf(compressed, original), plan.imported)
        assertEquals(listOf(compressed), plan.compressed)
        assertEquals(listOf(original), plan.originals)
    }

    @Test
    fun excludesRowsFromAllOutputWhenImportIsDisabled() {
        val excluded = ImportedImage("excluded.png", byteArrayOf(1), "png", 1, 1, Density.MDPI).apply {
            importToProject = false
            compress = false
        }

        val plan = ImageProcessingPlan.from(listOf(excluded))

        assertTrue(plan.imported.isEmpty())
        assertTrue(plan.compressed.isEmpty())
        assertTrue(plan.originals.isEmpty())
    }
}

class TinifyRoutingTest {
    @Test
    fun nonCompressRowsDoNotRequireAnApiKey() {
        val image = ImportedImage("original.png", byteArrayOf(1), "png", 1, 1, Density.MDPI).apply {
            compress = false
        }

        val result = TinifyClient().compressWithFailures(listOf(image), "")

        assertTrue(result.compressed.isEmpty())
        assertTrue(result.failures.isEmpty())
    }
}

class TargetDirectoryStoreTest {
    @Test
    fun prefersCachedPathAndFallsBackToProjectRoot() {
        assertEquals("D:\\cached", TargetDirectoryStore.initialPath(" D:\\cached ", "D:\\project"))
        assertEquals("D:\\project", TargetDirectoryStore.initialPath("", "D:\\project"))
    }
}

class LanhuLinkParserTest {
    @Test
    fun extractsInviteAndProjectParameters() {
        val invite = "https://lanhuapp.com/link/#/invite?sid=lx0xjXin"
        assertEquals("lx0xjXin", LanhuLinkParser.inviteId(invite))

        val project = "https://lanhuapp.com/web/#/item/project/detailDetach?tid=team-1&pid=project-1&image_id=image-1"
        assertEquals(
            Triple("project-1", "team-1", "image-1"),
            LanhuLinkParser.projectParameters(project)
        )

        val detail = "https://lanhuapp.com/web/#/item/project/detailDetach?" +
            "pid=project-1&image_id=image-1&tid=team-1&project_id=project-1&fromEditor=true&type=image"
        assertEquals(
            Triple("project-1", "team-1", "image-1"),
            LanhuLinkParser.projectParameters(detail)
        )
        val camelCaseDetail =
            "https://lanhuapp.com/web/#/item/project/detailDetach?type=image" +
                "&pid=project-2&teamId=team-2&project_id=project-2&image_id=image-2"
        assertEquals(
            Triple("project-2", "team-2", "image-2"),
            LanhuLinkParser.projectParameters(camelCaseDetail)
        )
        assertEquals(
            listOf("tid、team_id 或 teamId"),
            LanhuLinkParser.missingProjectParameters(
                "https://lanhuapp.com/web/#/item/project/detailDetach?pid=project-2"
            )
        )
        assertEquals(
            emptyList(),
            LanhuLinkParser.missingProjectParameters(
                "https://lanhuapp.com/link/#/invite?sid=share-1"
            )
        )
    }

    @Test
    fun extractsUrlFromLanhuCopiedShareText() {
        val copiedText = "https://lanhuapp.com/link/#/invite?sid=lXNhPcxa " +
            "分享人: Olamet-安卓-郑文凯 团队名称: 时间在线-海外的团队 " +
            "相关项目: Olamet 2.0 链接有效期: 20261009~20261023"
        assertEquals(
            "https://lanhuapp.com/link/#/invite?sid=lXNhPcxa",
            LanhuLinkParser.extractUrl(copiedText)
        )
        assertEquals(
            "https://lanhuapp.com/link/#/invite?sid=lXNhPcxa",
            LanhuLinkParser.extractUrl("  https://lanhuapp.com/link/#/invite?sid=lXNhPcxa  ")
        )
    }

    @Test
    fun extractsDistinctUrlsForBatchImport() {
        val text = """
            https://lanhuapp.com/link/#/invite?sid=first
            https://lanhuapp.com/link/#/invite?sid=second
            https://lanhuapp.com/link/#/invite?sid=first
        """.trimIndent()
        assertEquals(
            listOf(
                "https://lanhuapp.com/link/#/invite?sid=first",
                "https://lanhuapp.com/link/#/invite?sid=second"
            ),
            LanhuLinkParser.extractUrls(text)
        )
    }

    @Test
    fun addsSupportedExtensionWhenLanhuNameHasNone() {
        val asset = LanhuDesignAsset("id-1", "登录页", 1080, 1920, "https://example.test/image")
        assertEquals("登录页.png", asset.fileName())
        val sketchAsset = LanhuDesignAsset(
            "id-1b",
            "图标",
            24,
            24,
            "https://cdn.lanhuapp.com/com/SKETCHPNG3739abc"
        )
        assertEquals("png", sketchAsset.availableVariants().single().format)
    }

    @Test
    fun normalizesJpegVariantFormatToJpg() {
        val asset = LanhuDesignAsset("id-2", "图标", 24, 24, "https://example.test/icon.jpeg")
        assertEquals("jpg", asset.availableVariants().single().format)
    }

    @Test
    fun recognizesExtensionlessSketchPngUrlAndOffersFormatFallbackWithoutSvg() {
        val document = JsonParser.parseString(
            """
            {
              "id": "icon",
              "name": "图标",
              "isAsset": true,
              "hasExportImage": true,
              "image": {
                "imageUrl": "https://cdn.lanhuapp.com/com/SKETCHPNG3739abc"
              },
              "svgUrl": "https://cdn.lanhuapp.com/com/SKETCHSVG3739abc"
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertEquals(listOf("png", "webp"), variants.map { it.format })
        assertEquals(listOf("PNG 原图", "WebP 原图"), variants.map { it.label })
    }
}

class LanhuSliceParserTest {
    @Test
    fun extractsNestedAssetsAndAllSupportedResolutionUrls() {
        val document = JsonParser.parseString(
            """
            {
              "layers": [
                {
                  "web_id": "modern",
                  "name": "图标",
                  "isAsset": true,
                  "width": 48,
                  "height": 48,
                  "images": {
                    "png_xxxhd": "https://cdn.test/icon-3.png",
                    "png_xhd": "https://cdn.test/icon-2.png"
                  }
                },
                {
                  "id": "legacy",
                  "name": "旧图标",
                  "exportable": "1",
                  "frame": {"width": 24, "height": 24},
                  "image": {
                    "imageUrl": "https://cdn.test/legacy.png"
                  }
                },
                {
                  "name": "容器",
                  "url": "https://cdn.test/not-a-slice.png"
                }
              ]
            }
            """.trimIndent()
        )

        val slices = LanhuSliceParser.parse(document)

        assertEquals(listOf("图标", "旧图标"), slices.map { it.name })
        assertEquals(2, slices.size)
        assertEquals(2, slices.first().availableVariants().size)
        assertEquals("PNG xxxhd", slices.first().availableVariants().first().label)
        assertEquals("https://cdn.test/legacy.png", slices[1].url)
        assertEquals("24×24", "${slices[1].width}×${slices[1].height}")
    }

    @Test
    fun ignoresUnsupportedOrUnmarkedLayerWithoutImagePayload() {
        val document = JsonParser.parseString(
            """{"layers":[{"name":"容器","url":"https://cdn.test/page.png"}]}"""
        )

        assertTrue(LanhuSliceParser.parse(document).isEmpty())
    }

    @Test
    fun parsesJsonDocumentWrappedAsString() {
        val document = JsonParser.parseString(
            """{"data":"{\"layers\":[{\"id\":\"wrapped\",\"name\":\"图标\",\"hasExportImage\":true,\"image\":{\"imageUrl\":\"https://cdn.test/wrapped.png\"}}]}"}"""
        )

        assertEquals(listOf("图标"), LanhuSliceParser.parse(document).map { it.name })
    }

    @Test
    fun parsesEmbeddedBitmapPayloadUsedByLanhuPlDocuments() {
        val document = JsonParser.parseString(
            """
            {
              "info": [
                {
                  "web_id": "embedded",
                  "name": "内嵌图标",
                  "hasExportImage": true,
                  "image": {
                    "bitmap": "data:image/png;base64,iVBORw0KGgo="
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val slices = LanhuSliceParser.parse(document)

        assertEquals(listOf("内嵌图标"), slices.map { it.name })
        assertTrue(slices.single().url.startsWith("data:image/png;base64,"))
    }

    @Test
    fun ignoresRegularImageLayerWithoutExportMarker() {
        val document = JsonParser.parseString(
            """{"layers":[{"id":"photo","name":"普通图片","image":{"imageUrl":"https://cdn.test/photo.png"}}]}"""
        )

        assertTrue(LanhuSliceParser.parse(document).isEmpty())
    }

    @Test
    fun ignoresFigmaImageFillAndKeepsOnlyExportedBitmapLayer() {
        val document = JsonParser.parseString(
            """
            {
              "meta": {"host": {"name": "figma"}},
              "layers": [
                {
                  "id": "fill",
                  "name": "图片填充",
                  "type": "shapeLayer",
                  "ddsImage": {"imageUrl": "https://cdn.test/fill.png"}
                },
                {
                  "id": "slice",
                  "name": "导出图标",
                  "type": "bitmapLayer",
                  "hasExportImage": true,
                  "image": {"imageUrl": "https://cdn.test/icon.png"}
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("导出图标"), LanhuSliceParser.parse(document).map { it.name })
    }

    @Test
    fun prefersVariantUrlScaleOverDocumentScale() {
        val document = JsonParser.parseString(
            """
            {
              "sliceScale": 2,
              "layers": [
                {
                  "id": "icon",
                  "name": "图标",
                  "type": "bitmapLayer",
                  "image": {"imageUrl": "https://cdn.test/icon@3x.png"}
                }
              ]
            }
            """.trimIndent()
        )

        val variant = LanhuSliceParser.parse(document).single().availableVariants().single()

        assertEquals(Density.XXHDPI, variant.sourceDensity)
    }

    @Test
    fun parsesWebpVariantsAlongsidePngVariants() {
        val document = JsonParser.parseString(
            """
            {
              "id": "icon",
              "name": "图标",
              "isAsset": true,
              "images": {
                "png_xxhd": "https://cdn.test/icon.png",
                "webp_xxhd": "https://cdn.test/icon.webp"
              }
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertTrue(variants.any { it.format == "png" && it.sourceDensity == Density.XXHDPI })
        assertTrue(variants.any { it.format == "webp" && it.sourceDensity == Density.XXHDPI })
    }

    @Test
    fun parsesDpiNamedHighResolutionVariantsBeforeFallbackUrl() {
        val document = JsonParser.parseString(
            """
            {
              "id": "icon",
              "name": "图标",
              "image": {"imageUrl": "https://cdn.test/icon-low.png"},
              "images": {
                "png_xxxhdpi": "https://cdn.test/icon-4x.png",
                "png_mdpi": "https://cdn.test/icon-1x.png"
              }
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertEquals("PNG xxxhd", variants.first().label)
        assertEquals("https://cdn.test/icon-4x.png", variants.first().url)
        assertEquals(Density.XXXHDPI, variants.first().sourceDensity)
    }

    @Test
    fun parsesHighResolutionUrlWrappedInStringifiedImagesObject() {
        val document = JsonParser.parseString(
            """
            {
              "id": "icon",
              "name": "图标",
              "images": "{\"png_xxxhd\": {\"url\": \"https://cdn.test/icon-4x.png\"}}",
              "image": {"imageUrl": "https://cdn.test/icon-low.png"}
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertEquals("https://cdn.test/icon-4x.png", variants.first().url)
        assertEquals(Density.XXXHDPI, variants.first().sourceDensity)
    }

    @Test
    fun parsesResolutionVariantsStoredAsImageArray() {
        val document = JsonParser.parseString(
            """
            {
              "id": "icon",
              "name": "图标",
              "isAsset": true,
              "images": [
                {"name": "png_xxxhdpi", "url": "https://cdn.test/icon-4x.png"},
                {"name": "png_mdpi", "url": "https://cdn.test/icon-1x.png"}
              ]
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertEquals(listOf("PNG xxxhd", "PNG md"), variants.map { it.label })
        assertEquals(listOf(Density.XXXHDPI, Density.MDPI), variants.map { it.sourceDensity })
    }

    @Test
    fun generatesDensityVariantsFromLanhuOssImageUrl() {
        val document = JsonParser.parseString(
            """
            {
              "sliceScale": 3,
              "layers": [
                {
                  "id": "icon",
                  "name": "图标",
                  "hasExportImage": true,
                  "width": 30,
                  "height": 30,
                  "image": {
                    "imageUrl": "https://alipic.lanhuapp.com/icon.png"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertEquals(
            listOf(
                "PNG md", "PNG hd", "PNG xhd", "PNG xxhd", "PNG xxxhd",
                "WebP md", "WebP hd", "WebP xhd", "WebP xxhd", "WebP xxxhd"
            ),
            variants.map { it.label }
        )
        assertEquals(Density.XXHDPI, variants[3].sourceDensity)
        assertEquals(
            "https://alipic.lanhuapp.com/icon.png?x-oss-process=image/resize,w_30,h_30/format,png",
            variants.first().url
        )
        assertEquals(
            "https://alipic.lanhuapp.com/icon.png?x-oss-process=image/resize,w_90,h_90/format,png",
            variants[3].url
        )
        assertEquals(
            "https://alipic.lanhuapp.com/icon.png?x-oss-process=image/resize,w_90,h_90/format,webp",
            variants[8].url
        )
    }

    @Test
    fun generatesDensityVariantsWhenLanhuDimensionsContainUnits() {
        val document = JsonParser.parseString(
            """
            {
              "sliceScale": 3,
              "layers": [
                {
                  "id": "icon",
                  "name": "带单位尺寸",
                  "hasExportImage": true,
                  "frame": {"width": "30dp", "height": "30dp"},
                  "image": {"imageUrl": "https://alipic.lanhuapp.com/icon.png"}
                }
              ]
            }
            """.trimIndent()
        )

        val variants = LanhuSliceParser.parse(document).single().availableVariants()

        assertEquals(10, variants.size)
        assertEquals("PNG xxhd", variants[3].label)
        assertEquals(
            "https://alipic.lanhuapp.com/icon.png?x-oss-process=image/resize,w_90,h_90/format,png",
            variants[3].url
        )
    }
}

class GitStageServiceTest {
    @Test
    fun stagesGeneratedFilesWithoutCreatingACommit() {
        val repository = Files.createTempDirectory("lanhu-git-stage")
        try {
            val init = ProcessBuilder("git", "-C", repository.toString(), "init")
                .redirectErrorStream(true)
                .start()
            assertEquals(0, init.waitFor())

            val output = repository.resolve("src/main/res/drawable-xxhdpi/icon.png")
            Files.createDirectories(output.parent)
            Files.write(output, byteArrayOf(1, 2, 3))

            val result = GitStageService.stage(repository, listOf(output))

            assertEquals(1, result.stagedFiles)
            val staged = ProcessBuilder("git", "-C", repository.toString(), "diff", "--cached", "--name-only")
                .redirectErrorStream(true)
                .start()
            val stagedNames = staged.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, staged.waitFor())
            assertTrue(stagedNames.contains("src/main/res/drawable-xxhdpi/icon.png"))
        } finally {
            repository.toFile().deleteRecursively()
        }
    }
}
