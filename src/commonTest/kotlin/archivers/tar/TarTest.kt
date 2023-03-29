package archivers.tar

import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.async.use
import com.soywiz.korio.file.VfsOpenMode
import com.soywiz.korio.file.std.applicationVfs
import com.soywiz.korio.file.std.localCurrentDirVfs
import com.soywiz.korio.file.std.rootLocalVfs
import com.soywiz.korio.file.std.userHomeVfs
import com.soywiz.korio.stream.readAll
import com.soywiz.korio.stream.writeFile
import resourceDir
import utils.SystemUtil
import utils.getUserName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TarTest {

    @Test
    fun test() = suspendTest {
        val output = rootLocalVfs["$resourceDir/test.tar"]
        val file1 =  rootLocalVfs["$resourceDir/xml/test.xml"]
        output.open(VfsOpenMode.CREATE).use {
            TarArchiveAsyncOutputStream(this).use {
                val entry = TarArchiveEntry("test1.xml")
                entry.setUserName(SystemUtil.getUserName())
                entry.setSize(file1.size())
                putArchiveEntry(entry)
                writeFile(file1)
                closeArchiveEntry()
            }
        }

        val byteArray = ByteArray(file1.size().toInt())
        output.open(VfsOpenMode.READ).use {
            TarArchiveAsyncInputStream(this).use {
                val entry = this.getNextTarEntry()
                assertTrue(entry != null)
                assertEquals(entry.name, "test1.xml")
                assertEquals(entry.userName, SystemUtil.getUserName())
                assertEquals(entry.size, file1.size())
                read(byteArray, 0, file1.size().toInt())
            }
        }
        file1.openInputStream().use {
            assertEquals(readAll().decodeToString(), byteArray.decodeToString())
        }
        output.delete()
    }
}