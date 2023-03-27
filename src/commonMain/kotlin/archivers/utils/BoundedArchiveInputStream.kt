package archivers.utils

import com.soywiz.korio.lang.IOException
import com.soywiz.korio.stream.SyncInputStream
import utils.ByteBuffer
import kotlin.math.min

/**
 * NIO backed bounded input stream for reading a predefined amount of data from.
 * @ThreadSafe this base class is thread safe but implementations must not be.
 * @since 1.21
 */
abstract class BoundedArchiveInputStream(start: Long, remaining: Long) : SyncInputStream {
    private val end: Long
    private var singleByteBuffer: ByteBuffer? = null
    private var loc: Long

    /**
     * Create a new bounded input stream.
     *
     * @param start     position in the stream from where the reading of this bounded stream starts.
     * @param remaining amount of bytes which are allowed to read from the bounded stream.
     */
    init {
        end = start + remaining
        if (end < start) {
            // check for potential vulnerability due to overflow
            throw IllegalArgumentException("Invalid length of stream at offset=$start, length=$remaining")
        }
        loc = start
    }

    override fun read(): Int {
        if (loc >= end) {
            return -1
        }
        if (singleByteBuffer == null) {
            singleByteBuffer = ByteBuffer(1)
        } else {
            singleByteBuffer?.reset()
        }
        val read = read(loc, singleByteBuffer!!)
        if (read < 1) {
            return -1
        }
        loc++
        return singleByteBuffer!!.readByte() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        if (loc >= end) {
            return -1
        }
        val maxLen: Long = min(len.toLong(), end - loc)
        if (maxLen <= 0) {
            return 0
        }
        if (offset < 0 || offset > buffer.size || maxLen > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset or len are out of bounds")
        }
        val buf: ByteBuffer = ByteBuffer.wrap(buffer, offset, maxLen.toInt())
        val ret = read(loc, buf)
        if (ret > 0) {
            loc += ret.toLong()
        }
        return ret
    }

    /**
     * Read content of the stream into a [ByteBuffer].
     * @param pos position to start the read.
     * @param buf buffer to add the read content.
     * @return number of read bytes.
     * @throws IOException if I/O fails.
     */
    @Throws(IOException::class)
    protected abstract fun read(pos: Long, buf: ByteBuffer): Int
}

