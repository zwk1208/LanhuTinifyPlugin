package com.lanhu.tinify

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

object TargetDirectoryStore {
    private const val PROPERTY = "com.lanhu.tinify.target-directory"

    fun get(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(PROPERTY)?.takeIf { it.isNotBlank() }

    fun set(project: Project, path: String) {
        PropertiesComponent.getInstance(project).setValue(PROPERTY, path.trim())
    }

    fun initialPath(cachedPath: String?, projectBasePath: String?): String? =
        cachedPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: projectBasePath?.trim()?.takeIf { it.isNotEmpty() }

    fun initialLocation(project: Project): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()
        return initialPath(get(project), project.basePath)?.let { path ->
            fileSystem.refreshAndFindFileByPath(path)
        }
    }
}
