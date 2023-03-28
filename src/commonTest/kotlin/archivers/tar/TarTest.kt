package archivers.tar

import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.async.use
import com.soywiz.korio.file.VfsOpenMode
import com.soywiz.korio.file.std.localVfs
import com.soywiz.korio.file.std.rootLocalVfs
import com.soywiz.korio.file.std.tempVfs
import com.soywiz.korio.stream.writeFile
import okio.Path.Companion.toPath
import kotlin.test.Test

class TarTest {

    companion object {
        private val resourceDir = "Program/Kotlin_Projects/Kompress/src/commonTest/resources"
    }

    @Test
    fun test() = suspendTest {
        val output = com.soywiz.korio.file.std.userHomeVfs["$resourceDir/bla.tar"]//java.io.File(dir, "bla.tar")
        val file1 =  com.soywiz.korio.file.std.userHomeVfs["$resourceDir/test.xml"]
        output.open(VfsOpenMode.WRITE).use {
            TarArchiveAsyncOutputStream(this).use {
                val entry = TarArchiveEntry("test1.xml")
                entry.setModTime(0)
                entry.setSize(file1.size())
//                entry.setMode(32768)
                putArchiveEntry(entry)
                this.writeFile(file1)
                closeArchiveEntry()
            }
        }
    }
}