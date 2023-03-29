import com.soywiz.korio.file.std.localCurrentDirVfs

// In darwin, for instance, localCurrentDirVfs.absolutePath may be Kompress/build/bin/macosArm64/debugTest
actual val resourceDir: String get() = "${localCurrentDirVfs.parent.parent.parent.parent.absolutePath}/src/commonTest/resources"