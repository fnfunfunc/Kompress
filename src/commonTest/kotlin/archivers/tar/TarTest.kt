package archivers.tar

import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.async.use
import com.soywiz.korio.file.VfsOpenMode
import com.soywiz.korio.file.std.userHomeVfs
import com.soywiz.korio.stream.writeFile
import okio.Path.Companion.toPath
import kotlin.test.Test

class TarTest {

    companion object {
        private val resourceDir = "Program/Kotlin_Projects/Kompress/src/commonTest/resources"
    }

    @Test
    fun test() = suspendTest {
        val output = userHomeVfs["$resourceDir/bla.tar"]//java.io.File(dir, "bla.tar")
        val file1 =  userHomeVfs["$resourceDir/xml/test.xml"]
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