package utils


import com.soywiz.kds.internal.KdsInternalApi
import com.soywiz.kmem.arraycopy
import com.soywiz.korio.stream.openSync
import kotlin.jvm.JvmOverloads
import kotlin.math.min

class Buffer(totalSize: Int, buffer: ByteArray = ByteArray(totalSize)) : ByteBuffer(totalSize, buffer)

@OptIn(KdsInternalApi::class)
open class ByteBuffer(val totalSize: Int, private val buffer: ByteArray = ByteArray(totalSize)) {

    private var readPos = 0
    private var writePos = 0
    var availableWrite = totalSize; private set
    var availableRead = 0; private set

    val internalBuffer get() = buffer

    private val internalReadPos get() = readPos umod totalSize

    private val internalWritePos get() = writePos umod totalSize

    @KdsInternalApi
    private fun internalWriteSkip(count: Int) {
        if (count < 0 || count > availableWrite) error("Try to write more than available")
        writePos += count
        availableRead += count
        availableWrite -= count
    }

    @KdsInternalApi
    fun internalReadSkip(count: Int) {
        if (count < 0 || count > availableRead) error("Try to write more than available")
        readPos += count
        availableRead -= count
        availableWrite += count
    }

    val availableReadBeforeWrap: Int get() = min(availableRead, (totalSize - (readPos umod totalSize)))
    val availableWriteBeforeWrap: Int get() = min(availableWrite, (totalSize - (readPos umod totalSize)))

    fun readPosition() = readPos

    fun writePosition() = writePos

    fun write(consume: ByteBuffer) {
        while (consume.availableRead > 0) {
            val copySize = min(consume.availableReadBeforeWrap, this.availableWriteBeforeWrap)
            arraycopy(
                consume.internalBuffer,
                consume.internalReadPos,
                this.internalBuffer,
                this.internalWritePos,
                copySize
            )
            consume.internalReadSkip(copySize)
            this.internalWriteSkip(copySize)
        }
    }

    // @TODO: Optimize
    @JvmOverloads
    fun writeHead(data: ByteArray, offset: Int = 0, size: Int = data.size - offset): Int {
        val toWrite = min(availableWrite, size)
        for (n in 0 until toWrite) {
            readPos = (readPos - 1) umod totalSize
            buffer[readPos] = data[offset + size - n - 1]
        }
        availableRead += toWrite
        availableWrite -= toWrite
        return toWrite
    }

    @JvmOverloads
    fun write(data: ByteArray, offset: Int = 0, size: Int = data.size - offset): Int {
        var remaining = min(availableWrite, size)
        var coffset = offset
        var totalWrite = 0
        while (remaining > 0) {
            val chunkSize = min(remaining, availableWriteBeforeWrap)
            if (chunkSize <= 0) break
            arraycopy(data, coffset, buffer, internalWritePos, chunkSize)
            internalWriteSkip(chunkSize)
            coffset += chunkSize
            remaining -= chunkSize
            totalWrite += chunkSize
        }
        return totalWrite
    }

    @JvmOverloads
    fun read(data: ByteArray, offset: Int = 0, size: Int = data.size - offset): Int = skip(peek(data, offset, size))

    fun seekReadTo(readPosition: Int) {
        val goto = readPosition.coerceIn(0 until readPos + availableRead)
        val offset = goto - readPos
        readPos = goto
        availableRead += offset
        availableWrite -= offset
    }

    fun seekWriteTo(writePosition: Int) {
        val goto = writePosition.coerceIn(0 until writePos + availableWrite)
        val offset = goto - writePos
        writePos = goto
        availableRead -= offset
        availableWrite += offset
    }

    fun skip(size: Int): Int {
        val toRead = min(availableRead, size)
        readPos = (readPos + toRead) umod totalSize
        availableWrite += toRead
        availableRead -= toRead
        return toRead
    }

    fun peek(data: ByteArray, offset: Int = 0, size: Int = data.size - offset): Int {
        var toRead = min(availableRead, size)
        var readCount = 0
        val buffer = buffer
        var coffset = offset
        var lReadPos = readPos

        while (true) {
            val toReadChunk = min(toRead, availableReadBeforeWrap)
            if (toReadChunk <= 0) break
            arraycopy(buffer, lReadPos umod totalSize, data, coffset, toReadChunk)
            toRead -= toReadChunk
            coffset += toReadChunk
            lReadPos += toReadChunk
            readCount += toReadChunk
        }
        return readCount
    }

    fun readBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        return out.copyOf(read(out))
    }

    fun readByte(): Int {
        if (availableRead <= 0) return -1
        val out = buffer[readPos].toInt() and 0xFF
        readPos = (readPos + 1) umod totalSize
        availableRead--
        availableWrite++
        return out
    }

    fun writeByte(v: Int): Boolean {
        if (availableWrite <= 0) return false
        buffer[writePos] = v.toByte()
        writePos = (writePos + 1) umod totalSize
        availableWrite--
        availableRead++
        return true
    }

    fun reset() {
        readPos = 0
        writePos = 0
        availableRead = 0
        availableWrite = totalSize
    }

    fun peek(offset: Int = 0) = buffer[(readPos + offset) umod totalSize]
    override fun equals(other: Any?): Boolean = (other is ByteBuffer) && this.availableRead == other.availableRead && equaler(availableRead) { this.peek(it) == other.peek(it) }
    override fun hashCode(): Int = contentHashCode()
    fun contentHashCode(): Int = hashCoder(availableRead) { peek(it).toInt() }

    fun openSync() = internalBuffer.openSync()

    companion object {
        fun wrap(byteArray: ByteArray, offset: Int = 0, length: Int = byteArray.size - offset) =
            Buffer(totalSize = length, buffer = byteArray.sliceArray(offset, length))

    }
}

private inline fun hashCoder(count: Int, gen: (index: Int) -> Int): Int {
    var out = 0
    for (n in 0 until count) {
        out *= 7
        out += gen(n)
    }
    return out
}

private inline fun equaler(count: Int, gen: (index: Int) -> Boolean): Boolean {
    for (n in 0 until count) if (!gen(n)) return false
    return true
}