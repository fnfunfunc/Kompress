package archivers

import utils.CommonCharset
import utils.decodeToString
import utils.dropLastZero
import utils.encodeToByteArray
import kotlin.math.min

object ArchiveUtils {

    private const val MAX_SANITIZED_NAME_LENGTH = 255

    /**
     * Returns true if the first N bytes of an array are all zero
     *
     * @param a
     * The array to check
     * @param size
     * The number of characters to check (not the size of the array)
     * @return true if the first N bytes are zero
     */
    fun isArrayZero(a: ByteArray, size: Int): Boolean {
        for (i in 0 until size) {
            if (a[i] != 0.toByte()) {
                return false
            }
        }
        return true
    }


    /**
     * Compare byte buffers
     *
     * @param buffer1 the first buffer
     * @param buffer2 the second buffer
     * @return `true` if buffer1 and buffer2 have same contents
     */
    fun isEqual(buffer1: ByteArray, buffer2: ByteArray): Boolean {
        return isEqual(
            buffer1,
            0,
            buffer1.size,
            buffer2,
            0,
            buffer2.size,
            false
        )
    }

    /**
     * Compare byte buffers, optionally ignoring trailing nulls
     *
     * @param buffer1 the first buffer
     * @param buffer2 the second buffer
     * @param ignoreTrailingNulls whether to ignore trailing nulls
     * @return `true` if buffer1 and buffer2 have same contents
     */
    fun isEqual(buffer1: ByteArray, buffer2: ByteArray, ignoreTrailingNulls: Boolean): Boolean {
        return isEqual(
            buffer1,
            0,
            buffer1.size,
            buffer2,
            0,
            buffer2.size,
            ignoreTrailingNulls
        )
    }

    /**
     * Compare byte buffers
     *
     * @param buffer1 the first buffer
     * @param offset1 the first offset
     * @param length1 the first length
     * @param buffer2 the second buffer
     * @param offset2 the second offset
     * @param length2 the second length
     * @return `true` if buffer1 and buffer2 have same contents
     */
    fun isEqual(
        buffer1: ByteArray, offset1: Int, length1: Int,
        buffer2: ByteArray, offset2: Int, length2: Int
    ): Boolean {
        return isEqual(
            buffer1,
            offset1,
            length1,
            buffer2,
            offset2,
            length2,
            false
        )
    }

    /**
     * Compare byte buffers, optionally ignoring trailing nulls
     *
     * @param buffer1 first buffer
     * @param offset1 first offset
     * @param length1 first length
     * @param buffer2 second buffer
     * @param offset2 second offset
     * @param length2 second length
     * @param ignoreTrailingZero whether to ignore trailing nulls
     * @return `true` if buffer1 and buffer2 have same contents, having regard to trailing nulls
     */
    fun isEqual(
        buffer1: ByteArray, offset1: Int, length1: Int,
        buffer2: ByteArray, offset2: Int, length2: Int,
        ignoreTrailingZero: Boolean
    ): Boolean {
        val minLen: Int = min(length1, length2)
        for (i in 0 until minLen) {
            if (buffer1[offset1 + i] != buffer2[offset2 + i]) {
                return false
            }
        }
        if (length1 == length2) {
            return true
        }
        if (ignoreTrailingZero) {
            if (length1 > length2) {
                for (i in length2 until length1) {
                    if (buffer1[offset1 + i].toInt() != 0) {
                        return false
                    }
                }
            } else {
                for (i in length1 until length2) {
                    if (buffer2[offset2 + i].toInt() != 0) {
                        return false
                    }
                }
            }
            return true
        }
        return false
    }

    /**
     * Compare byte buffers, ignoring trailing nulls
     *
     * @param buffer1 the first buffer
     * @param offset1 the first offset
     * @param length1 the first length
     * @param buffer2 the second buffer
     * @param offset2 the second offset
     * @param length2 the second length
     * @return `true` if buffer1 and buffer2 have same contents, having regard to trailing nulls
     */
    fun isEqualWithTrailingZero(
        buffer1: ByteArray, offset1: Int, length1: Int,
        buffer2: ByteArray, offset2: Int, length2: Int
    ): Boolean {
        return isEqual(
            buffer1,
            offset1,
            length1,
            buffer2,
            offset2,
            length2,
            true
        )
    }

    /**
     * Check if buffer contents matches Ascii String.
     *
     * @param expected the expected strin
     * @param buffer the buffer
     * @return `true` if buffer is the same as the expected string
     */
    fun matchAsciiBuffer(expected: String, buffer: ByteArray): Boolean {
        return matchAsciiBuffer(expected, buffer, 0, buffer.size)
    }

    /**
     * Check if buffer contents matches Ascii String.
     *
     * @param expected expected string
     * @param buffer the buffer
     * @param offset offset to read from
     * @param length length of the buffer
     * @return `true` if buffer is the same as the expected string
     */
    fun matchAsciiBuffer(
        expected: String, buffer: ByteArray, offset: Int, length: Int
    ): Boolean {
        val buffer1: ByteArray = expected.encodeToByteArray(CommonCharset.US_ASCII)
        return isEqual(
            buffer1,
            0,
            buffer1.size,
            buffer,
            offset,
            length,
            false
        )
    }

    /**
     * Returns a "sanitized" version of the string given as arguments,
     * where sanitized means non-printable characters have been
     * replaced with a question mark and the outcome is not longer
     * than 255 chars.
     *
     *
     * This method is used to clean up file names when they are
     * used in exception messages as they may end up in log files or
     * as console output and may have been read from a corrupted
     * input.
     *
     * @param s the string to sanitize
     * @return a sanitized version of the argument
     * @since 1.12
     */
    fun sanitize(s: String): String {
        val cs = s.toCharArray()
        val chars =
            if (cs.size <= MAX_SANITIZED_NAME_LENGTH) cs else cs.copyOf(MAX_SANITIZED_NAME_LENGTH)
        if (cs.size > MAX_SANITIZED_NAME_LENGTH) {
            chars.fill(element = '.', fromIndex = MAX_SANITIZED_NAME_LENGTH - 3, toIndex = MAX_SANITIZED_NAME_LENGTH)
        }
        return chars.map { c ->
            if (!c.isISOControl()) c else '?'
        }.toTypedArray().contentToString()
    }

    /**
     * Convert a string to Ascii bytes.
     * Used for comparing "magic" strings which need to be independent of the default Locale.
     *
     * @param inputString string to convert
     * @return the bytes
     */
    fun toAsciiBytes(inputString: String): ByteArray {
        return inputString.encodeToByteArray(CommonCharset.US_ASCII)
    }

    /**
     * Convert an input byte array to a String using the ASCII character set.
     *
     * @param inputBytes bytes to convert
     * @return the bytes, interpreted as an Ascii string
     */
    fun toAsciiString(inputBytes: ByteArray): String {
        return inputBytes.decodeToString(CommonCharset.US_ASCII)
    }

    /**
     * Convert an input byte array to a String using the ASCII character set.
     *
     * @param inputBytes input byte array
     * @param offset offset within array
     * @param length length of array
     * @return the bytes, interpreted as an Ascii string
     */
    fun toAsciiString(inputBytes: ByteArray, offset: Int, length: Int): String {
        return inputBytes.sliceArray(offset until (offset + length)).decodeToString(CommonCharset.US_ASCII)
    }



}