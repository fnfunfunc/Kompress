package utils

import okio.Buffer
import okio.Source

sealed interface SimpleChecksum {
    val initialValue: Int
    fun update(old: Int, data: ByteArray, offset: Int = 0, len: Int = data.size - offset): Int
}

object CRC32 : SimpleChecksum {
    override val initialValue = 0

    internal val TABLE = IntArray(0x100) {
        var c = it
        for (k in 0 until 8) c = (if ((c and 1) != 0) -0x12477ce0 xor (c ushr 1) else c ushr 1)
        c
    }

    override fun update(old: Int, data: ByteArray, offset: Int, len: Int): Int {
        var c = old.inv()
        val table = TABLE
        for (n in offset until offset + len) c = table[(c xor (data[n].toInt() and 0xFF)) and 0xff] xor (c ushr 8)
        return c.inv()
    }
}

fun SimpleChecksum.compute(data: ByteArray, offset: Int = 0, len: Int = data.size - offset) = update(initialValue, data, offset, len)

fun ByteArray.checksum(checksum: SimpleChecksum): Int = checksum.compute(this)

fun Source.checksum(checksum: SimpleChecksum): Int {
    var value = checksum.initialValue
    val temp = Buffer()
    while (true) {
        val read = this.read(temp, 1024)
        if (read <= 0) break
        value = checksum.update(value, temp.readByteArray(), 0, read.toInt())
    }

    return value
}