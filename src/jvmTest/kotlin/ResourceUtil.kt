import com.soywiz.korio.file.std.localCurrentDirVfs

actual val resourceDir: String
    get() = "${localCurrentDirVfs.absolutePath}/src/commonTest/resources"