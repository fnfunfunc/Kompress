package archivers.sevenzip.rangeCoder

import okio.IOException


class BitTreeEncoder(var NumBitLevels: Int) {
    var Models: ShortArray

    init {
        Models = ShortArray(1 shl NumBitLevels)
    }

    fun Init() {
        Decoder.InitBitModels(Models)
    }

    @Throws(IOException::class)
    fun Encode(rangeEncoder: Encoder, symbol: Int) {
        var m = 1
        var bitIndex = NumBitLevels
        while (bitIndex != 0) {
            bitIndex--
            val bit = symbol ushr bitIndex and 1
            rangeEncoder.Encode(Models, m, bit)
            m = m shl 1 or bit
        }
    }

    @Throws(IOException::class)
    fun ReverseEncode(rangeEncoder: Encoder, symbol: Int) {
        var symbol = symbol

        var m = 1
        for (i in 0 until NumBitLevels) {
            val bit = symbol and 1
            rangeEncoder.Encode(Models, m, bit)
            m = m shl 1 or bit
            symbol = symbol shr 1
        }
    }

    fun GetPrice(symbol: Int): Int {
        var price = 0
        var m = 1
        var bitIndex = NumBitLevels
        while (bitIndex != 0) {
            bitIndex--
            val bit = symbol ushr bitIndex and 1
            price += Encoder.GetPrice(Models[m].toInt(), bit)
            m = (m shl 1) + bit
        }
        return price
    }

    fun ReverseGetPrice(symbol: Int): Int {
        var symbol = symbol
        var price = 0
        var m = 1
        for (i in NumBitLevels downTo 1) {
            val bit = symbol and 1
            symbol = symbol ushr 1
            price += Encoder.GetPrice(Models[m].toInt(), bit)
            m = m shl 1 or bit
        }
        return price
    }

    companion object {
        fun ReverseGetPrice(
            Models: ShortArray, startIndex: Int,
            NumBitLevels: Int, symbol: Int
        ): Int {
            var symbol = symbol
            var price = 0
            var m = 1
            for (i in NumBitLevels downTo 1) {
                val bit = symbol and 1
                symbol = symbol ushr 1
                price += Encoder.GetPrice(Models[startIndex + m].toInt(), bit)
                m = m shl 1 or bit
            }
            return price
        }

        @Throws(IOException::class)
        fun ReverseEncode(
            Models: ShortArray?, startIndex: Int,
            rangeEncoder: Encoder, NumBitLevels: Int, symbol: Int
        ) {
            var symbol = symbol
            var m = 1
            for (i in 0 until NumBitLevels) {
                val bit = symbol and 1
                rangeEncoder.Encode(Models!!, startIndex + m, bit)
                m = m shl 1 or bit
                symbol = symbol shr 1
            }
        }
    }
}

