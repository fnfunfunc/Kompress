package archivers.tar

import com.soywiz.kmem.arraycopy
import com.soywiz.korio.async.suspendTest
import com.soywiz.korio.stream.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TarUtilTest {

    private fun checkName(string: String) {
        val buff = ByteArray(100)
        val len: Int = TarUtil.formatNameBytes(string, buff, 0, buff.size)
        assertEquals(string, TarUtil.parseName(buff, 0, len))
    }

    @Test
    @Throws(Exception::class)
    fun parseFromPAX01SparseHeaders() {
        val map = "0,10,20,0,20,5"
        val sparse: List<TarArchiveStructSparse> = TarUtil.parseFromPAX01SparseHeaders(map)
        assertEquals(3, sparse.size)
        assertEquals(0, sparse[0].offset)
        assertEquals(10, sparse[0].numbytes)
        assertEquals(20, sparse[1].offset)
        assertEquals(0, sparse[1].numbytes)
        assertEquals(20, sparse[2].offset)
        assertEquals(5, sparse[2].numbytes)
    }

    @Test
    fun parseFromPAX01SparseHeadersRejectsNegativeNumbytes() {
        assertFails {
            TarUtil.parseFromPAX01SparseHeaders("0,10,20,0,20,-5")
        }
    }

    @Test
    fun parseFromPAX01SparseHeadersRejectsNegativeOffset() {
        assertFails {
            TarUtil.parseFromPAX01SparseHeaders(
                "0,10,20,0,-2,5"
            )
        }
    }

    @Test
    fun parseFromPAX01SparseHeadersRejectsNonNumericNumbytes() {
        assertFails {
            TarUtil.parseFromPAX01SparseHeaders("0,10,20,0,20,b")
        }
    }

    @Test
    fun parsePAX1XSparseHeaders() = suspendTest {
        val header = "1\n0\n20\n".encodeToByteArray()
        val block = ByteArray(512)
        arraycopy(header, 0, block, 0, header.size)
        val stream = block.openAsync()
        val sparse = TarUtil.parsePAX1XSparseHeadersAsync(stream, 512)
        assertEquals(1, sparse.size)
        assertEquals(0, sparse[0].offset)
        assertEquals(20, sparse[0].numbytes)
        assertEquals(-1, stream.read())
    }

    @Test
    fun readNonAsciiPaxHeader() = suspendTest {
        val ae = "\u00e4"
        val line = "11 path=$ae\n"
        val byteArray = line.encodeToByteArray()
        assertEquals(
            11,
            byteArray.size
        )
        val headers: Map<String, String> = TarUtil
            .parsePaxHeadersAsync(
                byteArray.openAsync(),
                mutableListOf(),
                mapOf(), -1
            )
        assertEquals(1, headers.size)
        assertEquals(ae, headers["path"])
    }

    @Test
    fun streamTest() = suspendTest {
        val stream = "123".openAsync()
        stream.write(97)

        println(stream.getLength())

        val s = stream.base.toAsyncStream()
        println(s.readAll().decodeToString())
    }
}