package com.psyche.kelivo.workspace

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

internal data class WorkspaceDocumentRoot(val id: String, val name: String, val directory: File)

/** A parsed document ID. Virtual categories have no host path of their own. */
internal sealed interface DocumentTarget {
    data object Root : DocumentTarget
    data class Category(val id: String) : DocumentTarget
    data class Workspace(val workspaceId: String, val relativePath: String) : DocumentTarget
    data class Session(val conversationId: String, val relativePath: String) : DocumentTarget
    data class Output(val conversationId: String, val relativePath: String) : DocumentTarget
    data class Rootfs(val relativePath: String) : DocumentTarget
}

/** Document IDs contain logical paths, never arbitrary host paths. */
internal object WorkspaceDocumentPaths {
    const val ROOT = "workspaces"

    const val CATEGORY_WORKSPACES = "root/workspaces"
    const val CATEGORY_OUTPUTS = "root/outputs"
    const val CATEGORY_SESSIONS = "root/sessions"
    const val CATEGORY_ROOTFS = "root/rootfs"

    val CATEGORIES = listOf(
        CATEGORY_WORKSPACES,
        CATEGORY_OUTPUTS,
        CATEGORY_SESSIONS,
        CATEGORY_ROOTFS,
    )

    private const val WORKSPACE_PREFIX = "workspace/"
    private const val SESSION_PREFIX = "session/"
    private const val OUTPUT_PREFIX = "output/"
    private const val ROOTFS_PREFIX = "rootfs/"

    fun categoryTitle(id: String): String = when (id) {
        CATEGORY_WORKSPACES -> "工作区"
        CATEGORY_OUTPUTS -> "聊天输出"
        CATEGORY_SESSIONS -> "会话"
        CATEGORY_ROOTFS -> "rootfs"
        else -> throw FileNotFoundException("Unknown category")
    }

    fun workspacesDirectory(appData: File): File = File(appData, "workspaces")

    fun sessionsDirectory(appData: File): File = File(appData, "sessions")

    fun rootfsDirectory(appData: File): File = File(appData, "environment/rootfs")

    fun validId(id: String): Boolean = id.isNotEmpty() && id != "." && id != ".." &&
        id.none { it == '/' || it == '\\' || it == '\u0000' }

    fun documentId(workspaceId: String, relativePath: String = ""): String =
        prefixedId(WORKSPACE_PREFIX, workspaceId, relativePath)

    fun sessionDocumentId(conversationId: String, relativePath: String = ""): String =
        prefixedId(SESSION_PREFIX, conversationId, relativePath)

    fun outputDocumentId(conversationId: String, relativePath: String = ""): String =
        prefixedId(OUTPUT_PREFIX, conversationId, relativePath)

    fun rootfsDocumentId(relativePath: String = ""): String =
        if (relativePath.isEmpty()) ROOTFS_PREFIX.dropLast(1) else "$ROOTFS_PREFIX$relativePath"

    private fun prefixedId(prefix: String, id: String, relativePath: String): String =
        "$prefix$id" + if (relativePath.isEmpty()) "" else "/$relativePath"

    fun parse(documentId: String): DocumentTarget? {
        if (documentId == ROOT) return DocumentTarget.Root
        if (documentId in CATEGORIES) return DocumentTarget.Category(documentId)
        return when {
            documentId.startsWith(WORKSPACE_PREFIX) -> {
                val (id, relative) = splitId(documentId, WORKSPACE_PREFIX) ?: return null
                DocumentTarget.Workspace(id, relative)
            }
            documentId.startsWith(SESSION_PREFIX) -> {
                val (id, relative) = splitId(documentId, SESSION_PREFIX) ?: return null
                DocumentTarget.Session(id, relative)
            }
            documentId.startsWith(OUTPUT_PREFIX) -> {
                val (id, relative) = splitId(documentId, OUTPUT_PREFIX) ?: return null
                DocumentTarget.Output(id, relative)
            }
            documentId.startsWith(ROOTFS_PREFIX) -> {
                val relative = documentId.removePrefix(ROOTFS_PREFIX)
                if (!validRelativePath(relative)) return null
                DocumentTarget.Rootfs(relative)
            }
            else -> null
        }
    }

    private fun splitId(documentId: String, prefix: String): Pair<String, String>? {
        val parts = documentId.removePrefix(prefix).split('/', limit = 2)
        val id = parts[0]
        if (!validId(id)) return null
        val relative = parts.getOrElse(1) { "" }
        if (!validRelativePath(relative)) return null
        return id to relative
    }

    fun managedDirectory(appData: File, id: String): File {
        if (!validId(id)) throw FileNotFoundException("Invalid workspace ID")
        val expected = File(appData.canonicalFile, "workspaces/$id/files")
        // Neither the workspace root nor an ancestor may redirect to app data
        // or another workspace, including when restored from another device.
        if (expected.canonicalFile != expected || (expected.exists() && !expected.isDirectory)) {
            throw FileNotFoundException("Invalid workspace root")
        }
        return expected
    }

    /** Resolve a relative path under [root] without accepting `..` escapes.
     * With [followLeaf] false a symbolic link stays a link, so delete/rename
     * never touch what a PRoot link2symlink points at. */
    fun resolveInRoot(
        root: File,
        relativePath: String,
        followLeaf: Boolean = true,
    ): File {
        if (!validRelativePath(relativePath)) throw FileNotFoundException("Invalid document path")
        val base = root.absoluteFile
        if (base.exists() && base.canonicalFile != base) {
            throw FileNotFoundException("Invalid document root")
        }
        if (relativePath.isEmpty()) return base
        val target = File(base, relativePath)
        val file = if (followLeaf) {
            target.canonicalFile
        } else {
            val parent = target.parentFile
                ?: throw FileNotFoundException("Invalid document path")
            File(parent.canonicalFile, target.name)
        }
        if (!isWithin(base, file)) throw FileNotFoundException("Path outside document root")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Document no longer exists")
        }
        return file
    }

    fun validRelativePath(relative: String): Boolean {
        if (relative.isEmpty()) return true
        return relative.split('/').none {
            it.isEmpty() || it == "." || it == ".." || '\u0000' in it || '\\' in it
        }
    }

    fun validFileName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' }

    fun isWithin(parent: File, child: File): Boolean =
        child.path.startsWith(parent.path + File.separator)
}
