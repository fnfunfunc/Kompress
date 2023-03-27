package archivers.tar

import com.soywiz.korio.stream.AsyncInputStream
import com.soywiz.korio.stream.SyncInputStream

class TarArchiveSparseZeroInputStream: SyncInputStream {
    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        return 0
    }

    override fun close() {

    }

    override fun read(): Int {
        return 0
    }
}

class TarArchiveSparseZeroAsyncInputStream: AsyncInputStream {
    override suspend fun close() {

    }

    override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        return 0
    }

    override suspend fun read(): Int {
        return 0
    }
}