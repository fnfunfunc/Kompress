package utils

import okio.FileSystem
import okio.Path

expect object FileUtil {
    val fileSystem: FileSystem

    val tempDirectoryPath: Path
}


expect fun String.commonToPath(normalize: Boolean): Path