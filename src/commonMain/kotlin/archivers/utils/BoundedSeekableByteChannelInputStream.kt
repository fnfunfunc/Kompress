package archivers.utils

import utils.ByteBuffer

/**
 * InputStream that delegates requests to the underlying SeekableByteChannel, making sure that only bytes from a certain
 * range can be read.
 * @ThreadSafe
 * @since 1.21
 */
class BoundedSeekableByteChannelInputStream(
    start: Long, remaining: Long,
    private val channel: ByteBuffer
) : BoundedArchiveInputStream(start, remaining) {

    /**
     * Create a bounded stream on the underlying [SeekableByteChannel]
     *
     * @param start     Position in the stream from where the reading of this bounded stream starts
     * @param remaining Amount of bytes which are allowed to read from the bounded stream
     * @param channel   Channel which the reads will be delegated to
     */

    override fun read(pos: Long, buf: ByteBuffer): Int {
        channel.seekReadTo(pos.toInt())
        // buf.flip()
        // TODO(Maybe check)
        val readAvailable = channel.availableRead
        buf.write(channel)
        return readAvailable
    }
}

