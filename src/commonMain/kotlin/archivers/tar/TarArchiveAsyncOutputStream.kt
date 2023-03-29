package archivers.tar

import archivers.ArchiveEntry
import archivers.utils.CountingAsyncOutputStream
import archivers.utils.FixedLengthBlockAsyncOutputStream
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.soywiz.kmem.arrayfill
import com.soywiz.korio.lang.Charset
import com.soywiz.korio.lang.Charsets
import com.soywiz.korio.lang.IOException
import com.soywiz.korio.stream.AsyncOutputStream
import kotlinx.datetime.Instant
import okio.Path
import utils.CommonCharset
import utils.canEncode
import kotlin.coroutines.cancellation.CancellationException

class TarArchiveAsyncOutputStream : AsyncOutputStream {

    private var currSize: Long = 0
    private var currName: String? = null
    private var currBytes: Long = 0
    private var recordBuf: ByteArray
    private var longFileMode: Int = LONGFILE_ERROR
    private var bigNumberMode: Int = BIGNUMBER_ERROR

    private var recordsWritten: Long = 0

    private var recordsPerBlock = 0

    private var closed = false

    /**
     * Indicates if putArchiveEntry has been called without closeArchiveEntry
     */
    private var haveUnclosedEntry = false

    /**
     * indicates if this archive is finished
     */
    private var finished = false

    private var out: FixedLengthBlockAsyncOutputStream

    private var countingOut: CountingAsyncOutputStream


    // the provided encoding (for unit tests)
    val encoding: String? = null

    private var addPaxHeadersForNonAsciiNames = false

    /**
     * Constructor for TarArchiveOutputStream.
     *
     *
     * Uses a block size of 512 bytes.
     *
     * @param os the output stream to use
     */
    constructor(os: AsyncOutputStream) :
        this(os, BLOCK_SIZE_UNSPECIFIED)


    /**
     * Constructor for TarArchiveOutputStream.
     *
     * @param os the output stream to use
     * @param blockSize the block size to use. Must be a multiple of 512 bytes.
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    constructor(
        outputStream: AsyncOutputStream, blockSize: Int,
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
            CountingAsyncOutputStream(outputStream).also { countingOut = it },
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

    private fun addInstantPaxHeader(
        paxHeaders: MutableMap<String, String>,
        header: String, seconds: Long, nanos: Int,
    ) {
        val bdSeconds: BigDecimal = BigDecimal.fromLong(seconds)
        val bdNanos: BigDecimal =
            BigDecimal.fromLong(nanos.toLong()).moveDecimalPoint(9).scale(7)
        val timestamp: BigDecimal = bdSeconds.add(bdNanos)
        paxHeaders[header] = timestamp.toPlainString()
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
            paxHeaders, "gid", entry.userId,
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
        failForBigNumber("mode", entry.mode.toLong(), TarConstants.MAXID)
    }



    override suspend fun write(buffer: ByteArray, offset: Int, len: Int) {
        if (!haveUnclosedEntry) {
            throw IllegalStateException("No current tar entry")
        }
        if (currBytes + len > currSize) {
            throw IOException(
                "Request to write '" + len
                        + "' bytes exceeds size in header of '"
                        + currSize + "' bytes for entry '"
                        + currName + "'"
            )
        }
        out.write(buffer, offset, len)
        currBytes += len.toLong()
    }

    override suspend fun close() {
        try {
            if (!finished) {
                finish()
            }
        } finally {
            if (!closed) {
                out.close()
                closed = true
            }
        }
    }

    @Throws(IOException::class, CancellationException::class)
    suspend fun closeArchiveEntry() {
        if (finished) {
            throw IOException("Stream has already been finished")
        }
        if (!haveUnclosedEntry) {
            throw IOException("No current entry to close")
        }
        out.flushBlock()
        if (currBytes < currSize) {
            throw IOException(
                "Entry '$currName' closed at '$currBytes' before the '$currSize' bytes specified in the header were wriiten"
            )
        }
        recordsWritten += currSize / RECORD_SIZE
        if (0L != currSize % RECORD_SIZE) {
            recordsWritten++
        }
        haveUnclosedEntry = false
    }

    @Throws(IOException::class)
    fun createArchiveEntry(inputFile: Path, entryName: String): ArchiveEntry? {
        if (finished) {
            throw IOException("Stream has already been finished")
        }
        return TarArchiveEntry(inputFile, entryName)
    }

    private fun encodeExtendedPaxHeadersContents(headers: Map<String, String>): ByteArray? {
        val w = StringBuilder()
        headers.forEach { (k, v) ->
            var len = (k.length + v.length
                    + 3 /* blank, equals and newline */
                    + 2) /* guess 9 < actual length < 100 */
            var line = "$len $k=$v\n"
            var actualLength: Int = line.encodeToByteArray().size
            while (len != actualLength) {
                // Adjust for cases where length < 10 or > 100
                // or where UTF-8 encoding isn't a single octet
                // per character.
                // Must be in loop as size may go from 99 to 100 in
                // first pass so we'd need a second.
                len = actualLength
                line = "$len $k=$v\n"
                actualLength = line.encodeToByteArray().size
            }
            w.append(line)
        }
        return w.toString().encodeToByteArray()
    }

    private fun failForBigNumber(field: String, value: Long, maxValue: Long) {
        failForBigNumber(field, value, maxValue, "")
    }

    private fun failForBigNumber(
        field: String, value: Long, maxValue: Long,
        additionalMsg: String,
    ) {
        if (value < 0 || value > maxValue) {
            throw IllegalArgumentException(
                field + " '" + value //NOSONAR
                        + "' is too big ( > "
                        + maxValue + " )." + additionalMsg
            )
        }
    }

    private fun failForBigNumbers(entry: TarArchiveEntry) {
        failForBigNumber("entry size", entry.size, TarConstants.MAXSIZE)
        failForBigNumberWithPosixMessage("group id", entry.groupId, TarConstants.MAXID)
        failForBigNumber(
            "last modification time",
            entry.getLastModifiedTime().epochSeconds,
            TarConstants.MAXSIZE
        )
        failForBigNumber("user id", entry.userId, TarConstants.MAXID)
        failForBigNumber("mode", entry.mode.toLong(), TarConstants.MAXID)
        failForBigNumber(
            "major device number", entry.devMajor.toLong(),
            TarConstants.MAXID
        )
        failForBigNumber(
            "minor device number", entry.devMinor.toLong(),
            TarConstants.MAXID
        )
    }

    private fun failForBigNumberWithPosixMessage(
        field: String, value: Long,
        maxValue: Long,
    ) {
        failForBigNumber(
            field, value, maxValue,
            " Use STAR or POSIX extensions to overcome this limit"
        )
    }

    /**
     * Ends the TAR archive without closing the underlying OutputStream.
     *
     * An archive consists of a series of file entries terminated by an
     * end-of-archive entry, which consists of two 512 blocks of zero bytes.
     * POSIX.1 requires two EOF records, like some other implementations.
     *
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun finish() {
        if (finished) {
            throw IOException("This archive has already been finished")
        }
        if (haveUnclosedEntry) {
            throw IOException("This archive contains unclosed entries.")
        }
        writeEOFRecord()
        writeEOFRecord()
        padAsNeeded()
        out.flush()
        finished = true
    }

    @Throws(IOException::class, CancellationException::class)
    suspend fun flush() {
        out.flush()
    }

    fun getBytesWritten(): Long {
        return countingOut.getBytesWritten()
    }

    /**
     * Handles long file or link names according to the longFileMode setting.
     *
     *
     * I.e. if the given name is too long to be written to a plain tar header then   * it
     * creates a pax header who's name is given by the paxHeaderName parameter if longFileMode is
     * POSIX  * it creates a GNU longlink entry who's type is given by the linkType parameter
     * if longFileMode is GNU  * it throws an exception if longFileMode is ERROR  * it
     * truncates the name if longFileMode is TRUNCATE
     *
     * @param entry entry the name belongs to
     * @param name the name to write
     * @param paxHeaders current map of pax headers
     * @param paxHeaderName name of the pax header to write
     * @param linkType type of the GNU entry to write
     * @param fieldName the name of the field
     * @throws IllegalArgumentException if the [TarArchiveOutputStream.longFileMode] equals
     * [TarArchiveOutputStream.LONGFILE_ERROR] and the file
     * name is too long
     * @return whether a pax header has been written.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun handleLongName(
        entry: TarArchiveEntry, name: String,
        paxHeaders: MutableMap<String, String>,
        paxHeaderName: String, linkType: Byte, fieldName: String,
    ): Boolean {
        val encodedName = name.encodeToByteArray()
        val len: Int = encodedName.size // - encodedName.position()
        if (len >= TarConstants.NAMELEN) {
            if (longFileMode == LONGFILE_POSIX) {
                paxHeaders[paxHeaderName] = name
                return true
            }
            if (longFileMode == LONGFILE_GNU) {
                // create a TarEntry for the LongLink, the contents
                // of which are the link's name
                val longLinkEntry = TarArchiveEntry(
                    TarConstants.GNU_LONGLINK,
                    linkType
                )
                longLinkEntry.setSize(len + 1L) // +1 for NUL
                transferModTime(entry, longLinkEntry)
                putArchiveEntry(longLinkEntry)
                write(encodedName, 0, len)
                write(0) // NUL terminator
                closeArchiveEntry()
            } else if (longFileMode != LONGFILE_TRUNCATE) {
                throw IllegalArgumentException(
                    (fieldName + " '" + name //NOSONAR
                            + "' is too long ( > "
                            + TarConstants.NAMELEN) + " bytes)"
                )
            }
        }
        return false
    }

    @Throws(IOException::class, CancellationException::class)
    private suspend fun padAsNeeded() {
        val start: Int = (recordsWritten % recordsPerBlock).toInt()
        if (start != 0) {
            for (i in start until recordsPerBlock) {
                writeEOFRecord()
            }
        }
    }

    /**
     * Put an entry on the output stream. This writes the entry's header record and positions the
     * output stream for writing the contents of the entry. Once this method is called, the stream
     * is ready for calls to write() to write the entry's contents. Once the contents are written,
     * closeArchiveEntry() <B>MUST</B> be called to ensure that all buffered data is completely
     * written to the output stream.
     *
     * @param archiveEntry The TarEntry to be written to the archive.
     * @throws IOException on error
     * @throws ClassCastException if archiveEntry is not an instance of TarArchiveEntry
     * @throws IllegalArgumentException if the [TarArchiveOutputStream.longFileMode] equals
     * [TarArchiveOutputStream.LONGFILE_ERROR] and the file
     * name is too long
     * @throws IllegalArgumentException if the [TarArchiveOutputStream.bigNumberMode] equals
     * [TarArchiveOutputStream.BIGNUMBER_ERROR] and one of the numeric values
     * exceeds the limits of a traditional tar header.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun putArchiveEntry(archiveEntry: ArchiveEntry) {
        if (finished) {
            throw IOException("Stream has already been finished")
        }
        val entry = archiveEntry as TarArchiveEntry
        if (entry.isGlobalPaxHeader()) {
            val data = encodeExtendedPaxHeadersContents(entry.getExtraPaxHeaders())
            entry.setSize(data!!.size.toLong())
            entry.writeEntryHeader(
                recordBuf,
                null,
                bigNumberMode == BIGNUMBER_STAR
            )
            writeRecord(recordBuf)
            currSize = entry.size
            currBytes = 0
            haveUnclosedEntry = true
            write(data)
            closeArchiveEntry()
        } else {
            val paxHeaders: MutableMap<String, String> = mutableMapOf()
            val entryName: String = entry.name
            val paxHeaderContainsPath = handleLongName(
                entry, entryName, paxHeaders, "path",
                TarConstants.LF_GNUTYPE_LONGNAME, "file name"
            )
            val linkName: String = entry.linkName
            val paxHeaderContainsLinkPath = (linkName.isNotEmpty() && handleLongName(
                entry, linkName, paxHeaders, "linkpath",
                TarConstants.LF_GNUTYPE_LONGLINK, "link name"
            ))
            if (bigNumberMode == BIGNUMBER_POSIX) {
                addPaxHeadersForBigNumbers(paxHeaders, entry)
            } else if (bigNumberMode != BIGNUMBER_STAR) {
                failForBigNumbers(entry)
            }

            if (addPaxHeadersForNonAsciiNames && !paxHeaderContainsPath && !CommonCharset.US_ASCII.canEncode(entryName)
//                && !org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.ASCII.canEncode(entryName)
            ) {
                paxHeaders["path"] = entryName
            }

            if (addPaxHeadersForNonAsciiNames && !paxHeaderContainsLinkPath
                && (entry.isLink() || entry.isSymbolicLink()) && !CommonCharset.US_ASCII.canEncode(linkName)
//                && !org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.ASCII.canEncode(linkName)
            ) {
                paxHeaders["linkpath"] = linkName
            }

            paxHeaders.putAll(entry.getExtraPaxHeaders())
            if (paxHeaders.isNotEmpty()) {
                writePaxHeaders(entry, entryName, paxHeaders)
            }
            entry.writeEntryHeader(
                recordBuf,
                null,
                bigNumberMode == BIGNUMBER_STAR
            )
            writeRecord(recordBuf)
            currBytes = 0
            currSize = if (entry.isDirectory()) {
                0
            } else {
                entry.size
            }
            currName = entryName
            haveUnclosedEntry = true
        }
    }

    /**
     * Whether to add a PAX extension header for non-ASCII file names.
     *
     * @param b whether to add a PAX extension header for non-ASCII file names.
     * @since 1.4
     */
    fun setAddPaxHeadersForNonAsciiNames(b: Boolean) {
        addPaxHeadersForNonAsciiNames = b
    }

    /**
     * Set the big number mode. This can be BIGNUMBER_ERROR(0), BIGNUMBER_STAR(1) or
     * BIGNUMBER_POSIX(2). This specifies the treatment of big files (sizes &gt;
     * TarConstants.MAXSIZE) and other numeric values too big to fit into a traditional tar header.
     * Default is BIGNUMBER_ERROR.
     *
     * @param bigNumberMode the mode to use
     * @since 1.4
     */
    fun setBigNumberMode(bigNumberMode: Int) {
        this.bigNumberMode = bigNumberMode
    }

    /**
     * Set the long file mode. This can be LONGFILE_ERROR(0), LONGFILE_TRUNCATE(1), LONGFILE_GNU(2) or
     * LONGFILE_POSIX(3). This specifies the treatment of long file names (names &gt;=
     * TarConstants.NAMELEN). Default is LONGFILE_ERROR.
     *
     * @param longFileMode the mode to use
     */
    fun setLongFileMode(longFileMode: Int) {
        this.longFileMode = longFileMode
    }

    /**
     * @return true if the character could lead to problems when used inside a TarArchiveEntry name
     * for a PAX header.
     */
    private fun shouldBeReplaced(c: Char): Boolean {
        // when used as last character TAE will consider the PAX header a directory
        return c.code == 0 || c == '/' || c == '\\' // same as '/' as slashes get "normalized" on Windows
    }

    private fun stripTo7Bits(name: String): String? {
        val length = name.length
        val result = StringBuilder(length)
        for (i in 0 until length) {
            val stripped = (name[i].code and 0x7F).toChar()
            if (shouldBeReplaced(stripped)) {
                result.append("_")
            } else {
                result.append(stripped)
            }
        }
        return result.toString()
    }

    private fun transferModTime(from: TarArchiveEntry, to: TarArchiveEntry) {
        var fromModTimeSeconds: Long = from.getLastModifiedTime().epochSeconds
        if (fromModTimeSeconds < 0 || fromModTimeSeconds > TarConstants.MAXSIZE) {
            fromModTimeSeconds = 0
        }
        to.setLastModifiedTime(Instant.fromEpochSeconds(fromModTimeSeconds))
    }

    /**
     * Write an EOF (end of archive) record to the tar archive. An EOF record consists of a record
     * of all zeros.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun writeEOFRecord() {
        arrayfill(recordBuf, 0)
        writeRecord(recordBuf)
    }

    /**
     * Writes a PAX extended header with the given map as contents.
     *
     * @since 1.4
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun writePaxHeaders(
        entry: TarArchiveEntry?,
        entryName: String?,
        headers: Map<String, String>,
    ) {
        var name = "./PaxHeaders.X/" + stripTo7Bits(entryName!!)
        if (name.length >= TarConstants.NAMELEN) {
            name = name.substring(0, TarConstants.NAMELEN - 1)
        }
        val pex = TarArchiveEntry(
            name,
            TarConstants.LF_PAX_EXTENDED_HEADER_LC
        )
        transferModTime(entry!!, pex)
        val data = encodeExtendedPaxHeadersContents(headers)
        pex.setSize(data!!.size.toLong())
        putArchiveEntry(pex)
        write(data)
        closeArchiveEntry()
    }

    /**
     * Write an archive record to the archive.
     *
     * @param record The record data to write to the archive.
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun writeRecord(record: ByteArray) {
        if (record.size != RECORD_SIZE) {
            throw IOException(
                "Record to write has length '"
                        + record.size
                        + "' which is not the record size of '"
                        + RECORD_SIZE + "'"
            )
        }
        out.write(record)
        recordsWritten++
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