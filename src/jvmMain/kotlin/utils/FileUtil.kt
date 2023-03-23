package utils

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath


actual fun String.commonToPath(normalize: Boolean): Path = this.toPath(normalize = normalize)

actual object FileUtil {
    actual val fileSystem: FileSystem
        get() = FileSystem.SYSTEM

    actual val tempDirectoryPath: Path
        get() = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
}