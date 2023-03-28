package archivers.tar

import com.soywiz.kmem.ByteArrayBuilder
import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.async.use
import com.soywiz.korio.lang.use
import com.soywiz.korio.stream.MemoryAsyncStreamBase
import com.soywiz.korio.stream.bufferedInput
import com.soywiz.korio.stream.openAsync
import com.soywiz.korio.stream.toAsyncStream
import utils.sliceArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TarArchiveOutputStreamTest {

    @Test
    @Throws(Exception::class)
    fun testBigNumberErrorMode() = suspendTest {
        val t = TarArchiveEntry("foo")
        t.setSize(8589934592L)
        val bos = ByteArray(200).openAsync()
        TarArchiveAsyncOutputStream(bos).use {
            assertFails {
                putArchiveEntry(t)
            }
        }
    }

    @Test
    fun testWriteNonAsciiPathNamePaxHeader() = suspendTest {
        val n = "\u00e4"
        val t = TarArchiveEntry(n)
        t.setSize(10 * 1024)
        val byteArrayBuilder = ByteArrayBuilder()
        val bos = MemoryAsyncStreamBase(byteArrayBuilder).toAsyncStream()
        TarArchiveAsyncOutputStream(bos).use {
            setAddPaxHeadersForNonAsciiNames(true)
            putArchiveEntry(t)
            write(ByteArray(10 * 1024))
            closeArchiveEntry()
        }
        val data: ByteArray = byteArrayBuilder.data
        assertEquals(
            "11 path=$n\n",
            data.sliceArray(512, 11).decodeToString()
        )
        val bis = data.openAsync()
        TarArchiveAsyncInputStream(bis).use {
            val e: TarArchiveEntry = getNextTarEntry()!!
            assertEquals(n, e.name)
            assertEquals(TarConstants.LF_NORMAL, e.linkFlag)
        }
    }



    @Test
    fun testWriteSimplePaxHeaders() = suspendTest {
        val m: MutableMap<String, String> =  mutableMapOf<String, String>()
        m["a"] = "b"
        val data = writePaxHeader(m)
        assertEquals(
            "00000000006 ", data.sliceArray(offset = TarConstants.NAMELEN
                    + TarConstants.MODELEN
                    + TarConstants.UIDLEN
                    + TarConstants.GIDLEN, 12).decodeToString()
        )
        assertEquals(
            "6 a=b\n",
            data.sliceArray(512, 6).decodeToString()
        )
    }

    @Test
    fun testPaxHeadersWithLength101() = suspendTest {
        val m: MutableMap<String, String> = mutableMapOf()
        m["a"] = ("0123456789012345678901234567890123456789"
                + "01234567890123456789012345678901234567890123456789"
                + "0123")
        val data: ByteArray = writePaxHeader(m)

        assertEquals(
            "00000000145 ", data.sliceArray(offset = TarConstants.NAMELEN
                    + TarConstants.MODELEN
                    + TarConstants.UIDLEN
                    + TarConstants.GIDLEN, 12).decodeToString()
        )
        assertEquals(
            "101 a=0123456789012345678901234567890123456789"
                    + "01234567890123456789012345678901234567890123456789"
                    + "0123\n", data.sliceArray(512, 101).decodeToString()
        )
    }

    private suspend fun writePaxHeader(m: Map<String, String>): ByteArray = run {
        val byteArrayBuilder = ByteArrayBuilder()
        val bos = MemoryAsyncStreamBase(byteArrayBuilder).toAsyncStream()
        TarArchiveAsyncOutputStream(bos).use {
            writePaxHeaders(TarArchiveEntry("x"), "foo", m)

            // add a dummy entry so data gets written
            val t = TarArchiveEntry("foo")
            t.setSize(10 * 1024)
            putArchiveEntry(t)
            write(ByteArray(10 * 1024))
            closeArchiveEntry()
        }
        byteArrayBuilder.data
    }
}