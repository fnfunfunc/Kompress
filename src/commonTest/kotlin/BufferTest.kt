import com.soywiz.kds.internal.KdsInternalApi
import utils.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class BufferTest {
    @Test
    fun bufferTest() {
        val buffer = Buffer(20)
        var a = 'a'.code
        while (a < 'z'.code) {
            buffer.writeByte(a++)
        }
        val byteArray = ByteArray(10)
        buffer.seekReadTo(5)
        buffer.read(byteArray)
        assertEquals("fghijklmno", byteArray.decodeToString())
    }
}

