package archivers.tar

import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.async.use
import com.soywiz.korio.file.VfsOpenMode
import com.soywiz.korio.file.std.localCurrentDirVfs
import com.soywiz.korio.file.std.standardVfs
import com.soywiz.korio.file.std.userHomeVfs
import com.soywiz.korio.stream.writeFile
import utils.SystemUtil
import utils.getUserName
import kotlin.test.Test

class TarTest {

    companion object {
        private val resourceDir = "${localCurrentDirVfs.absolutePath}/src/commonTest/resources"
    }

    @Test
    fun test() = suspendTest {
        val output = userHomeVfs["$resourceDir/test.tar"]
        val file1 =  userHomeVfs["$resourceDir/xml/test.xml"]
        output.open(VfsOpenMode.CREATE).use {
            TarArchiveAsyncOutputStream(this).use {
                val entry = TarArchiveEntry("test1.xml")
                entry.setUserName(SystemUtil.getUserName())
                entry.setSize(file1.size())
                putArchiveEntry(entry)
                this.writeFile(file1)
                closeArchiveEntry()
            }
        }
    }
}