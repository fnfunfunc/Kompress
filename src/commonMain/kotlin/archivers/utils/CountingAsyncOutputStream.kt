package archivers.utils

import com.soywiz.korio.stream.AsyncOutputStream

class CountingAsyncOutputStream(private val outputStream: AsyncOutputStream): AsyncOutputStream {

    private var bytesWritten: Long = 0

    /**
     * Increments the counter of already written bytes.
     * Doesn't increment if the EOF has been hit (written == -1)
     *
     * @param written the number of bytes written
     */
    fun count(written: Long) {
        if (written != -1L) {
            bytesWritten += written
        }
    }

    /**
     * Returns the current number of bytes written to this stream.
     * @return the number of written bytes
     */
    fun getBytesWritten(): Long {
        return bytesWritten
    }


    override suspend fun write(buffer: ByteArray, offset: Int, len: Int) {
        outputStream.write(buffer, offset, len)
        count(len.toLong())
    }


    override suspend fun write(byte: Int) {
        outputStream.write(byte)
        count(1)
    }

    suspend fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override suspend fun close() {
        outputStream.close()
    }
}