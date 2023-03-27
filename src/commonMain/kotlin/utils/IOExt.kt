package utils

import okio.BufferedSource

fun BufferedSource.read(): Int {
    val tempByte = ByteArray(1)
    val size = read(tempByte, 0, 1)
    if (size <= 0) return -1
    return tempByte[0].unsigned
}