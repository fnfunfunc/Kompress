package archivers.utils

import com.soywiz.korio.stream.AsyncInputStream


/**
 * A stream that limits reading from a wrapped stream to a given number of bytes.
 * @NotThreadSafe
 * @since 1.6
 */
class BoundedAsyncInputStream(private val `in`: AsyncInputStream, size: Long) : AsyncInputStream {

    /**
     * @return bytes remaining to read
     * @since 1.21
     */
    var bytesRemaining: Long
        private set

    /**
     * Creates the stream that will at most read the given amount of
     * bytes from the given stream.
     * @param in the stream to read from
     * @param size the maximum amount of bytes to read
     */
    init {
        bytesRemaining = size
    }

    override suspend fun close() {
        // there isn't anything to close in this stream and the nested
        // stream is controlled externally
        `in`.close()
    }

    override suspend fun read(): Int {
        if (bytesRemaining > 0) {
            --bytesRemaining
            return `in`.read()
        }
        return -1
    }

    override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return -1
        }
        var bytesToRead = len
        if (bytesToRead > bytesRemaining) {
            bytesToRead = bytesRemaining.toInt()
        }
        val bytesRead: Int = `in`.read(buffer, offset, bytesToRead)
        if (bytesRead >= 0) {
            bytesRemaining -= bytesRead.toLong()
        }
        return bytesRead
    }

}
