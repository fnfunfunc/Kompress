/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package archivers.tar

import archivers.zip.ZipEncoding
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import com.soywiz.kmem.arraycopy
import com.soywiz.korio.stream.AsyncInputStream
import com.soywiz.korio.stream.SyncInputStream
import com.soywiz.korio.stream.readBytes
import com.soywiz.korio.stream.readBytesUpTo
import okio.Buffer
import okio.IOException
import utils.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.pow

object TarUtil {

    private const val BYTE_MASK = 255

    /**
     * Encapsulates the algorithms used up to Commons Compress 1.3 as
     * ZipEncoding.
     */
    val fallbackEncoding: ZipEncoding = object : ZipEncoding {
        override fun canEncode(name: String): Boolean {
            return true
        }

        override fun decode(data: ByteArray): String {
            val length = data.size
            val result: StringBuilder = StringBuilder(length)
            for (b in data) {
                if (b.toInt() == 0) { // Trailing null
                    break
                }
                result.append((b.toInt() and 0xFF).toChar()) // Allow for sign-extension
            }
            return result.toString()
        }

        override fun encode(name: String): ByteArray {
            val length = name.length
            val buf = ByteArray(length)

            // copy until end of input or output is reached.
            for (i in 0 until length) {
                buf[i] = name[i].code.toByte()
            }
            return buf
        }
    }

    /**
     * Compute the checksum of a tar entry header.
     *
     * @param buf The tar entry's header buffer.
     * @return The computed checksum.
     */
    fun computeCheckSum(buf: ByteArray): Long {
        var sum: Long = 0
        for (element in buf) {
            sum += (BYTE_MASK and element.toInt()).toLong()
        }
        return sum
    }

    // Helper method to generate the exception message
    private fun exceptionMessage(
        buffer: ByteArray, offset: Int,
        length: Int, current: Int, currentByte: Byte
    ): String? {
        // default charset is good enough for an exception message,
        //
        // the alternative was to modify parseOctal and
        // parseOctalOrBinary to receive the ZipEncoding of the
        // archive (deprecating the existing public methods, of
        // course) and dealing with the fact that ZipEncoding#decode
        // can throw an IOException which parseOctal* doesn't declare
        var string: String = buffer.sliceArray(offset, length).decodeToString(CommonCharset.UTF8)
        string = string.replace("\u0000", "{NUL}") // Replace NULs to allow string to be printed
        return "Invalid byte " + currentByte + " at offset " + (current - offset) + " in '" + string + "' len=" + length
    }


    /**
     * Writes an octal value into a buffer.
     *
     * Uses [.formatUnsignedOctalString] to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by NUL and then space.
     *
     * @param value The value to convert
     * @param buf The destination buffer
     * @param offset The starting offset into the buffer.
     * @param length The size of the buffer.
     * @return The updated value of offset, i.e. offset+length
     * @throws IllegalArgumentException if the value (and trailer) will not fit in the buffer
     */
    fun formatCheckSumOctalBytes(value: Long, buf: ByteArray, offset: Int, length: Int): Int {
        var idx = length - 2 // for NUL and space
        formatUnsignedOctalString(value, buf, offset, idx)
        buf[offset + idx++] = 0 // Trailing null
        buf[offset + idx] = ' '.code.toByte() // Trailing space
        return offset + length
    }


    private fun formatBigIntegerBinary(
        value: Long, buf: ByteArray,
        offset: Int,
        length: Int,
        negative: Boolean
    ) {
        val `val` = BigInteger.fromLong(value)
        val b: ByteArray = `val`.toByteArray()
        val len = b.size
        if (len > length - 1) {
            throw IllegalArgumentException(
                "Value " + value +
                        " is too large for " + length + " byte field."
            )
        }
        val off = offset + length - len
        arraycopy(b, 0, buf, off, len)
        val fill = (if (negative) 0xff else 0).toByte()
        for (i in offset + 1 until off) {
            buf[i] = fill
        }
    }

    private fun formatLongBinary(
        value: Long, buf: ByteArray,
        offset: Int, length: Int,
        negative: Boolean
    ) {
        val bits = (length - 1) * 8
        val max = 1L shl bits
        var `val`: Long = abs(value) // Long.MIN_VALUE stays Long.MIN_VALUE
        if (`val` < 0 || `val` >= max) {
            throw IllegalArgumentException(
                "Value " + value +
                        " is too large for " + length + " byte field."
            )
        }
        if (negative) {
            `val` = `val` xor (max - 1)
            `val`++
            `val` = `val` or (0xffL shl bits)
        }
        for (i in offset + length - 1 downTo offset) {
            buf[i] = `val`.toByte()
            `val` = `val` shr 8
        }
    }

    /**
     * Write an octal long integer into a buffer.
     *
     * Uses [.formatUnsignedOctalString] to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by a space.
     *
     * @param value The value to write as octal
     * @param buf The destinationbuffer.
     * @param offset The starting offset into the buffer.
     * @param length The length of the buffer
     * @return The updated offset
     * @throws IllegalArgumentException if the value (and trailer) will not fit in the buffer
     */
    fun formatLongOctalBytes(value: Long, buf: ByteArray, offset: Int, length: Int): Int {
        val idx = length - 1 // For space
        formatUnsignedOctalString(value, buf, offset, idx)
        buf[offset + idx] = ' '.code.toByte() // Trailing space
        return offset + length
    }

    /**
     * Write an long integer into a buffer as an octal string if this
     * will fit, or as a binary number otherwise.
     *
     * Uses [.formatUnsignedOctalString] to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by a space.
     *
     * @param value The value to write into the buffer.
     * @param buf The destination buffer.
     * @param offset The starting offset into the buffer.
     * @param length The length of the buffer.
     * @return The updated offset.
     * @throws IllegalArgumentException if the value (and trailer)
     * will not fit in the buffer.
     * @since 1.4
     */
    fun formatLongOctalOrBinaryBytes(
        value: Long, buf: ByteArray, offset: Int, length: Int
    ): Int {

        // Check whether we are dealing with UID/GID or SIZE field
        val maxAsOctalChar = if (length == TarConstants.UIDLEN) TarConstants.MAXID else TarConstants.MAXSIZE
        val negative = value < 0
        if (!negative && value <= maxAsOctalChar) { // OK to store as octal chars
            return formatLongOctalBytes(value, buf, offset, length)
        }
        if (length < 9) {
            formatLongBinary(value, buf, offset, length, negative)
        } else {
            formatBigIntegerBinary(
                value,
                buf,
                offset,
                length,
                negative
            )
        }
        buf[offset] = (if (negative) 0xff else 0x80).toByte()
        return offset + length
    }

    /**
     * Copy a name into a buffer.
     * Copies characters from the name into the buffer
     * starting at the specified offset.
     * If the buffer is longer than the name, the buffer
     * is filled with trailing NULs.
     * If the name is longer than the buffer,
     * the output is truncated.
     *
     * @param name The header name from which to copy the characters.
     * @param buf The buffer where the name is to be stored.
     * @param offset The starting offset into the buffer
     * @param length The maximum number of header bytes to copy.
     * @return The updated offset, i.e. offset + length
     */
    fun formatNameBytes(name: String, buf: ByteArray, offset: Int, length: Int): Int {
        return try {
            formatNameBytes(
                name,
                buf,
                offset,
                length,
                null
            )
        } catch (ex: IOException) { // NOSONAR
            try {
                formatNameBytes(
                    name, buf, offset, length,
                    fallbackEncoding
                )
            } catch (ex2: IOException) {
                // impossible
                throw ex2 //NOSONAR
            }
        }
    }

    /**
     * Copy a name into a buffer.
     * Copies characters from the name into the buffer
     * starting at the specified offset.
     * If the buffer is longer than the name, the buffer
     * is filled with trailing NULs.
     * If the name is longer than the buffer,
     * the output is truncated.
     *
     * @param name The header name from which to copy the characters.
     * @param buf The buffer where the name is to be stored.
     * @param offset The starting offset into the buffer
     * @param length The maximum number of header bytes to copy.
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     * @return The updated offset, i.e. offset + length
     * @throws IOException on error
     */
    @Throws(IOException::class)
    fun formatNameBytes(
        name: String, buf: ByteArray, offset: Int,
        length: Int,
        zipEncoding: ZipEncoding? = null
    ): Int {
        var len = name.length
        var b = zipEncoding?.encode(name) ?: name.encodeToByteArray(commonCharset = CommonCharset.UTF8)
        while (b.size > length && len > 0) {
            --len
            val subName = name.substring(0, len)
            b = zipEncoding?.encode(subName) ?: name.substring(0, --len)
                .encodeToByteArray(commonCharset = CommonCharset.UTF8)
        }
        val limit: Int = b.size//b.limit() - b.position()

        arraycopy(b, 0, buf, offset, limit)

        // Pad any remaining output bytes with NUL
        for (i in limit until length) {
            buf[offset + i] = 0
        }
        return offset + length
    }

    /**
     * Write an octal integer into a buffer.
     *
     * Uses [.formatUnsignedOctalString] to format
     * the value as an octal string with leading zeros.
     * The converted number is followed by space and NUL
     *
     * @param value The value to write
     * @param buf The buffer to receive the output
     * @param offset The starting offset into the buffer
     * @param length The size of the output buffer
     * @return The updated offset, i.e offset+length
     * @throws IllegalArgumentException if the value (and trailer) will not fit in the buffer
     */
    fun formatOctalBytes(value: Long, buf: ByteArray, offset: Int, length: Int): Int {
        var idx = length - 2 // For space and trailing null
        formatUnsignedOctalString(value, buf, offset, idx)
        buf[offset + idx++] = ' '.code.toByte() // Trailing space
        buf[offset + idx] = 0 // Trailing null
        return offset + length
    }

    /**
     * Fill buffer with unsigned octal number, padded with leading zeroes.
     *
     * @param value number to convert to octal - treated as unsigned
     * @param buffer destination buffer
     * @param offset starting offset in buffer
     * @param length length of buffer to fill
     * @throws IllegalArgumentException if the value will not fit in the buffer
     */
    fun formatUnsignedOctalString(
        value: Long, buffer: ByteArray,
        offset: Int, length: Int
    ) {
        var remaining = length
        remaining--
        if (value == 0L) {
            buffer[offset + remaining--] = '0'.code.toByte()
        } else {
            var `val` = value
            while (remaining >= 0 && `val` != 0L) {

                // CheckStyle:MagicNumber OFF
                buffer[offset + remaining] = ('0'.code.toByte() + (`val` and 7L).toByte()).toByte()
                `val` = `val` ushr 3
                --remaining
            }
            if (`val` != 0L) {
                throw IllegalArgumentException("$value=$value will not fit in octal number buffer of length $length")
            }
        }
        while (remaining >= 0) {
            // leading zeros
            buffer[offset + remaining] = '0'.code.toByte()
            --remaining
        }
    }

    private fun parseBinaryBigInteger(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        negative: Boolean
    ): Long {
        val remainder = ByteArray(length - 1)
        arraycopy(buffer, offset + 1, remainder, 0, length - 1)
        val value: BigInteger = BigInteger.fromByteArray(remainder, sign = if (negative) Sign.NEGATIVE else Sign.POSITIVE)
//        if (negative) {
//            // 2's complement
//            `val` = `val`.add(BigInteger.fromInt(-1)).not()
//        }
        if (value.bitLength() > 63) {
            throw IllegalArgumentException(
                "At offset " + offset + ", "
                        + length + " byte binary number"
                        + " exceeds maximum signed long"
                        + " value"
            )
        }
        return if (negative) - value.longValue() else value.longValue()
    }

    private fun parseBinaryLong(
        buffer: ByteArray, offset: Int,
        length: Int,
        negative: Boolean
    ): Long {
        if (length >= 9) {
            throw IllegalArgumentException(
                "At offset " + offset + ", "
                        + length + " byte binary number"
                        + " exceeds maximum signed long"
                        + " value"
            )
        }
        var value: Long = 0
        for (i in 1 until length) {
            value = (value shl 8) + (buffer[offset + i].toInt() and 0xff)
        }
        if (negative) {
            // 2's complement
            value--
            value = value xor 2.0.pow((length - 1) * 8.0).toLong() - 1
        }
        return if (negative) -value else value
    }

    /**
     * Parse a boolean byte from a buffer.
     * Leading spaces and NUL are ignored.
     * The buffer may contain trailing spaces or NULs.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @return The boolean value of the bytes.
     * @throws IllegalArgumentException if an invalid byte is detected.
     */
    fun parseBoolean(buffer: ByteArray, offset: Int): Boolean {
        return buffer[offset].toInt() == 1
    }

    /**
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     * GNU.sparse.map
     * Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     * @param sparseMap the sparse map string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     * @return unmodifiable list of sparse headers parsed from sparse map
     * @throws IOException Corrupted TAR archive.
     * @since 1.21
     */
    @Throws(IOException::class)
    fun parseFromPAX01SparseHeaders(sparseMap: String): List<TarArchiveStructSparse> {
        val sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        val sparseHeaderStrings = sparseMap.split(",".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        if (sparseHeaderStrings.size % 2 == 1) {
            throw IOException("Corrupted TAR archive. Bad format in GNU.sparse.map PAX Header")
        }
        var i = 0
        while (i < sparseHeaderStrings.size) {
            var sparseOffset: Long
            try {
                sparseOffset = sparseHeaderStrings[i].toLong()
            } catch (ex: NumberFormatException) {
                throw IOException(
                    "Corrupted TAR archive."
                            + " Sparse struct offset contains a non-numeric value"
                )
            }
            if (sparseOffset < 0) {
                throw IOException(
                    ("Corrupted TAR archive."
                            + " Sparse struct offset contains negative value")
                )
            }
            var sparseNumbytes: Long
            try {
                sparseNumbytes = sparseHeaderStrings[i + 1].toLong()
            } catch (ex: NumberFormatException) {
                throw IOException(
                    ("Corrupted TAR archive."
                            + " Sparse struct numbytes contains a non-numeric value")
                )
            }
            if (sparseNumbytes < 0) {
                throw IOException(
                    ("Corrupted TAR archive."
                            + " Sparse struct numbytes contains negative value")
                )
            }
            sparseHeaders.add(TarArchiveStructSparse(sparseOffset, sparseNumbytes))
            i += 2
        }
        return sparseHeaders.toList()
    }

    /**
     * Parse an entry name from a buffer.
     * Parsing stops when a NUL is found
     * or the buffer length is reached.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse.
     * @return The entry name.
     */
    fun parseName(buffer: ByteArray, offset: Int, length: Int): String {
        return try {
            parseName(
                buffer,
                offset,
                length,
                null
            )
        } catch (ex: IOException) { // NOSONAR
            try {
                parseName(
                    buffer,
                    offset,
                    length,
                    fallbackEncoding
                )
            } catch (ex2: IOException) {
                // impossible
                throw ex2 //NOSONAR
            }
        }
    }

    /**
     * Parse an entry name from a buffer.
     * Parsing stops when a NUL is found
     * or the buffer length is reached.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse.
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     * @return The entry name.
     * @throws IOException on error
     */
    @Throws(IOException::class)
    fun parseName(
        buffer: ByteArray, offset: Int,
        length: Int,
        encoding: ZipEncoding?
    ): String {
        var len = 0
        var i = offset
        while (len < length && buffer[i].toInt() != 0) {
            len++
            i++
        }
        if (len > 0) {
            val b = ByteArray(len)
            arraycopy(buffer, offset, b, 0, len)
            return encoding?.decode(b) ?: b.decodeToString(commonCharset = CommonCharset.UTF8)
        }
        return ""
    }

    /**
     * Parse an octal string from a buffer.
     *
     *
     * Leading spaces are ignored.
     * The buffer must contain a trailing space or NUL,
     * and may contain an additional trailing space or NUL.
     *
     *
     * The input buffer is allowed to contain all NULs,
     * in which case the method returns 0L
     * (this allows for missing fields).
     *
     *
     * To work-around some tar implementations that insert a
     * leading NUL this method returns 0 if it detects a leading NUL
     * since Commons Compress 1.4.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse - must be at least 2 bytes.
     * @return The long value of the octal string.
     * @throws IllegalArgumentException if the trailing space/NUL is missing or if a invalid byte is detected.
     */
    fun parseOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        var result: Long = 0
        var end = offset + length
        var start = offset
        if (length < 2) {
            throw IllegalArgumentException("Length $length must be at least 2")
        }
        if (buffer[start].toInt() == 0) {
            return 0L
        }


        // Skip leading spaces
        while (start < end) {
            if (buffer[start] != ' '.code.toByte()) {
                break
            }
            start++
        }

        // Trim all trailing NULs and spaces.
        // The ustar and POSIX tar specs require a trailing NUL or
        // space but some implementations use the extra digit for big
        // sizes/uids/gids ...
        var trailer = buffer[end - 1]
        while (start < end && (trailer.toInt() == 0 || trailer == ' '.code.toByte())) {
            end--
            trailer = buffer[end - 1]
        }
        while (start < end) {
            val currentByte = buffer[start]
            // CheckStyle:MagicNumber OFF
            if (currentByte < '0'.code.toByte() || currentByte > '7'.code.toByte()) {
                throw IllegalArgumentException(
                    exceptionMessage(
                        buffer,
                        offset,
                        length,
                        start,
                        currentByte
                    )
                )
            }
            result = (result shl 3) + (currentByte - '0'.code.toByte()) // convert from ASCII
            start++
        }
        return result
    }

    /**
     * Compute the value contained in a byte buffer.  If the most
     * significant bit of the first byte in the buffer is set, this
     * bit is ignored and the rest of the buffer is interpreted as a
     * binary number.  Otherwise, the buffer is interpreted as an
     * octal number as per the parseOctal function above.
     *
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @param length The maximum number of bytes to parse.
     * @return The long value of the octal or binary string.
     * @throws IllegalArgumentException if the trailing space/NUL is
     * missing or an invalid byte is detected in an octal number, or
     * if a binary number would exceed the size of a signed long
     * 64-bit integer.
     * @since 1.4
     */
    fun parseOctalOrBinary(
        buffer: ByteArray, offset: Int,
        length: Int
    ): Long {
        if (buffer[offset].toInt() and 0x80 == 0) {
            return parseOctal(buffer, offset, length)
        }
        val negative = buffer[offset] == 0xff.toByte()
        return if (length < 9) {
            parseBinaryLong(buffer, offset, length, negative)
        } else parseBinaryBigInteger(
            buffer,
            offset,
            length,
            negative
        )
    }

    /**
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     * GNU.sparse.map
     * Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     *
     * Will internally invoke [.parseFromPAX01SparseHeaders] and map IOExceptions to a RzuntimeException, You
     * should use [.parseFromPAX01SparseHeaders] directly instead.
     *
     * @param sparseMap the sparse map string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     * @return sparse headers parsed from sparse map
     */
    @Deprecated("use #parseFromPAX01SparseHeaders instead")
    fun parsePAX01SparseHeaders(sparseMap: String): List<TarArchiveStructSparse?>? {
        return try {
            parseFromPAX01SparseHeaders(sparseMap)
        } catch (ex: IOException) {
            throw ex
        }
    }

    /**
     * For PAX Format 1.X:
     * The sparse map itself is stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines. The map is padded with nulls to the nearest block boundary.
     * The first number gives the number of entries in the map. Following are map entries, each one consisting of two numbers
     * giving the offset and size of the data block it describes.
     * @param bufferedSource parsing source.
     * @param recordSize The size the TAR header
     * @return sparse headers
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    fun parsePAX1XSparseHeaders(
        bufferedSource: SyncInputStream,
        recordSize: Int
    ): List<TarArchiveStructSparse> {
        // for 1.X PAX Headers
        val sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        var bytesRead: Long = 0
        var readResult: LongArray =
            readLineOfNumberForPax1X(bufferedSource)
        var sparseHeadersCount = readResult[0]
        if (sparseHeadersCount < 0) {
            // overflow while reading number?
            throw IOException("Corrupted TAR archive. Negative value in sparse headers block")
        }
        bytesRead += readResult[1]
        while (sparseHeadersCount-- > 0) {
            readResult = readLineOfNumberForPax1X(bufferedSource)
            val sparseOffset = readResult[0]
            if (sparseOffset < 0) {
                throw IOException(
                    "Corrupted TAR archive."
                            + " Sparse header block offset contains negative value"
                )
            }
            bytesRead += readResult[1]
            readResult = readLineOfNumberForPax1X(bufferedSource)
            val sparseNumbytes = readResult[0]
            if (sparseNumbytes < 0) {
                throw IOException(
                    ("Corrupted TAR archive."
                            + " Sparse header block numbytes contains negative value")
                )
            }
            bytesRead += readResult[1]
            sparseHeaders.add(TarArchiveStructSparse(sparseOffset, sparseNumbytes))
        }

        // skip the rest of this record data
        val bytesToSkip = recordSize - bytesRead % recordSize
        bufferedSource.skip(bytesToSkip.toInt())
        return sparseHeaders
    }

    /**
     * For PAX Format 1.X:
     * The sparse map itself is stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines. The map is padded with nulls to the nearest block boundary.
     * The first number gives the number of entries in the map. Following are map entries, each one consisting of two numbers
     * giving the offset and size of the data block it describes.
     * @param bufferedSource parsing source.
     * @param recordSize The size the TAR header
     * @return sparse headers
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun parsePAX1XSparseHeadersAsync(
        bufferedSource: AsyncInputStream,
        recordSize: Int
    ): List<TarArchiveStructSparse> {
        // for 1.X PAX Headers
        val sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        var bytesRead: Long = 0
        var readResult: LongArray =
            readLineOfNumberForPax1XAsync(bufferedSource)
        var sparseHeadersCount = readResult[0]
        if (sparseHeadersCount < 0) {
            // overflow while reading number?
            throw IOException("Corrupted TAR archive. Negative value in sparse headers block")
        }
        bytesRead += readResult[1]
        while (sparseHeadersCount-- > 0) {
            readResult = readLineOfNumberForPax1XAsync(bufferedSource)
            val sparseOffset = readResult[0]
            if (sparseOffset < 0) {
                throw IOException(
                    "Corrupted TAR archive."
                            + " Sparse header block offset contains negative value"
                )
            }
            bytesRead += readResult[1]
            readResult = readLineOfNumberForPax1XAsync(bufferedSource)
            val sparseNumbytes = readResult[0]
            if (sparseNumbytes < 0) {
                throw IOException(
                    ("Corrupted TAR archive."
                            + " Sparse header block numbytes contains negative value")
                )
            }
            bytesRead += readResult[1]
            sparseHeaders.add(TarArchiveStructSparse(sparseOffset, sparseNumbytes))
        }

        // skip the rest of this record data
        val bytesToSkip = recordSize - bytesRead % recordSize
        IOUtil.skip(bufferedSource, bytesToSkip)
        return sparseHeaders
    }



    /**
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes)
     * may appear multi times, and they look like:
     *
     * GNU.sparse.size=size
     * GNU.sparse.numblocks=numblocks
     * repeat numblocks times
     * GNU.sparse.offset=offset
     * GNU.sparse.numbytes=numbytes
     * end repeat
     *
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     *
     * GNU.sparse.map
     * Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     * @param bufferedSource input stream to read keys and values
     * @param sparseHeaders used in PAX Format 0.0 &amp; 0.1, as it may appear multiple times,
     * the sparse headers need to be stored in an array, not a map
     * @param globalPaxHeaders global PAX headers of the tar archive
     * @return map of PAX headers values found inside of the current (local or global) PAX headers tar entry.
     * @throws IOException if an I/O error occurs.
     */
    @Deprecated("use the four-arg version instead",
        ReplaceWith("org.apache.commons.compress.archivers.tar.TarUtils.parsePaxHeaders(inputStream, sparseHeaders, globalPaxHeaders, -1)")
    )
    @Throws(IOException::class)
    fun parsePaxHeaders(
        bufferedSource: SyncInputStream,
        sparseHeaders: MutableList<TarArchiveStructSparse>,
        globalPaxHeaders: Map<String, String>
    ): Map<String, String> {
        return parsePaxHeaders(
            bufferedSource,
            sparseHeaders,
            globalPaxHeaders,
            -1
        )
    }

    /**
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes)
     * may appear multi times, and they look like:
     *
     * GNU.sparse.size=size
     * GNU.sparse.numblocks=numblocks
     * repeat numblocks times
     * GNU.sparse.offset=offset
     * GNU.sparse.numbytes=numbytes
     * end repeat
     *
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     *
     * GNU.sparse.map
     * Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     * @param bufferedSource input stream to read keys and values
     * @param sparseHeaders used in PAX Format 0.0 &amp; 0.1, as it may appear multiple times,
     * the sparse headers need to be stored in an array, not a map
     * @param globalPaxHeaders global PAX headers of the tar archive
     * @param headerSize total size of the PAX header, will be ignored if negative
     * @return map of PAX headers values found inside of the current (local or global) PAX headers tar entry.
     * @throws IOException if an I/O error occurs.
     * @since 1.21
     */
    @Throws(IOException::class)
    fun parsePaxHeaders(
        bufferedSource: SyncInputStream,
        sparseHeaders: MutableList<TarArchiveStructSparse>, globalPaxHeaders: Map<String, String>,
        headerSize: Long
    ): Map<String, String> {
        val headers: MutableMap<String, String> = globalPaxHeaders.toMutableMap()
        var offset: Long? = null
        // Format is "length keyword=value\n";
        var totalRead = 0
        while (true) { // get length
            var ch: Int
            var len = 0
            var read = 0

            while (bufferedSource.read().also { ch = it } != -1) {
                read++
                totalRead++
                if (ch == '\n'.code) { // blank line in header
                    break
                }
                if (ch == ' '.code) { // End of length string
                    // Get keyword
                    val coll = Buffer()
                    while (bufferedSource.read().also { ch = it } != -1) {
                        read++
                        totalRead++
                        if (totalRead < 0 || headerSize in 0..totalRead) {
                            break
                        }
                        if (ch == '='.code) { // end of keyword
                            val keyword: String = coll.readByteArray().decodeToString(CommonCharset.UTF8) // coll.toString(CharsetNames.UTF_8)
                            // Get rest of entry
                            val restLen = len - read
                            if (restLen <= 1) { // only NL
                                headers.remove(keyword)
                            } else if (headerSize >= 0 && restLen > headerSize - totalRead) {
                                throw IOException(
                                    "Paxheader value size " + restLen
                                            + " exceeds size of header record"
                                )
                            } else {
                                val rest: ByteArray = bufferedSource.readBytes(restLen) //IOUtils.readRange(bufferedSource, restLen)
                                val got = rest.size
                                if (got != restLen) {
                                    throw IOException(
                                        ("Failed to read "
                                                + "Paxheader. Expected "
                                                + restLen
                                                + " bytes, read "
                                                + got)
                                    )
                                }
                                totalRead += restLen
                                // Drop trailing NL
                                if (rest[restLen - 1] != '\n'.code.toByte()) {
                                    throw IOException(
                                        ("Failed to read Paxheader."
                                                + "Value should end with a newline")
                                    )
                                }
                                val value: String = rest.sliceArray(0, restLen - 1).decodeToString(CommonCharset.UTF8)
                                headers[keyword] = value

                                // for 0.0 PAX Headers
                                if ((keyword == TarGnuSparseKeys.OFFSET)) {
                                    if (offset != null) {
                                        // previous GNU.sparse.offset header but but no numBytes
                                        sparseHeaders.add(TarArchiveStructSparse(offset, 0))
                                    }
                                    try {
                                        offset = value.toLong()
                                    } catch (ex: NumberFormatException) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.OFFSET).toString() + " contains a non-numeric value")
                                        )
                                    }
                                    if (offset < 0) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.OFFSET).toString() + " contains negative value")
                                        )
                                    }
                                }

                                // for 0.0 PAX Headers
                                if ((keyword == TarGnuSparseKeys.NUMBYTES)) {
                                    if (offset == null) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.OFFSET).toString() + " is expected before GNU.sparse.numbytes shows up.")
                                        )
                                    }
                                    var numbytes: Long
                                    try {
                                        numbytes = value.toLong()
                                    } catch (ex: NumberFormatException) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.NUMBYTES).toString() + " contains a non-numeric value.")
                                        )
                                    }
                                    if (numbytes < 0) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.NUMBYTES).toString() + " contains negative value")
                                        )
                                    }
                                    sparseHeaders.add(TarArchiveStructSparse(offset, numbytes))
                                    offset = null
                                }
                            }
                            break
                        }
                        coll.writeByte((ch.toByte()).toInt())
                    }
                    break // Processed single header
                }

                // COMPRESS-530 : throw if we encounter a non-number while reading length
                if (ch < '0'.code || ch > '9'.code) {
                    throw IOException("Failed to read Paxheader. Encountered a non-number while reading length")
                }
                len *= 10
                len += ch - '0'.code
            }
            if (ch == -1) { // EOF
                break
            }
        }
        if (offset != null) {
            // offset but no numBytes
            sparseHeaders.add(TarArchiveStructSparse(offset, 0))
        }
        return headers
    }

    suspend fun parsePaxHeadersAsync(
        bufferedSource: AsyncInputStream,
        sparseHeaders: MutableList<TarArchiveStructSparse>, globalPaxHeaders: Map<String, String>,
        headerSize: Long
    ): Map<String, String> {
        val headers: MutableMap<String, String> = globalPaxHeaders.toMutableMap()
        var offset: Long? = null
        // Format is "length keyword=value\n";
        var totalRead = 0
        while (true) { // get length
            var ch: Int
            var len = 0
            var read = 0

            while (bufferedSource.read().also { ch = it } != -1) {
                read++
                totalRead++
                if (ch == '\n'.code) { // blank line in header
                    break
                }
                if (ch == ' '.code) { // End of length string
                    // Get keyword
                    val coll = Buffer()
                    while (bufferedSource.read().also { ch = it } != -1) {
                        read++
                        totalRead++
                        if (totalRead < 0 || headerSize in 0..totalRead) {
                            break
                        }
                        if (ch == '='.code) { // end of keyword
                            val keyword: String = coll.readByteArray().decodeToString(CommonCharset.UTF8) // coll.toString(CharsetNames.UTF_8)
                            // Get rest of entry
                            val restLen = len - read
                            if (restLen <= 1) { // only NL
                                headers.remove(keyword)
                            } else if (headerSize >= 0 && restLen > headerSize - totalRead) {
                                throw IOException(
                                    "Paxheader value size " + restLen
                                            + " exceeds size of header record"
                                )
                            } else {
                                val rest: ByteArray = bufferedSource.readBytesUpTo(restLen) //IOUtils.readRange(bufferedSource, restLen)
                                val got = rest.size
                                if (got != restLen) {
                                    throw IOException(
                                        ("Failed to read "
                                                + "Paxheader. Expected "
                                                + restLen
                                                + " bytes, read "
                                                + got)
                                    )
                                }
                                totalRead += restLen
                                // Drop trailing NL
                                if (rest[restLen - 1] != '\n'.code.toByte()) {
                                    throw IOException(
                                        ("Failed to read Paxheader."
                                                + "Value should end with a newline")
                                    )
                                }
                                val value: String = rest.sliceArray(0, restLen - 1).decodeToString(CommonCharset.UTF8)
                                headers[keyword] = value

                                // for 0.0 PAX Headers
                                if ((keyword == TarGnuSparseKeys.OFFSET)) {
                                    if (offset != null) {
                                        // previous GNU.sparse.offset header but but no numBytes
                                        sparseHeaders.add(TarArchiveStructSparse(offset, 0))
                                    }
                                    try {
                                        offset = value.toLong()
                                    } catch (ex: NumberFormatException) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.OFFSET).toString() + " contains a non-numeric value")
                                        )
                                    }
                                    if (offset < 0) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.OFFSET).toString() + " contains negative value")
                                        )
                                    }
                                }

                                // for 0.0 PAX Headers
                                if ((keyword == TarGnuSparseKeys.NUMBYTES)) {
                                    if (offset == null) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.OFFSET).toString() + " is expected before GNU.sparse.numbytes shows up.")
                                        )
                                    }
                                    var numbytes: Long
                                    try {
                                        numbytes = value.toLong()
                                    } catch (ex: NumberFormatException) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.NUMBYTES).toString() + " contains a non-numeric value.")
                                        )
                                    }
                                    if (numbytes < 0) {
                                        throw IOException(
                                            (("Failed to read Paxheader."
                                                    + TarGnuSparseKeys.NUMBYTES).toString() + " contains negative value")
                                        )
                                    }
                                    sparseHeaders.add(TarArchiveStructSparse(offset, numbytes))
                                    offset = null
                                }
                            }
                            break
                        }
                        coll.writeByte((ch.toByte()).toInt())
                    }
                    break // Processed single header
                }

                // COMPRESS-530 : throw if we encounter a non-number while reading length
                if (ch < '0'.code || ch > '9'.code) {
                    throw IOException("Failed to read Paxheader. Encountered a non-number while reading length")
                }
                len *= 10
                len += ch - '0'.code
            }
            if (ch == -1) { // EOF
                break
            }
        }
        if (offset != null) {
            // offset but no numBytes
            sparseHeaders.add(TarArchiveStructSparse(offset, 0))
        }
        return headers
    }

    /**
     * Parses the content of a PAX 1.0 sparse block.
     * @since 1.20
     * @param buffer The buffer from which to parse.
     * @param offset The offset into the buffer from which to parse.
     * @return a parsed sparse struct
     */
    fun parseSparse(buffer: ByteArray, offset: Int): TarArchiveStructSparse {
        val sparseOffset: Long = parseOctalOrBinary(
            buffer,
            offset,
            TarConstants.SPARSE_OFFSET_LEN
        )
        val sparseNumbytes: Long = parseOctalOrBinary(
            buffer,
            offset + TarConstants.SPARSE_OFFSET_LEN,
            TarConstants.SPARSE_NUMBYTES_LEN
        )
        return TarArchiveStructSparse(sparseOffset, sparseNumbytes)
    }

    /**
     * For 1.X PAX Format, the sparse headers are stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines.
     *
     * @param bufferedSource the input stream of the tar file
     * @return the decimal number delimited by '\n', and the bytes read from input stream
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun readLineOfNumberForPax1X(bufferedSource: SyncInputStream): LongArray {
        var number: Int
        var result: Long = 0
        var bytesRead: Long = 0
        while (bufferedSource.read().also { number = it } != '\n'.code) {
            bytesRead += 1
            if (number == -1) {
                throw IOException("Unexpected EOF when reading parse information of 1.X PAX format")
            }
            if (number < '0'.code || number > '9'.code) {
                throw IOException("Corrupted TAR archive. Non-numeric value in sparse headers block")
            }
            result = result * 10 + (number - '0'.code)
        }
        bytesRead += 1
        return longArrayOf(result, bytesRead)
    }

    @Throws(IOException::class, CancellationException::class)
    private suspend fun readLineOfNumberForPax1XAsync(bufferedSource: AsyncInputStream): LongArray {
        var number: Int
        var result: Long = 0
        var bytesRead: Long = 0
        while (bufferedSource.read().also { number = it } != '\n'.code) {
            bytesRead += 1
            if (number == -1) {
                throw IOException("Unexpected EOF when reading parse information of 1.X PAX format")
            }
            if (number < '0'.code || number > '9'.code) {
                throw IOException("Corrupted TAR archive. Non-numeric value in sparse headers block, the number is $number")
            }
            result = result * 10 + (number - '0'.code)
        }
        bytesRead += 1
        return longArrayOf(result, bytesRead)
    }

    /**
     * @since 1.21
     */
    @Throws(IOException::class)
    fun readSparseStructs(buffer: ByteArray, offset: Int, entries: Int): List<TarArchiveStructSparse> {
        val sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        for (i in 0 until entries) {
            try {
                val sparseHeader: TarArchiveStructSparse =
                    parseSparse(
                        buffer,
                        offset + i * (TarConstants.SPARSE_OFFSET_LEN + TarConstants.SPARSE_NUMBYTES_LEN)
                    )
                if (sparseHeader.offset < 0) {
                    throw IOException("Corrupted TAR archive, sparse entry with negative offset")
                }
                if (sparseHeader.numbytes < 0) {
                    throw IOException("Corrupted TAR archive, sparse entry with negative numbytes")
                }
                sparseHeaders.add(sparseHeader)
            } catch (ex: IllegalArgumentException) {
                // thrown internally by parseOctalOrBinary
                throw IOException("Corrupted TAR archive, sparse entry is invalid", ex)
            }
        }
        return sparseHeaders.toList()
    }


    /**
     * Wikipedia [says](https://en.wikipedia.org/wiki/Tar_(computing)#File_header):
     * <blockquote>
     * The checksum is calculated by taking the sum of the unsigned byte values
     * of the header block with the eight checksum bytes taken to be ascii
     * spaces (decimal value 32). It is stored as a six digit octal number with
     * leading zeroes followed by a NUL and then a space. Various
     * implementations do not adhere to this format. For better compatibility,
     * ignore leading and trailing whitespace, and get the first six digits. In
     * addition, some historic tar implementations treated bytes as signed.
     * Implementations typically calculate the checksum both ways, and treat it
     * as good if either the signed or unsigned sum matches the included
     * checksum.
    </blockquote> *
     *
     *
     * The return value of this method should be treated as a best-effort
     * heuristic rather than an absolute and final truth. The checksum
     * verification logic may well evolve over time as more special cases
     * are encountered.
     *
     * @param header tar header
     * @return whether the checksum is reasonably good
     * @see [COMPRESS-191](https://issues.apache.org/jira/browse/COMPRESS-191)
     *
     * @since 1.5
     */
    fun verifyCheckSum(header: ByteArray): Boolean {
        val storedSum: Long = parseOctal(
            header,
            TarConstants.CHKSUM_OFFSET,
            TarConstants.CHKSUMLEN
        )
        var unsignedSum: Long = 0
        var signedSum: Long = 0
        for (i in header.indices) {
            var b = header[i]
            if (TarConstants.CHKSUM_OFFSET <= i && i < TarConstants.CHKSUM_OFFSET + TarConstants.CHKSUMLEN) {
                b = ' '.code.toByte()
            }
            unsignedSum += (0xff and b.toInt()).toLong()
            signedSum += b.toLong()
        }
        return storedSum == unsignedSum || storedSum == signedSum
    }

}