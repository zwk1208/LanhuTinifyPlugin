package com.lanhu.tinify

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

internal data class LanhuShareInfo(
    val projectId: String,
    val teamId: String,
    val resolvedUrl: String,
    val requiresPassword: Boolean,
    val imageId: String? = null
)

internal data class LanhuDesignAsset(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val url: String,
    val previewUrl: String? = null,
    val aliases: Set<String> = emptySet(),
    val variants: List<LanhuDesignVariant> = emptyList()
) {
    fun availableVariants(): List<LanhuDesignVariant> =
        variants.filter { it.format.isSupportedBitmapFormat() }.ifEmpty {
            val normalizedUrl = url.lowercase()
            val extension = runCatching { URI.create(url).path.orEmpty() }
                .getOrDefault(url)
                .substringAfterLast('.', "")
                .lowercase()
            listOf(
                LanhuDesignVariant(
                    "原图",
                    url,
                    format = when (extension) {
                        "jpeg" -> "jpg"
                        "png", "jpg", "webp" -> extension
                        else -> when {
                            normalizedUrl.contains("svg") -> "svg"
                            normalizedUrl.contains("webp") -> "webp"
                            normalizedUrl.contains("png") -> "png"
                            normalizedUrl.contains("jpeg") || normalizedUrl.contains("jpg") -> "jpg"
                            else -> "unknown"
                        }
                    }
                )
            ).filter { it.format.isSupportedBitmapFormat() }
        }

    fun fileName(): String {
        val cleaned = name.substringAfterLast('/').ifBlank { "lanhu_$id" }
        return if (cleaned.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
            cleaned
        } else {
            "$cleaned.png"
        }
    }
}

internal data class LanhuDesignVariant(
    val label: String,
    val url: String,
    val sourceDensity: Density? = null,
    val format: String = "unknown"
)

private fun String.isSupportedBitmapFormat(): Boolean =
    lowercase() in setOf("png", "jpg", "jpeg", "webp")

internal data class LanhuDownloadedAsset(
    val fileName: String,
    val bytes: ByteArray,
    val contentType: String?
)

internal object LanhuLinkParser {
    private val urlPattern = Regex(
        """https?://(?:www\.)?lanhuapp\.com/[^\s]+""",
        RegexOption.IGNORE_CASE
    )
    private val invitePattern = Regex("""[?&]sid=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val projectPattern = Regex("""[?&](?:pid|project[_-]?id)=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val teamPattern = Regex("""[?&](?:tid|team[_-]?id)=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val imagePattern = Regex("""[?&](?:image[_-]?id|doc[_-]?id)=([^&#\s]+)""", RegexOption.IGNORE_CASE)

    fun extractUrls(text: String): List<String> =
        urlPattern.findAll(text)
            .map { match ->
                match.value.trimEnd(
                    '.', ',', ';', ':', '，', '。', '；', '：', ')', '）', ']', '】', '}', '》', '"', '\''
                )
            }
            .distinct()
            .toList()

    fun extractUrl(text: String): String? = extractUrls(text).firstOrNull()

    fun inviteId(url: String): String? = invitePattern.find(url)?.groupValues?.get(1)

    fun projectParameters(url: String): Triple<String, String, String?>? {
        val projectId = projectPattern.find(url)?.groupValues?.get(1) ?: return null
        val teamId = teamPattern.find(url)?.groupValues?.get(1) ?: return null
        return Triple(projectId, teamId, imagePattern.find(url)?.groupValues?.get(1))
    }

    fun missingProjectParameters(url: String): List<String> {
        val isProjectUrl = url.contains("/web/", ignoreCase = true) ||
            url.contains("detailDetach", ignoreCase = true)
        if (!isProjectUrl) return emptyList()
        return buildList {
            if (projectPattern.find(url) == null) add("pid 或 project_id")
            if (teamPattern.find(url) == null) add("tid、team_id 或 teamId")
        }
    }
}

internal class LanhuClient(
    private val cookie: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
) {
    private val signedUrls = mutableMapOf<String, String>()

    fun resolveShareLink(url: String): LanhuShareInfo {
        LanhuLinkParser.projectParameters(url)?.let { (projectId, teamId, imageId) ->
            return LanhuShareInfo(
                projectId = projectId,
                teamId = teamId,
                resolvedUrl = url,
                requiresPassword = false,
                imageId = imageId
            )
        }
        val missingProjectParameters = LanhuLinkParser.missingProjectParameters(url)
        if (missingProjectParameters.isNotEmpty()) {
            throw IllegalArgumentException(
                "蓝湖详情地址缺少必要参数：${missingProjectParameters.joinToString("、")}。" +
                    "请复制完整详情地址，或改用包含 sid 的蓝湖分享链接。"
            )
        }

        val sid = LanhuLinkParser.inviteId(url)
            ?: throw IllegalArgumentException("蓝湖链接中没有找到 sid 分享参数")
        val response = postJson(
            "/api/share/url/resolve",
            """{"short_id":"${jsonEscape(sid)}"}""",
            acceptedCodes = setOf(30009, 30010, 30501, 10001, 39996)
        )
        val data = response.getAsJsonObject("data")
        val resolvedUrl = data.string("url").orEmpty()
        val projectId = data.string("project_id").takeUnless { it.isNullOrBlank() }
            ?: data.string("project_ids")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val teamId = data.string("team_id").orEmpty()
        if (projectId.isBlank() || teamId.isBlank() || resolvedUrl.isBlank()) {
            throw IllegalStateException(
                "蓝湖未返回可访问的项目地址，可能需要先登录、加入团队或输入分享密码"
            )
        }
        return LanhuShareInfo(
            projectId = projectId,
            teamId = teamId,
            resolvedUrl = resolvedUrl,
            requiresPassword = data.int("need_pwd") == 1,
            imageId = LanhuLinkParser.projectParameters(resolvedUrl)?.third
        )
    }

    fun listDesigns(share: LanhuShareInfo): List<LanhuDesignAsset> {
        val query = buildString {
            append("project_id=").append(encode(share.projectId))
            append("&team_id=").append(encode(share.teamId))
            append("&dds_status=1&position=1&show_cb_src=1&comment=1")
        }
        val root = getJson("/api/project/images?$query")
        val data = root.getAsJsonObject("data")
        val images = data?.getAsJsonArray("images")
            ?: throw IllegalStateException("蓝湖响应中没有找到设计稿图片列表")
        val assets = images.mapNotNull { element ->
            val image = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val url = image.string("url")
                ?: image.string("image_url")
                ?: image.string("preview_url")
                ?: return@mapNotNull null
            LanhuDesignAsset(
                id = image.string("id") ?: image.string("image_id") ?: url.hashCode().toString(),
                name = image.string("name") ?: "lanhu_design",
                width = image.int("width") ?: 0,
                height = image.int("height") ?: 0,
                url = absoluteUrl(url),
                previewUrl = image.string("preview_url")?.let(::absoluteUrl),
                aliases = setOfNotNull(image.string("id"), image.string("image_id"))
            )
        }.distinctBy { it.id }
        return share.imageId?.let { requestedImageId ->
            assets.filter { it.id == requestedImageId || requestedImageId in it.aliases }.ifEmpty {
                throw IllegalStateException("蓝湖项目中未找到指定页面，可能页面已删除或当前登录态无权访问")
            }
        } ?: assets
    }

    fun listSlices(share: LanhuShareInfo, page: LanhuDesignAsset): List<LanhuDesignAsset> {
        val query = buildString {
            append("dds_status=1")
            append("&image_id=").append(encode(page.id))
            append("&team_id=").append(encode(share.teamId))
            append("&project_id=").append(encode(share.projectId))
            append("&all_versions=0")
        }
        val detail = getJsonWithFallback(
            primaryPath = "/project/image?$query",
            fallbackPath = "/api/project/image?$query"
        )
        val result = detail.getAsJsonObject("result")
            ?: detail.getAsJsonObject("data")?.getAsJsonObject("result")
            ?: detail.getAsJsonObject("data")
            ?: throw IllegalStateException(
                "步骤 3/6 页面详情响应缺少 result/data。请确认 Cookie 对应的团队有权访问该页面。"
            )
        val jsonUrl = findJsonUrl(result)
        if (!jsonUrl.isNullOrBlank()) {
            val document = runCatching { getJsonUrl(jsonUrl) }
                .getOrElse {
                    throw IllegalStateException(
                        "步骤 4/6 页面图层 JSON 下载或解析失败：${safeMessage(it)}。请在蓝湖页面确认切图弹框能正常打开。",
                        it
                    )
                }
            val slices = LanhuSliceParser.parse(document)
            if (slices.isNotEmpty()) return slices
        }
        return runCatching { listDdsSlices(share, page) }
            .mapCatching { slices ->
                if (slices.isEmpty()) {
                    throw IllegalStateException(
                        "DDS Schema 返回成功但没有可导出的图层（可能是切图数据由浏览器运行时生成）"
                    )
                }
                slices
            }
            .getOrElse {
                throw IllegalStateException(
                    "步骤 5/6 未找到可解析的页面切图：页面 JSON 没有导出图层，DDS 备用链路也失败：${safeMessage(it)}。" +
                        "请在蓝湖右侧切图面板确认存在可下载切图。",
                    it
                )
            }
    }

    fun download(
        asset: LanhuDesignAsset,
        variant: LanhuDesignVariant? = null,
        share: LanhuShareInfo? = null
    ): LanhuDownloadedAsset {
        val sourceUrl = variant?.url ?: asset.url
        val response = downloadBytes(sourceUrl, share, "正式下载")
        return LanhuDownloadedAsset(
            fileName = asset.fileName(),
            bytes = response.bytes,
            contentType = response.contentType
        )
    }

    fun downloadPreview(asset: LanhuDesignAsset, share: LanhuShareInfo? = null): ByteArray {
        val previewUrl = asset.variants
            .maxByOrNull { it.sourceDensity?.scale ?: 0f }
            ?.url
            ?: asset.previewUrl
            ?: asset.availableVariants().first().url
        return downloadBytes(previewUrl, share, "预览").bytes
    }

    private data class LanhuBytes(
        val bytes: ByteArray,
        val contentType: String? = null
    )

    private fun downloadBytes(
        sourceUrl: String,
        share: LanhuShareInfo?,
        purpose: String
    ): LanhuBytes {
        decodeImageData(sourceUrl)?.let { return it }
        val direct = sendBytes(sourceUrl)
        if (direct.statusCode() in 200..299) {
            return LanhuBytes(
                bytes = direct.body(),
                contentType = direct.headers().firstValue("Content-Type").orElse(null)
            )
        }
        if (direct.statusCode() !in setOf(401, 403) || share == null) {
            throw IllegalStateException("${purpose}蓝湖图片失败（HTTP ${direct.statusCode()}）。请检查 Cookie 和图片权限。")
        }
        val signedUrl = signedUrls[sourceUrl] ?: signUrl(share, sourceUrl).also {
            signedUrls[sourceUrl] = it
        }
        val signed = sendBytes(signedUrl)
        if (signed.statusCode() !in 200..299) {
            throw IllegalStateException(
                "${purpose}蓝湖签名图片失败（HTTP ${signed.statusCode()}）。请确认当前 Cookie 对应的团队允许导出切图。"
            )
        }
        return LanhuBytes(
            bytes = signed.body(),
            contentType = signed.headers().firstValue("Content-Type").orElse(null)
        )
    }

    private fun sendBytes(url: String): HttpResponse<ByteArray> =
        httpClient.send(
            requestBuilder(url).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )

    private fun decodeImageData(value: String): LanhuBytes? {
        if (value.startsWith("data:image/", ignoreCase = true)) {
            val comma = value.indexOf(',')
            if (comma < 0) throw IllegalStateException("蓝湖切图的内嵌图片数据格式无效")
            val metadata = value.substring(5, comma)
            val payload = value.substring(comma + 1)
            val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
                runCatching { Base64.getMimeDecoder().decode(payload) }
                    .getOrElse { throw IllegalStateException("蓝湖切图的 Base64 图片数据无效") }
            } else {
                java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8)
                    .toByteArray(StandardCharsets.ISO_8859_1)
            }
            return LanhuBytes(bytes, metadata.substringBefore(';'))
        }
        if (value.length < 32 || !value.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(value) }.getOrNull() ?: return null
        return if (looksLikeImage(bytes)) LanhuBytes(bytes) else null
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (
            bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) ||
                bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) ||
                bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46))
            )

    private fun signUrl(share: LanhuShareInfo, url: String): String {
        val body = JsonObject().apply {
            addProperty("team_id", share.teamId)
            addProperty("project_id", share.projectId)
            add("urls", com.google.gson.JsonArray().apply { add(url) })
        }
        val root = postJson("/project/sign_url", body.toString())
        val result = root.get("result")
            ?: root.getAsJsonObject("data")?.get("result")
        val signed = when {
            result?.isJsonArray == true -> result.asJsonArray.firstOrNull()?.signedUrlValue()
            result?.isJsonObject == true -> result.asJsonObject.signedUrlValue()
            result?.isJsonPrimitive == true -> result.asString
            else -> null
        }?.takeIf { it.isNotBlank() }
        return signed ?: throw IllegalStateException("蓝湖资源需要签名，但签名接口未返回下载地址")
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.replace(Regex("(?i)(cookie|authorization|signature|sign)[^\\s:：]*[:：]?\\s*[^\\s]+"), "$1=***")
            ?.take(240)
            ?.ifBlank { "未知错误" }
            ?: "未知错误"

    private fun getJson(path: String): JsonObject {
        val request = requestBuilder("$BASE_URL$path").GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return try {
            parseResponse(response)
        } catch (error: Throwable) {
            throw IllegalStateException("请求 ${path.substringBefore('?')} 失败：${safeMessage(error)}", error)
        }
    }

    private fun getJsonWithFallback(primaryPath: String, fallbackPath: String): JsonObject {
        return try {
            getJson(primaryPath)
        } catch (primaryFailure: Throwable) {
            try {
                getJson(fallbackPath)
            } catch (_: LanhuHttpException) {
                throw primaryFailure
            } catch (_: Throwable) {
                throw primaryFailure
            }
        }
    }

    private fun findJsonUrl(element: JsonElement): String? {
        if (element.isJsonArray) {
            return element.asJsonArray.asSequence()
                .mapNotNull(::findJsonUrl)
                .firstOrNull()
        }
        if (!element.isJsonObject) return null
        val objectValue = element.asJsonObject
        listOf("json_url", "jsonUrl", "jsonurl", "jsonURL", "d2c_url", "d2cUrl").forEach { key ->
            objectValue.string(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return objectValue.entrySet().asSequence()
            .mapNotNull { (_, child) -> findJsonUrl(child) }
            .firstOrNull()
    }

    private fun listDdsSlices(
        share: LanhuShareInfo,
        page: LanhuDesignAsset
    ): List<LanhuDesignAsset> {
        val query = buildString {
            append("project_id=").append(encode(share.projectId))
            append("&team_id=").append(encode(share.teamId))
            append("&img_limit=500&detach=1")
        }
        val multiInfo = getJsonWithFallback(
            primaryPath = "/api/project/multi_info?$query",
            fallbackPath = "/project/multi_info?$query"
        )
        val pageIds = setOf(page.id) + page.aliases
        val versionId = findLatestVersion(multiInfo, pageIds)
            ?: throw IllegalStateException("蓝湖页面没有可用的设计稿版本")
        val schemaUrlResponse = getJsonAbsolute(
            "https://dds.lanhuapp.com/api/dds/image/store_schema_revise" +
                "?version_id=${encode(versionId)}",
            authorization = "Basic dW5kZWZpbmVkOg==",
            referer = "https://dds.lanhuapp.com/"
        )
        val schemaUrl = findResourceUrl(schemaUrlResponse)
            ?: throw IllegalStateException("蓝湖未返回页面图层 Schema 地址")
        return LanhuSliceParser.parse(getJsonUrl(schemaUrl))
    }

    private fun findLatestVersion(element: JsonElement, pageIds: Set<String>): String? {
        if (element.isJsonArray) {
            return element.asJsonArray.asSequence()
                .mapNotNull { findLatestVersion(it, pageIds) }
                .firstOrNull()
        }
        if (!element.isJsonObject) return null
        val objectValue = element.asJsonObject
        val id = objectValue.string("id") ?: objectValue.string("image_id")
        if (id in pageIds) {
            objectValue.string("latest_version")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            objectValue.string("version_id")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return objectValue.entrySet().asSequence()
            .mapNotNull { (_, child) -> findLatestVersion(child, pageIds) }
            .firstOrNull()
    }

    private fun findResourceUrl(element: JsonElement): String? {
        if (element.isJsonArray) {
            return element.asJsonArray.asSequence()
                .mapNotNull(::findResourceUrl)
                .firstOrNull()
        }
        if (!element.isJsonObject) return null
        val objectValue = element.asJsonObject
        listOf("data_resource_url", "dataResourceUrl", "schema_url", "schemaUrl").forEach { key ->
            objectValue.string(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return objectValue.entrySet().asSequence()
            .mapNotNull { (_, child) -> findResourceUrl(child) }
            .firstOrNull()
    }

    private fun getJsonUrl(url: String): JsonElement {
        return getJsonAbsolute(url)
    }

    private fun getJsonAbsolute(
        url: String,
        authorization: String? = null,
        referer: String = "https://lanhuapp.com/web/"
    ): JsonElement {
        val request = requestBuilder(absoluteUrl(url))
            .header("Referer", referer)
            .apply { authorization?.let { header("Authorization", it) } }
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "读取蓝湖页面图层失败（HTTP ${response.statusCode()}，可能需要有效 Cookie 或资源签名）"
            )
        }
        return runCatching { JsonParser.parseString(response.body()) }
            .getOrElse { throw IllegalStateException("蓝湖页面图层数据无法解析") }
    }

    private fun postJson(path: String, body: String, acceptedCodes: Set<Int> = emptySet()): JsonObject {
        val request = requestBuilder("$BASE_URL$path")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return parseResponse(response, acceptedCodes)
    }

    private fun requestBuilder(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://lanhuapp.com/web/")
            .header("request-from", "web")
            .header("real-path", "/item/project/detailDetach")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }

    private fun parseResponse(response: HttpResponse<String>, acceptedCodes: Set<Int> = emptySet()): JsonObject {
        if (response.statusCode() !in 200..299) {
            throw LanhuHttpException(response.statusCode())
        }
        val root = runCatching { JsonParser.parseString(response.body()).asJsonObject }
            .getOrElse { throw IllegalStateException("蓝湖返回了无法解析的数据") }
        val code = root.int("code")
        if (code != null && code != 0 && code !in acceptedCodes) {
            val message = root.string("message") ?: root.string("msg") ?: "未知错误"
            throw IllegalStateException("蓝湖 API：$message（错误码 $code）")
        }
        return root
    }

    private fun absoluteUrl(url: String): String = lanhuAbsoluteUrl(url)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.let { element ->
            runCatching { element.asInt }.getOrNull()
        }

    private fun JsonObject.signedUrlValue(): String? =
        listOf("url", "signed_url", "signedUrl", "download_url", "downloadUrl")
            .asSequence()
            .mapNotNull { key -> get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } }
            .firstOrNull()

    private fun JsonElement.signedUrlValue(): String? =
        when {
            isJsonObject -> asJsonObject.signedUrlValue()
            isJsonPrimitive -> runCatching { asString }.getOrNull()
            else -> null
        }

    companion object {
        private const val BASE_URL = "https://lanhuapp.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    }

    private class LanhuHttpException(val statusCode: Int) :
        IllegalStateException("蓝湖请求失败（HTTP $statusCode）")
}

internal object LanhuSliceParser {
            fun parse(document: JsonElement): List<LanhuDesignAsset> {
                val isFigma = document.isFigmaDocument()
                val documentDensity = document.sourceDensity()
                val result = mutableListOf<LanhuDesignAsset>()
                collect(document, result, HashSet(), isFigma, documentDensity, allowLegacyDds = false)
                if (result.isNotEmpty() || isFigma) return result.distinctBy { it.id }

                // Old Sketch/DDS documents may expose bitmap layers without an explicit
                // export marker. Only bitmap-typed DDS nodes are accepted as a fallback;
                // generic ddsImage fields are image fills and must not become slices.
                val legacyResult = mutableListOf<LanhuDesignAsset>()
                collect(document, legacyResult, HashSet(), isFigma, documentDensity, allowLegacyDds = true)
                return legacyResult.distinctBy { it.id }
            }

            private fun collect(
                element: JsonElement,
                result: MutableList<LanhuDesignAsset>,
                seen: MutableSet<String>,
                isFigma: Boolean,
                sourceDensity: Density?,
                allowLegacyDds: Boolean
            ) {
                when {
                    element.isJsonArray -> element.asJsonArray.forEach {
                        collect(it, result, seen, isFigma, sourceDensity, allowLegacyDds)
                    }
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                        val text = element.asString.trim()
                        if (text.startsWith("{") || text.startsWith("[")) {
                            runCatching { JsonParser.parseString(text) }
                                .getOrNull()
                                ?.let { collect(it, result, seen, isFigma, sourceDensity, allowLegacyDds) }
                        }
                    }
                    !element.isJsonObject -> Unit
                    else -> {
                        val value = element.asJsonObject
                        val variants = variants(value, sourceDensity)
                        val hasExplicitExportMarker =
                            value.flagValue("exportable") ||
                                value.flagValue("hasExportImage") ||
                                value.flagValue("isExportable") ||
                                value.flagValue("is_exportable") ||
                                value.flagValue("isSlice") ||
                                value.flagValue("is_slice") ||
                                value.stringValue("type")?.lowercase() in setOf("asset", "slice", "export")
                        val hasDensityVariants = hasDensityVariants(value.get("images"))
                        val hasPhotoshopAssetMarker =
                            value.flagValue("isAsset") && hasDensityVariants
                        val hasImagePayload = value.objectValue("image")?.let {
                            it.stringValue("imageUrl") != null ||
                                it.stringValue("image_url") != null
                        } == true
                        val hasFigmaExportPayload =
                            isFigma && value.flagValue("hasExportImage") && hasImagePayload
                        val hasTypedBitmapPayload =
                            !isFigma && isBitmapLayer(value) && hasImagePayload
                        val hasStructuredExportPayload = hasDensityVariants
                        val hasLegacyDdsPayload =
                            allowLegacyDds && !isFigma &&
                                isBitmapLayer(value) &&
                                value.objectValue("ddsImage")?.stringValue("imageUrl") != null
                        val isDownloadableLayer =
                            hasExplicitExportMarker ||
                                hasPhotoshopAssetMarker ||
                                hasFigmaExportPayload ||
                                hasTypedBitmapPayload ||
                                hasStructuredExportPayload ||
                                hasLegacyDdsPayload
                        val hasLayerIdentity =
                            value.has("web_id") || value.has("id") || value.has("name")
                        val bitmapVariants = variants.filter { it.format.isSupportedBitmapFormat() }
                        val hasInlineImage = bitmapVariants.any { isInlineImage(it.url) }
                        if (isDownloadableLayer && bitmapVariants.isNotEmpty() &&
                            (hasLayerIdentity || hasInlineImage)
                        ) {
                            bitmapVariants.firstOrNull()?.let { first ->
                                val id = value.stringValue("web_id")
                                    ?: value.stringValue("id")
                                    ?: first.url
                                if (seen.add(id)) {
                                    result += LanhuDesignAsset(
                                        id = id,
                                        name = value.stringValue("name") ?: "lanhu_slice_$id",
                                        width = value.sliceDimension("width"),
                                        height = value.sliceDimension("height"),
                                        url = first.url,
                                        previewUrl = first.url,
                                        variants = bitmapVariants
                                    )
                                }
                            }
                        }
                        value.entrySet().forEach { (_, child) ->
                            collect(child, result, seen, isFigma, sourceDensity, allowLegacyDds)
                        }
                    }
                }
            }

            private fun variants(layer: JsonObject, documentDensity: Density?): List<LanhuDesignVariant> {
                val effectiveSourceDensity = documentDensity ?: layer.sourceDensity()
                val sources = buildList<JsonElement> {
                    add(layer)
                    listOf("images", "image", "ddsImage").forEach { key ->
                        layer.get(key)?.takeUnless { it.isJsonNull }?.let { element ->
                            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                                val text = element.asString.trim()
                                if (text.startsWith("{") || text.startsWith("[")) {
                                    runCatching { JsonParser.parseString(text) }.getOrNull()?.let(::add)
                                }
                            } else {
                                add(element)
                            }
                        }
                    }
                }
                val preferredKeys = listOf(
                    "png_xxxhd", "png_xxxhdpi", "png_xxhd", "png_xxhdpi",
                    "png_xhd", "png_xhdpi", "png_hd", "png_hdpi", "png_md", "png_mdpi",
                    "png_4x",
                    "png_3x", "png_2x", "png_1x", "jpg_xxxhd", "jpg_xxxhdpi",
                    "jpg_xxhd", "jpg_xxhdpi", "jpg_xhd", "jpg_xhdpi", "jpg_hd", "jpg_hdpi",
                    "jpg_md", "jpg_mdpi", "jpg_4x", "jpg_3x", "jpg_2x", "jpg_1x",
                    "webp_xxxhd", "webp_xxxhdpi", "webp_xxhd", "webp_xxhdpi",
                    "webp_xhd", "webp_xhdpi", "webp_hd", "webp_hdpi",
                    "webp_md", "webp_mdpi", "webp_4x", "webp_3x", "webp_2x", "webp_1x", "webp",
                    "format_url", "imageUrl", "image_url",
                    "format_base64", "bitmap", "url", "orgUrl", "svgUrl", "svg"
                )
                val candidates = preferredKeys.flatMap { key ->
                    sources.mapNotNull { source ->
                        source.takeIf { it.isJsonObject }?.asJsonObject
                            ?.stringValue(key)?.takeIf { isDownloadableValue(key, it) }?.let {
                            LanhuDesignVariant(
                                label(key),
                                lanhuAbsoluteUrl(it),
                                densityFromVariantKey(key) ?: densityFromUrl(it),
                                formatFor(key, it)
                            )
                        }
                    }
                }
                val discovered = sources.flatMap { source ->
                    discoverVariants(source, effectiveSourceDensity)
                }
                val explicitDensityVariants = (candidates + discovered).any {
                    it.label != "原图" &&
                        it.sourceDensity != null &&
                        it.format != "unknown"
                }
                val generated = if (explicitDensityVariants) {
                    emptyList()
                } else {
                    sources
                        .filter { it.isJsonObject }
                        .flatMap {
                            it.asJsonObject.generatedDensityVariants(
                                sourceDensity = effectiveSourceDensity ?: Density.XXHDPI,
                                logicalWidth = layer.sliceDimension("width"),
                                logicalHeight = layer.sliceDimension("height")
                            )
                        }
                }
                val generatedFormats = if (generated.isEmpty()) {
                    sources
                        .filter { it.isJsonObject }
                        .flatMap {
                            it.asJsonObject.generatedFormatVariants()
                        }
                } else {
                    emptyList()
                }
                val generatedUrls = generated.mapTo(mutableSetOf()) { it.url }
                val generatedFormatUrls = generatedFormats.mapTo(mutableSetOf()) { it.url }
                val generatedFormatSources = generatedFormats
                    .mapTo(mutableSetOf()) { it.url.substringBefore("?x-oss-process").substringBefore("&x-oss-process") }
                val fallback = (candidates + discovered).filter {
                    it.url !in generatedUrls && it.url !in generatedFormatUrls &&
                        it.url !in generatedFormatSources &&
                        (generated.isEmpty() || it.label != "原图")
                }
                return (generated + generatedFormats + fallback).distinctBy { it.url }
            }

            private fun JsonObject.generatedDensityVariants(
                sourceDensity: Density?,
                logicalWidth: Int,
                logicalHeight: Int
            ): List<LanhuDesignVariant> {
                if (sourceDensity == null) return emptyList()
                if (logicalWidth <= 0 || logicalHeight <= 0) return emptyList()
                val baseUrl = listOf("imageUrl", "image_url", "url", "orgUrl")
                    .asSequence()
                    .mapNotNull { stringValue(it) }
                    .firstOrNull { isLanhuHttpImage(it) }
                    ?: return emptyList()
                return listOf("png", "webp").flatMap { format ->
                    Density.entries.map { density ->
                        LanhuDesignVariant(
                            labelForVariant("imageUrl", format, density),
                            buildLanhuScaleUrl(
                                baseUrl,
                                logicalWidth,
                                logicalHeight,
                                density,
                                format
                            ),
                            density,
                            format
                        )
                    }
                }
            }

            private fun JsonObject.generatedFormatVariants(): List<LanhuDesignVariant> {
                val baseUrl = listOf("imageUrl", "image_url", "url", "orgUrl")
                    .asSequence()
                    .mapNotNull { stringValue(it) }
                    .firstOrNull { isLanhuHttpImage(it) }
                    ?: return emptyList()
                return listOf("png", "webp").map { format ->
                    LanhuDesignVariant(
                        labelForVariant("imageUrl", format, null),
                        buildLanhuFormatUrl(baseUrl, format),
                        null,
                        format
                    )
                }
            }

            private fun buildLanhuScaleUrl(
                imageUrl: String,
                logicalWidth: Int,
                logicalHeight: Int,
                targetDensity: Density,
                format: String
            ): String {
                val targetWidth = (logicalWidth * targetDensity.scale).toInt().coerceAtLeast(1)
                val targetHeight = (logicalHeight * targetDensity.scale).toInt().coerceAtLeast(1)
                val separator = if (imageUrl.contains('?')) "&" else "?"
                return "$imageUrl${separator}x-oss-process=image/resize,w_${targetWidth},h_${targetHeight}/format,$format"
            }

            private fun buildLanhuFormatUrl(imageUrl: String, format: String): String {
                val separator = if (imageUrl.contains('?')) "&" else "?"
                return "$imageUrl${separator}x-oss-process=image/format,$format"
            }

            private fun isLanhuHttpImage(url: String): Boolean =
                runCatching {
                    URI.create(url).host?.lowercase()?.contains("lanhu") == true
                }.getOrDefault(false)

            private fun hasDensityVariants(element: JsonElement?): Boolean {
                if (element == null || element.isJsonNull) return false
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    val text = element.asString.trim()
                    if (text.startsWith("{") || text.startsWith("[")) {
                        return runCatching { hasDensityVariants(JsonParser.parseString(text)) }
                            .getOrDefault(false)
                    }
                    return false
                }
                if (element.isJsonArray) {
                    return element.asJsonArray.any(::hasDensityVariants)
                }
                if (!element.isJsonObject) return false
                val value = element.asJsonObject
                val namedVariant = listOf("name", "key", "format", "type")
                    .asSequence()
                    .mapNotNull { value.stringValue(it) }
                    .any(::looksLikeVariantKey)
                val directUrl = listOf("url", "imageUrl", "image_url", "value")
                    .asSequence()
                    .mapNotNull { value.stringValue(it) }
                    .firstOrNull()
                if (namedVariant && directUrl != null) return true
                if (value.entrySet().any { (key, child) ->
                        looksLikeVariantKey(key) &&
                            child.stringContent()?.let { isDownloadableValue(key, it) } == true
                    }) {
                    return true
                }
                return value.entrySet().any { (_, child) -> hasDensityVariants(child) }
            }

            private fun discoverVariants(
                source: JsonElement,
                documentDensity: Density?
            ): List<LanhuDesignVariant> {
                val result = mutableListOf<LanhuDesignVariant>()

                fun addVariant(key: String, value: String) {
                    if (!isDownloadableValue(key, value)) return
                    val density = densityFromVariantKey(key) ?: densityFromUrl(value)
                    val format = formatFor(key, value)
                    if (density == null && format == "unknown") return
                    result += LanhuDesignVariant(
                        labelForVariant(key, format, density),
                        lanhuAbsoluteUrl(value),
                        density,
                        format
                    )
                }

                fun visit(element: JsonElement, path: List<String>) {
                    when {
                        element.isJsonArray -> element.asJsonArray.forEachIndexed { index, child ->
                            visit(child, path + index.toString())
                        }
                        element.isJsonObject -> {
                            val value = element.asJsonObject
                            val objectHint = listOf("name", "key", "format", "type")
                                .asSequence()
                                .mapNotNull { value.stringValue(it) }
                                .firstOrNull { looksLikeVariantKey(it) }
                            val objectUrl = listOf("url", "imageUrl", "image_url", "value")
                                .asSequence()
                                .mapNotNull { value.stringValue(it) }
                                .firstOrNull()
                            val pathHint = path.asReversed().firstOrNull(::looksLikeVariantKey)
                            val hint = objectHint ?: pathHint
                            if (hint != null && objectUrl != null) {
                                addVariant(hint, objectUrl)
                            }
                            value.entrySet().forEach { (key, child) ->
                                visit(child, path + key)
                            }
                        }
                        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                            val value = element.asString.trim()
                            val hint = path.asReversed().firstOrNull(::looksLikeVariantKey)
                            if (hint != null) addVariant(hint, value)
                        }
                    }
                }

                visit(source, emptyList())
                return result
            }

            private fun looksLikeVariantKey(key: String): Boolean {
                val normalized = key.lowercase()
                if (normalized in setOf("1", "2", "3", "4")) return true
                return densityFromVariantKey(key) != null ||
                    normalized.matches(Regex(".*(?:png|webp|jpg|jpeg).*"))
            }

            private fun labelForVariant(
                key: String,
                format: String,
                density: Density?
            ): String {
                val formatLabel = when (format.lowercase()) {
                    "png" -> "PNG"
                    "webp" -> "WebP"
                    "jpg", "jpeg" -> "JPG"
                    else -> ""
                }
                val densityLabel = when (density) {
                    Density.MDPI -> "md"
                    Density.HDPI -> "hd"
                    Density.XHDPI -> "xhd"
                    Density.XXHDPI -> "xxhd"
                    Density.XXXHDPI -> "xxxhd"
                    null -> null
                }
                return when {
                    formatLabel.isNotBlank() && densityLabel != null -> "$formatLabel $densityLabel"
                    formatLabel.isNotBlank() -> "$formatLabel 原图"
                    else -> label(key)
                }
            }

            private fun isBitmapLayer(layer: JsonObject): Boolean {
                val type = listOf("type", "layerType", "ddsType")
                    .asSequence()
                    .mapNotNull { layer.stringValue(it) }
                    .firstOrNull()
                    ?.lowercase()
                    ?: return false
                return type == "bitmaplayer" ||
                    type == "bitmap" ||
                    type == "image" ||
                    type == "imagelayer" ||
                    type == "asset" ||
                    type == "slice"
            }

            private fun isDownloadableValue(key: String, value: String): Boolean =
                value.startsWith("http://") || value.startsWith("https://") ||
                    value.startsWith("//") || value.startsWith("/") ||
                    value.startsWith("data:image/", ignoreCase = true) ||
                    (key.equals("bitmap", ignoreCase = true) ||
                        key.equals("format_base64", ignoreCase = true)) &&
                    value.length >= 32 &&
                    value.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))

            private fun JsonElement.stringContent(): String? =
                when {
                    isJsonPrimitive -> runCatching { asString }.getOrNull()
                    isJsonObject -> asJsonObject.stringValue("url")
                    else -> null
                }

            private fun isInlineImage(value: String): Boolean =
                value.startsWith("data:image/", ignoreCase = true) ||
                    (value.length >= 32 && value.matches(Regex("[A-Za-z0-9+/=\\r\\n]+")))

            private fun label(key: String): String =
                when (key.lowercase()) {
                    "png_xxxhd" -> "PNG xxxhd"
                    "png_xxxhdpi" -> "PNG xxxhd"
                    "png_xxhd" -> "PNG xxhd"
                    "png_xxhdpi" -> "PNG xxhd"
                    "png_xhd" -> "PNG xhd"
                    "png_xhdpi" -> "PNG xhd"
                    "png_hd" -> "PNG hd"
                    "png_hdpi" -> "PNG hd"
                    "png_md" -> "PNG md"
                    "png_mdpi" -> "PNG md"
                    "png_4x" -> "PNG 4x"
                    "png_3x" -> "PNG 3x"
                    "png_2x" -> "PNG 2x"
                    "png_1x" -> "PNG 1x"
                    "webp_xxxhd" -> "WebP xxxhd"
                    "webp_xxxhdpi" -> "WebP xxxhd"
                    "webp_xxhd" -> "WebP xxhd"
                    "webp_xxhdpi" -> "WebP xxhd"
                    "webp_xhd" -> "WebP xhd"
                    "webp_xhdpi" -> "WebP xhd"
                    "webp_hd" -> "WebP hd"
                    "webp_hdpi" -> "WebP hd"
                    "webp_md" -> "WebP md"
                    "webp_mdpi" -> "WebP md"
                    "webp_4x" -> "WebP 4x"
                    "webp_3x" -> "WebP 3x"
                    "webp_2x" -> "WebP 2x"
                    "webp_1x" -> "WebP 1x"
                    "webp" -> "WebP"
                    "imageurl", "image_url", "format_url", "url", "orgurl" -> "原图"
                    else -> key
                }

            private fun formatFor(key: String, value: String): String {
                val normalizedKey = key.lowercase()
                if (normalizedKey.startsWith("png")) return "png"
                if (normalizedKey.startsWith("webp")) return "webp"
                if (normalizedKey.startsWith("jpg") || normalizedKey.startsWith("jpeg")) return "jpg"
                if (normalizedKey.contains("svg") ||
                    value.startsWith("data:image/svg", ignoreCase = true)
                ) return "svg"
                if (value.startsWith("data:image/webp", ignoreCase = true)) return "webp"
                if (value.startsWith("data:image/png", ignoreCase = true)) return "png"
                val normalizedValue = value.lowercase()
                if (normalizedValue.contains("png")) return "png"
                if (normalizedValue.contains("webp")) return "webp"
                if (normalizedValue.contains("jpeg") || normalizedValue.contains("jpg")) return "jpg"
                val path = runCatching { URI.create(value).path.orEmpty() }.getOrDefault(value)
                return when (path.substringAfterLast('.', "").lowercase()) {
                    "png" -> "png"
                    "webp" -> "webp"
                    "jpg", "jpeg" -> "jpg"
                    "svg" -> "svg"
                    else -> "unknown"
                }
            }

            private fun sourceDensity(key: String): Density? =
                when (key.lowercase()) {
                    "png_xxxhd", "png_xxxhdpi", "jpg_xxxhd", "jpg_xxxhdpi",
                    "jpeg_xxxhd", "jpeg_xxxhdpi", "webp_xxxhd", "webp_xxxhdpi" -> Density.XXXHDPI
                    "png_xxhd", "png_xxhdpi", "jpg_xxhd", "jpg_xxhdpi",
                    "jpeg_xxhd", "jpeg_xxhdpi", "webp_xxhd", "webp_xxhdpi" -> Density.XXHDPI
                    "png_xhd", "png_xhdpi", "jpg_xhd", "jpg_xhdpi",
                    "jpeg_xhd", "jpeg_xhdpi", "webp_xhd", "webp_xhdpi" -> Density.XHDPI
                    "png_hd", "png_hdpi", "jpg_hd", "jpg_hdpi",
                    "jpeg_hd", "jpeg_hdpi", "webp_hd", "webp_hdpi" -> Density.HDPI
                    "png_md", "png_mdpi", "jpg_md", "jpg_mdpi",
                    "jpeg_md", "jpeg_mdpi", "webp_md", "webp_mdpi",
                    "png", "jpg", "jpeg", "webp" -> Density.MDPI
                    "png_4x", "jpg_4x", "jpeg_4x", "webp_4x" -> Density.XXXHDPI
                    "png_3x", "jpg_3x", "jpeg_3x", "webp_3x" -> Density.XXHDPI
                    "png_2x", "jpg_2x", "jpeg_2x", "webp_2x" -> Density.XHDPI
                    "png_1x", "jpg_1x", "jpeg_1x", "webp_1x" -> Density.MDPI
                    else -> null
                }

            private fun densityFromVariantKey(key: String): Density? {
                sourceDensity(key)?.let { return it }
                val normalized = key.lowercase()
                return when {
                    normalized.contains("xxxhdpi") || normalized.contains("xxxhd") ||
                        Regex("""(?:^|[_-])4x(?:$|[_-])""").containsMatchIn(normalized) ->
                        Density.XXXHDPI
                    normalized.contains("xxhdpi") || normalized.contains("xxhd") ||
                        Regex("""(?:^|[_-])3x(?:$|[_-])""").containsMatchIn(normalized) ->
                        Density.XXHDPI
                    normalized.contains("xhdpi") || normalized.contains("xhd") ||
                        Regex("""(?:^|[_-])2x(?:$|[_-])""").containsMatchIn(normalized) ->
                        Density.XHDPI
                    normalized.contains("hdpi") || normalized.matches(Regex(".*(?:^|[_-])hd(?:$|[_-]).*")) ||
                        Regex("""(?:^|[_-])1\.5x(?:$|[_-])""").containsMatchIn(normalized) ->
                        Density.HDPI
                    normalized.contains("mdpi") || normalized.matches(Regex(".*(?:^|[_-])md(?:$|[_-]).*")) ||
                        Regex("""(?:^|[_-])1x(?:$|[_-])""").containsMatchIn(normalized) ->
                        Density.MDPI
                    normalized.matches(Regex("^(?:png|webp|jpg|jpeg)$")) -> Density.MDPI
                    else -> null
                }
            }

            private fun densityFromUrl(url: String): Density? {
                val lower = url.lowercase()
                return when {
                    Regex("""(?:@|[_-])4x(?:[._/?]|$)""").containsMatchIn(lower) -> Density.XXXHDPI
                    Regex("""(?:@|[_-])3x(?:[._/?]|$)""").containsMatchIn(lower) -> Density.XXHDPI
                    Regex("""(?:@|[_-])2x(?:[._/?]|$)""").containsMatchIn(lower) -> Density.XHDPI
                    Regex("""(?:@|[_-])1\.5x(?:[._/?]|$)""").containsMatchIn(lower) -> Density.HDPI
                    Regex("""(?:@|[_-])1x(?:[._/?]|$)""").containsMatchIn(lower) -> Density.MDPI
                    else -> null
                }
            }

            private fun JsonElement.isFigmaDocument(): Boolean {
                if (isJsonArray) return asJsonArray.any { it.isFigmaDocument() }
                if (isJsonPrimitive && asJsonPrimitive.isString) {
                    val text = asString.trim()
                    if (text.startsWith("{") || text.startsWith("[")) {
                        return runCatching { JsonParser.parseString(text) }
                            .getOrNull()
                            ?.isFigmaDocument() == true
                    }
                }
                if (!isJsonObject) return false
                val value = asJsonObject
                val hostName = value.objectValue("host")?.stringValue("name")
                val origin = value.objectValue("artboard")?.stringValue("origin")
                    ?: value.stringValue("origin")
                if (hostName.equals("figma", true) || origin.equals("figma", true)) return true
                return value.entrySet().any { (_, child) -> child.isFigmaDocument() }
            }

            private fun JsonElement.sourceDensity(): Density? {
                if (isJsonPrimitive && asJsonPrimitive.isString) {
                    val text = asString.trim()
                    if (text.startsWith("{") || text.startsWith("[")) {
                        return runCatching { JsonParser.parseString(text) }
                            .getOrNull()
                            ?.sourceDensity()
                    }
                }
                if (!isJsonObject) return null
                val value = asJsonObject
                val scale = value.stringValue("sliceScale")?.toFloatOrNull()
                    ?: value.stringValue("exportScale")?.toFloatOrNull()
                    ?: value.objectValue("meta")?.stringValue("sliceScale")?.toFloatOrNull()
                return densityForScale(scale)
                    ?: value.entrySet().asSequence()
                        .mapNotNull { (_, child) -> child.sourceDensity() }
                        .firstOrNull()
            }

            private fun densityForScale(scale: Float?): Density? =
                when {
                    scale == null -> null
                    scale <= 1.0f -> Density.MDPI
                    scale <= 1.75f -> Density.HDPI
                    scale <= 2.5f -> Density.XHDPI
                    scale <= 3.5f -> Density.XXHDPI
                    else -> Density.XXXHDPI
                }

            private fun JsonObject.stringValue(name: String): String? {
                val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
                if (value.isJsonPrimitive) return runCatching { value.asString }.getOrNull()
                if (value.isJsonObject) {
                    val nested = value.asJsonObject
                    return listOf("url", "imageUrl", "image_url", "value")
                        .asSequence()
                        .mapNotNull { nested.stringValue(it) }
                        .firstOrNull()
                }
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val text = value.asString.trim()
                    if (text.startsWith("{")) {
                        return runCatching { JsonParser.parseString(text).asJsonObject.stringValue(name) }
                            .getOrNull()
                    }
                }
                return null
            }

            private fun JsonObject.intValue(name: String): Int? =
                get(name)?.takeUnless { it.isJsonNull }?.let {
                    runCatching { it.asInt }.getOrNull()
                        ?: runCatching {
                            Regex("""[-+]?(?:\d+(?:\.\d+)?|\.\d+)""")
                                .find(it.asString)
                                ?.value
                                ?.toDoubleOrNull()
                                ?.toInt()
                        }.getOrNull()
                }

            private fun JsonObject.sliceDimension(name: String): Int {
                val aliases = if (name == "width") {
                    listOf("width", "w", "pixelWidth", "originalWidth", "designWidth")
                } else {
                    listOf("height", "h", "pixelHeight", "originalHeight", "designHeight")
                }
                val direct = aliases.asSequence()
                    .mapNotNull { intValue(it) }
                    .firstOrNull()
                    ?: intValue("frame_$name")
                if (direct != null) return direct
                listOf("frame", "bounds", "ddsOriginFrame", "image", "ddsImage")
                    .asSequence()
                    .mapNotNull { objectValue(it) }
                    .mapNotNull { frame ->
                        aliases.asSequence().mapNotNull { frame.intValue(it) }.firstOrNull()
                            ?: frame.intValue("frame_$name")
                    }
                    .firstOrNull()
                    ?.let { return it }
                val size = listOf("size", "frameSize", "dimensions")
                    .asSequence()
                    .mapNotNull { key ->
                        stringValue(key)
                            ?: objectValue(key)?.let { value ->
                                "${value.intValue("width") ?: 0}x${value.intValue("height") ?: 0}"
                            }
                    }
                    .firstOrNull { it.contains('x', true) }
                    ?: listOf("ddsImage", "image")
                        .asSequence()
                        .mapNotNull { objectValue(it)?.stringValue("size") }
                        .firstOrNull()
                val parts = size?.split('x', 'X', '×', '*', ',')
                return parts?.getOrNull(if (name == "width") 0 else 1)
                    ?.let {
                        Regex("""[-+]?(?:\d+(?:\.\d+)?|\.\d+)""")
                            .find(it)
                            ?.value
                            ?.toDoubleOrNull()
                            ?.toInt()
                    } ?: 0
            }

            private fun JsonObject.objectValue(name: String): JsonObject? {
                val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
                if (value.isJsonObject) return value.asJsonObject
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val text = value.asString.trim()
                    if (text.startsWith("{")) {
                        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    }
                }
                return null
            }

            private fun JsonObject.flagValue(name: String): Boolean =
                get(name)?.takeUnless { it.isJsonNull }?.let {
                    runCatching {
                        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                            it.asString.equals("true", true) || it.asString == "1"
                        } else {
                            it.asBoolean
                        }
                    }.getOrDefault(false)
                } ?: false
        }

internal fun lanhuAbsoluteUrl(url: String): String =
    when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://lanhuapp.com$url"
        else -> url
    }
