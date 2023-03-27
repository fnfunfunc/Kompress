package utils

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

expect object FileUtil {
    val fileSystem: FileSystem

    val tempDirectoryPath: Path
}

fun FileUtil.getFileSizeByPath(path: Path): Long =
    fileSystem.metadata(path).size ?: 0