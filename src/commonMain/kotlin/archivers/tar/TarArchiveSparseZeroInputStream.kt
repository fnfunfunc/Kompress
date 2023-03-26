package archivers.tar

import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout

class TarArchiveSparseZeroSource: Source {
    /**
     * Returns 0.
     *
     * @return 0
     * @throws IOException
     */
    @Throws(IOException::class)
    fun read(): Int {
        return 0
    }

    /**
     * Returns the input.
     *
     * @param n bytes to skip
     * @return bytes actually skipped
     */
    fun skip(n: Long): Long {
        return n
    }

    override fun close() {

    }

    override fun read(sink: Buffer, byteCount: Long): Long {
        return 0
    }

    override fun timeout(): Timeout {
        return Timeout.NONE
    }
}