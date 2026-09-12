package saien.updater.jvm

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.updater.Checkpoint
import saien.updater.CheckpointStore
import saien.updater.UpdateErrorCode
import saien.updater.UpdateException

/** Fails closed on corrupt state or unsupported atomic replacement. */
public class FileCheckpointStore(directory: Path) : CheckpointStore {
    private val root = privateDirectory(directory)

    override suspend fun transaction(
        scope: String,
        transform: (Checkpoint?) -> Checkpoint,
    ): Checkpoint {
        val name =
            MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8)).toHex()
        val lockPath = root.resolve("$name.lock")
        return locks
            .computeIfAbsent(lockPath) { Mutex() }
            .withLock {
                runInterruptible(Dispatchers.IO) {
                    try {
                        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { lockChannel
                            ->
                            lockChannel.lock().use {
                                val statePath = root.resolve("$name.checkpoint")
                                val previous =
                                    if (Files.exists(statePath, NOFOLLOW_LINKS)) read(statePath)
                                    else null
                                val next = transform(previous)
                                val content =
                                    "${next.sequence}\n${next.payloadDigest}\n${next.observedAt}\n"
                                        .toByteArray(Charsets.UTF_8)
                                val temporary = Files.createTempFile(root, "checkpoint-", ".tmp")
                                try {
                                    FileChannel.open(temporary, WRITE).use { output ->
                                        val buffer = ByteBuffer.wrap(content)
                                        while (buffer.hasRemaining()) output.write(buffer)
                                        output.force(true)
                                    }
                                    Files.move(temporary, statePath, ATOMIC_MOVE, REPLACE_EXISTING)
                                    // Directory fsync is not supported by all JVM/OS providers.
                                    if (!System.getProperty("os.name").startsWith("Windows")) {
                                        FileChannel.open(root, READ).use { it.force(true) }
                                    }
                                } finally {
                                    Files.deleteIfExists(temporary)
                                }
                                next
                            }
                        }
                    } catch (error: UpdateException) {
                        throw error
                    } catch (error: java.io.IOException) {
                        throw UpdateException(
                            UpdateErrorCode.STORAGE,
                            "Cannot persist update checkpoint",
                            error,
                        )
                    }
                }
            }
    }

    private fun read(path: Path): Checkpoint {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > 256) corrupt()
        val lines = Files.readString(path, Charsets.UTF_8).lines()
        if (lines.size != 4 || lines.last().isNotEmpty()) corrupt()
        val sequence = lines[0].toLongOrNull()?.takeIf { it > 0 } ?: corrupt()
        if (!lines[1].matches(Regex("[a-f0-9]{64}"))) corrupt()
        val observedAt = lines[2].toLongOrNull()?.takeIf { it >= 0 } ?: corrupt()
        return Checkpoint(sequence, lines[1], observedAt)
    }

    private fun corrupt(): Nothing =
        throw UpdateException(
            UpdateErrorCode.STORAGE,
            "Invalid update checkpoint; refusing to reset trust state",
        )

    private companion object {
        val locks = ConcurrentHashMap<Path, Mutex>()
    }
}

internal fun privateDirectory(path: Path): Path {
    Files.createDirectories(path)
    require(!Files.isSymbolicLink(path)) { "Storage directory cannot be a symlink" }
    val root = path.toRealPath()
    if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(
            root,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
    }
    return root
}
