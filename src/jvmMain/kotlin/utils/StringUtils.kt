package utils

import com.soywiz.korio.util.toByteArray
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction


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

actual fun CommonCharset.canEncode(string: String): Boolean {
    val nioCharset = toCharset()
    return nioCharset.newEncoder(this == CommonCharset.UTF8).canEncode(string)
}

private fun Charset.newEncoder(useReplacement: Boolean): CharsetEncoder {
    val REPLACEMENT = '?'
    val REPLACEMENT_BYTES = byteArrayOf(REPLACEMENT.code.toByte())
    return if (useReplacement) {
        newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(REPLACEMENT_BYTES)
    } else newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
}