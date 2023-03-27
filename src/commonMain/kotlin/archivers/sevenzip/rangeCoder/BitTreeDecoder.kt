package archivers.sevenzip.rangeCoder

import okio.IOException


class BitTreeDecoder(var NumBitLevels: Int) {
    var Models: ShortArray

    init {
        Models = ShortArray(1 shl NumBitLevels)
    }

    fun Init() {
        Decoder.InitBitModels(Models)
    }

    @Throws(IOException::class)
    fun Decode(rangeDecoder: Decoder): Int {
        var m = 1
        for (bitIndex in NumBitLevels downTo 1) m = (m shl 1) + rangeDecoder.DecodeBit(Models, m)
        return m - (1 shl NumBitLevels)
    }

    @Throws(IOException::class)
    fun ReverseDecode(rangeDecoder: Decoder): Int {
        var m = 1
        var symbol = 0
        for (bitIndex in 0 until NumBitLevels) {
            val bit: Int = rangeDecoder.DecodeBit(Models, m)
            m = m shl 1
            m += bit
            symbol = symbol or (bit shl bitIndex)
        }
        return symbol
    }

    companion object {
        @Throws(IOException::class)
        fun ReverseDecode(
            Models: ShortArray, startIndex: Int,
            rangeDecoder: Decoder, NumBitLevels: Int
        ): Int {
            var m = 1
            var symbol = 0
            for (bitIndex in 0 until NumBitLevels) {
                val bit: Int = rangeDecoder.DecodeBit(Models, startIndex + m)
                m = m shl 1
                m += bit
                symbol = symbol or (bit shl bitIndex)
            }
            return symbol
        }
    }
}
