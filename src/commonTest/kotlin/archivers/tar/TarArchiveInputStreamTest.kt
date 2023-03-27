package archivers.tar

import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.async.use
import com.soywiz.korio.file.std.resourcesVfs
import com.soywiz.korio.lang.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class TarArchiveInputStreamTest {

//    private fun getTestStream(name: String): TarArchiveInputStream? {
//        return TarArchiveInputStream(
//            TarArchiveInputStreamTest::class.getResourceAsStream(name)
//        )
//    }


    @Test
    @Throws(Exception::class)
    fun shouldReadGNULongNameEntryWithWrongName() = suspendTest {
        val inputStream = resourcesVfs["/COMPRESS-324.tar"].openInputStream()
//        val byteArray = ByteArray(1000)
//        inputStream.read(byteArray, 0, 1000)
//        println(byteArray.decodeToString())
        val tarArchiveInputStream = TarArchiveAsyncInputStream(inputStream)
//        println(inputStream.readAll().decodeToString())
        val entry = tarArchiveInputStream.getNextTarEntry() ?: return@suspendTest
        assertEquals(
            "1234567890123456789012345678901234567890123456789012345678901234567890"
                    + "1234567890123456789012345678901234567890123456789012345678901234567890"
                    + "1234567890123456789012345678901234567890123456789012345678901234567890"
                    + "1234567890123456789012345678901234567890.txt",
            entry.name
        )
        println(entry)

        inputStream.close()
    }

    @Test
    fun testCompress197() = suspendTest {
        try {
            TarArchiveAsyncInputStream(resourcesVfs["/COMPRESS-197.tar"].openInputStream()).use {
                var entry = getNextTarEntry()
                while (entry != null) {
                    println(entry)
                    entry = getNextTarEntry()
                }
            }
        } catch (e: IOException) {
            fail("COMPRESS-197: " + e.message)
        }
    }
}