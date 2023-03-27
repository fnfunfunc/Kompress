package utils

import com.soywiz.korio.util.toByteArray
import okio.ByteString.Companion.encode
import java.nio.ByteBuffer


fun CommonCharset.toCharset() = when (this) {
    CommonCharset.UTF8 -> Charsets.UTF_8
    CommonCharset.UTF16 -> Charsets.UTF_16
    CommonCharset.US_ASCII -> Charsets.US_ASCII
    CommonCharset.ISO_8859_1 -> Charsets.ISO_8859_1
}

actual fun String.encode(commonCharset: CommonCharset): String {
    val charset = commonCharset.toCharset()
    return charset.run {
        decode(encode(this@encode)).toString()
    }
}

actual fun String.encodeToByteArray(commonCharset: CommonCharset): ByteArray {
    val charset = commonCharset.toCharset()
    return charset.encode(this@encodeToByteArray).toByteArray()
}

actual fun ByteArray.decodeToString(commonCharset: CommonCharset): String {
    val charset = commonCharset.toCharset()
    return charset.decode(ByteBuffer.wrap(this)).toString()
}