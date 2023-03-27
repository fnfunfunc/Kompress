package utils

/*
MIT License

Copyright (c) 2017 Carlos Ballesteros Velasco

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

fun hashCode(a: Array<Any?>?): Int {
    if (a == null) return 0
    var result = 1
    for (element in a) result = 31 * result + (element?.hashCode() ?: 0)
    return result
}

fun arrayAdd(array: ByteArray, value: Byte, start: Int = 0, end: Int = array.size) { for (n in start until end) array[n] = (array[n] + value).toByte() }
fun arrayAdd(array: ShortArray, value: Short, start: Int = 0, end: Int = array.size) { for (n in start until end) array[n] = (array[n] + value).toShort() }
fun arrayAdd(array: IntArray, value: Int, start: Int = 0, end: Int = array.size) { for (n in start until end) array[n] = array[n] + value }
fun arrayAdd(array: LongArray, value: Long, start: Int = 0, end: Int = array.size) { for (n in start until end) array[n] = array[n] + value }
fun arrayAdd(array: FloatArray, value: Float, start: Int = 0, end: Int = array.size) { for (n in start until end) array[n] = array[n] + value }
fun arrayAdd(array: DoubleArray, value: Double, start: Int = 0, end: Int = array.size) { for (n in start until end) array[n] = array[n] + value }


/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun <T> arrayFill(array: Array<T>, value: T, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: BooleanArray, value: Boolean, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: LongArray, value: Long, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: ByteArray, value: Byte, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: ShortArray, value: Short, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: IntArray, value: Int, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: FloatArray, value: Float, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)
/** Fills the [array] with the [value] starting a [start] end ending at [end] (end is not inclusive) */
fun arrayFill(array: DoubleArray, value: Double, start: Int = 0, end: Int = array.size): Unit = array.fill(value, start, end)


private inline fun arrayIndexOf(
    starting: Int,
    selfSize: Int,
    subSize: Int,
    crossinline equal: (n: Int, m: Int) -> Boolean
): Int {
    for (n in starting until selfSize - subSize) {
        var eq = 0
        for (m in 0 until subSize) {
            if (!equal(n + m, m)) {
                break
            }
            eq++
        }
        if (eq == subSize) {
            return n
        }
    }
    return -1
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun <T> arrayCopy(src: Array<out T>, srcPos: Int, dst: Array<T>, dstPos: Int, size: Int) {
    src.copyInto(dst as Array<T>, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: BooleanArray, srcPos: Int, dst: BooleanArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: LongArray, srcPos: Int, dst: LongArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: ShortArray, srcPos: Int, dst: ShortArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: CharArray, srcPos: Int, dst: CharArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: IntArray, srcPos: Int, dst: IntArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: FloatArray, srcPos: Int, dst: FloatArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun arrayCopy(src: DoubleArray, srcPos: Int, dst: DoubleArray, dstPos: Int, size: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + size)
}

/** Copies [size] elements of [src] starting at [srcPos] into [dst] at [dstPos]  */
fun <T> arrayCopy(src: List<T>, srcPos: Int, dst: MutableList<T>, dstPos: Int, size: Int) {
    if (src === dst) error("Not supporting the same array")
    for (n in 0 until size) {
        dst[dstPos + n] = src[srcPos]
    }
}

inline fun <T> arrayCopy(size: Int, src: Any?, srcPos: Int, dst: Any?, dstPos: Int, setDst: (Int, T) -> Unit, getSrc: (Int) -> T) {
    val overlapping = src === dst && dstPos > srcPos
    if (overlapping) {
        var n = size
        while (--n >= 0) setDst(dstPos + n, getSrc(srcPos + n))
    } else {
        for (n in 0 until size) setDst(dstPos + n, getSrc(srcPos + n))
    }
}

fun BooleanArray.indexOf(sub: BooleanArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun ByteArray.indexOf(sub: ByteArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun ShortArray.indexOf(sub: ShortArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun CharArray.indexOf(sub: CharArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun IntArray.indexOf(sub: IntArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun LongArray.indexOf(sub: LongArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun FloatArray.indexOf(sub: FloatArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun DoubleArray.indexOf(sub: DoubleArray, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

fun <T> Array<T>.indexOf(sub: Array<T>, starting: Int = 0): Int =
    arrayIndexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }



fun ByteArray.sliceArray(offset: Int, length: Int): ByteArray =
    sliceArray(offset until offset + length)

fun ByteArray.dropLastZero(): ByteArray = dropLastWhile { it == 0.toByte() }.toByteArray()