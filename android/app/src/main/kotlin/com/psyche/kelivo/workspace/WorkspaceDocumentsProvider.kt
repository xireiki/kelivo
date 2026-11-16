package com.psyche.kelivo.workspace

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.webkit.MimeTypeMap
import com.psyche.kelivo.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kelivo files in the Android system document picker. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private lateinit var appData: File
    private lateinit var store: WorkspaceDocumentsStore
    private val directoryObservers = DirectoryObservers()
    private val authority: String get() = requireNotNull(context).packageName + ".workspace.documents"

    private val workspacesDir: File
        get() = WorkspaceDocumentPaths.workspacesDirectory(appData)
    private val sessionsDir: File
        get() = WorkspaceDocumentPaths.sessionsDirectory(appData)
    private val rootfsDir: File
        get() = WorkspaceDocumentPaths.rootfsDirectory(appData)

    private data class DocumentEntry(
        val id: String,
        val name: String,
        val file: File,
        val root: File,
        val isDirectory: Boolean,
        val supportsCreate: Boolean = false,
        val supportsWrite: Boolean = false,
        val supportsDelete: Boolean = false,
        val supportsRename: Boolean = false,
    )

    override fun onCreate(): Boolean {
        // path_provider_android's getApplicationDocumentsDirectory uses this directory.
        appData = requireNotNull(context).getDir("flutter", Context.MODE_PRIVATE).canonicalFile
        store = WorkspaceDocumentsStore(appData)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            addValues(mapOf(
                Root.COLUMN_ROOT_ID to WorkspaceDocumentPaths.ROOT,
                Root.COLUMN_DOCUMENT_ID to WorkspaceDocumentPaths.ROOT,
                Root.COLUMN_TITLE to "Kelivo",
                Root.COLUMN_ICON to R.mipmap.ic_launcher,
                Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_SUPPORTS_CREATE),
                Root.COLUMN_MIME_TYPES to "*/*",
            ))
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            addDocument(describe(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        val cursor = ObservedCursor(projection ?: DOCUMENT_COLUMNS, requireNotNull(context).contentResolver, uri, directoryObservers)
        try {
            // A rename/delete in Flutter is committed to the same WAL. Watching
            // it keeps the picker current without publishing a second registry.
            cursor.watch(appData) { it == "kelivo.db" || it == "kelivo.db-wal" }
            when (val target = parseOrThrow(parentDocumentId)) {
                DocumentTarget.Root -> {
                    for (id in WorkspaceDocumentPaths.CATEGORIES) {
                        cursor.addDocument(categoryEntry(id))
                    }
                }
                is DocumentTarget.Category -> when (target.id) {
                    WorkspaceDocumentPaths.CATEGORY_WORKSPACES -> {
                        cursor.watch(workspacesDir) { true }
                        for (workspace in store.list()) cursor.addDocument(workspaceEntry(workspace))
                    }
                    WorkspaceDocumentPaths.CATEGORY_SESSIONS -> {
                        cursor.watch(sessionsDir) { true }
                        for (entry in conversationEntries(outputsOnly = false)) {
                            cursor.addDocument(entry)
                        }
                    }
                    WorkspaceDocumentPaths.CATEGORY_OUTPUTS -> {
                        cursor.watch(sessionsDir) { true }
                        for (entry in conversationEntries(outputsOnly = true)) {
                            cursor.addDocument(entry)
                        }
                    }
                    WorkspaceDocumentPaths.CATEGORY_ROOTFS -> {
                        val parent = describe(parentDocumentId)
                        if (Files.isDirectory(parent.file.toPath())) {
                            cursor.watch(parent.file) { true }
                        }
                        addChildren(cursor, target, parent)
                    }
                    else -> throw FileNotFoundException("Unknown category")
                }
                else -> {
                    val parent = describe(parentDocumentId)
                    if (Files.isDirectory(parent.file.toPath())) {
                        cursor.watch(parent.file) { true }
                    }
                    addChildren(cursor, target, parent)
                }
            }
            return cursor
        } catch (error: Exception) {
            cursor.close()
            throw error
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = describe(parentDocumentId)
        if (!parent.isDirectory || !parent.supportsCreate) {
            throw FileNotFoundException("Not a directory")
        }
        val name = displayName.trim()
        if (!WorkspaceDocumentPaths.validFileName(name)) throw IllegalArgumentException("Invalid document name")
        var target = File(parent.file, name)
        var counter = 2
        while (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            val dot = name.lastIndexOf('.')
            val candidate = if (dot <= 0) "$name ($counter)"
                else "${name.substring(0, dot)} ($counter)${name.substring(dot)}"
            target = File(parent.file, candidate)
            counter++
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            target.mkdir()
        } else {
            target.createNewFile()
        }
        if (!created) throw IOException("Unable to create document")
        requireNotNull(context).contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null,
        )
        return childId(parseOrThrow(parentDocumentId), target.name)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val source = describe(documentId, followLeaf = false)
        if (!source.supportsRename) throw FileNotFoundException("Cannot rename document")
        val name = displayName.trim()
        if (!WorkspaceDocumentPaths.validFileName(name)) throw IllegalArgumentException("Invalid document name")
        if (name == source.file.name) return null
        val parent = source.file.parentFile ?: throw FileNotFoundException("No parent directory")
        val target = File(parent, name)
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target)
        if (!source.file.renameTo(target)) throw IOException("Unable to rename document")
        val resolver = requireNotNull(context).contentResolver
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId(documentId)), null)
        resolver.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null)
        return renamedId(documentId, name)
    }

    override fun deleteDocument(documentId: String) {
        val entry = describe(documentId, followLeaf = false)
        if (!entry.supportsDelete) throw FileNotFoundException("Cannot delete document")
        deleteTree(entry.file.toPath())
        val resolver = requireNotNull(context).contentResolver
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId(documentId)), null)
        resolver.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val entry = describe(documentId)
        if (entry.isDirectory || !entry.file.isFile) throw FileNotFoundException("Not a file")
        val truncate = mode == "w" || mode == "wt" || mode == "rwt"
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND
            "rw", "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE
            else -> throw FileNotFoundException("Unsupported mode")
        }
        val descriptor = ParcelFileDescriptor.open(entry.file, flags)
        try {
            // Validate the opened descriptor as well, in case a running command
            // changed a symlink between path validation and open().
            val openedPath = Os.readlink("/proc/self/fd/${descriptor.fd}")
            if (!WorkspaceDocumentPaths.isWithin(entry.root, File(openedPath))) {
                throw FileNotFoundException("Path outside workspace")
            }
            // Truncate only after the descriptor is known to be inside the
            // workspace, so a swapped symlink cannot erase an outside file.
            if (truncate) Os.ftruncate(descriptor.fileDescriptor, 0)
            signal?.throwIfCanceled()
            return descriptor
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    override fun getDocumentType(documentId: String): String {
        if (documentId == WorkspaceDocumentPaths.ROOT) return Document.MIME_TYPE_DIR
        val entry = describe(documentId)
        return if (entry.isDirectory) Document.MIME_TYPE_DIR else mimeType(entry.file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = describe(parentDocumentId)
        val child = describe(documentId)
        parent.isDirectory && WorkspaceDocumentPaths.isWithin(parent.root, child.file)
    } catch (_: IOException) {
        false
    }

    private fun parseOrThrow(documentId: String): DocumentTarget =
        WorkspaceDocumentPaths.parse(documentId) ?: throw FileNotFoundException("Unknown document")

    private fun describe(documentId: String, followLeaf: Boolean = true): DocumentEntry {
        return when (val target = parseOrThrow(documentId)) {
            DocumentTarget.Root -> DocumentEntry(
                documentId, "Kelivo", appData, appData, isDirectory = true,
            )
            is DocumentTarget.Category -> categoryEntry(target.id)
            is DocumentTarget.Workspace -> {
                val workspace = store.list().firstOrNull { it.id == target.workspaceId }
                    ?: throw FileNotFoundException("Workspace no longer exists")
                val file = WorkspaceDocumentPaths.resolveInRoot(
                    workspace.directory, target.relativePath, followLeaf,
                )
                if (target.relativePath.isEmpty()) {
                    // A workspace restored without files stays an empty folder.
                    DocumentEntry(
                        documentId, workspace.name, file, workspace.directory, isDirectory = true,
                        supportsCreate = true,
                    )
                } else {
                    childEntry(documentId, file, workspace.directory)
                }
            }
            is DocumentTarget.Session -> {
                val directory = sessionDirectory(target.conversationId)
                val file = WorkspaceDocumentPaths.resolveInRoot(
                    directory, target.relativePath, followLeaf,
                )
                if (target.relativePath.isEmpty()) {
                    DocumentEntry(
                        documentId,
                        sessionName(target.conversationId, directory),
                        file,
                        directory,
                        isDirectory = true,
                        supportsCreate = true,
                        supportsDelete = true,
                    )
                } else {
                    childEntry(documentId, file, directory)
                }
            }
            is DocumentTarget.Output -> {
                val directory = outputsDirectory(target.conversationId)
                val file = WorkspaceDocumentPaths.resolveInRoot(
                    directory, target.relativePath, followLeaf,
                )
                if (target.relativePath.isEmpty()) {
                    DocumentEntry(
                        documentId,
                        sessionName(target.conversationId, directory.parentFile ?: directory),
                        file,
                        directory,
                        isDirectory = true,
                        supportsCreate = true,
                        supportsDelete = true,
                    )
                } else {
                    childEntry(documentId, file, directory)
                }
            }
            is DocumentTarget.Rootfs -> {
                val file = WorkspaceDocumentPaths.resolveInRoot(
                    rootfsDir, target.relativePath, followLeaf,
                )
                if (target.relativePath.isEmpty()) {
                    DocumentEntry(
                        documentId, "rootfs", file, rootfsDir, isDirectory = true,
                        supportsCreate = true,
                    )
                } else {
                    childEntry(documentId, file, rootfsDir)
                }
            }
        }
    }

    private fun categoryEntry(id: String): DocumentEntry {
        val file = when (id) {
            WorkspaceDocumentPaths.CATEGORY_WORKSPACES -> workspacesDir
            WorkspaceDocumentPaths.CATEGORY_SESSIONS,
            WorkspaceDocumentPaths.CATEGORY_OUTPUTS,
            -> sessionsDir
            WorkspaceDocumentPaths.CATEGORY_ROOTFS -> rootfsDir
            else -> throw FileNotFoundException("Unknown category")
        }
        return DocumentEntry(
            id,
            WorkspaceDocumentPaths.categoryTitle(id),
            file,
            file,
            isDirectory = true,
            supportsCreate = id == WorkspaceDocumentPaths.CATEGORY_ROOTFS,
        )
    }

    private fun workspaceEntry(workspace: WorkspaceDocumentRoot): DocumentEntry = DocumentEntry(
        WorkspaceDocumentPaths.documentId(workspace.id),
        workspace.name,
        workspace.directory,
        workspace.directory,
        isDirectory = true,
        supportsCreate = true,
    )

    private fun childEntry(id: String, file: File, root: File): DocumentEntry {
        val isDirectory = Files.isDirectory(file.toPath())
        return DocumentEntry(
            id,
            file.name,
            file,
            root,
            isDirectory = isDirectory,
            supportsCreate = isDirectory,
            supportsWrite = !isDirectory,
            supportsDelete = true,
            supportsRename = true,
        )
    }

    private fun addChildren(cursor: MatrixCursor, parent: DocumentTarget, entry: DocumentEntry) {
        val children = entry.file.listFiles().orEmpty()
            .filterNot { it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        for (child in children) {
            val file = try {
                WorkspaceDocumentPaths.resolveInRoot(entry.file, child.name)
            } catch (_: IOException) {
                // Files may disappear while enumerating. Escaping links and
                // non-regular files are intentionally not exposed.
                continue
            }
            cursor.addDocument(childEntry(childId(parent, child.name), file, entry.root))
        }
    }

    private fun conversationEntries(outputsOnly: Boolean): List<DocumentEntry> {
        if (!Files.isDirectory(sessionsDir.toPath())) return emptyList()
        val created = store.conversations().associate { it.id to it.createdAtMillis }
        val entries = mutableListOf<DocumentEntry>()
        Files.newDirectoryStream(sessionsDir.toPath()).use { stream ->
            for (path in stream) {
                val id = path.fileName.toString()
                if (!WorkspaceDocumentPaths.validId(id)) continue
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) continue
                val sessionDir = path.toFile()
                val directory = if (outputsOnly) {
                    try {
                        WorkspaceDocumentPaths.resolveInRoot(sessionDir, "outputs")
                    } catch (_: IOException) {
                        continue
                    }
                } else {
                    sessionDir
                }
                if (!Files.isDirectory(directory.toPath())) continue
                entries += DocumentEntry(
                    if (outputsOnly) {
                        WorkspaceDocumentPaths.outputDocumentId(id)
                    } else {
                        WorkspaceDocumentPaths.sessionDocumentId(id)
                    },
                    sessionName(id, created[id] ?: creationMillis(sessionDir)),
                    directory,
                    directory,
                    isDirectory = true,
                    supportsCreate = true,
                    supportsDelete = true,
                )
            }
        }
        // The display name starts with yyyyMMddHHmmss, so this sorts newest first.
        return entries.sortedByDescending { it.name }
    }

    private fun sessionDirectory(conversationId: String): File =
        WorkspaceDocumentPaths.resolveInRoot(sessionsDir, conversationId)

    private fun outputsDirectory(conversationId: String): File =
        WorkspaceDocumentPaths.resolveInRoot(sessionDirectory(conversationId), "outputs")

    private fun sessionName(conversationId: String, createdAtMillis: Long): String {
        val time = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(createdAtMillis))
        return "$time-${conversationId.substringBefore('-').take(8)}"
    }

    private fun sessionName(conversationId: String, sessionDir: File): String =
        sessionName(conversationId, creationMillis(sessionDir))

    private fun creationMillis(file: File): Long {
        val created = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .creationTime().toMillis()
        } catch (_: IOException) {
            0L
        }
        return if (created > 0) created else file.lastModified()
    }

    private fun childId(parent: DocumentTarget, name: String): String = when (parent) {
        is DocumentTarget.Category -> {
            if (parent.id == WorkspaceDocumentPaths.CATEGORY_ROOTFS) {
                WorkspaceDocumentPaths.rootfsDocumentId(name)
            } else {
                throw FileNotFoundException("Folder is read-only")
            }
        }
        is DocumentTarget.Workspace -> WorkspaceDocumentPaths.documentId(
            parent.workspaceId, join(parent.relativePath, name),
        )
        is DocumentTarget.Session -> WorkspaceDocumentPaths.sessionDocumentId(
            parent.conversationId, join(parent.relativePath, name),
        )
        is DocumentTarget.Output -> WorkspaceDocumentPaths.outputDocumentId(
            parent.conversationId, join(parent.relativePath, name),
        )
        is DocumentTarget.Rootfs -> WorkspaceDocumentPaths.rootfsDocumentId(
            join(parent.relativePath, name),
        )
        else -> throw FileNotFoundException("Folder is read-only")
    }

    private fun renamedId(documentId: String, name: String): String =
        when (val target = parseOrThrow(documentId)) {
            is DocumentTarget.Workspace -> WorkspaceDocumentPaths.documentId(
                target.workspaceId, replaceName(target.relativePath, name),
            )
            is DocumentTarget.Session -> WorkspaceDocumentPaths.sessionDocumentId(
                target.conversationId, replaceName(target.relativePath, name),
            )
            is DocumentTarget.Output -> WorkspaceDocumentPaths.outputDocumentId(
                target.conversationId, replaceName(target.relativePath, name),
            )
            is DocumentTarget.Rootfs -> WorkspaceDocumentPaths.rootfsDocumentId(
                replaceName(target.relativePath, name),
            )
            else -> throw FileNotFoundException("Cannot rename document")
        }

    private fun parentDocumentId(documentId: String): String =
        when (val target = parseOrThrow(documentId)) {
            is DocumentTarget.Workspace -> if (target.relativePath.contains('/')) {
                WorkspaceDocumentPaths.documentId(
                    target.workspaceId, target.relativePath.substringBeforeLast('/'),
                )
            } else {
                WorkspaceDocumentPaths.CATEGORY_WORKSPACES
            }
            is DocumentTarget.Session -> if (target.relativePath.contains('/')) {
                WorkspaceDocumentPaths.sessionDocumentId(
                    target.conversationId, target.relativePath.substringBeforeLast('/'),
                )
            } else {
                WorkspaceDocumentPaths.CATEGORY_SESSIONS
            }
            is DocumentTarget.Output -> if (target.relativePath.contains('/')) {
                WorkspaceDocumentPaths.outputDocumentId(
                    target.conversationId, target.relativePath.substringBeforeLast('/'),
                )
            } else {
                WorkspaceDocumentPaths.CATEGORY_OUTPUTS
            }
            is DocumentTarget.Rootfs -> if (target.relativePath.contains('/')) {
                WorkspaceDocumentPaths.rootfsDocumentId(target.relativePath.substringBeforeLast('/'))
            } else {
                WorkspaceDocumentPaths.CATEGORY_ROOTFS
            }
            else -> WorkspaceDocumentPaths.ROOT
        }

    private fun join(relativePath: String, name: String): String =
        if (relativePath.isEmpty()) name else "$relativePath/$name"

    private fun replaceName(relativePath: String, name: String): String =
        join(relativePath.substringBeforeLast('/', ""), name)

    private fun MatrixCursor.addDocument(entry: DocumentEntry) {
        var flags = 0
        if (entry.isDirectory) {
            if (entry.supportsCreate) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            // The top level is virtual; picking it as a tree would grant
            // access to a folder that cannot hold files.
            if (entry.id == WorkspaceDocumentPaths.ROOT && Build.VERSION.SDK_INT >= 30) {
                flags = flags or Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
            }
        } else if (entry.supportsWrite) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (entry.supportsDelete) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (entry.supportsRename) flags = flags or Document.FLAG_SUPPORTS_RENAME
        addValues(mapOf(
            Document.COLUMN_DOCUMENT_ID to entry.id,
            Document.COLUMN_DISPLAY_NAME to entry.name,
            Document.COLUMN_MIME_TYPE to if (entry.isDirectory) Document.MIME_TYPE_DIR else mimeType(entry.file),
            Document.COLUMN_FLAGS to flags,
            Document.COLUMN_SIZE to if (entry.file.isFile) entry.file.length() else null,
            Document.COLUMN_LAST_MODIFIED to entry.file.lastModified().takeIf { it > 0 },
        ))
    }

    /** Delete without following symlinks, so a link inside the workspace cannot
     * erase files outside it. */
    private fun deleteTree(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return when (file.extension.lowercase()) {
            "md", "markdown" -> "text/markdown"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        }
    }

    private fun MatrixCursor.addValues(values: Map<String, Any?>) {
        addRow(columnNames.map { values[it] }.toTypedArray())
    }

    /** Android reuses one inotify watch per path within the process. Share it
     * across overlapping queries so closing an old cursor cannot stop a new one. */
    private class DirectoryObservers {
        private class Entry(val observer: FileObserver, val listeners: MutableSet<(String?) -> Unit>)
        private val entries = mutableMapOf<String, Entry>()

        @Suppress("DEPRECATION")
        @Synchronized
        fun subscribe(directory: File, listener: (String?) -> Unit): () -> Unit {
            val key = directory.path
            val entry = entries.getOrPut(key) {
                val listeners = mutableSetOf<(String?) -> Unit>()
                val mask = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or
                    FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
                    FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
                val observer = object : FileObserver(key, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        val snapshot = synchronized(this@DirectoryObservers) {
                            if (event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0 &&
                                entries[key]?.observer === this) {
                                // This watch follows the old inode. The next
                                // query must watch the directory now at this path.
                                entries.remove(key)
                                stopWatching()
                            }
                            listeners.toList()
                        }
                        // Call outside the registry lock: cursor cleanup also
                        // acquires this lock when it releases a subscription.
                        snapshot.forEach { it(path) }
                    }
                }
                Entry(observer, listeners)
            }
            entry.listeners.add(listener)
            if (entry.listeners.size == 1) entry.observer.startWatching()
            return {
                synchronized(this) {
                    if (entry.listeners.remove(listener) && entry.listeners.isEmpty()) {
                        if (entries[key] === entry) entries.remove(key)
                        entry.observer.stopWatching()
                    }
                }
            }
        }
    }

    /** Release only this cursor's subscriptions when the picker closes it. */
    private class ObservedCursor(
        columns: Array<out String>,
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val directoryObservers: DirectoryObservers,
    ) : MatrixCursor(columns) {
        private val handler = Handler(Looper.getMainLooper())
        private val subscriptions = mutableListOf<() -> Unit>()
        private val changed = Runnable { if (!isClosed) resolver.notifyChange(uri, null) }

        init { setNotificationUri(resolver, uri) }

        @Synchronized
        fun watch(directory: File, matches: (String?) -> Boolean) {
            subscriptions += directoryObservers.subscribe(directory) { path ->
                synchronized(this) {
                    if (!isClosed && matches(path)) {
                        handler.removeCallbacks(changed)
                        handler.postDelayed(changed, 150)
                    }
                }
            }
        }

        @Synchronized
        override fun close() {
            if (isClosed) return
            super.close()
            subscriptions.forEach { it() }
            subscriptions.clear()
            handler.removeCallbacks(changed)
        }
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
