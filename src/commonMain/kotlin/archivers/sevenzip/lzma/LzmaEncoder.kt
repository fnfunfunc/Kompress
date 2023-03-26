package archivers.sevenzip.lzma

import archivers.sevenzip.ICodeProgress
import archivers.sevenzip.lz.BinTree
import archivers.sevenzip.rangeCoder.BitTreeEncoder
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import kotlin.math.min
import archivers.sevenzip.rangeCoder.Encoder as RangeEncoder

class Encoder {
    var _state = Base.StateInit()
    var _previousByte: Byte = 0
    var _repDistances = IntArray(Base.kNumRepDistances)
    fun BaseInit() {
        _state = Base.StateInit()
        _previousByte = 0
        for (i in 0 until Base.kNumRepDistances) _repDistances[i] = 0
    }

    inner class LiteralEncoder() {
        inner class Encoder2() {
            var m_Encoders = ShortArray(0x300)
            fun Init() {
                RangeEncoder.InitBitModels(m_Encoders)
            }

            @Throws(IOException::class)
            fun Encode(rangeEncoder: RangeEncoder, symbol: Byte) {
                var context = 1
                for (i in 7 downTo 0) {
                    val bit = symbol.toInt() shr i and 1
                    rangeEncoder.Encode(m_Encoders, context, bit)
                    context = context shl 1 or bit
                }
            }

            @Throws(IOException::class)
            fun EncodeMatched(rangeEncoder: RangeEncoder, matchByte: Byte, symbol: Byte) {
                var context = 1
                var same = true
                for (i in 7 downTo 0) {
                    val bit = symbol.toInt() shr i and 1
                    var state = context
                    if (same) {
                        val matchBit = matchByte.toInt() shr i and 1
                        state += 1 + matchBit shl 8
                        same = matchBit == bit
                    }
                    rangeEncoder.Encode(m_Encoders, state, bit)
                    context = context shl 1 or bit
                }
            }

            fun GetPrice(matchMode: Boolean, matchByte: Byte, symbol: Byte): Int {
                var price = 0
                var context = 1
                var i = 7
                if (matchMode) {
                    while (i >= 0) {
                        val matchBit = matchByte.toInt() shr i and 1
                        val bit = symbol.toInt() shr i and 1
                        price += RangeEncoder.GetPrice(
                            m_Encoders[((1 + matchBit) shl 8) + context].toInt(),
                            bit
                        )
                        context = context shl 1 or bit
                        if (matchBit != bit) {
                            i--
                            break
                        }
                        i--
                    }
                }
                while (i >= 0) {
                    val bit = symbol.toInt() shr i and 1
                    price += RangeEncoder.GetPrice(m_Encoders[context].toInt(), bit)
                    context = context shl 1 or bit
                    i--
                }
                return price
            }
        }

        var m_Coders: Array<Encoder2?>? = null
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
            for (i in 0 until numStates) m_Coders!![i] = Encoder2()
        }

        fun Init() {
            val numStates = 1 shl m_NumPrevBits + m_NumPosBits
            for (i in 0 until numStates) m_Coders!![i]!!.Init()
        }

        fun GetSubCoder(pos: Int, prevByte: Byte): Encoder2? {
            return m_Coders!![((pos and m_PosMask) shl m_NumPrevBits) + ((prevByte.toInt() and 0xFF) ushr (8 - m_NumPrevBits))]
        }
    }

    open inner class LenEncoder() {
        var _choice = ShortArray(2)
        var _lowCoder: Array<BitTreeEncoder?> = arrayOfNulls<BitTreeEncoder>(Base.kNumPosStatesEncodingMax)
        var _midCoder: Array<BitTreeEncoder?> = arrayOfNulls<BitTreeEncoder>(Base.kNumPosStatesEncodingMax)
        var _highCoder: BitTreeEncoder = BitTreeEncoder(Base.kNumHighLenBits)

        init {
            for (posState in 0 until Base.kNumPosStatesEncodingMax) {
                _lowCoder[posState] = BitTreeEncoder(Base.kNumLowLenBits)
                _midCoder[posState] = BitTreeEncoder(Base.kNumMidLenBits)
            }
        }

        fun Init(numPosStates: Int) {
            RangeEncoder.InitBitModels(_choice)
            for (posState in 0 until numPosStates) {
                _lowCoder[posState]!!.Init()
                _midCoder[posState]!!.Init()
            }
            _highCoder.Init()
        }

        @Throws(IOException::class)
        open fun Encode(rangeEncoder: RangeEncoder, symbol: Int, posState: Int) {
            var symbol = symbol
            if (symbol < Base.kNumLowLenSymbols) {
                rangeEncoder.Encode(_choice, 0, 0)
                _lowCoder[posState]!!.Encode(rangeEncoder, symbol)
            } else {
                symbol -= Base.kNumLowLenSymbols
                rangeEncoder.Encode(_choice, 0, 1)
                if (symbol < Base.kNumMidLenSymbols) {
                    rangeEncoder.Encode(_choice, 1, 0)
                    _midCoder[posState]!!.Encode(rangeEncoder, symbol)
                } else {
                    rangeEncoder.Encode(_choice, 1, 1)
                    _highCoder.Encode(rangeEncoder, symbol - Base.kNumMidLenSymbols)
                }
            }
        }

        fun SetPrices(posState: Int, numSymbols: Int, prices: IntArray, st: Int) {
            val a0: Int = RangeEncoder.GetPrice0(_choice[0].toInt())
            val a1: Int = RangeEncoder.GetPrice1(_choice[0].toInt())
            val b0: Int = a1 + RangeEncoder.GetPrice0(_choice[1].toInt())
            val b1: Int = a1 + RangeEncoder.GetPrice1(_choice[1].toInt())
            var i = 0
            i = 0
            while (i < Base.kNumLowLenSymbols) {
                if (i >= numSymbols) return
                prices[st + i] = a0 + _lowCoder[posState]!!.GetPrice(i)
                i++
            }
            while (i < Base.kNumLowLenSymbols + Base.kNumMidLenSymbols) {
                if (i >= numSymbols) return
                prices[st + i] = b0 + _midCoder[posState]!!.GetPrice(i - Base.kNumLowLenSymbols)
                i++
            }
            while (i < numSymbols) {
                prices[st + i] = b1 + _highCoder.GetPrice(i - Base.kNumLowLenSymbols - Base.kNumMidLenSymbols)
                i++
            }
        }
    }

    inner class LenPriceTableEncoder : LenEncoder() {
        var _prices = IntArray(Base.kNumLenSymbols shl Base.kNumPosStatesBitsEncodingMax)
        var _tableSize = 0
        var _counters = IntArray(Base.kNumPosStatesEncodingMax)
        fun SetTableSize(tableSize: Int) {
            _tableSize = tableSize
        }

        fun GetPrice(symbol: Int, posState: Int): Int {
            return _prices[posState * Base.kNumLenSymbols + symbol]
        }

        fun UpdateTable(posState: Int) {
            SetPrices(posState, _tableSize, _prices, posState * Base.kNumLenSymbols)
            _counters[posState] = _tableSize
        }

        fun UpdateTables(numPosStates: Int) {
            for (posState in 0 until numPosStates) UpdateTable(posState)
        }

        @Throws(IOException::class)
        override fun Encode(rangeEncoder: RangeEncoder, symbol: Int, posState: Int) {
            super.Encode(rangeEncoder, symbol, posState)
            if (--_counters[posState] == 0) UpdateTable(posState)
        }
    }

    inner class Optimal() {
        var State = 0
        var Prev1IsChar = false
        var Prev2 = false
        var PosPrev2 = 0
        var BackPrev2 = 0
        var Price = 0
        var PosPrev = 0
        var BackPrev = 0
        var Backs0 = 0
        var Backs1 = 0
        var Backs2 = 0
        var Backs3 = 0
        fun MakeAsChar() {
            BackPrev = -1
            Prev1IsChar = false
        }

        fun MakeAsShortRep() {
            BackPrev = 0
            Prev1IsChar = false
        }

        fun IsShortRep(): Boolean {
            return BackPrev == 0
        }
    }

    var _optimum = arrayOfNulls<Optimal>(kNumOpts)
    var _matchFinder: BinTree? = null
    var _rangeEncoder: RangeEncoder = RangeEncoder()
    var _isMatch = ShortArray(Base.kNumStates shl Base.kNumPosStatesBitsMax)
    var _isRep = ShortArray(Base.kNumStates)
    var _isRepG0 = ShortArray(Base.kNumStates)
    var _isRepG1 = ShortArray(Base.kNumStates)
    var _isRepG2 = ShortArray(Base.kNumStates)
    var _isRep0Long = ShortArray(Base.kNumStates shl Base.kNumPosStatesBitsMax)
    var _posSlotEncoder: Array<BitTreeEncoder?> =
        arrayOfNulls<BitTreeEncoder>(Base.kNumLenToPosStates) // kNumPosSlotBits
    var _posEncoders = ShortArray(Base.kNumFullDistances - Base.kEndPosModelIndex)
    var _posAlignEncoder: BitTreeEncoder = BitTreeEncoder(Base.kNumAlignBits)
    var _lenEncoder = LenPriceTableEncoder()
    var _repMatchLenEncoder = LenPriceTableEncoder()
    var _literalEncoder = LiteralEncoder()
    var _matchDistances = IntArray(Base.kMatchMaxLen * 2 + 2)
    var _numFastBytes = kNumFastBytesDefault
    var _longestMatchLength = 0
    var _numDistancePairs = 0
    var _additionalOffset = 0
    var _optimumEndIndex = 0
    var _optimumCurrentIndex = 0
    var _longestMatchWasFound = false
    var _posSlotPrices = IntArray(1 shl Base.kNumPosSlotBits + Base.kNumLenToPosStatesBits)
    var _distancesPrices = IntArray(Base.kNumFullDistances shl Base.kNumLenToPosStatesBits)
    var _alignPrices = IntArray(Base.kAlignTableSize)
    var _alignPriceCount = 0
    var _distTableSize = kDefaultDictionaryLogSize * 2
    var _posStateBits = 2
    var _posStateMask = 4 - 1
    var _numLiteralPosStateBits = 0
    var _numLiteralContextBits = 3
    var _dictionarySize = 1 shl kDefaultDictionaryLogSize
    var _dictionarySizePrev = -1
    var _numFastBytesPrev = -1
    var nowPos64: Long = 0
    var _finished = false
    var _inStream: BufferedSource? = null
    var _matchFinderType = EMatchFinderTypeBT4
    var _writeEndMark = false
    var _needReleaseMFStream = false
    fun Create() {
        if (_matchFinder == null) {
            val bt = BinTree()
            var numHashBytes = 4
            if (_matchFinderType == EMatchFinderTypeBT2) numHashBytes = 2
            bt.SetType(numHashBytes)
            _matchFinder = bt
        }
        _literalEncoder.Create(_numLiteralPosStateBits, _numLiteralContextBits)
        if (_dictionarySize == _dictionarySizePrev && _numFastBytesPrev == _numFastBytes) return
        _matchFinder!!.Create(_dictionarySize, kNumOpts, _numFastBytes, Base.kMatchMaxLen + 1)
        _dictionarySizePrev = _dictionarySize
        _numFastBytesPrev = _numFastBytes
    }

    fun SetWriteEndMarkerMode(writeEndMarker: Boolean) {
        _writeEndMark = writeEndMarker
    }

    fun Init() {
        BaseInit()
        _rangeEncoder.Init()
        RangeEncoder.InitBitModels(_isMatch)
        RangeEncoder.InitBitModels(_isRep0Long)
        RangeEncoder.InitBitModels(_isRep)
        RangeEncoder.InitBitModels(_isRepG0)
        RangeEncoder.InitBitModels(_isRepG1)
        RangeEncoder.InitBitModels(_isRepG2)
        RangeEncoder.InitBitModels(_posEncoders)
        _literalEncoder.Init()
        for (i in 0 until Base.kNumLenToPosStates) _posSlotEncoder[i]!!.Init()
        _lenEncoder.Init(1 shl _posStateBits)
        _repMatchLenEncoder.Init(1 shl _posStateBits)
        _posAlignEncoder.Init()
        _longestMatchWasFound = false
        _optimumEndIndex = 0
        _optimumCurrentIndex = 0
        _additionalOffset = 0
    }

    @Throws(IOException::class)
    fun ReadMatchDistances(): Int {
        var lenRes = 0
        _numDistancePairs = _matchFinder!!.GetMatches(_matchDistances)
        if (_numDistancePairs > 0) {
            lenRes = _matchDistances[_numDistancePairs - 2]
            if (lenRes == _numFastBytes) lenRes += _matchFinder!!.GetMatchLen(
                lenRes - 1,
                _matchDistances[_numDistancePairs - 1],
                Base.kMatchMaxLen - lenRes
            )
        }
        _additionalOffset++
        return lenRes
    }

    @Throws(IOException::class)
    fun MovePos(num: Int) {
        if (num > 0) {
            _matchFinder!!.Skip(num)
            _additionalOffset += num
        }
    }

    fun GetRepLen1Price(state: Int, posState: Int): Int {
        return RangeEncoder.GetPrice0(_isRepG0[state].toInt()) +
                RangeEncoder.GetPrice0(_isRep0Long[(state shl Base.kNumPosStatesBitsMax) + posState].toInt())
    }

    fun GetPureRepPrice(repIndex: Int, state: Int, posState: Int): Int {
        var price: Int
        if (repIndex == 0) {
            price = RangeEncoder.GetPrice0(_isRepG0[state].toInt())
            price += RangeEncoder.GetPrice1(_isRep0Long[(state shl Base.kNumPosStatesBitsMax) + posState].toInt())
        } else {
            price = RangeEncoder.GetPrice1(_isRepG0[state].toInt())
            if (repIndex == 1) price += RangeEncoder.GetPrice0(_isRepG1[state].toInt()) else {
                price += RangeEncoder.GetPrice1(_isRepG1[state].toInt())
                price += RangeEncoder.GetPrice(_isRepG2[state].toInt(), repIndex - 2)
            }
        }
        return price
    }

    fun GetRepPrice(repIndex: Int, len: Int, state: Int, posState: Int): Int {
        val price = _repMatchLenEncoder.GetPrice(len - Base.kMatchMinLen, posState)
        return price + GetPureRepPrice(repIndex, state, posState)
    }

    fun GetPosLenPrice(pos: Int, len: Int, posState: Int): Int {
        val price: Int
        val lenToPosState = Base.GetLenToPosState(len)
        if (pos < Base.kNumFullDistances) price =
            _distancesPrices[(lenToPosState * Base.kNumFullDistances) + pos] else price =
            _posSlotPrices[(lenToPosState shl Base.kNumPosSlotBits) + GetPosSlot2(pos)] +
                    _alignPrices[pos and Base.kAlignMask]
        return price + _lenEncoder.GetPrice(len - Base.kMatchMinLen, posState)
    }

    fun Backward(cur: Int): Int {
        var cur = cur
        _optimumEndIndex = cur
        var posMem = _optimum[cur]!!.PosPrev
        var backMem = _optimum[cur]!!.BackPrev
        do {
            if (_optimum[cur]!!.Prev1IsChar) {
                _optimum[posMem]!!.MakeAsChar()
                _optimum[posMem]!!.PosPrev = posMem - 1
                if (_optimum[cur]!!.Prev2) {
                    _optimum[posMem - 1]!!.Prev1IsChar = false
                    _optimum[posMem - 1]!!.PosPrev = _optimum[cur]!!.PosPrev2
                    _optimum[posMem - 1]!!.BackPrev = _optimum[cur]!!.BackPrev2
                }
            }
            val posPrev = posMem
            val backCur = backMem
            backMem = _optimum[posPrev]!!.BackPrev
            posMem = _optimum[posPrev]!!.PosPrev
            _optimum[posPrev]!!.BackPrev = backCur
            _optimum[posPrev]!!.PosPrev = cur
            cur = posPrev
        } while (cur > 0)
        backRes = _optimum[0]!!.BackPrev
        _optimumCurrentIndex = _optimum[0]!!.PosPrev
        return _optimumCurrentIndex
    }

    var reps = IntArray(Base.kNumRepDistances)
    var repLens = IntArray(Base.kNumRepDistances)
    var backRes = 0

    @Throws(IOException::class)
    fun GetOptimum(position: Int): Int {
        var position = position
        if (_optimumEndIndex != _optimumCurrentIndex) {
            val lenRes = _optimum[_optimumCurrentIndex]!!.PosPrev - _optimumCurrentIndex
            backRes = _optimum[_optimumCurrentIndex]!!.BackPrev
            _optimumCurrentIndex = _optimum[_optimumCurrentIndex]!!.PosPrev
            return lenRes
        }
        _optimumEndIndex = 0
        _optimumCurrentIndex = _optimumEndIndex
        val lenMain: Int
        var numDistancePairs: Int
        if (!_longestMatchWasFound) {
            lenMain = ReadMatchDistances()
        } else {
            lenMain = _longestMatchLength
            _longestMatchWasFound = false
        }
        numDistancePairs = _numDistancePairs
        var numAvailableBytes: Int = _matchFinder!!.GetNumAvailableBytes() + 1
        if (numAvailableBytes < 2) {
            backRes = -1
            return 1
        }
        if (numAvailableBytes > Base.kMatchMaxLen) numAvailableBytes = Base.kMatchMaxLen
        var repMaxIndex = 0
        var i: Int
        i = 0
        while (i < Base.kNumRepDistances) {
            reps[i] = _repDistances[i]
            repLens[i] = _matchFinder!!.GetMatchLen(0 - 1, reps[i], Base.kMatchMaxLen)
            if (repLens[i] > repLens[repMaxIndex]) repMaxIndex = i
            i++
        }
        if (repLens[repMaxIndex] >= _numFastBytes) {
            backRes = repMaxIndex
            val lenRes = repLens[repMaxIndex]
            MovePos(lenRes - 1)
            return lenRes
        }
        if (lenMain >= _numFastBytes) {
            backRes = _matchDistances[numDistancePairs - 1] + Base.kNumRepDistances
            MovePos(lenMain - 1)
            return lenMain
        }
        var currentByte: Byte = _matchFinder!!.GetIndexByte(0 - 1)
        var matchByte: Byte = _matchFinder!!.GetIndexByte(0 - _repDistances[0] - 1 - 1)
        if (lenMain < 2 && currentByte != matchByte && repLens[repMaxIndex] < 2) {
            backRes = -1
            return 1
        }
        _optimum[0]!!.State = _state
        var posState = position and _posStateMask
        _optimum[1]!!.Price =
            RangeEncoder.GetPrice0(_isMatch[(_state shl Base.kNumPosStatesBitsMax) + posState].toInt()) +
                    _literalEncoder.GetSubCoder(position, _previousByte)!!
                        .GetPrice(!Base.StateIsCharState(_state), matchByte, currentByte)
        _optimum[1]!!.MakeAsChar()
        var matchPrice: Int =
            RangeEncoder.GetPrice1(_isMatch[(_state shl Base.kNumPosStatesBitsMax) + posState].toInt())
        var repMatchPrice: Int = matchPrice + RangeEncoder.GetPrice1(_isRep[_state].toInt())
        if (matchByte == currentByte) {
            val shortRepPrice = repMatchPrice + GetRepLen1Price(_state, posState)
            if (shortRepPrice < _optimum[1]!!.Price) {
                _optimum[1]!!.Price = shortRepPrice
                _optimum[1]!!.MakeAsShortRep()
            }
        }
        var lenEnd = if (lenMain >= repLens[repMaxIndex]) lenMain else repLens[repMaxIndex]
        if (lenEnd < 2) {
            backRes = _optimum[1]!!.BackPrev
            return 1
        }
        _optimum[1]!!.PosPrev = 0
        _optimum[0]!!.Backs0 = reps[0]
        _optimum[0]!!.Backs1 = reps[1]
        _optimum[0]!!.Backs2 = reps[2]
        _optimum[0]!!.Backs3 = reps[3]
        var len = lenEnd
        do _optimum[len--]!!.Price = kIfinityPrice while (len >= 2)
        i = 0
        while (i < Base.kNumRepDistances) {
            var repLen = repLens[i]
            if (repLen < 2) {
                i++
                continue
            }
            val price = repMatchPrice + GetPureRepPrice(i, _state, posState)
            do {
                val curAndLenPrice = price + _repMatchLenEncoder.GetPrice(repLen - 2, posState)
                val optimum = _optimum[repLen]
                if (curAndLenPrice < optimum!!.Price) {
                    optimum.Price = curAndLenPrice
                    optimum.PosPrev = 0
                    optimum.BackPrev = i
                    optimum.Prev1IsChar = false
                }
            } while (--repLen >= 2)
            i++
        }
        var normalMatchPrice: Int = matchPrice + RangeEncoder.GetPrice0(_isRep[_state].toInt())
        len = if (repLens[0] >= 2) repLens[0] + 1 else 2
        if (len <= lenMain) {
            var offs = 0
            while (len > _matchDistances[offs]) offs += 2
            while (true) {
                val distance = _matchDistances[offs + 1]
                val curAndLenPrice = normalMatchPrice + GetPosLenPrice(distance, len, posState)
                val optimum = _optimum[len]
                if (curAndLenPrice < optimum!!.Price) {
                    optimum.Price = curAndLenPrice
                    optimum.PosPrev = 0
                    optimum.BackPrev = distance + Base.kNumRepDistances
                    optimum.Prev1IsChar = false
                }
                if (len == _matchDistances[offs]) {
                    offs += 2
                    if (offs == numDistancePairs) break
                }
                len++
            }
        }
        var cur = 0
        while (true) {
            cur++
            if (cur == lenEnd) return Backward(cur)
            var newLen = ReadMatchDistances()
            numDistancePairs = _numDistancePairs
            if (newLen >= _numFastBytes) {
                _longestMatchLength = newLen
                _longestMatchWasFound = true
                return Backward(cur)
            }
            position++
            var posPrev = _optimum[cur]!!.PosPrev
            var state: Int
            if (_optimum[cur]!!.Prev1IsChar) {
                posPrev--
                if (_optimum[cur]!!.Prev2) {
                    state = _optimum[_optimum[cur]!!.PosPrev2]!!.State
                    if (_optimum[cur]!!.BackPrev2 < Base.kNumRepDistances) state =
                        Base.StateUpdateRep(state) else state = Base.StateUpdateMatch(state)
                } else state = _optimum[posPrev]!!.State
                state = Base.StateUpdateChar(state)
            } else state = _optimum[posPrev]!!.State
            if (posPrev == cur - 1) {
                if (_optimum[cur]!!.IsShortRep()) state = Base.StateUpdateShortRep(state) else state =
                    Base.StateUpdateChar(state)
            } else {
                var pos: Int
                if (_optimum[cur]!!.Prev1IsChar && _optimum[cur]!!.Prev2) {
                    posPrev = _optimum[cur]!!.PosPrev2
                    pos = _optimum[cur]!!.BackPrev2
                    state = Base.StateUpdateRep(state)
                } else {
                    pos = _optimum[cur]!!.BackPrev
                    if (pos < Base.kNumRepDistances) state = Base.StateUpdateRep(state) else state =
                        Base.StateUpdateMatch(state)
                }
                val opt = _optimum[posPrev]
                if (pos < Base.kNumRepDistances) {
                    if (pos == 0) {
                        reps[0] = opt!!.Backs0
                        reps[1] = opt.Backs1
                        reps[2] = opt.Backs2
                        reps[3] = opt.Backs3
                    } else if (pos == 1) {
                        reps[0] = opt!!.Backs1
                        reps[1] = opt.Backs0
                        reps[2] = opt.Backs2
                        reps[3] = opt.Backs3
                    } else if (pos == 2) {
                        reps[0] = opt!!.Backs2
                        reps[1] = opt.Backs0
                        reps[2] = opt.Backs1
                        reps[3] = opt.Backs3
                    } else {
                        reps[0] = opt!!.Backs3
                        reps[1] = opt.Backs0
                        reps[2] = opt.Backs1
                        reps[3] = opt.Backs2
                    }
                } else {
                    reps[0] = pos - Base.kNumRepDistances
                    reps[1] = opt!!.Backs0
                    reps[2] = opt.Backs1
                    reps[3] = opt.Backs2
                }
            }
            _optimum[cur]!!.State = state
            _optimum[cur]!!.Backs0 = reps[0]
            _optimum[cur]!!.Backs1 = reps[1]
            _optimum[cur]!!.Backs2 = reps[2]
            _optimum[cur]!!.Backs3 = reps[3]
            val curPrice = _optimum[cur]!!.Price
            currentByte = _matchFinder!!.GetIndexByte(0 - 1)
            matchByte = _matchFinder!!.GetIndexByte(0 - reps[0] - 1 - 1)
            posState = position and _posStateMask
            val curAnd1Price: Int = curPrice +
                    RangeEncoder.GetPrice0(_isMatch[(state shl Base.kNumPosStatesBitsMax) + posState].toInt()) +
                    _literalEncoder.GetSubCoder(position, _matchFinder!!.GetIndexByte(0 - 2))!!
                        .GetPrice(!Base.StateIsCharState(state), matchByte, currentByte)
            val nextOptimum = _optimum[cur + 1]
            var nextIsChar = false
            if (curAnd1Price < nextOptimum!!.Price) {
                nextOptimum.Price = curAnd1Price
                nextOptimum.PosPrev = cur
                nextOptimum.MakeAsChar()
                nextIsChar = true
            }
            matchPrice =
                curPrice + RangeEncoder.GetPrice1(_isMatch[(state shl Base.kNumPosStatesBitsMax) + posState].toInt())
            repMatchPrice = matchPrice + RangeEncoder.GetPrice1(_isRep[state].toInt())
            if (matchByte == currentByte &&
                !(nextOptimum.PosPrev < cur && nextOptimum.BackPrev == 0)
            ) {
                val shortRepPrice = repMatchPrice + GetRepLen1Price(state, posState)
                if (shortRepPrice <= nextOptimum.Price) {
                    nextOptimum.Price = shortRepPrice
                    nextOptimum.PosPrev = cur
                    nextOptimum.MakeAsShortRep()
                    nextIsChar = true
                }
            }
            var numAvailableBytesFull: Int = _matchFinder!!.GetNumAvailableBytes() + 1
            numAvailableBytesFull = min(kNumOpts - 1 - cur, numAvailableBytesFull)
            numAvailableBytes = numAvailableBytesFull
            if (numAvailableBytes < 2) continue
            if (numAvailableBytes > _numFastBytes) numAvailableBytes = _numFastBytes
            if (!nextIsChar && matchByte != currentByte) {
                // try Literal + rep0
                val t: Int = min(numAvailableBytesFull - 1, _numFastBytes)
                val lenTest2: Int = _matchFinder!!.GetMatchLen(0, reps[0], t)
                if (lenTest2 >= 2) {
                    val state2 = Base.StateUpdateChar(state)
                    val posStateNext = position + 1 and _posStateMask
                    val nextRepMatchPrice: Int = curAnd1Price +
                            RangeEncoder.GetPrice1(_isMatch[(state2 shl Base.kNumPosStatesBitsMax) + posStateNext].toInt()) +
                            RangeEncoder.GetPrice1(_isRep[state2].toInt())
                    run {
                        val offset: Int = cur + 1 + lenTest2
                        while (lenEnd < offset) _optimum.get(++lenEnd)!!.Price = kIfinityPrice
                        val curAndLenPrice: Int = nextRepMatchPrice + GetRepPrice(
                            0, lenTest2, state2, posStateNext
                        )
                        val optimum: Optimal? = _optimum.get(offset)
                        if (curAndLenPrice < optimum!!.Price) {
                            optimum.Price = curAndLenPrice
                            optimum.PosPrev = cur + 1
                            optimum.BackPrev = 0
                            optimum.Prev1IsChar = true
                            optimum.Prev2 = false
                        }
                    }
                }
            }
            var startLen = 2 // speed optimization 
            for (repIndex in 0 until Base.kNumRepDistances) {
                var lenTest: Int = _matchFinder!!.GetMatchLen(0 - 1, reps[repIndex], numAvailableBytes)
                if (lenTest < 2) continue
                val lenTestTemp = lenTest
                do {
                    while (lenEnd < cur + lenTest) _optimum[++lenEnd]!!.Price = kIfinityPrice
                    val curAndLenPrice = repMatchPrice + GetRepPrice(repIndex, lenTest, state, posState)
                    val optimum = _optimum[cur + lenTest]
                    if (curAndLenPrice < optimum!!.Price) {
                        optimum.Price = curAndLenPrice
                        optimum.PosPrev = cur
                        optimum.BackPrev = repIndex
                        optimum.Prev1IsChar = false
                    }
                } while (--lenTest >= 2)
                lenTest = lenTestTemp
                if (repIndex == 0) startLen = lenTest + 1

                // if (_maxMode)
                if (lenTest < numAvailableBytesFull) {
                    val t: Int = min(numAvailableBytesFull - 1 - lenTest, _numFastBytes)
                    val lenTest2: Int = _matchFinder!!.GetMatchLen(lenTest, reps[repIndex], t)
                    if (lenTest2 >= 2) {
                        var state2 = Base.StateUpdateRep(state)
                        var posStateNext = position + lenTest and _posStateMask
                        val curAndLenCharPrice: Int =
                            repMatchPrice + GetRepPrice(repIndex, lenTest, state, posState) +
                                    RangeEncoder.GetPrice0(_isMatch[(state2 shl Base.kNumPosStatesBitsMax) + posStateNext].toInt()) +
                                    _literalEncoder.GetSubCoder(
                                        position + lenTest,
                                        _matchFinder!!.GetIndexByte(lenTest - 1 - 1)
                                    )!!.GetPrice(
                                        true,
                                        _matchFinder!!.GetIndexByte(lenTest - 1 - (reps[repIndex] + 1)),
                                        _matchFinder!!.GetIndexByte(lenTest - 1)
                                    )
                        state2 = Base.StateUpdateChar(state2)
                        posStateNext = (position + lenTest + 1) and _posStateMask
                        val nextMatchPrice: Int =
                            curAndLenCharPrice + RangeEncoder.GetPrice1(
                                _isMatch[(state2 shl Base.kNumPosStatesBitsMax) + posStateNext].toInt()
                            )
                        val nextRepMatchPrice: Int = nextMatchPrice + RangeEncoder.GetPrice1(
                            _isRep[state2].toInt()
                        )

                        // for(; lenTest2 >= 2; lenTest2--)
                        run {
                            val offset: Int = lenTest + 1 + lenTest2
                            while (lenEnd < cur + offset) _optimum.get(++lenEnd)!!.Price =
                                kIfinityPrice
                            val curAndLenPrice: Int =
                                nextRepMatchPrice + GetRepPrice(0, lenTest2, state2, posStateNext)
                            val optimum: Optimal? = _optimum.get(cur + offset)
                            if (curAndLenPrice < optimum!!.Price) {
                                optimum.Price = curAndLenPrice
                                optimum.PosPrev = cur + lenTest + 1
                                optimum.BackPrev = 0
                                optimum.Prev1IsChar = true
                                optimum.Prev2 = true
                                optimum.PosPrev2 = cur
                                optimum.BackPrev2 = repIndex
                            }
                        }
                    }
                }
            }
            if (newLen > numAvailableBytes) {
                newLen = numAvailableBytes
                numDistancePairs = 0
                while (newLen > _matchDistances[numDistancePairs]) {
                    numDistancePairs += 2
                }
                _matchDistances[numDistancePairs] = newLen
                numDistancePairs += 2
            }
            if (newLen >= startLen) {
                normalMatchPrice = matchPrice + RangeEncoder.GetPrice0(_isRep[state].toInt())
                while (lenEnd < cur + newLen) _optimum[++lenEnd]!!.Price = kIfinityPrice
                var offs = 0
                while (startLen > _matchDistances[offs]) offs += 2
                var lenTest = startLen
                while (true) {
                    val curBack = _matchDistances[offs + 1]
                    var curAndLenPrice = normalMatchPrice + GetPosLenPrice(curBack, lenTest, posState)
                    var optimum = _optimum[cur + lenTest]
                    if (curAndLenPrice < optimum!!.Price) {
                        optimum.Price = curAndLenPrice
                        optimum.PosPrev = cur
                        optimum.BackPrev = curBack + Base.kNumRepDistances
                        optimum.Prev1IsChar = false
                    }
                    if (lenTest == _matchDistances[offs]) {
                        if (lenTest < numAvailableBytesFull) {
                            val t: Int = min(numAvailableBytesFull - 1 - lenTest, _numFastBytes)
                            val lenTest2: Int = _matchFinder!!.GetMatchLen(lenTest, curBack, t)
                            if (lenTest2 >= 2) {
                                var state2 = Base.StateUpdateMatch(state)
                                var posStateNext = (position + lenTest) and _posStateMask
                                val curAndLenCharPrice: Int = (curAndLenPrice +
                                        RangeEncoder.GetPrice0(_isMatch[(state2 shl Base.kNumPosStatesBitsMax) + posStateNext].toInt()) +
                                        _literalEncoder.GetSubCoder(
                                            position + lenTest,
                                            _matchFinder!!.GetIndexByte(lenTest - 1 - 1)
                                        )!!.GetPrice(
                                            true,
                                            _matchFinder!!.GetIndexByte(lenTest - (curBack + 1) - 1),
                                            _matchFinder!!.GetIndexByte(lenTest - 1)
                                        ))
                                state2 = Base.StateUpdateChar(state2)
                                posStateNext = (position + lenTest + 1) and _posStateMask
                                val nextMatchPrice: Int =
                                    curAndLenCharPrice + RangeEncoder.GetPrice1(
                                        _isMatch[(state2 shl Base.kNumPosStatesBitsMax) + posStateNext].toInt()
                                    )
                                val nextRepMatchPrice: Int =
                                    nextMatchPrice + RangeEncoder.GetPrice1(
                                        _isRep[state2].toInt()
                                    )
                                val offset = lenTest + 1 + lenTest2
                                while (lenEnd < cur + offset) _optimum[++lenEnd]!!.Price = kIfinityPrice
                                curAndLenPrice = nextRepMatchPrice + GetRepPrice(0, lenTest2, state2, posStateNext)
                                optimum = _optimum[cur + offset]
                                if (curAndLenPrice < optimum!!.Price) {
                                    optimum.Price = curAndLenPrice
                                    optimum.PosPrev = cur + lenTest + 1
                                    optimum.BackPrev = 0
                                    optimum.Prev1IsChar = true
                                    optimum.Prev2 = true
                                    optimum.PosPrev2 = cur
                                    optimum.BackPrev2 = curBack + Base.kNumRepDistances
                                }
                            }
                        }
                        offs += 2
                        if (offs == numDistancePairs) break
                    }
                    lenTest++
                }
            }
        }
    }

    fun ChangePair(smallDist: Int, bigDist: Int): Boolean {
        val kDif = 7
        return (smallDist < (1 shl (32 - kDif)) && bigDist >= (smallDist shl kDif))
    }

    @Throws(IOException::class)
    fun WriteEndMarker(posState: Int) {
        if (!_writeEndMark) return
        _rangeEncoder.Encode(_isMatch, (_state shl Base.kNumPosStatesBitsMax) + posState, 1)
        _rangeEncoder.Encode(_isRep, _state, 0)
        _state = Base.StateUpdateMatch(_state)
        val len = Base.kMatchMinLen
        _lenEncoder.Encode(_rangeEncoder, len - Base.kMatchMinLen, posState)
        val posSlot = (1 shl Base.kNumPosSlotBits) - 1
        val lenToPosState = Base.GetLenToPosState(len)
        _posSlotEncoder[lenToPosState]!!.Encode(_rangeEncoder, posSlot)
        val footerBits = 30
        val posReduced = (1 shl footerBits) - 1
        _rangeEncoder.EncodeDirectBits(posReduced shr Base.kNumAlignBits, footerBits - Base.kNumAlignBits)
        _posAlignEncoder.ReverseEncode(_rangeEncoder, posReduced and Base.kAlignMask)
    }

    @Throws(IOException::class)
    fun Flush(nowPos: Int) {
        ReleaseMFStream()
        WriteEndMarker(nowPos and _posStateMask)
        _rangeEncoder.FlushData()
        _rangeEncoder.FlushStream()
    }

    @Throws(IOException::class)
    fun CodeOneBlock(inSize: LongArray, outSize: LongArray, finished: BooleanArray) {
        inSize[0] = 0
        outSize[0] = 0
        finished[0] = true
        if (_inStream != null) {
            _matchFinder!!.SetStream(_inStream)
            _matchFinder!!.Init()
            _needReleaseMFStream = true
            _inStream = null
        }
        if (_finished) return
        _finished = true
        val progressPosValuePrev = nowPos64
        if (nowPos64 == 0L) {
            if (_matchFinder!!.GetNumAvailableBytes() == 0) {
                Flush(nowPos64.toInt())
                return
            }
            ReadMatchDistances()
            val posState = (nowPos64).toInt() and _posStateMask
            _rangeEncoder.Encode(_isMatch, (_state shl Base.kNumPosStatesBitsMax) + posState, 0)
            _state = Base.StateUpdateChar(_state)
            val curByte: Byte = _matchFinder!!.GetIndexByte(0 - _additionalOffset)
            _literalEncoder.GetSubCoder((nowPos64).toInt(), _previousByte)!!.Encode(_rangeEncoder, curByte)
            _previousByte = curByte
            _additionalOffset--
            nowPos64++
        }
        if (_matchFinder!!.GetNumAvailableBytes() == 0) {
            Flush(nowPos64.toInt())
            return
        }
        while (true) {
            val len = GetOptimum(nowPos64.toInt())
            var pos = backRes
            val posState = (nowPos64.toInt()) and _posStateMask
            val complexState = (_state shl Base.kNumPosStatesBitsMax) + posState
            if (len == 1 && pos == -1) {
                _rangeEncoder.Encode(_isMatch, complexState, 0)
                val curByte: Byte = _matchFinder!!.GetIndexByte((0 - _additionalOffset))
                val subCoder: LiteralEncoder.Encoder2? = _literalEncoder.GetSubCoder(nowPos64.toInt(), _previousByte)
                if (!Base.StateIsCharState(_state)) {
                    val matchByte: Byte = _matchFinder!!.GetIndexByte((0 - _repDistances[0] - 1 - _additionalOffset))
                    subCoder!!.EncodeMatched(_rangeEncoder, matchByte, curByte)
                } else subCoder!!.Encode(_rangeEncoder, curByte)
                _previousByte = curByte
                _state = Base.StateUpdateChar(_state)
            } else {
                _rangeEncoder.Encode(_isMatch, complexState, 1)
                if (pos < Base.kNumRepDistances) {
                    _rangeEncoder.Encode(_isRep, _state, 1)
                    if (pos == 0) {
                        _rangeEncoder.Encode(_isRepG0, _state, 0)
                        if (len == 1) _rangeEncoder.Encode(_isRep0Long, complexState, 0) else _rangeEncoder.Encode(
                            _isRep0Long,
                            complexState,
                            1
                        )
                    } else {
                        _rangeEncoder.Encode(_isRepG0, _state, 1)
                        if (pos == 1) _rangeEncoder.Encode(_isRepG1, _state, 0) else {
                            _rangeEncoder.Encode(_isRepG1, _state, 1)
                            _rangeEncoder.Encode(_isRepG2, _state, pos - 2)
                        }
                    }
                    if (len == 1) _state = Base.StateUpdateShortRep(_state) else {
                        _repMatchLenEncoder.Encode(_rangeEncoder, len - Base.kMatchMinLen, posState)
                        _state = Base.StateUpdateRep(_state)
                    }
                    val distance = _repDistances[pos]
                    if (pos != 0) {
                        for (i in pos downTo 1) _repDistances[i] = _repDistances[i - 1]
                        _repDistances[0] = distance
                    }
                } else {
                    _rangeEncoder.Encode(_isRep, _state, 0)
                    _state = Base.StateUpdateMatch(_state)
                    _lenEncoder.Encode(_rangeEncoder, len - Base.kMatchMinLen, posState)
                    pos -= Base.kNumRepDistances
                    val posSlot = GetPosSlot(pos)
                    val lenToPosState = Base.GetLenToPosState(len)
                    _posSlotEncoder[lenToPosState]!!.Encode(_rangeEncoder, posSlot)
                    if (posSlot >= Base.kStartPosModelIndex) {
                        val footerBits = ((posSlot shr 1) - 1)
                        val baseVal = ((2 or (posSlot and 1)) shl footerBits)
                        val posReduced = pos - baseVal
                        if (posSlot < Base.kEndPosModelIndex) BitTreeEncoder.ReverseEncode(
                            _posEncoders,
                            baseVal - posSlot - 1, _rangeEncoder, footerBits, posReduced
                        ) else {
                            _rangeEncoder.EncodeDirectBits(
                                posReduced shr Base.kNumAlignBits,
                                footerBits - Base.kNumAlignBits
                            )
                            _posAlignEncoder.ReverseEncode(_rangeEncoder, posReduced and Base.kAlignMask)
                            _alignPriceCount++
                        }
                    }
                    val distance = pos
                    for (i in Base.kNumRepDistances - 1 downTo 1) _repDistances[i] = _repDistances[i - 1]
                    _repDistances[0] = distance
                    _matchPriceCount++
                }
                _previousByte = _matchFinder!!.GetIndexByte(len - 1 - _additionalOffset)
            }
            _additionalOffset -= len
            nowPos64 += len.toLong()
            if (_additionalOffset == 0) {
                // if (!_fastMode)
                if (_matchPriceCount >= (1 shl 7)) FillDistancesPrices()
                if (_alignPriceCount >= Base.kAlignTableSize) FillAlignPrices()
                inSize[0] = nowPos64
                outSize[0] = _rangeEncoder.GetProcessedSizeAdd()
                if (_matchFinder!!.GetNumAvailableBytes() == 0) {
                    Flush(nowPos64.toInt())
                    return
                }
                if (nowPos64 - progressPosValuePrev >= (1 shl 12)) {
                    _finished = false
                    finished[0] = false
                    return
                }
            }
        }
    }

    fun ReleaseMFStream() {
        if (_matchFinder != null && _needReleaseMFStream) {
            _matchFinder!!.ReleaseStream()
            _needReleaseMFStream = false
        }
    }

    fun SetOutStream(outStream: BufferedSink?) {
        _rangeEncoder.SetStream(outStream)
    }

    fun ReleaseOutStream() {
        _rangeEncoder.ReleaseStream()
    }

    fun ReleaseStreams() {
        ReleaseMFStream()
        ReleaseOutStream()
    }

    fun SetStreams(
        inStream: BufferedSource?, outStream: BufferedSink?,
        inSize: Long, outSize: Long
    ) {
        _inStream = inStream
        _finished = false
        Create()
        SetOutStream(outStream)
        Init()

        // if (!_fastMode)
        run {
            FillDistancesPrices()
            FillAlignPrices()
        }
        _lenEncoder.SetTableSize(_numFastBytes + 1 - Base.kMatchMinLen)
        _lenEncoder.UpdateTables(1 shl _posStateBits)
        _repMatchLenEncoder.SetTableSize(_numFastBytes + 1 - Base.kMatchMinLen)
        _repMatchLenEncoder.UpdateTables(1 shl _posStateBits)
        nowPos64 = 0
    }

    var processedInSize = LongArray(1)
    var processedOutSize = LongArray(1)
    var finished = BooleanArray(1)

    @Throws(IOException::class)
    fun Code(
        inStream: BufferedSource?, outStream: BufferedSink?,
        inSize: Long, outSize: Long, progress: ICodeProgress?
    ) {
        _needReleaseMFStream = false
        try {
            SetStreams(inStream, outStream, inSize, outSize)
            while (true) {
                CodeOneBlock(processedInSize, processedOutSize, finished)
                if (finished[0]) return
                if (progress != null) {
                    progress.SetProgress(processedInSize[0], processedOutSize[0])
                }
            }
        } finally {
            ReleaseStreams()
        }
    }

    var properties = ByteArray(kPropSize)

    @Throws(IOException::class)
    fun WriteCoderProperties(outStream: BufferedSink) {
        properties[0] = ((_posStateBits * 5 + _numLiteralPosStateBits) * 9 + _numLiteralContextBits).toByte()
        for (i in 0..3) properties[1 + i] = (_dictionarySize shr (8 * i)).toByte()
        outStream.write(properties, 0, kPropSize)
    }

    var tempPrices = IntArray(Base.kNumFullDistances)
    var _matchPriceCount = 0

    init {
        for (i in 0 until kNumOpts) _optimum[i] = Optimal()
        for (i in 0 until Base.kNumLenToPosStates) _posSlotEncoder[i] = BitTreeEncoder(Base.kNumPosSlotBits)
    }

    fun FillDistancesPrices() {
        for (i in Base.kStartPosModelIndex until Base.kNumFullDistances) {
            val posSlot = GetPosSlot(i)
            val footerBits = ((posSlot shr 1) - 1)
            val baseVal = ((2 or (posSlot and 1)) shl footerBits)
            tempPrices[i] = BitTreeEncoder.ReverseGetPrice(
                _posEncoders,
                baseVal - posSlot - 1, footerBits, i - baseVal
            )
        }
        for (lenToPosState in 0 until Base.kNumLenToPosStates) {
            var posSlot: Int
            val encoder: BitTreeEncoder? = _posSlotEncoder[lenToPosState]
            val st = (lenToPosState shl Base.kNumPosSlotBits)
            posSlot = 0
            while (posSlot < _distTableSize) {
                _posSlotPrices[st + posSlot] = encoder!!.GetPrice(posSlot)
                posSlot++
            }
            posSlot = Base.kEndPosModelIndex
            while (posSlot < _distTableSize) {
                _posSlotPrices[st + posSlot] += ((((posSlot shr 1) - 1) - Base.kNumAlignBits) shl RangeEncoder.kNumBitPriceShiftBits)
                posSlot++
            }
            val st2 = lenToPosState * Base.kNumFullDistances
            var i: Int
            i = 0
            while (i < Base.kStartPosModelIndex) {
                _distancesPrices[st2 + i] = _posSlotPrices[st + i]
                i++
            }
            while (i < Base.kNumFullDistances) {
                _distancesPrices[st2 + i] = _posSlotPrices[st + GetPosSlot(i)] + tempPrices[i]
                i++
            }
        }
        _matchPriceCount = 0
    }

    fun FillAlignPrices() {
        for (i in 0 until Base.kAlignTableSize) _alignPrices[i] = _posAlignEncoder.ReverseGetPrice(i)
        _alignPriceCount = 0
    }

    fun SetAlgorithm(algorithm: Int): Boolean {
        /*
		_fastMode = (algorithm == 0);
		_maxMode = (algorithm >= 2);
		*/
        return true
    }

    fun SetDictionarySize(dictionarySize: Int): Boolean {
        val kDicLogSizeMaxCompress = 29
        if (dictionarySize < (1 shl Base.kDicLogSizeMin) || (dictionarySize > (1 shl kDicLogSizeMaxCompress))) return false
        _dictionarySize = dictionarySize
        var dicLogSize: Int = 0
        while (dictionarySize > (1 shl dicLogSize)) {
            dicLogSize++
        }
        _distTableSize = dicLogSize * 2
        return true
    }

    fun SetNumFastBytes(numFastBytes: Int): Boolean {
        if (numFastBytes < 5 || numFastBytes > Base.kMatchMaxLen) return false
        _numFastBytes = numFastBytes
        return true
    }

    fun SetMatchFinder(matchFinderIndex: Int): Boolean {
        if (matchFinderIndex < 0 || matchFinderIndex > 2) return false
        val matchFinderIndexPrev = _matchFinderType
        _matchFinderType = matchFinderIndex
        if (_matchFinder != null && matchFinderIndexPrev != _matchFinderType) {
            _dictionarySizePrev = -1
            _matchFinder = null
        }
        return true
    }

    fun SetLcLpPb(lc: Int, lp: Int, pb: Int): Boolean {
        if ((lp < 0) || (lp > Base.kNumLitPosStatesBitsEncodingMax) || (
                    lc < 0) || (lc > Base.kNumLitContextBitsMax) || (
                    pb < 0) || (pb > Base.kNumPosStatesBitsEncodingMax)
        ) return false
        _numLiteralPosStateBits = lp
        _numLiteralContextBits = lc
        _posStateBits = pb
        _posStateMask = ((1) shl _posStateBits) - 1
        return true
    }

    fun SetEndMarkerMode(endMarkerMode: Boolean) {
        _writeEndMark = endMarkerMode
    }

    companion object {
        val EMatchFinderTypeBT2 = 0
        val EMatchFinderTypeBT4 = 1
        val kIfinityPrice = 0xFFFFFFF
        var g_FastPos = ByteArray(1 shl 11)

        init {
            val kFastSlots = 22
            var c = 2
            g_FastPos[0] = 0
            g_FastPos[1] = 1
            for (slotFast in 2 until kFastSlots) {
                val k = (1 shl ((slotFast shr 1) - 1))
                var j = 0
                while (j < k) {
                    g_FastPos[c] = slotFast.toByte()
                    j++
                    c++
                }
            }
        }

        fun GetPosSlot(pos: Int): Int {
            if (pos < (1 shl 11)) return g_FastPos[pos].toInt()
            return if (pos < (1 shl 21)) (g_FastPos.get(pos shr 10) + 20) else (g_FastPos.get(pos shr 20) + 40)
        }

        fun GetPosSlot2(pos: Int): Int {
            if (pos < (1 shl 17)) return (g_FastPos[pos shr 6] + 12)
            return if (pos < (1 shl 27)) (g_FastPos.get(pos shr 16) + 32) else (g_FastPos.get(pos shr 26) + 52)
        }

        val kDefaultDictionaryLogSize = 22
        val kNumFastBytesDefault = 0x20
        val kNumLenSpecSymbols = Base.kNumLowLenSymbols + Base.kNumMidLenSymbols
        val kNumOpts = 1 shl 12
        val kPropSize = 5
    }
}


