package compress.methods

import okio.BufferedSink
import okio.BufferedSource

interface CompressMethod {
    suspend fun compress(inputSource: BufferedSource, outputSink: BufferedSink, fileSize: Long)
}