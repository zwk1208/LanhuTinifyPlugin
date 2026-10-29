package com.lanhu.tinify

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class TinifyClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
    private val endpoint: URI = URI.create("https://api.tinify.com/shrink"),
    private val timeout: Duration = Duration.ofSeconds(60),
    private val maxRetries: Int = 2
) {
    data class CompressedImage(val source: ImportedImage, val bytes: ByteArray)
    data class CompressionFailure(val source: ImportedImage, val error: Throwable)
    data class CompressionResult(
        val compressed: List<CompressedImage>,
        val failures: List<CompressionFailure>
    )

    fun compress(
        images: List<ImportedImage>,
        apiKey: String,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): List<CompressedImage> {
        val result = compressWithFailures(images, apiKey, onProgress, isCancelled)
        result.failures.firstOrNull()?.let { throw it.error }
        return result.compressed
    }

    /**
     * Processes every image independently so a quota error or a transient API failure does
     * not discard successful results or hide which rows can be written as originals.
     */
    fun compressWithFailures(
        images: List<ImportedImage>,
        apiKey: String,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): CompressionResult {
        val candidates = images.filter { it.importToProject && it.compress }
        if (candidates.isEmpty()) return CompressionResult(emptyList(), emptyList())
        require(apiKey.isNotBlank()) { "必须填写 Tinify API 密钥" }
        val compressed = mutableListOf<CompressedImage>()
        val failures = mutableListOf<CompressionFailure>()
        val total = candidates.size
        var completed = 0
        for (batch in BatchSplitter.split(candidates)) {
            for (image in batch) {
                if (isCancelled()) return CompressionResult(compressed, failures)
                try {
                    compressed += CompressedImage(image, compressOne(image.bytes, apiKey, isCancelled))
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += CompressionFailure(image, failure)
                } finally {
                    completed++
                    onProgress(completed, total)
                }
            }
        }
        return CompressionResult(compressed, failures)
    }

    private fun compressOne(bytes: ByteArray, apiKey: String, isCancelled: () -> Boolean): ByteArray {
        val auth = Base64.getEncoder().encodeToString(("api:$apiKey").toByteArray(Charsets.UTF_8))
        var lastFailure: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            if (isCancelled()) throw java.util.concurrent.CancellationException("压缩已取消")
            try {
                val request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Basic $auth")
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() !in 200..299) {
                    val failure = TinifyException("Tinify 上传失败（HTTP ${response.statusCode()}）", response.statusCode())
                    if (attempt < maxRetries && (response.statusCode() >= 500 || response.statusCode() == 429)) {
                        lastFailure = failure
                        Thread.sleep(250L * (attempt + 1))
                        return@repeat
                    }
                    throw failure
                }
                val location = response.headers().firstValue("Location").orElseThrow {
                    TinifyException("Tinify 响应未包含下载地址", response.statusCode())
                }
                val downloadRequest = HttpRequest.newBuilder(URI.create(location))
                    .timeout(timeout)
                    .header("Authorization", "Basic $auth")
                    .GET()
                    .build()
                val download = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray())
                if (download.statusCode() !in 200..299) {
                    val failure = TinifyException("Tinify 下载失败（HTTP ${download.statusCode()}）", download.statusCode())
                    if (attempt < maxRetries && (download.statusCode() >= 500 || download.statusCode() == 429)) {
                        lastFailure = failure
                        Thread.sleep(250L * (attempt + 1))
                        return@repeat
                    }
                    throw failure
                }
                return download.body()
            } catch (e: java.io.IOException) {
                lastFailure = e
                if (attempt < maxRetries) Thread.sleep(250L * (attempt + 1)) else throw e
            }
        }
        throw lastFailure ?: IllegalStateException("Tinify 请求失败")
    }

    class TinifyException(message: String, val statusCode: Int) : RuntimeException(message)
}
