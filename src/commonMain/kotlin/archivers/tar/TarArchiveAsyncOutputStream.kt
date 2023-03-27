package archivers.tar

import archivers.utils.CountingAsyncOutputStream
import archivers.utils.FixedLengthBlockAsyncOutputStream
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.soywiz.korio.stream.AsyncOutputStream
import kotlinx.datetime.Instant

class TarArchiveAsyncOutputStream : AsyncOutputStream {

    private val currSize: Long = 0
    private val currName: String? = null
    private val currBytes: Long = 0
    private lateinit var recordBuf: ByteArray
    private val longFileMode: Int = LONGFILE_ERROR
    private val bigNumberMode: Int = BIGNUMBER_ERROR

    private val recordsWritten: Long = 0

    private var recordsPerBlock = 0

    private var closed = false

    /**
     * Indicates if putArchiveEntry has been called without closeArchiveEntry
     */
    private val haveUnclosedEntry = false

    /**
     * indicates if this archive is finished
     */
    private val finished = false

    private var out: FixedLengthBlockAsyncOutputStream

    private var countingOut: CountingAsyncOutputStream

    // the provided encoding (for unit tests)
    val encoding: String = ""

    private val addPaxHeadersForNonAsciiNames = false

    /**
     * Constructor for TarArchiveOutputStream.
     *
     *
     * Uses a block size of 512 bytes.
     *
     * @param os the output stream to use
     */
    constructor(os: AsyncOutputStream) : this(os, BLOCK_SIZE_UNSPECIFIED)


    /**
     * Constructor for TarArchiveOutputStream.
     *
     * @param os the output stream to use
     * @param blockSize the block size to use . Must be a multiple of 512 bytes.
     * @param recordSize the record size to use. Must be 512 bytes.
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    @Deprecated(
        """recordSize must always be 512 bytes. An IllegalArgumentException will be thrown
      if any other value is used."""
    )
    constructor(
        os: AsyncOutputStream, blockSize: Int,
        recordSize: Int,
    ) : this(os, blockSize) {
        if (recordSize != RECORD_SIZE) {
            throw IllegalArgumentException(
                "Tar record size must always be 512 bytes. Attempt to set size of $recordSize"
            )
        }
    }

    /**
     * Constructor for TarArchiveOutputStream.
     *
     * @param os the output stream to use
     * @param blockSize the block size to use. Must be a multiple of 512 bytes.
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    constructor(
        os: AsyncOutputStream, blockSize: Int,
    ) {
        val realBlockSize: Int = if (BLOCK_SIZE_UNSPECIFIED == blockSize) {
            RECORD_SIZE
        } else {
            blockSize
        }
        if (realBlockSize <= 0 || realBlockSize % RECORD_SIZE != 0) {
            throw IllegalArgumentException("Block size must be a multiple of 512 bytes. Attempt to use set size of $blockSize")
        }
        out = FixedLengthBlockAsyncOutputStream(
            CountingAsyncOutputStream(os).also { countingOut = it },
            RECORD_SIZE
        )
        recordBuf = ByteArray(RECORD_SIZE)
        recordsPerBlock = realBlockSize / RECORD_SIZE
    }

    private fun addFileTimePaxHeader(
        paxHeaders: MutableMap<String, String>,
        header: String, value: Instant,
    ) {
        val seconds: Long = value.epochSeconds
        val nanos: Int = value.nanosecondsOfSecond
        if (nanos == 0) {
            paxHeaders[header] = seconds.toString()
        } else {
            addInstantPaxHeader(paxHeaders, header, seconds, nanos)
        }
    }

    private fun addFileTimePaxHeaderForBigNumber(
        paxHeaders: MutableMap<String, String>,
        header: String, value: Instant,
        maxValue: Long,
    ) {
        val seconds: Long = value.epochSeconds
        val nanos: Int = value.nanosecondsOfSecond
        if (nanos == 0) {
            addPaxHeaderForBigNumber(paxHeaders, header, seconds, maxValue)
        } else {
            addInstantPaxHeader(paxHeaders, header, seconds, nanos)
        }
    }

    private fun addPaxHeaderForBigNumber(
        paxHeaders: MutableMap<String, String>,
        header: String, value: Long,
        maxValue: Long,
    ) {
        if (value < 0 || value > maxValue) {
            paxHeaders[header] = value.toString()
        }
    }

    private fun addPaxHeadersForBigNumbers(
        paxHeaders: MutableMap<String, String>,
        entry: TarArchiveEntry,
    ) {
        addPaxHeaderForBigNumber(
            paxHeaders, "size", entry.size,
            TarConstants.MAXSIZE
        )
        addPaxHeaderForBigNumber(
            paxHeaders, "gid", entry.groupId,
            TarConstants.MAXID
        )
        addFileTimePaxHeaderForBigNumber(
            paxHeaders, "mtime",
            entry.getLastModifiedTime(), TarConstants.MAXSIZE
        )
        addFileTimePaxHeader(paxHeaders, "atime", entry.getLastAccessTime())
        if (entry.getStatusChangeTime() != TarArchiveEntry.defaultInstant) {
            addFileTimePaxHeader(paxHeaders, "ctime", entry.getStatusChangeTime())
        } else {
            // ctime is usually set from creation time on platforms where the real ctime is not available
            addFileTimePaxHeader(paxHeaders, "ctime", entry.getCreationTime())
        }
        addPaxHeaderForBigNumber(
            paxHeaders, "uid", entry.userId,
            TarConstants.MAXID
        )
        // libarchive extensions
        addFileTimePaxHeader(paxHeaders, "LIBARCHIVE.creationtime", entry.getCreationTime())
        // star extensions by Jörg Schilling
        addPaxHeaderForBigNumber(
            paxHeaders, "SCHILY.devmajor",
            entry.devMajor.toLong(), TarConstants.MAXID
        )
        addPaxHeaderForBigNumber(
            paxHeaders, "SCHILY.devminor",
            entry.devMinor.toLong(), TarConstants.MAXID
        )
        // there is no PAX header for file mode
        failForBigNumber("mode", entry.mode, TarConstants.MAXID)
    }



    private fun addInstantPaxHeader(
        paxHeaders: MutableMap<String, String>,
        header: String, seconds: Long, nanos: Int,
    ) {
        val bdSeconds= BigDecimal.fromLong(seconds)
        val bdNanos = BigDecimal.fromLong(nanos.toLong()).moveDecimalPoint(9).scale(7)
        val timestamp = bdSeconds.add(bdNanos)
        paxHeaders[header] = timestamp.toPlainString()
    }

    override suspend fun write(buffer: ByteArray, offset: Int, len: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun close() {

    }

    companion object {
        /**
         * Fail if a long file name is required in the archive.
         */
        const val LONGFILE_ERROR = 0

        /**
         * Long paths will be truncated in the archive.
         */
        const val LONGFILE_TRUNCATE = 1

        /**
         * GNU tar extensions are used to store long file names in the archive.
         */
        const val LONGFILE_GNU = 2

        /**
         * POSIX/PAX extensions are used to store long file names in the archive.
         */
        const val LONGFILE_POSIX = 3

        /**
         * Fail if a big number (e.g. size &gt; 8GiB) is required in the archive.
         */
        const val BIGNUMBER_ERROR = 0

        /**
         * star/GNU tar/BSD tar extensions are used to store big number in the archive.
         */
        const val BIGNUMBER_STAR = 1

        /**
         * POSIX/PAX extensions are used to store big numbers in the archive.
         */
        const val BIGNUMBER_POSIX = 2

        private const val RECORD_SIZE = 512

        private const val BLOCK_SIZE_UNSPECIFIED = -511
    }
}