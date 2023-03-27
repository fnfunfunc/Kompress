package utils

enum class CommonCharset {
    UTF8,
    UTF16,
    US_ASCII, // US-ASCII
    ISO_8859_1; // ISO-8859-1

    companion object {
        fun defaultCharset() = UTF8
    }
}

expect fun String.encode(commonCharset: CommonCharset): String

expect fun String.encodeToByteArray(commonCharset: CommonCharset): ByteArray

expect fun ByteArray.decodeToString(commonCharset: CommonCharset): String