package utils

import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.Foundation.*

@Suppress("CAST_NEVER_SUCCEEDS")
inline fun String.toNSString() = (this as NSString)

@Suppress("CAST_NEVER_SUCCEEDS")
inline fun NSString.string() = (this as String)

fun CommonCharset.toNSStringEncoding(): NSStringEncoding = when (this) {
    CommonCharset.UTF8 -> NSUTF8StringEncoding
    CommonCharset.UTF16 -> NSUTF16StringEncoding
    CommonCharset.US_ASCII -> NSASCIIStringEncoding
    CommonCharset.ISO_8859_1 -> NSISOLatin1StringEncoding
}

actual fun CommonCharset.canEncode(string: String): Boolean = (string as NSString).canBeConvertedToEncoding(toNSStringEncoding())


actual fun String.encode(commonCharset: CommonCharset): String {
    val stringEncoding = commonCharset.toNSStringEncoding()
    val data =
        toNSString().dataUsingEncoding(stringEncoding, allowLossyConversion = true)
            ?: throw Exception("Unsupported character encoding, or the string cannot use the character encoding")

    return NSString.create(data, stringEncoding)?.string() ?: throw Exception("TODO")
}

actual fun String.encodeToByteArray(commonCharset: CommonCharset): ByteArray {
    val stringEncoding = commonCharset.toNSStringEncoding()
    val data =
        toNSString().dataUsingEncoding(stringEncoding, allowLossyConversion = true)
            ?: throw Exception("Unsupported character encoding, or the string cannot use the character encoding")
    val bytesPointer = data.bytes ?: throw Exception("TODO")
    val bytesLength = data.length.toInt()
    return bytesPointer.readBytes(count = bytesLength)
}

actual fun ByteArray.decodeToString(commonCharset: CommonCharset): String {
    val stringEncoding = commonCharset.toNSStringEncoding()
    val data = toKString().toNSString().dataUsingEncoding(stringEncoding, allowLossyConversion = true)
        ?: throw Exception("Unsupported character encoding, or the string cannot use the character encoding")
    return NSString.create(data, stringEncoding)?.string() ?: throw Exception("TODO")
}

