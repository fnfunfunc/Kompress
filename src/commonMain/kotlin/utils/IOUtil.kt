package utils

import com.soywiz.korio.lang.IOException
import com.soywiz.korio.stream.AsyncInputStream
import com.soywiz.korio.stream.SyncInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

object IOUtil {

    private const val COPY_BUF_SIZE = 8024
    private const val SKIP_BUF_SIZE = 4096

    private val SKIP_BUF = ByteArray(SKIP_BUF_SIZE)

    /**
     * Skips the given number of bytes by repeatedly invoking skip on
     * the given input stream if necessary.
     *
     *
     * In a case where the stream's skip() method returns 0 before
     * the requested number of bytes has been skip this implementation
     * will fall back to using the read() method.
     *
     *
     * This method will only skip less than the requested number of
     * bytes if the end of the input stream has been reached.
     *
     * @param input stream to skip bytes in
     * @param numToSkip the number of bytes to skip
     * @return the number of bytes actually skipped
     * @throws IOException on error
     */
    @Throws(IOException::class)
    fun skip(input: SyncInputStream, numToSkip: Long): Long {
        var numToSkip = numToSkip
        val available = numToSkip
        while (numToSkip > 0) {
            val skipped = input.skipStream(numToSkip)
            if (skipped == 0L) {
                break
            }
            numToSkip -= skipped
        }
        while (numToSkip > 0) {
            val read: Int = readFully(
                input,
                SKIP_BUF,
                0,
                min(numToSkip, SKIP_BUF_SIZE.toLong()).toInt()
            )
            if (read < 1) {
                break
            }
            numToSkip -= read.toLong()
        }
        return available - numToSkip
    }

    @Throws(IOException::class, CancellationException::class)
    suspend fun skip(input: AsyncInputStream, numToSkip: Long): Long {
        var numToSkip = numToSkip
        val available = numToSkip
        while (numToSkip > 0) {
            val skipped = input.skipStream(numToSkip)
            if (skipped == 0L) {
                break
            }
            numToSkip -= skipped
        }
        while (numToSkip > 0) {
            val read: Int = readFully(
                input,
                SKIP_BUF,
                0,
                min(numToSkip, SKIP_BUF_SIZE.toLong()).toInt()
            )
            if (read < 1) {
                break
            }
            numToSkip -= read.toLong()
        }
        return available - numToSkip
    }

    // toByteArray(InputStream) copied from:
    // commons/proper/io/trunk/src/main/java/org/apache/commons/io/IOUtils.java?revision=1428941
    // January 8th, 2013
    //
    // Assuming our copy() works just as well as theirs!  :-)
    /**
     * Reads as much from input as possible to fill the given array
     * with the given amount of bytes.
     *
     *
     * This method may invoke read repeatedly to read the bytes and
     * only read less bytes than the requested length if the end of
     * the stream has been reached.
     *
     * @param input stream to read from
     * @param array buffer to fill
     * @param offset offset into the buffer to start filling at
     * @param len of bytes to read
     * @return the number of bytes actually read
     * @throws IOException
     * if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun readFully(input: SyncInputStream, array: ByteArray, offset: Int, len: Int): Int {
        if (len < 0 || offset < 0 || len + offset > array.size || len + offset < 0) {
            throw IndexOutOfBoundsException()
        }
        var count = 0
        var x = 0
        while (count != len) {
            x = input.read(array, offset + count, len - count)
            if (x == -1) {
                break
            }
            count += x
        }
        return count
    }

    @Throws(IOException::class, CancellationException::class)
    suspend fun readFully(input: AsyncInputStream, array: ByteArray, offset: Int, len: Int): Int {
        if (len < 0 || offset < 0 || len + offset > array.size || len + offset < 0) {
            throw IndexOutOfBoundsException()
        }
        var count = 0
        var x = 0
        while (count != len) {
            x = input.read(array, offset + count, len - count)
            if (x == -1) {
                break
            }
            count += x
        }
        return count
    }

    /**
     * Reads as much from input as possible to fill the given array.
     *
     *
     * This method may invoke read repeatedly to fill the array and
     * only read less bytes than the length of the array if the end of
     * the stream has been reached.
     *
     * @param input stream to read from
     * @param array buffer to fill
     * @return the number of bytes actually read
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun readFully(input: AsyncInputStream, array: ByteArray): Int {
        return readFully(input, array, 0, array.size)
    }
}