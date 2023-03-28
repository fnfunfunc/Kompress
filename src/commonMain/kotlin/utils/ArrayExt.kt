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