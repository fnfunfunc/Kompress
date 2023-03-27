package archivers.utils

import com.soywiz.korio.stream.AsyncOutputStream
import utils.Buffer

class FixedLengthBlockAsyncOutputStream {

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

    @Throws(java.io.IOException::class)
    private fun writeBlock() {
        buffer.flip()
        val i: Int = out.write(buffer)
        val hasRemaining: Boolean = buffer.hasRemaining()
        if (i != blockSize || hasRemaining) {
            val msg: String = String.format(
                "Failed to write %,d bytes atomically. Only wrote  %,d",
                blockSize, i
            )
            throw java.io.IOException(msg)
        }
        buffer.clear()
    }
}