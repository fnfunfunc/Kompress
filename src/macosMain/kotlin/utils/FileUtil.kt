package utils

import okio.FileSystem
import okio.Path


actual object FileUtil {
    actual val fileSystem: FileSystem
        get() = FileSystem.SYSTEM

    actual val tempDirectoryPath: Path
        get() = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
}