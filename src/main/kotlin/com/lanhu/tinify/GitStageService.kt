package com.lanhu.tinify

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class GitStageResult(
    val stagedFiles: Int,
    val message: String?
)

internal object GitStageService {
    private val timeout = 10L

    fun stage(startDirectory: Path, files: List<Path>): GitStageResult {
        val candidates = files
            .map { it.toAbsolutePath().normalize() }
            .filter { Files.isRegularFile(it) }
            .distinct()
        if (candidates.isEmpty()) return GitStageResult(0, null)

        val gitRoot = findGitRoot(startDirectory) ?: return GitStageResult(
            0,
            "目标目录不在 Git 仓库中，请手动将生成文件添加到 Git"
        )
        val relativePaths = candidates.mapNotNull { file ->
            if (file.startsWith(gitRoot)) gitRoot.relativize(file).toString() else null
        }
        if (relativePaths.isEmpty()) {
            return GitStageResult(0, "生成文件不在当前 Git 仓库内，请手动添加到 Git")
        }

        val command = mutableListOf("git", "-C", gitRoot.toString(), "add", "--")
        command += relativePaths
        return runGit(command)?.let { output ->
            if (output.exitCode == 0) {
                GitStageResult(relativePaths.size, null)
            } else {
                GitStageResult(0, "Git add 失败：${output.output.ifBlank { "请手动添加生成文件" }}")
            }
        } ?: GitStageResult(0, "无法运行 Git，请手动将生成文件添加到 Git")
    }

    private fun findGitRoot(startDirectory: Path): Path? {
        val directory = startDirectory.toAbsolutePath().normalize()
        if (!Files.isDirectory(directory)) return null
        val result = runGit(listOf("git", "-C", directory.toString(), "rev-parse", "--show-toplevel"))
            ?: return null
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
    }

    private fun runGit(command: List<String>): CommandResult? {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return CommandResult(-1, "Git 命令超时")
            }
            CommandResult(process.exitValue(), output.trim())
        } catch (_: java.io.IOException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CommandResult(-1, "Git 命令被中断")
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}
