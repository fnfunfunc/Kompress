package archivers.sevenzip.lzma

import archivers.sevenzip.lz.OutWindow
import archivers.sevenzip.rangeCoder.BitTreeDecoder
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import kotlin.math.max
import archivers.sevenzip.rangeCoder.Decoder as RangeDecoder

class LzmaDecoder {
    inner class LenDecoder {
        var m_Choice = ShortArray(2)
        var m_LowCoder: Array<BitTreeDecoder?> = arrayOfNulls<BitTreeDecoder>(Base.kNumPosStatesMax)
        var m_MidCoder: Array<BitTreeDecoder?> = arrayOfNulls<BitTreeDecoder>(Base.kNumPosStatesMax)
        var m_HighCoder: BitTreeDecoder = BitTreeDecoder(Base.kNumHighLenBits)
        var m_NumPosStates = 0
        fun create(numPosStates: Int) {
            while (m_NumPosStates < numPosStates) {
                m_LowCoder[m_NumPosStates] = BitTreeDecoder(Base.kNumLowLenBits)
                m_MidCoder[m_NumPosStates] = BitTreeDecoder(Base.kNumMidLenBits)
                m_NumPosStates++
            }
        }

        fun Init() {
            RangeDecoder.InitBitModels(m_Choice)
            for (posState in 0 until m_NumPosStates) {
                m_LowCoder[posState]!!.Init()
                m_MidCoder[posState]!!.Init()
            }
            m_HighCoder.Init()
        }

        @Throws(IOException::class)
        fun decode(rangeDecoder: RangeDecoder, posState: Int): Int {
            if (rangeDecoder.DecodeBit(m_Choice, 0) == 0) return m_LowCoder[posState]!!.Decode(rangeDecoder)
            var symbol = Base.kNumLowLenSymbols
            symbol += if (rangeDecoder.DecodeBit(m_Choice, 1) == 0)
                m_MidCoder[posState]!!.Decode(rangeDecoder) else Base.kNumMidLenSymbols + m_HighCoder.Decode(
                rangeDecoder
            )
            return symbol
        }
    }

    inner class LiteralDecoder {
        inner class Decoder2 {
            var m_Decoders = ShortArray(0x300)
            fun init() {
                RangeDecoder.InitBitModels(m_Decoders)
            }

            @Throws(IOException::class)
            fun decodeNormal(rangeDecoder: RangeDecoder): Byte {
                var symbol = 1
                do symbol = symbol shl 1 or rangeDecoder.DecodeBit(m_Decoders, symbol) while (symbol < 0x100)
                return symbol.toByte()
            }

            @Throws(IOException::class)
            fun decodeWithMatchByte(rangeDecoder: RangeDecoder, matchByte: Byte): Byte {
                var matchByte = matchByte
                var symbol = 1
                do {
                    val matchBit = matchByte.toInt() shr 7 and 1
                    matchByte = (matchByte.toInt() shl 1).toByte()
                    val bit: Int = rangeDecoder.DecodeBit(m_Decoders, (1 + matchBit shl 8) + symbol)
                    symbol = symbol shl 1 or bit
                    if (matchBit != bit) {
                        while (symbol < 0x100) symbol = symbol shl 1 or rangeDecoder.DecodeBit(m_Decoders, symbol)
                        break
                    }
                } while (symbol < 0x100)
                return symbol.toByte()
            }
        }

        var m_Coders: Array<Decoder2?>? = null
        var m_NumPrevBits = 0
        var m_NumPosBits = 0
        var m_PosMask = 0
        fun Create(numPosBits: Int, numPrevBits: Int) {
            if (m_Coders != null && m_NumPrevBits == numPrevBits && m_NumPosBits == numPosBits) return
            m_NumPosBits = numPosBits
            m_PosMask = (1 shl numPosBits) - 1
            m_NumPrevBits = numPrevBits
            val numStates = 1 shl m_NumPrevBits + m_NumPosBits
            m_Coders = arrayOfNulls(numStates)
            for (i in 0 until numStates) m_Coders!![i] = Decoder2()
        }

        fun init() {
            val numStates = 1 shl m_NumPrevBits + m_NumPosBits
            for (i in 0 until numStates) m_Coders!![i]!!.init()
        }

        fun GetDecoder(pos: Int, prevByte: Byte): Decoder2? {
            return m_Coders!![(pos and m_PosMask shl m_NumPrevBits) + (prevByte.toInt() and 0xFF ushr 8 - m_NumPrevBits)]
        }
    }

    var m_OutWindow: OutWindow = OutWindow()
    var m_RangeDecoder: archivers.sevenzip.rangeCoder.Decoder = archivers.sevenzip.rangeCoder.Decoder()
    var m_IsMatchDecoders = ShortArray(Base.kNumStates shl Base.kNumPosStatesBitsMax)
    var m_IsRepDecoders = ShortArray(Base.kNumStates)
    var m_IsRepG0Decoders = ShortArray(Base.kNumStates)
    var m_IsRepG1Decoders = ShortArray(Base.kNumStates)
    var m_IsRepG2Decoders = ShortArray(Base.kNumStates)
    var m_IsRep0LongDecoders = ShortArray(Base.kNumStates shl Base.kNumPosStatesBitsMax)
    var m_PosSlotDecoder: Array<BitTreeDecoder?> = arrayOfNulls<BitTreeDecoder>(Base.kNumLenToPosStates)
    var m_PosDecoders = ShortArray(Base.kNumFullDistances - Base.kEndPosModelIndex)
    var m_PosAlignDecoder: BitTreeDecoder = BitTreeDecoder(Base.kNumAlignBits)
    var m_LenDecoder = LenDecoder()
    var m_RepLenDecoder = LenDecoder()
    var m_LiteralDecoder = LiteralDecoder()
    var m_DictionarySize = -1
    var m_DictionarySizeCheck = -1
    var m_PosStateMask = 0

    init {
        for (i in 0 until Base.kNumLenToPosStates) m_PosSlotDecoder[i] = BitTreeDecoder(Base.kNumPosSlotBits)
    }

    fun setDictionarySize(dictionarySize: Int): Boolean {
        if (dictionarySize < 0) return false
        if (m_DictionarySize != dictionarySize) {
            m_DictionarySize = dictionarySize
            m_DictionarySizeCheck = max(m_DictionarySize, 1)
            m_OutWindow.Create(max(m_DictionarySizeCheck, 1 shl 12))
        }
        return true
    }

    fun setLcLpPb(lc: Int, lp: Int, pb: Int): Boolean {
        if (lc > Base.kNumLitContextBitsMax || lp > 4 || pb > Base.kNumPosStatesBitsMax) return false
        m_LiteralDecoder.Create(lp, lc)
        val numPosStates = 1 shl pb
        m_LenDecoder.create(numPosStates)
        m_RepLenDecoder.create(numPosStates)
        m_PosStateMask = numPosStates - 1
        return true
    }

    @Throws(IOException::class)
    fun init() {
        m_OutWindow.Init(false)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_IsMatchDecoders)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_IsRep0LongDecoders)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_IsRepDecoders)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_IsRepG0Decoders)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_IsRepG1Decoders)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_IsRepG2Decoders)
        archivers.sevenzip.rangeCoder.Decoder.InitBitModels(m_PosDecoders)
        m_LiteralDecoder.init()
        var i: Int = 0
        while (i < Base.kNumLenToPosStates) {
            m_PosSlotDecoder[i]!!.Init()
            i++
        }
        m_LenDecoder.Init()
        m_RepLenDecoder.Init()
        m_PosAlignDecoder.Init()
        m_RangeDecoder.Init()
    }

    @Throws(IOException::class)
    fun code(
        inStream: BufferedSource, outStream: BufferedSink,
        outSize: Long
    ): Boolean {
        m_RangeDecoder.SetStream(inStream)
        m_OutWindow.SetStream(outStream)
        init()
        var state = Base.StateInit()
        var rep0 = 0
        var rep1 = 0
        var rep2 = 0
        var rep3 = 0
        var nowPos64: Long = 0
        var prevByte: Byte = 0
        while (outSize < 0 || nowPos64 < outSize) {
            val posState = nowPos64.toInt() and m_PosStateMask
            if (m_RangeDecoder.DecodeBit(m_IsMatchDecoders, (state shl Base.kNumPosStatesBitsMax) + posState) == 0) {
                val decoder2: LiteralDecoder.Decoder2? = m_LiteralDecoder.GetDecoder(nowPos64.toInt(), prevByte)
                prevByte =
                    if (!Base.StateIsCharState(state)) decoder2!!.decodeWithMatchByte(
                        m_RangeDecoder,
                        m_OutWindow.GetByte(rep0)
                    ) else decoder2!!.decodeNormal(m_RangeDecoder)
                m_OutWindow.PutByte(prevByte)
                state = Base.StateUpdateChar(state)
                nowPos64++
            } else {
                var len: Int
                if (m_RangeDecoder.DecodeBit(m_IsRepDecoders, state) == 1) {
                    len = 0
                    if (m_RangeDecoder.DecodeBit(m_IsRepG0Decoders, state) == 0) {
                        if (m_RangeDecoder.DecodeBit(
                                m_IsRep0LongDecoders,
                                (state shl Base.kNumPosStatesBitsMax) + posState
                            ) == 0
                        ) {
                            state = Base.StateUpdateShortRep(state)
                            len = 1
                        }
                    } else {
                        var distance: Int
                        if (m_RangeDecoder.DecodeBit(m_IsRepG1Decoders, state) == 0) distance = rep1 else {
                            if (m_RangeDecoder.DecodeBit(m_IsRepG2Decoders, state) == 0) distance = rep2 else {
                                distance = rep3
                                rep3 = rep2
                            }
                            rep2 = rep1
                        }
                        rep1 = rep0
                        rep0 = distance
                    }
                    if (len == 0) {
                        len = m_RepLenDecoder.decode(m_RangeDecoder, posState) + Base.kMatchMinLen
                        state = Base.StateUpdateRep(state)
                    }
                } else {
                    rep3 = rep2
                    rep2 = rep1
                    rep1 = rep0
                    len = Base.kMatchMinLen + m_LenDecoder.decode(m_RangeDecoder, posState)
                    state = Base.StateUpdateMatch(state)
                    val posSlot: Int = m_PosSlotDecoder[Base.GetLenToPosState(len)]!!.Decode(m_RangeDecoder)
                    if (posSlot >= Base.kStartPosModelIndex) {
                        val numDirectBits = (posSlot shr 1) - 1
                        rep0 = 2 or (posSlot and 1) shl numDirectBits
                        if (posSlot < Base.kEndPosModelIndex) rep0 += BitTreeDecoder.ReverseDecode(
                            m_PosDecoders,
                            rep0 - posSlot - 1, m_RangeDecoder, numDirectBits
                        ) else {
                            rep0 += m_RangeDecoder.DecodeDirectBits(
                                numDirectBits - Base.kNumAlignBits
                            ) shl Base.kNumAlignBits
                            rep0 += m_PosAlignDecoder.ReverseDecode(m_RangeDecoder)
                            if (rep0 < 0) {
                                if (rep0 == -1) break
                                return false
                            }
                        }
                    } else rep0 = posSlot
                }
                if (rep0 >= nowPos64 || rep0 >= m_DictionarySizeCheck) {
                    // m_OutWindow.Flush();
                    return false
                }
                m_OutWindow.CopyBlock(rep0, len)
                nowPos64 += len.toLong()
                prevByte = m_OutWindow.GetByte(0)
            }
        }
        m_OutWindow.Flush()
        m_OutWindow.ReleaseStream()
        m_RangeDecoder.ReleaseStream()
        return true
    }

    fun setDecoderProperties(properties: ByteArray): Boolean {
        if (properties.size < 5) return false
        val `val` = properties[0].toInt() and 0xFF
        val lc = `val` % 9
        val remainder = `val` / 9
        val lp = remainder % 5
        val pb = remainder / 5
        var dictionarySize = 0
        for (i in 0..3) dictionarySize += properties[1 + i].toInt() and 0xFF shl i * 8
        return if (!setLcLpPb(lc, lp, pb)) false else setDictionarySize(dictionarySize)
    }
}

