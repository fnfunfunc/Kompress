package utils

import okio.Buffer
import okio.BufferedSource
import kotlin.math.max
import kotlin.math.min

//class BitReader(
//    val bufferedSource: BufferedSource,
//    val bigChunkSize: Int = BIG_CHUNK_SIZE,
//    val readWithSize: Int = READ_WHEN_LESS_THAN
//) {
//
//    var bitData = 0
//
//    var bitsAvailable = 0
//
//    inline fun discardBits(): BitReader {
//        this.bitData = 0
//        this.bitsAvailable = 0
//        return this
//    }
//
//    private val sbuffers = Buffer()
//    private var sbuffersReadPos = 0.0
//    private var sbuffersPos = 0.0
//
//    val requirePrepare get() = sbuffers.size < readWithSize
//
//    fun internalPeekBytes(out: ByteArray, offset: Int = 0, size: Int = out.size - offset): ByteArray {
//        sbuffers.peek().read(sink = out, offset, size)
//        return out
//    }
//
//    fun returnToBuffer(data: ByteArray, offset: Int, size: Int) {
//        sbuffers.write(data, offset, size)
//        sbuffersPos += size
//    }
//
//    fun ensureBits(bitcount: Int) {
//        while (this.bitsAvailable < bitcount) {
//            this.bitData = this.bitData or (_su8() shl this.bitsAvailable)
//            this.bitsAvailable += 8
//        }
//    }
//
//
//    fun peekBits(bitcount: Int): Int {
//        return this.bitData and ((1 shl bitcount) - 1)
//    }
//
//
//    fun skipBits(bitcount: Int) {
//        this.bitData = this.bitData ushr bitcount
//        this.bitsAvailable -= bitcount
//    }
//
//    fun readBits(bitcount: Int): Int {
//        ensureBits(bitcount)
//        val readed = peekBits(bitcount)
//        skipBits(bitcount)
//        return readed
//    }
//
//    fun sreadBit(): Boolean = readBits(1) != 0
//
//    //var lastReadByte = 0
//
//    private inline fun _su8(): Int {
//        sbuffersReadPos++
//        return sbuffers.readByte().toInt()
//    }
//
//    fun sbytes_noalign(count: Int, out: ByteArray) {
//        var offset = 0
//        var count = count
//        if (bitsAvailable >= 8) {
//            if (bitsAvailable % 8 != 0) {
//                val bits = (bitsAvailable % 8)
//                skipBits(bits)
//            }
//            while (bitsAvailable >= 8) {
//                val byte = readBits(8).toByte()
//                out[offset++] = byte
//                count--
//            }
//        }
//        discardBits()
//        val readCount = sbuffers.read(out, offset, count)
//        if (readCount > 0) sbuffersReadPos += readCount
//        //for (n in 0 until count) out[offset + n] = _su8().toByte()
//    }
//
//    fun sbytes(count: Int): ByteArray = sbytes(count, ByteArray(count))
//    fun sbytes(count: Int, out: ByteArray): ByteArray {
//        sbytes_noalign(count, out)
//        return out
//    }
//    fun su8(): Int = discardBits()._su8()
//    fun su16LE(): Int {
//        sbytes_noalign(2, temp)
//        return temp.readU16LE(0)
//    }
//    fun su32LE(): Int {
//        sbytes_noalign(4, temp)
//        return temp.readS32LE(0)
//    }
//    fun su32BE(): Int {
//        sbytes_noalign(4, temp)
//        return temp.readS32BE(0)
//    }
//
//    private val temp = ByteArray(4)
//    suspend fun abytes(count: Int, out: ByteArray = ByteArray(count)): ByteArray {
//        prepareBytesUpTo(count)
//        return sbytes(count, out)
//    }
//
//    suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
//        prepareBytesUpTo(len)
//        val out = sbuffers.read(buffer, offset, len)
//        sbuffersReadPos += out
//        return out
//    }
//
//
//
//    companion object {
//        const val BIG_CHUNK_SIZE = 8 * 1024 * 1024 // 8 MB
//
//        const val READ_WHEN_LESS_THAN = 32 * 1024
//
//        suspend fun fromSource(source: BufferedSource): BitReader {
//            val bigChunkSize = max(READ_WHEN_LESS_THAN, min(source.buffer.size, BIG_CHUNK_SIZE.toLong()).toInt())
//            val readWithSize = max(bigChunkSize / 2, READ_WHEN_LESS_THAN)
//            return BitReader(source, bigChunkSize, readWithSize)
//        }
//    }
//}