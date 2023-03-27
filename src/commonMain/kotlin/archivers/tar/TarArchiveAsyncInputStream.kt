package archivers.tar

import archivers.ArchiveAsyncInputStream
import archivers.ArchiveEntry
import archivers.ArchiveUtils
import archivers.utils.BoundedAsyncInputStream
import com.soywiz.kmem.ByteArrayBuilder
import com.soywiz.korio.lang.IOException
import com.soywiz.korio.stream.*
import utils.CommonCharset
import utils.IOUtil
import utils.decodeToString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

class TarArchiveAsyncInputStream : ArchiveAsyncInputStream {

    private val smallBuf = ByteArray(SMALL_BUFFER_SIZE)

    /** The size the TAR header  */
    private var recordSize = 0

    /** The buffer to store the TAR header  */
    private var recordBuffer: ByteArray

    /** The size of a block  */
    private var blockSize = 0

    /** True if file has hit EOF  */
    private var hasHitEOF = false

    /** Size of the current entry  */
    private var entrySize: Long = 0

    /** How far into the entry the stream is at  */
    private var entryOffset: Long = 0

    /** An input stream to read from  */
    private lateinit var inputStream: AsyncInputStream

    /** Input streams for reading sparse entries  */
    private var sparseInputStreams: MutableList<AsyncInputStream> = mutableListOf()

    /** the index of current input stream being read when reading sparse entries  */
    private var currentSparseInputStreamIndex = 0

    /** The meta-data about the current entry  */
    private var currEntry: TarArchiveEntry? = null

    // the provided encoding (for unit tests)
    var encoding: String? = null

    // the global PAX header
    private var globalPaxHeaders: MutableMap<String, String> = hashMapOf()

    // the global sparse headers, this is only used in PAX Format 0.X
    private val globalSparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()

    private var lenient = false

    /**
     * Constructor for TarInputStream.
     * @param inputStream the input stream to use
     */
    constructor(inputStream: AsyncInputStream) : this(
        inputStream,
        TarConstants.DEFAULT_BLKSIZE,
        TarConstants.DEFAULT_RCDSIZE
    )

    /**
     * Constructor for TarInputStream.
     * @param inputStream the input stream to use
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [TarArchiveEntry.UNKNOWN]. When set to false such illegal fields cause an
     * exception instead.
     * @since 1.19
     */
    constructor(inputStream: AsyncInputStream, lenient: Boolean) : this(
        inputStream,
        TarConstants.DEFAULT_BLKSIZE,
        TarConstants.DEFAULT_RCDSIZE,
        null,
        lenient
    )


    /**
     * Constructor for TarInputStream.
     * @param inputStream the input stream to use
     * @param blockSize the block size to use
     */
    constructor(inputStream: AsyncInputStream, blockSize: Int) : this(
        inputStream,
        blockSize,
        TarConstants.DEFAULT_RCDSIZE
    )


    /**
     * Constructor for TarInputStream.
     * @param inputStream the input stream to use
     * @param blockSize the block size to use
     * @param recordSize the record size to use
     */
    constructor(inputStream: AsyncInputStream, blockSize: Int, recordSize: Int) : this(
        inputStream,
        blockSize,
        recordSize,
        null
    )


    /**
     * Constructor for TarInputStream.
     * @param `is` the input stream to use
     * @param blockSize the block size to use
     * @param recordSize the record size to use
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    constructor(
        inputStream: AsyncInputStream, blockSize: Int, recordSize: Int,
        encoding: String?,
    ) : this(inputStream, blockSize, recordSize, encoding, false)


    /**
     * Constructor for TarInputStream.
     * @param `is` the input stream to use
     * @param blockSize the block size to use
     * @param recordSize the record size to use
     * @param encoding name of the encoding to use for file names
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [TarArchiveEntry.UNKNOWN]. When set to false such illegal fields cause an
     * exception instead.
     * @since 1.19
     */
    constructor(
        inputStream: AsyncInputStream, blockSize: Int, recordSize: Int,
        encoding: String?, lenient: Boolean,
    ) {
        this.inputStream = inputStream
        this.hasHitEOF = false
        this.encoding = encoding
        this.recordSize = recordSize
        recordBuffer = ByteArray(recordSize)
        this.blockSize = blockSize
        this.lenient = lenient
    }

    /**
     * Constructor for TarInputStream.
     * @param inputStream the input stream to use
     * @param blockSize the block size to use
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    constructor(
        inputStream: AsyncInputStream, blockSize: Int,
        encoding: String?,
    ) : this(inputStream, blockSize, TarConstants.DEFAULT_RCDSIZE, encoding)


    /**
     * Constructor for TarInputStream.
     * @param inputStream the input stream to use
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    constructor(inputStream: AsyncInputStream, encoding: String?) : this(
        inputStream, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE,
        encoding
    )

    @Throws(IOException::class)
    private fun applyPaxHeadersToCurrentEntry(
        headers: Map<String, String>,
        sparseHeaders: List<TarArchiveStructSparse>,
    ) {
        currEntry!!.updateEntryFromPaxHeaders(headers)
        currEntry!!.setSparseHeaders(sparseHeaders)
    }

    /**
     * Get the available data that can be read from the current
     * entry in the archive. This does not indicate how much data
     * is left in the entire archive, only in the current entry.
     * This value is determined from the entry's size header field
     * and the amount of data already read from the current entry.
     * Integer.MAX_VALUE is returned in case more than Integer.MAX_VALUE
     * bytes are left in the current entry in the archive.
     *
     * @return The number of available bytes for the current entry.
     * @throws IOException for signature
     */
    @Throws(IOException::class)
    fun available(): Int {
        if (isDirectory()) {
            return 0
        }
        return if (currEntry!!.getRealSize() - entryOffset > Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else (currEntry!!.getRealSize() - entryOffset).toInt()
    }

    /**
     * Build the input streams consisting of all-zero input streams and non-zero input streams.
     * When reading from the non-zero input streams, the data is actually read from the original input stream.
     * The size of each input stream is introduced by the sparse headers.
     *
     * NOTE : Some all-zero input streams and non-zero input streams have the size of 0. We DO NOT store the
     * 0 size input streams because they are meaningless.
     */
    @Throws(IOException::class)
    private fun buildSparseInputStreams() {
        currentSparseInputStreamIndex = -1
        sparseInputStreams = mutableListOf()
        val sparseHeaders = currEntry!!.getOrderedSparseHeaders()

        // Stream doesn't need to be closed at all as it doesn't use any resources
        val zeroInputStream: AsyncInputStream = TarArchiveSparseZeroAsyncInputStream() //NOSONAR
        // logical offset into the extracted entry
        var offset: Long = 0
        for (sparseHeader in sparseHeaders) {
            val zeroBlockSize: Long = sparseHeader.offset - offset
            if (zeroBlockSize < 0) {
                // sparse header says to move backwards inside of the extracted entry
                throw IOException("Corrupted struct sparse detected")
            }

            // only store the zero block if it is not empty
            if (zeroBlockSize > 0) {
                sparseInputStreams.add(BoundedAsyncInputStream(zeroInputStream, sparseHeader.offset - offset))
            }

            // only store the input streams with non-zero size
            if (sparseHeader.numbytes > 0) {
                sparseInputStreams.add(BoundedAsyncInputStream(inputStream, sparseHeader.numbytes))
            }
            offset = sparseHeader.offset + sparseHeader.numbytes
        }
        if (sparseInputStreams.isNotEmpty()) {
            currentSparseInputStreamIndex = 0
        }
    }

    /**
     * Whether this class is able to read the given entry.
     *
     * @return The implementation will return true if the [ArchiveEntry] is an instance of [TarArchiveEntry]
     */
//    fun canReadEntryData(ae: ArchiveEntry): Boolean {
//        return ae is TarArchiveEntry
//    }

    /**
     * This method is invoked once the end of the archive is hit, it
     * tries to consume the remaining bytes under the assumption that
     * the tool creating this archive has padded the last block.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun consumeRemainderOfLastBlock() {
        val bytesReadOfLastBlock: Long = bytesRead % blockSize
        if (bytesReadOfLastBlock > 0) {
            val skipped: Long = IOUtil.skip(inputStream, blockSize - bytesReadOfLastBlock)
            count(skipped)
        }
    }

    /**
     * For FileInputStream, the skip always return the number you input, so we
     * need the available bytes to determine how many bytes are actually skipped
     *
     * @param available available bytes returned by inputStream.available()
     * @param skipped   skipped bytes returned by inputStream.skip()
     * @param expected  bytes expected to skip
     * @return number of bytes actually skipped
     * @throws IOException if a truncated tar archive is detected
     */
    @Throws(IOException::class)
    private fun getActuallySkipped(available: Long, skipped: Long, expected: Long): Long {
        var actuallySkipped = skipped
        if (inputStream is SyncLengthStream) {
            actuallySkipped = min(skipped, available)
        }
        if (actuallySkipped != expected) {
            throw IOException("Truncated TAR archive")
        }
        return actuallySkipped
    }

    /**
     * Get the current TAR Archive Entry that this input stream is processing
     *
     * @return The current Archive Entry
     */
    fun getCurrentEntry(): TarArchiveEntry? {
        return currEntry
    }

    /**
     * Get the next entry in this tar archive as longname data.
     *
     * @return The next entry in the archive as longname data, or null.
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun getLongNameData(): ByteArray? {
        // read in the name
        val byteArrayBuilder = ByteArrayBuilder()
        val longName = MemorySyncStreamBase(byteArrayBuilder).toAsync().toAsyncStream()
        var length = 0
        while (read(smallBuf).also { length = it } >= 0) {
            longName.write(smallBuf, 0, length)
        }
        getNextEntry()
        if (currEntry == null) {
            // Bugzilla: 40334
            // Malformed tar file - long entry name not followed by entry
            return null
        }
        var longNameData: ByteArray = byteArrayBuilder.data
        // remove trailing null terminator(s)
        length = longNameData.size
        while (length > 0 && longNameData[length - 1].toInt() == 0) {
            --length
        }
        if (length != longNameData.size) {
            longNameData = longNameData.copyOf(length)
        }
        return longNameData
    }

    /**
     * Returns the next Archive Entry in this Stream.
     *
     * @return the next entry,
     * or `null` if there are no more entries
     * @throws IOException if the next entry could not be read
     */
    override suspend fun getNextEntry(): ArchiveEntry? {
        return getNextTarEntry()
    }

    /**
     * Get the next entry in this tar archive. This will skip
     * over any remaining data in the current entry, if there
     * is one, and place the input stream at the header of the
     * next entry, and read the header and instantiate a new
     * TarEntry from the header bytes and return that entry.
     * If there are no more entries in the archive, null will
     * be returned to indicate that the end of the archive has
     * been reached.
     *
     * @return The next TarEntry in the archive, or null.
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun getNextTarEntry(): TarArchiveEntry? {
        if (isAtEOF()) {
            return null
        }
        if (currEntry != null) {
            /* Skip will only go to the end of the current entry */
            IOUtil.skip(this, Long.MAX_VALUE)

            /* skip to the end of the last record */
            skipRecordPadding()
        }
        val headerBuf: ByteArray? = getRecord()
        if (headerBuf == null) {
            /* hit EOF */
            currEntry = null
            return null
        }
        currEntry = try {
            TarArchiveEntry(globalPaxHeaders, headerBuf, null, lenient)
        } catch (e: IllegalArgumentException) {
            throw IOException("Error detected parsing the header + $e")
        }
        entryOffset = 0
        entrySize = currEntry!!.size
        if (currEntry!!.isGNULongLinkEntry()) {
            val longLinkData = getLongNameData()
                ?: // Bugzilla: 40334
                // Malformed tar file - long link entry name not followed by
                // entry
                return null
            currEntry!!.setLinkName(longLinkData.decodeToString(CommonCharset.UTF8))
        }
        if (currEntry!!.isGNULongNameEntry()) {
            val longNameData = getLongNameData()
                ?: // Bugzilla: 40334
                // Malformed tar file - long entry name not followed by
                // entry
                return null
            // COMPRESS-509 : the name of directories should end with '/'
            val name: String = longNameData.decodeToString(CommonCharset.UTF8)
            if (name.isNotEmpty()) {
                currEntry!!.setName(name)
            }
            if (currEntry!!.isDirectory() && !name.endsWith("/")) {
                currEntry!!.setName("$name/")
            }
        }
        if (currEntry!!.isGlobalPaxHeader()) { // Process Global Pax headers
            readGlobalPaxHeaders()
        }
        try {
            if (currEntry!!.isPaxHeader()) { // Process Pax headers
                paxHeaders()
            } else if (globalPaxHeaders.isNotEmpty()) {
                applyPaxHeadersToCurrentEntry(globalPaxHeaders, globalSparseHeaders)
            }
        } catch (e: NumberFormatException) {
            throw IOException("Error detected parsing the pax header + $e")
        }
        if (currEntry!!.isOldGNUSparse()) { // Process sparse files
            readOldGNUSparse()
        }

        // If the size of the next element in the archive has changed
        // due to a new size being reported in the posix header
        // information, we update entrySize here so that it contains
        // the correct value.
        entrySize = currEntry!!.size
        return currEntry
    }

    /**
     * Get the next record in this tar archive. This will skip
     * over any remaining data in the current entry, if there
     * is one, and place the input stream at the header of the
     * next entry.
     *
     *
     * If there are no more entries in the archive, null will be
     * returned to indicate that the end of the archive has been
     * reached.  At the same time the `hasHitEOF` marker will be
     * set to true.
     *
     * @return The next header in the archive, or null.
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun getRecord(): ByteArray? {
        var headerBuf: ByteArray? = readRecord()
        setAtEOF(isEOFRecord(headerBuf))
        if (isAtEOF() && headerBuf != null) {
            tryToConsumeSecondEOFRecord()
            consumeRemainderOfLastBlock()
            headerBuf = null
        }
        return headerBuf
    }

    /**
     * Get the record size being used by this stream's buffer.
     *
     * @return The TarBuffer record size.
     */
    fun getRecordSize(): Int {
        return recordSize
    }

    fun isAtEOF(): Boolean {
        return hasHitEOF
    }

    private fun isDirectory(): Boolean {
        return currEntry != null && currEntry!!.isDirectory()
    }

    /**
     * Determine if an archive record indicate End of Archive. End of
     * archive is indicated by a record that consists entirely of null bytes.
     *
     * @param record The record data to check.
     * @return true if the record data is an End of Archive
     */
    fun isEOFRecord(record: ByteArray?): Boolean {
        return record == null || ArchiveUtils.isArrayZero(record, recordSize)
    }

    /**
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes)
     * may appear multi times, and they look like:
     *
     * GNU.sparse.size=size
     * GNU.sparse.numblocks=numblocks
     * repeat numblocks times
     * GNU.sparse.offset=offset
     * GNU.sparse.numbytes=numbytes
     * end repeat
     *
     *
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     *
     * GNU.sparse.map
     * Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     *
     * For PAX Format 1.X:
     * The sparse map itself is stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines. The map is padded with nulls to the nearest block boundary.
     * The first number gives the number of entries in the map. Following are map entries, each one consisting of two numbers
     * giving the offset and size of the data block it describes.
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun paxHeaders() {
        var sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        val headers: Map<String, String> = TarUtil.parsePaxHeadersAsync(this, sparseHeaders, globalPaxHeaders, entrySize)

        // for 0.1 PAX Headers
        headers[TarGnuSparseKeys.MAP]?.let {
            sparseHeaders = TarUtil.parseFromPAX01SparseHeaders(it).toMutableList()
        }

        getNextEntry() // Get the actual file entry
        if (currEntry == null) {
            throw IOException("premature end of tar archive. Didn't find any entry after PAX header.")
        }
        applyPaxHeadersToCurrentEntry(headers, sparseHeaders)

        // for 1.0 PAX Format, the sparse map is stored in the file data block
        if (currEntry!!.isPaxGNU1XSparse()) {
            sparseHeaders = TarUtil.parsePAX1XSparseHeadersAsync(inputStream, recordSize).toMutableList()
            currEntry!!.setSparseHeaders(sparseHeaders)
        }

        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams()
    }




    override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        var len = len
        if (len == 0) {
            return 0
        }
        var totalRead = 0

        if (isAtEOF() || isDirectory()) {
            return -1
        }

        if (currEntry == null) {
            throw IllegalStateException("No current tar entry")
        }


        if (entryOffset >= currEntry!!.getRealSize()) {
            return -1
        }

        len = min(len, available())

        totalRead = if (currEntry!!.isSparse()) {
            // for sparse entries, we need to read them in another way
            readSparse(buffer, offset, len)
        } else {

            inputStream.read(buffer, offset, len)
        }

        if (totalRead == -1) {
            if (len > 0) {
                throw IOException("Truncated TAR archive")
            }
            setAtEOF(true)
        } else {
            count(totalRead)
            entryOffset += totalRead.toLong()
        }

        return totalRead
    }

    @Throws(IOException::class, CancellationException::class)
    private suspend fun readGlobalPaxHeaders() {
        globalPaxHeaders = TarUtil.parsePaxHeadersAsync(this, globalSparseHeaders, globalPaxHeaders, entrySize).toMutableMap()
        getNextEntry() // Get the actual file entry
        if (currEntry == null) {
            throw IOException("Error detected parsing the pax header")
        }
    }

    /**
     * Adds the sparse chunks from the current entry to the sparse chunks,
     * including any additional sparse entries following the current entry.
     *
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun readOldGNUSparse() {
        if (currEntry!!.isExtended()) {
            var entry: TarArchiveSparseEntry
            do {
                val headerBuf = getRecord()
                    ?: throw IOException("premature end of tar archive. Didn't find extended_header after header with extended flag.")
                entry = TarArchiveSparseEntry(headerBuf)
                currEntry!!.sparseHeaders.addAll(entry.sparseHeaders)
            } while (entry.isExtended)
        }

        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams()
    }

    /**
     * Read a record from the input stream and return the data.
     *
     * @return The record data or null if EOF has been hit.
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun readRecord(): ByteArray? {
        val readNow: Int = IOUtil.readFully(inputStream, recordBuffer)
        count(readNow)
        return if (readNow != recordSize) {
            null
        } else recordBuffer
    }

    /**
     * For sparse tar entries, there are many "holes"(consisting of all 0) in the file. Only the non-zero data is
     * stored in tar files, and they are stored separately. The structure of non-zero data is introduced by the
     * sparse headers using the offset, where a block of non-zero data starts, and numbytes, the length of the
     * non-zero data block.
     * When reading sparse entries, the actual data is read out with "holes" and non-zero data combined together
     * according to the sparse headers.
     *
     * @param buf The buffer into which to place bytes read.
     * @param offset The offset at which to place bytes read.
     * @param numToRead The number of bytes to read.
     * @return The number of bytes read, or -1 at EOF.
     * @throws IOException on error
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun readSparse(buf: ByteArray, offset: Int, numToRead: Int): Int {
        // if there are no actual input streams, just read from the original input stream
        if (sparseInputStreams.isEmpty()) {
            return inputStream.read(buf, offset, numToRead)
        }
        if (currentSparseInputStreamIndex >= sparseInputStreams.size) {
            return -1
        }
        val currentInputStream = sparseInputStreams[currentSparseInputStreamIndex]
        val readLen: Int = currentInputStream.read(buf, offset, numToRead)

        // if the current input stream is the last input stream,
        // just return the number of bytes read from current input stream
        if (currentSparseInputStreamIndex == sparseInputStreams.size - 1) {
            return readLen
        }

        // if EOF of current input stream is meet, open a new input stream and recursively call read
        if (readLen == -1) {
            currentSparseInputStreamIndex++
            return readSparse(buf, offset, numToRead)
        }

        // if the rest data of current input stream is not long enough, open a new input stream
        // and recursively call read
        if (readLen < numToRead) {
            currentSparseInputStreamIndex++
            val readLenOfNext = readSparse(buf, offset + readLen, numToRead - readLen)
            return if (readLenOfNext == -1) {
                readLen
            } else readLen + readLenOfNext
        }
        // if the rest data of current input stream is enough(which means readLen == len), just return readLen
        return readLen
    }

    fun setAtEOF(b: Boolean) {
        hasHitEOF = b
    }

    fun setCurrentEntry(e: TarArchiveEntry) {
        currEntry = e
    }

    suspend fun skip(count: Long): Long {
        if (count <= 0 || isDirectory()) {
            return 0
        }
        val inputStream = inputStream
        val availableOfInputStream = if (inputStream is SyncLengthStream) {
            inputStream.length
        } else 0
//        val availableOfInputStream: Long = inputStream.available().toLong()
        val available: Long = currEntry!!.getRealSize() - entryOffset
        val numToSkip: Long = min(count, available)
        var skipped: Long

        if (!currEntry!!.isSparse()) {
            skipped = IOUtil.skip(inputStream, numToSkip)
            // for non-sparse entry, we should get the bytes actually skipped bytes along with
            // inputStream.available() if inputStream is instance of FileInputStream
            skipped = getActuallySkipped(availableOfInputStream, skipped, numToSkip)
        } else {
            skipped = skipSparse(numToSkip)
        }
        count(skipped)
        entryOffset += skipped
        return skipped
    }

    /**
     * The last record block should be written at the full size, so skip any
     * additional space used to fill a record after an entry.
     *
     * @throws IOException if a truncated tar archive is detected
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun skipRecordPadding() {
        if (!isDirectory() && entrySize > 0 && entrySize % recordSize != 0L) {
            val inputStream = inputStream
            val available = if (inputStream is SyncLengthStream) {
                inputStream.length
            } else 0
//            val available: Long = inputStream.available().toLong()
            val numRecords = entrySize / recordSize + 1
            val padding = numRecords * recordSize - entrySize
            var skipped: Long = IOUtil.skip(inputStream, padding)
            skipped = getActuallySkipped(available, skipped, padding)
            skipped = getActuallySkipped(available, skipped, padding)
            count(skipped)
        }
    }

    /**
     * Skip n bytes from current input stream, if the current input stream doesn't have enough data to skip,
     * jump to the next input stream and skip the rest bytes, keep doing this until total n bytes are skipped
     * or the input streams are all skipped
     *
     * @param n bytes of data to skip
     * @return actual bytes of data skipped
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun skipSparse(n: Long): Long {
        if (sparseInputStreams == null || sparseInputStreams.isEmpty()) {
            inputStream.skip(n.toInt())
            return n
        }
        var bytesSkipped: Long = 0
        while (bytesSkipped < n && currentSparseInputStreamIndex < sparseInputStreams.size) {
            val currentInputStream = sparseInputStreams[currentSparseInputStreamIndex]
            currentInputStream.skip((n - bytesSkipped).toInt())
            bytesSkipped += n - bytesSkipped
            if (bytesSkipped < n) {
                currentSparseInputStreamIndex++
            }
        }
        return bytesSkipped
    }

    /**
     * Tries to read the next record rewinding the stream if it is not a EOF record.
     *
     *
     * This is meant to protect against cases where a tar
     * implementation has written only one EOF record when two are
     * expected.  Actually this won't help since a non-conforming
     * implementation likely won't fill full blocks consisting of - by
     * default - ten records either so we probably have already read
     * beyond the archive anyway.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun tryToConsumeSecondEOFRecord() {
        var shouldReset = true
//        val marked: Boolean = inputStream.markSupported()
        val syncStream = inputStream.toAsyncStream().toSyncOrNull() ?: return
        val markableStream = syncStream.markable()
//        if (marked) {
        markableStream.mark(recordSize)
//            inputStream.mark(recordSize)
//        }

        try {
            shouldReset = !isEOFRecord(readRecord())
        } finally {
            if (shouldReset) {
                pushedBackBytes(recordSize.toLong())
                markableStream.reset()
            }
        }
    }

    override suspend fun close() {
        // Close all the input streams in sparseInputStreams
        for (inputStream in sparseInputStreams) {
            inputStream.close()
        }
        inputStream.close()
    }


    companion object {
        private const val SMALL_BUFFER_SIZE = 256

        /**
         * Checks if the signature matches what is expected for a tar file.
         *
         * @param signature
         * the bytes to check
         * @param length
         * the number of bytes to check
         * @return true, if this stream is a tar archive stream, false otherwise
         */
        fun matches(signature: ByteArray, length: Int): Boolean {
            if (length < TarConstants.VERSION_OFFSET + TarConstants.VERSIONLEN) {
                return false
            }
            if (ArchiveUtils.matchAsciiBuffer(
                    TarConstants.MAGIC_POSIX,
                    signature, TarConstants.MAGIC_OFFSET, TarConstants.MAGICLEN
                )
                &&
                ArchiveUtils.matchAsciiBuffer(
                    TarConstants.VERSION_POSIX,
                    signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN
                )
            ) {
                return true
            }
            return if (ArchiveUtils.matchAsciiBuffer(
                    TarConstants.MAGIC_GNU,
                    signature, TarConstants.MAGIC_OFFSET, TarConstants.MAGICLEN
                )
                &&
                (ArchiveUtils.matchAsciiBuffer(
                    TarConstants.VERSION_GNU_SPACE,
                    signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN
                ) || ArchiveUtils.matchAsciiBuffer(
                    TarConstants.VERSION_GNU_ZERO,
                    signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN
                ))
            ) {
                true
            } else (ArchiveUtils.matchAsciiBuffer(
                TarConstants.MAGIC_ANT,
                signature, TarConstants.MAGIC_OFFSET, TarConstants.MAGICLEN
            )
                    &&
                    ArchiveUtils.matchAsciiBuffer(
                        TarConstants.VERSION_ANT,
                        signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN
                    ))
            // COMPRESS-107 - recognise Ant tar files
        }
    }


}