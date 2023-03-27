import com.soywiz.kmem.ByteArrayBuilder
import com.soywiz.korio.stream.*
import utils.decodeToString
import utils.dropLastZero
import utils.seek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class StreamExtTest {

    @Test
    fun syncStreamSeekTest() {
        val stream = "Hello world".openSync()
        assertEquals("He", stream.readString(2))
        stream.seek(6)
        assertEquals("wo", stream.readString(2))
        stream.seek(0)
        assertEquals("Hello world", stream.readAll().decodeToString())
        assertFails {
            stream.seek(20)
        }
        stream.seek(stream.positionWrite)
        stream.writeString("!")
        stream.seek(0)
        assertEquals("Hello world!", stream.readAll().decodeToString())
        stream.close()
    }

    @Test
    fun syncOutputStreamTest() {
        val byteArrayBuilder = ByteArrayBuilder()
        val stream = MemorySyncStreamBase(byteArrayBuilder).toSyncStream()
        stream.writeBytes(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()))
        println(byteArrayBuilder.data.dropLastZero().decodeToString())
    }
}