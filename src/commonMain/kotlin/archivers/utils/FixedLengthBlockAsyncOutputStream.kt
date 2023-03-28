package archivers.utils

import com.soywiz.korio.stream.AsyncOutputStream
import utils.Buffer
import kotlin.math.min

class FixedLengthBlockAsyncOutputStream: AsyncOutputStream {

    private var buffer: Buffer

    private val blockSize: Int

    private val outputStream: AsyncOutputStream

    /**
     * Create a fixed length block output stream with given destination stream and block size
     * @param os   The stream to wrap.
     * @param blockSize The block size to use.
     */
    constructor(os: AsyncOutputStream, blockSize: Int) {
        outputStream = os
        buffer = Buffer(blockSize)
        this.blockSize = blockSize
    }



    private suspend fun maybeFlush() {
        if (buffer.availableWrite == 0) {
            writeBlock()
        }
    }

    /**
     * Potentially pads and then writes the current block to the underlying stream.
     * @throws IOException if writing fails
     */
    suspend fun flushBlock() {
        if (buffer.writePosition() != 0) {
            padBlock()
            writeBlock()
        }
    }

    private fun padBlock() {
        var bytesToWrite: Int = buffer.availableWrite
        if (bytesToWrite > 8) {
            val align: Int = buffer.writePosition() and 7
            if (align != 0) {
                val limit = 8 - align
                for (i in 0 until limit) {
                    buffer.writeByte(0)
                }
                bytesToWrite -= limit
            }
            val byteArray = ByteArray(8)
            while (bytesToWrite >= 8) {
                buffer.write(byteArray)
                bytesToWrite -= 8
            }
        }
        while (buffer.availableWrite > 0) {
            buffer.writeByte(0)
        }
    }

    private suspend fun writeBlock() {
        outputStream.write(buffer.internalBuffer)
//        val i: Int = out.write(buffer)
//        val hasRemaining: Boolean = buffer.hasRemaining()
//        if (i != blockSize || hasRemaining) {
//            val msg: String = String.format(
//                "Failed to write %,d bytes atomically. Only wrote  %,d",
//                blockSize, i
//            )
//            throw java.io.IOException(msg)
//        }
        buffer.reset()
    }

    override suspend fun write(buffer: ByteArray, offset: Int, len: Int) {
        var off = offset
        var len: Int = len
        while (len > 0) {
            val n: Int = min(len, buffer.size)
            this.buffer.write(buffer, off, n)
            maybeFlush()
            len -= n
            off += n
        }
    }

    suspend fun flush() {
        maybeFlush()
    }

    override suspend fun close() {
        outputStream.close()
    }
}