package utils

import com.soywiz.korio.stream.AsyncInputStream
import com.soywiz.korio.stream.SyncInputStream
import com.soywiz.korio.stream.SyncStream
import kotlin.math.min

private const val MAX_SKIP_BUFFER_SIZE = 2048

fun SyncStream.seek(position: Long) {
    if (position > this.positionRead + this.availableRead) {
        throw IndexOutOfBoundsException()
    }
    markPos = position
    reset()
}

fun SyncStream.skipStream(n: Long): Long {
    var remaining = n
    var nr: Int

    if (n <= 0) {
        return 0
    }

    val size: Int = min(MAX_SKIP_BUFFER_SIZE, remaining.toInt())
    val skipBuffer = ByteArray(size)
    while (remaining > 0) {
        nr = read(skipBuffer, 0, min(size, remaining.toInt()))
        if (nr < 0) {
            break
        }
        remaining -= nr.toLong()
    }

    return n - remaining
}

fun SyncInputStream.skipStream(n: Long): Long {
    var remaining = n
    var nr: Int

    if (n <= 0) {
        return 0
    }

    val size: Int = min(MAX_SKIP_BUFFER_SIZE, remaining.toInt())
    val skipBuffer = ByteArray(size)
    while (remaining > 0) {
        nr = read(skipBuffer, 0, min(size, remaining.toInt()))
        if (nr < 0) {
            break
        }
        remaining -= nr.toLong()
    }

    return n - remaining
}

suspend fun AsyncInputStream.skipStream(n: Long): Long {
    var remaining = n
    var nr: Int

    if (n <= 0) {
        return 0
    }

    val size: Int = min(MAX_SKIP_BUFFER_SIZE.toLong(), remaining).toInt()
    val skipBuffer = ByteArray(size)
    while (remaining > 0) {
        nr = read(skipBuffer, 0, min(size.toLong(), remaining).toInt())
        if (nr < 0) {
            break
        }
        remaining -= nr.toLong()
    }
    return n - remaining
}