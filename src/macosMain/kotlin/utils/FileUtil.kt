package utils

import kotlinx.cinterop.*
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.lstat
import platform.posix.stat


actual object FileUtil {
    actual val fileSystem: FileSystem
        get() = FileSystem.SYSTEM

    actual val tempDirectoryPath: Path
        get() = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
}