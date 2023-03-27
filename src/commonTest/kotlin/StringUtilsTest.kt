import com.soywiz.kds.RingBuffer
import com.soywiz.korio.stream.*
import utils.dropLastZero
import utils.encodeToByteArray
import kotlin.test.Test

class StringUtilsTest {

    @Test
    fun encodeTest() {
//        val english = "hello world"
//        val chinese = "你好，世界"
//        val japanese = "こんにちは世界"
//
//        val englishAscii = english.encode(commonCharset = CommonCharset.US_ASCII)
//
//        val chineseUtf8 = chinese.encode(commonCharset = CommonCharset.UTF8)
//
//
//        assertEquals(english, englishAscii)
//        assertEquals(chinese, chineseUtf8)
//
//        val usAscii = chinese.encodeToByteArray(commonCharset = CommonCharset.US_ASCII)
//        val utf8 = chinese.encodeToByteArray(commonCharset = CommonCharset.UTF8)
//        printByteArray(usAscii)
//        printByteArray(utf8)
//        println(utf8.decodeToString())
    }

    private fun printByteArray(byteArray: ByteArray) {
        print("[")
        byteArray.forEach {
            print("$it, ")
        }
        println("]")
    }

}