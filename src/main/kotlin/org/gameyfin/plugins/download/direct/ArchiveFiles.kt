package org.gameyfin.plugins.download.direct

import java.io.InterruptedIOException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Visits files incrementally, without following directory symlinks or retaining the tree. */
internal object ArchiveFiles {
    fun selected(files: List<Path>): (Path, (Path, Long) -> Unit) -> Unit = { root, consume ->
        for (relative in files.distinct()) {
            require(relative.root == null && relative.none { it.toString() in setOf("", ".", "..") })
            val file = root.resolve(relative)
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || file.toRealPath() != file) throw IOException("Selected file changed")
            consume(file, attributes.size())
        }
    }

    /** Open each component relative to a pinned directory handle so a replaced ancestor cannot redirect the read. */
    fun openSelected(root: Path, file: Path): InputStream {
        require(file.startsWith(root) && file != root)
        Files.newDirectoryStream(root.root).use { top ->
            if (top is SecureDirectoryStream<Path>) {
                return openRelative(top, file.iterator())
            }
        }
        // Providers without secure directory handles (including Windows) still reject links at open time.
        if (file.toRealPath() != file) throw IOException("Selected file changed")
        val input = Files.newInputStream(file, READ, NOFOLLOW_LINKS)
        try {
            if (file.toRealPath() != file) throw IOException("Selected file changed")
            return input
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    private fun openRelative(directory: SecureDirectoryStream<Path>, names: Iterator<Path>): InputStream {
        val name = names.next()
        if (!names.hasNext()) return Channels.newInputStream(directory.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)))
        return directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { openRelative(it, names) }
    }

    fun visit(root: Path, consume: (Path, Long) -> Unit) {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Archive traversal cancelled")
        }

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                when {
                    attrs.isRegularFile -> consume(file, attrs.size())
                    // Match the previous walker: include links to regular files, but not directory links.
                    attrs.isSymbolicLink && Files.isRegularFile(file) -> consume(file, Files.size(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
