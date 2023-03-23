package archivers.zip

import kotlinx.datetime.LocalDateTime
import okio.*
import okio.Path.Companion.toPath
import utils.FileUtil
import utils.extract
import utils.hex
import utils.unsigned
import kotlin.math.max

class ZipFile private constructor(
    val dummy: Boolean,
    private val fileHandle: FileHandle,
    private val caseSensitive: Boolean = true,
    private val name: String? = null,
) {
    private val files = LinkedHashMap<String, ZipEntry2>()
    private val filesPerFolder = LinkedHashMap<String, MutableMap<String, ZipEntry2>>()

    override fun toString(): String = "ZipFile($name)"

    companion object {
        suspend operator fun invoke(s: FileHandle, caseSensitive: Boolean = true, name: String? = null): ZipFile {
            return ZipFile(false, s, caseSensitive, name).also { it.read() }
        }

        internal val PK_END = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    }

    fun normalizeName(name: String) = if (caseSensitive) name.trim('/') else name.trim('/').toLowerCase()

    private suspend fun read() {
        //println("ZipFile reading...[0]")
        var endBytes = ByteArray(0)

        if (fileHandle.size() <= 8L) throw IllegalArgumentException("Zip file is too small length=${fileHandle.size()}")

        var pk_endIndex = -1
        val fileLength = fileHandle.size()

        for (chunkSize in listOf(0x16, 0x100, 0x1000, 0x10000)) {
            val pos = max(0L, fileLength - chunkSize)

            val source = fileHandle.source(pos).buffer()
            val byteBuffer = Buffer()
//            s.setPosition(pos)
//            val bytesLen = max(chunkSize, s.getAvailable().toIntClamp())
            val bytesLen = chunkSize
            source.read(sink = byteBuffer, byteCount = bytesLen.toLong())
//            val ebytes = s.readBytesExact(bytesLen)
//            endBytes = ebytes

            endBytes = byteBuffer.readByteArray()

            byteBuffer.close()
            source.close()

            pk_endIndex = endBytes.indexOf(PK_END)
            if (pk_endIndex >= 0) break
        }

        //println("ZipFile reading...[1]")

        if (pk_endIndex < 0) throw IllegalArgumentException(
            "Not a zip file (pk_endIndex < 0) : pk_endIndex=$pk_endIndex : ${
                endBytes.sliceArray(
                    endBytes.size - 32 until endBytes.size
                ).hex
            } : ${fileHandle.size()}"
        )

        val buffer = Buffer()
        buffer.read(endBytes.copyOfRange(pk_endIndex, endBytes.size))

        buffer.use {
            it.apply {
                val magic = readInt()
                if (magic != 0x504B_0506) throw IllegalStateException("Not a zip file ${magic.hex} instead of ${0x504B_0102.hex}")
                val diskNumber = readShortLe().toUShort()
                val startDiskNumber = readShortLe().toUShort()
                val entriesOnDisk = readShortLe().toUShort()
                val entriesInDirectory = readShortLe().toUShort()
                val directorySize = readIntLe()
                val directoryOffset = readIntLe()
                val commentLength = readShortLe()

                val ds = fileHandle.source(fileOffset = directoryOffset.toLong()).buffer().buffer

                for (n in 0.toUShort() until entriesInDirectory) {
                    ds.apply {
                        val magic = readInt()
                        if (magic != 0x504B_0102) throw IllegalStateException("Not a zip file record ${magic.hex} instead of ${0x504B_0102.hex}")
                        val versionMade = readShortLe().toUShort()
                        val versionExtract = readShortLe().toUShort()
                        val flags = readShortLe().toUShort()
                        val compressionMethod = readShortLe().toUShort()
                        val fileTime = readShortLe().toUShort()
                        val fileDate = readShortLe().toUShort()
                        val crc = readIntLe()
                        val compressedSize = readIntLe()
                        val uncompressedSize = readIntLe()
                        val fileNameLength = readShortLe().toUShort()
                        val extraLength = readShortLe().toUShort()
                        val fileCommentLength = readShortLe().toUShort()
                        val diskNumberStart = readShortLe().toUShort()
                        val internalAttributes = readShortLe().toUShort()
                        val externalAttributes = readShortLe().toUShort()
                        val headerOffset = readIntLe().toUInt()
                        val name = readByteString(fileNameLength.toLong()).toString()
                        val extra = readByteArray(extraLength.toLong())

                        val isDirectory = name.endsWith("/")
                        val normalizedName = normalizeName(name)

                        val baseFolder = normalizedName.substringBeforeLast('/', "")
                        val baseName = normalizedName.substringAfterLast('/')

                        val folder = filesPerFolder.getOrPut(baseFolder) { LinkedHashMap() }

                        val headerEntry = fileHandle.source(fileOffset = headerOffset.toLong()).buffer().buffer

                        val entry = ZipEntry2(
                            path = name,
                            compressionMethod = compressionMethod.toInt(),
                            isDirectory = isDirectory,
                            time = DosFileDateTime(fileTime.toInt(), fileDate.toInt()),
                            inode = n.toLong(),
                            offset = headerOffset.toInt(),
                            headerEntry = headerEntry,
                            compressedSize = compressedSize.unsigned,
                            uncompressedSize = uncompressedSize.unsigned
                        )

                        val paths = FileUtil.fileSystem.list(normalizedName.toPath(normalize = true))
                        for (i in 1 until paths.size) {
                            val f = paths[i]
                            val c = paths[i - 1]
                            if (c.toString() !in files) {
                                val folder2 = filesPerFolder.getOrPut(f.toString()) { LinkedHashMap() }
                                val entry2 = ZipEntry2(
                                    path = c.toString(),
                                    compressionMethod = 0,
                                    isDirectory = true,
                                    time = DosFileDateTime(0, 0),
                                    inode = 0L,
                                    offset = 0,
                                    headerEntry = Buffer(),
                                    compressedSize = 0L,
                                    uncompressedSize = 0L
                                )
                                folder2[c.name] = entry2
                                files[c.toString()] = entry2
                            }
                        }
                        folder[baseName] = entry
                        files[normalizedName] = entry
                    }
                }
            }
            files[""] = ZipEntry2(
                path = "",
                compressionMethod = 0,
                isDirectory = true,
                time = DosFileDateTime(0, 0),
                inode = 0L,
                offset = 0,
                headerEntry = Buffer(),
                compressedSize = 0L,
                uncompressedSize = 0L
            )
            Unit
        }
    }
}

data class DosFileDateTime(var dosTime: Int, var dosDate: Int) {
    val seconds: Int get() = 2 * dosTime.extract(0, 5)
    val minutes: Int get() = dosTime.extract(5, 6)
    val hours: Int get() = dosTime.extract(11, 5)
    val day: Int get() = dosDate.extract(0, 5)
    val month1: Int get() = dosDate.extract(5, 4)
    val fullYear: Int get() = 1980 + dosDate.extract(9, 7)
    val utc: LocalDateTime = LocalDateTime(
        year = fullYear,
        monthNumber = month1,
        dayOfMonth = day,
        hour = hours,
        minute = minutes,
        second = seconds
    )
}

data class ZipEntry(
    val versionMadeBy: Int,
    val extractVersion: Int,
    val headerOffset: Long,
    val compressionMethod: Int,
    val flags: Int,
    val date: Int,
    val time: Int,
    val crc32: Int,
    val compressedSize: Int,
    val uncompressedSize: Int,
    val nameBytes: ByteArray,
    val extraBytes: ByteArray,
    val diskNumberStart: Int,
    val internalAttributes: Int,
    val externalAttributes: Int,
    val commentBytes: ByteArray
)

data class ZipEntry2(
    val path: String,
    val compressionMethod: Int,
    val isDirectory: Boolean,
    val time: DosFileDateTime,
    val offset: Int,
    val inode: Long,
    val headerEntry: Buffer,
    val compressedSize: Long,
    val uncompressedSize: Long
)

internal inline fun array_indexOf(
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

public fun BooleanArray.indexOf(sub: BooleanArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun ByteArray.indexOf(sub: ByteArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun ShortArray.indexOf(sub: ShortArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun CharArray.indexOf(sub: CharArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun IntArray.indexOf(sub: IntArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun LongArray.indexOf(sub: LongArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun FloatArray.indexOf(sub: FloatArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun DoubleArray.indexOf(sub: DoubleArray, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }

public fun <T> Array<T>.indexOf(sub: Array<T>, starting: Int = 0): Int =
    array_indexOf(starting, size, sub.size) { n, m -> this[n] == sub[m] }
