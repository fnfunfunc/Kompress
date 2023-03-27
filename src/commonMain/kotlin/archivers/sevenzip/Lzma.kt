package archivers.sevenzip

import archivers.sevenzip.lzma.LzmaEncoder
import compress.methods.CompressMethod
import okio.BufferedSink
import okio.BufferedSource


/**
 * @TODO: Streaming! (right now loads the whole stream in-memory)
 */
object Lzma : CompressMethod {
    override suspend fun compress(inputSource: BufferedSource, outputSink: BufferedSink, fileSize: Long) {
        val algorithm = 2
        val matchFinder = 1
        val dictionarySize = 1 shl 23
        val lc = 3
        val lp = 0
        val pb = 2
        val fb = 128
        val eos = false

        val encoder = LzmaEncoder()

        if (!encoder.SetAlgorithm(algorithm)) throw Exception("Incorrect compression mode")
        if (!encoder.setDictionarySize(dictionarySize))
            throw Exception("Incorrect dictionary size")
        if (!encoder.setNumFastBytes(fb)) throw Exception("Incorrect -fb value")
        if (!encoder.setMatchFinder(matchFinder)) throw Exception("Incorrect -mf value")
        if (!encoder.setLcLpPb(lc, lp, pb)) throw Exception("Incorrect -lc or -lp or -pb value")
        encoder.setEndMarkerMode(eos)
        encoder.WriteCoderProperties(outputSink)
//        val fileSize: Long = if (eos) -1 else fileSize
        outputSink.writeLongLe(fileSize)
        println(fileSize)
        encoder.code(inputSource, outputSink, -1, -1, null)
    }
}