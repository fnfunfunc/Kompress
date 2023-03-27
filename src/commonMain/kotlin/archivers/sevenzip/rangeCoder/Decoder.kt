package archivers.sevenzip.rangeCoder

import okio.BufferedSource
import okio.IOException
import utils.read


class Decoder {
    var Range = 0
    var Code = 0
    var Stream: BufferedSource? = null
    fun SetStream(stream: BufferedSource?) {
        Stream = stream
    }

    fun ReleaseStream() {
        Stream = null
    }

    @Throws(IOException::class)
    fun Init() {
        Code = 0
        Range = -1
        for (i in 0..4) Code = Code shl 8 or Stream!!.read()
    }

    @Throws(IOException::class)
    fun DecodeDirectBits(numTotalBits: Int): Int {
        var result = 0
        for (i in numTotalBits downTo 1) {
            Range = Range ushr 1
            val t = Code - Range ushr 31
            Code -= Range and t - 1
            result = result shl 1 or 1 - t
            if (Range and kTopMask == 0) {
                Code = Code shl 8 or Stream!!.read()
                Range = Range shl 8
            }
        }
        return result
    }

    @Throws(IOException::class)
    fun DecodeBit(probs: ShortArray, index: Int): Int {
        val prob = probs[index].toInt()
        val newBound = (Range ushr kNumBitModelTotalBits) * prob
        return if (Code xor -0x80000000 < newBound xor -0x80000000) {
            Range = newBound
            probs[index] = (prob + (kBitModelTotal - prob ushr kNumMoveBits)).toShort()
            if (Range and kTopMask == 0) {
                Code = Code shl 8 or Stream!!.read()
                Range = Range shl 8
            }
            0
        } else {
            Range -= newBound
            Code -= newBound
            probs[index] = (prob - (prob ushr kNumMoveBits)).toShort()
            if (Range and kTopMask == 0) {
                Code = Code shl 8 or Stream!!.read()
                Range = Range shl 8
            }
            1
        }
    }

    companion object {
        const val kTopMask = ((1 shl 24) - 1).inv()
        const val kNumBitModelTotalBits = 11
        const val kBitModelTotal = 1 shl kNumBitModelTotalBits
        const val kNumMoveBits = 5
        fun InitBitModels(probs: ShortArray) {
            for (i in probs.indices) probs[i] = (kBitModelTotal ushr 1).toShort()
        }
    }
}
