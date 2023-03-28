package archivers.tar

import archivers.ArchiveUtils
import archivers.utils.BoundedArchiveInputStream
import archivers.utils.BoundedInputStream
import archivers.utils.BoundedSeekableByteChannelInputStream
import com.soywiz.kmem.ByteArrayBuilder
import com.soywiz.korio.lang.use
import com.soywiz.korio.stream.*
import okio.*
import utils.ByteBuffer
import utils.CommonCharset
import utils.decodeToString
import utils.seek

class TarFile : Closeable {

    /**
     * The meta-data about the current entry
     */
    private var currEntry: TarArchiveEntry? = null

    // the global PAX header
    private var globalPaxHeaders: Map<String, String> = hashMapOf()

    private val entries: MutableList<TarArchiveEntry> = mutableListOf()

    private var blockSize = 0

    private var lenient = false

    private var recordSize = 0

    private val smallBuf = ByteArray(SMALL_BUFFER_SIZE)


    private lateinit var recordBuffer: utils.Buffer

    // the global sparse headers, this is only used in PAX Format 0.X
    private val globalSparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()

    private var hasHitEOF = false

    private val sparseSources: MutableMap<String, List<SyncInputStream>> = hashMapOf()

    private var archiveCursor = Buffer.UnsafeCursor()


    private lateinit var archive: utils.Buffer


    constructor(archiveFileHandle: FileHandle) : this(
        archiveFileHandle,
        TarConstants.DEFAULT_BLKSIZE,
        TarConstants.DEFAULT_RCDSIZE,
        null,
        false
    )

    constructor(bufferCursor: Buffer.UnsafeCursor)

    /**
     * Constructor for TarFile.
     *
     * @param archiveCursor    the seekable byte channel to use
     * @param blockSize  the blocks size to use
     * @param recordSize the record size to use
     * @param encoding   the encoding to use
     * @param lenient    when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [TarArchiveEntry.UNKNOWN]. When set to false such illegal fields cause an
     * exception instead.
     * @throws IOException when reading the tar archive fails
     */
    @Throws(IOException::class)
    constructor(
        archiveFileHandle: FileHandle,
        blockSize: Int,
        recordSize: Int,
        encoding: String?,
        lenient: Boolean
    ) {
//        this.zipEncoding = ZipEncodingHelper.getZipEncoding(encoding)
        this.recordSize = recordSize
        this.recordBuffer = utils.Buffer(recordSize)//java.nio.ByteBuffer.allocate(recordSize)
        this.blockSize = blockSize
        this.lenient = lenient
        var entry: TarArchiveEntry?
        while (getNextTarEntry().also { entry = it } != null) {
            entries.add(entry!!)
        }


    }

    /**
     * Build the input streams consisting of all-zero input streams and non-zero input streams.
     * When reading from the non-zero input streams, the data is actually read from the original input stream.
     * The size of each input stream is introduced by the sparse headers.
     *
     * @implNote Some all-zero input streams and non-zero input streams have the size of 0. We DO NOT store the
     * 0 size input streams because they are meaningless.
     */
    @Throws(IOException::class)
    private fun buildSparseInputStreams() {
        val currentEntry = currEntry!!
        val sources: MutableList<SyncInputStream> = mutableListOf()
        val sparseHeaders: List<TarArchiveStructSparse> = currentEntry.getOrderedSparseHeaders()

        // Stream doesn't need to be closed at all as it doesn't use any resources
        val zeroInputStream = TarArchiveSparseZeroInputStream() //NOSONAR
        // logical offset into the extracted entry
        var offset: Long = 0
        var numberOfZeroBytesInSparseEntry: Long = 0
        for (sparseHeader in sparseHeaders) {
            val zeroBlockSize: Long = sparseHeader.offset - offset
            if (zeroBlockSize < 0) {
                // sparse header says to move backwards inside of the extracted entry
                throw IOException("Corrupted struct sparse detected")
            }

            // only store the zero block if it is not empty
            if (zeroBlockSize > 0) {
                sources.add(BoundedInputStream(zeroInputStream, zeroBlockSize))
                numberOfZeroBytesInSparseEntry += zeroBlockSize
            }

            // only store the input streams with non-zero size
            if (sparseHeader.numbytes > 0) {
                val start: Long = currentEntry.dataOffset + sparseHeader.offset - numberOfZeroBytesInSparseEntry
                if (start + sparseHeader.numbytes < start) {
                    // possible integer overflow
                    throw IOException("Unreadable TAR archive, sparse block offset or length too big")
                }
                sources.add(BoundedSeekableByteChannelInputStream(start, sparseHeader.numbytes, archive))
            }
            offset = sparseHeader.offset + sparseHeader.numbytes
        }
        sparseSources[currentEntry.name] = sources
    }

    /**
     * Update the current entry with the read pax headers
     * @param headers Headers read from the pax header
     * @param sparseHeaders Sparse headers read from pax header
     */
    @Throws(IOException::class)
    private fun applyPaxHeadersToCurrentEntry(
        headers: Map<String, String>,
        sparseHeaders: List<TarArchiveStructSparse>
    ) {
        currEntry!!.updateEntryFromPaxHeaders(headers)
        currEntry!!.setSparseHeaders(sparseHeaders)
    }

    /**
     * Read a record from the input stream and return the data.
     *
     * @return The record data or null if EOF has been hit.
     * @throws IOException if reading from the archive fails
     */
    @Throws(IOException::class)
    private fun readRecord(): utils.Buffer? {
        recordBuffer.reset()
        recordBuffer.write(archive)
        return if (recordBuffer.totalSize != recordSize) {
            null
        } else recordBuffer
    }

    /**
     * This method is invoked once the end of the archive is hit, it
     * tries to consume the remaining bytes under the assumption that
     * the tool creating this archive has padded the last block.
     */
    @Throws(IOException::class)
    private fun consumeRemainderOfLastBlock() {
        val bytesReadOfLastBlock: Long = archive.readPosition().toLong() % blockSize
        if (bytesReadOfLastBlock > 0) {
            repositionForwardBy((blockSize - bytesReadOfLastBlock).toInt())
        }
    }

    /**
     * Gets the input stream for the provided Tar Archive Entry.
     * @param entry Entry to get the input stream from
     * @return Input stream of the provided entry
     * @throws IOException Corrupted TAR archive. Can't read entry.
     */
    @Throws(IOException::class)
    fun getInputStream(entry: TarArchiveEntry): SyncInputStream {
        return try {
            BoundedTarEntryInputStream(entry, archive.openSync())
        } catch (ex: RuntimeException) {
            throw IOException("Corrupted TAR archive. Can't read entry", ex)
        }
    }

    /**
     * Get the next entry in this tar archive as longname data.
     *
     * @return The next entry in the archive as longname data, or null.
     * @throws IOException on error
     */
    @Throws(IOException::class)
    private fun getLongNameData(): ByteArray? {
        val longNameBuilder = ByteArrayBuilder()
        val longName = MemorySyncStreamBase(longNameBuilder).toSyncStream()
        var length: Int
        getInputStream(currEntry!!).use { inputStream ->
            while (inputStream.read(smallBuf).also { length = it } >= 0) {
                longName.write(smallBuf, 0, length)
            }
        }
        getNextTarEntry()
        if (currEntry == null) {
            // Bugzilla: 40334
            // Malformed tar file - long entry name not followed by entry
            return null
        }
        var longNameData: ByteArray = longName.toByteArray()
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
     * Get the next entry in this tar archive. This will skip
     * to the end of the current entry, if there is one, and
     * place the position of the channel at the header of the
     * next entry, and read the header and instantiate a new
     * TarEntry from the header bytes and return that entry.
     * If there are no more entries in the archive, null will
     * be returned to indicate that the end of the archive has
     * been reached.
     *
     * @return The next TarEntry in the archive, or null if there is no next entry.
     * @throws IOException when reading the next TarEntry fails
     */
    @Throws(IOException::class)
    private fun getNextTarEntry(): TarArchiveEntry? {
        if (isAtEOF()) {
            return null
        }
        currEntry?.let {
            // Skip to the end of the entry
            repositionForwardTo((it.dataOffset + it.size).toInt())
            throwExceptionIfPositionIsNotInArchive()
            skipRecordPadding()
        }
        val headerBuf = getRecord()
        if (null == headerBuf) {
            /* hit EOF */
            currEntry = null
            return null
        }
        currEntry = try {
            val position: Long = archive.readPosition().toLong()
            TarArchiveEntry(globalPaxHeaders, headerBuf.internalBuffer, null, lenient, position)
        } catch (e: IllegalArgumentException) {
            throw IOException("Error detected parsing the header", e)
        }
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
            currEntry!!.setName(name)
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
            throw IOException("Error detected parsing the pax header", e)
        }
        if (currEntry!!.isOldGNUSparse()) { // Process sparse files
            readOldGNUSparse()
        }
        return currEntry
    }

    @Throws(IOException::class)
    private fun readGlobalPaxHeaders() {
        val current = currEntry
        getInputStream(current!!).use { input ->
            globalPaxHeaders = TarUtil.parsePaxHeaders(
                input, globalSparseHeaders, globalPaxHeaders,
                current.size
            )
        }
        getNextTarEntry() // Get the actual file entry
        if (currEntry == null) {
            throw IOException("Error detected parsing the pax header")
        }
    }

    @Throws(IOException::class)
    private fun repositionForwardBy(offset: Int) {
        repositionForwardTo(archive.readPosition() + offset)
    }

    @Throws(IOException::class)
    private fun repositionForwardTo(newPosition: Int) {
        val currPosition: Int = archive.readPosition()
        if (newPosition < currPosition) {
            throw IOException("trying to move backwards inside of the archive")
        }
        archive.seekReadTo(newPosition)
//        archive.position(newPosition)
    }

    /**
     *
     *
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes)
     * may appear multi times, and they look like:
     * <pre>
     * GNU.sparse.size=size
     * GNU.sparse.numblocks=numblocks
     * repeat numblocks times
     * GNU.sparse.offset=offset
     * GNU.sparse.numbytes=numbytes
     * end repeat
    </pre> *
     *
     *
     *
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     * <pre>
     * GNU.sparse.map
     * Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
    </pre> *
     *
     *
     *
     * For PAX Format 1.X:
     * <br></br>
     * The sparse map itself is stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines. The map is padded with nulls to the nearest block boundary.
     * The first number gives the number of entries in the map. Following are map entries, each one consisting of two numbers
     * giving the offset and size of the data block it describes.
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun paxHeaders() {
        var sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        var headers: Map<String, String> = emptyMap()
        getInputStream(currEntry!!).use { input ->
            headers = TarUtil.parsePaxHeaders(input, sparseHeaders, globalPaxHeaders, currEntry!!.size)
        }

        // for 0.1 PAX Headers
        headers[TarGnuSparseKeys.MAP]?.let {
            sparseHeaders = TarUtil.parseFromPAX01SparseHeaders(it).toMutableList()
        }

        getNextTarEntry() // Get the actual file entry
        if (currEntry == null) {
            throw IOException("premature end of tar archive. Didn't find any entry after PAX header.")
        }
        applyPaxHeadersToCurrentEntry(headers, sparseHeaders)

        // for 1.0 PAX Format, the sparse map is stored in the file data block
        if (currEntry!!.isPaxGNU1XSparse()) {
            getInputStream(currEntry!!).use { input ->
                sparseHeaders = TarUtil.parsePAX1XSparseHeaders(input, recordSize).toMutableList()
            }
            currEntry!!.setSparseHeaders(sparseHeaders)
            // data of the entry is after the pax gnu entry. So we need to update the data position once again
            currEntry!!.setDataOffset(currEntry!!.dataOffset + recordSize)
        }

        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams()
    }

    /**
     * Adds the sparse chunks from the current entry to the sparse chunks,
     * including any additional sparse entries following the current entry.
     *
     * @throws IOException when reading the sparse entry fails
     */
    @Throws(IOException::class)
    private fun readOldGNUSparse() {
        if (currEntry!!.isExtended()) {
            var entry: TarArchiveSparseEntry
            do {
                val headerBuf = getRecord()
                    ?: throw IOException("premature end of tar archive. Didn't find extended_header after header with extended flag.")
                entry = TarArchiveSparseEntry(headerBuf.internalBuffer)
                currEntry!!.sparseHeaders.addAll(entry.sparseHeaders)
                currEntry!!.setDataOffset(currEntry!!.dataOffset + recordSize)
            } while (entry.isExtended)
        }

        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams()
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
     * @return The next TarEntry in the archive, or null if there is no next entry.
     * @throws IOException when reading the next TarEntry fails
     */
    @Throws(IOException::class)
    private fun getRecord(): utils.Buffer? {
        var headerBuf = readRecord()
        setAtEOF(isEOFRecord(headerBuf))
        if (isAtEOF() && headerBuf != null) {
            // Consume rest
            tryToConsumeSecondEOFRecord()
            consumeRemainderOfLastBlock()
            headerBuf = null
        }
        return headerBuf
    }

    fun isAtEOF(): Boolean {
        return hasHitEOF
    }

    private fun isDirectory(): Boolean {
        val cur = currEntry
        return cur != null && cur.isDirectory()
    }

    private fun isEOFRecord(headerBuf: utils.Buffer?): Boolean {
        return headerBuf == null || ArchiveUtils.isArrayZero(headerBuf.internalBuffer, recordSize)
    }

    fun setAtEOF(b: Boolean) {
        hasHitEOF = b
    }

    /**
     * The last record block should be written at the full size, so skip any
     * additional space used to fill a record after an entry
     *
     * @throws IOException when skipping the padding of the record fails
     */
    @Throws(IOException::class)
    private fun skipRecordPadding() {
        val current = currEntry!!
        if (!isDirectory() && current.size > 0 && current.size % recordSize.toLong() != 0.toLong()) {
            val numRecords: Long = current.size / recordSize + 1
            val padding: Long = numRecords * recordSize - current.size
            repositionForwardBy(padding.toInt())
            throwExceptionIfPositionIsNotInArchive()
        }
    }

    /**
     * Checks if the current position of the SeekableByteChannel is in the archive.
     * @throws IOException If the position is not in the archive
     */
    @Throws(IOException::class)
    private fun throwExceptionIfPositionIsNotInArchive() {
        if (archive.totalSize < archive.readPosition()) {
            throw IOException("Truncated TAR archive")
        }
    }

    override fun close() {
        archiveCursor.close()
//        recordBuffer.close() // TODO()
    }

    /**
     * Tries to read the next record resetting the position in the
     * archive if it is not a EOF record.
     *
     *
     * This is meant to protect against cases where a tar
     * implementation has written only one EOF record when two are
     * expected. Actually this won't help since a non-conforming
     * implementation likely won't fill full blocks consisting of - by
     * default - ten records either so we probably have already read
     * beyond the archive anyway.
     *
     * @throws IOException if reading the record of resetting the position in the archive fails
     */
    @Throws(IOException::class)
    private fun tryToConsumeSecondEOFRecord() {
        var shouldReset = true
        try {
            shouldReset = !isEOFRecord(readRecord())
        } finally {
            if (shouldReset) {
                archive.seekReadTo(archive.readPosition() - recordSize)
//                archive.position(archive.position() - recordSize)
            }
        }
    }

    companion object {
        private const val SMALL_BUFFER_SIZE = 256
    }


    private open inner class BoundedTarEntryInputStream internal constructor(
        entry: TarArchiveEntry,
        private val channel: SyncStream
    ) : BoundedArchiveInputStream(entry.dataOffset, entry.getRealSize()) {
        private val entry: TarArchiveEntry
        private var entryOffset: Long = 0
        private var currentSparseInputStreamIndex = 0

        init {
            if (channel.availableWrite - entry.size < entry.dataOffset) {
                throw IOException("entry size exceeds archive size")
            }
            this.entry = entry
        }

        override fun read(pos: Long, buf: ByteBuffer): Int {
            if (entryOffset >= entry.getRealSize()) {
                return -1
            }

            val totalRead: Int = if (entry.isSparse()) {
                readSparse(entryOffset, buf, buf.totalSize)
            } else {
                readArchive(pos, buf)
            }
            if (totalRead == -1) {
                if (buf.availableWrite > 0) {
                    throw IOException("Truncated TAR archive")
                }
                setAtEOF(true)
            } else {
                entryOffset += totalRead.toLong()
//                buf.flip()
            }
            return totalRead
        }

        @Throws(IOException::class)
        private fun readArchive(pos: Long, buf: ByteBuffer): Int {
            channel.seek(pos)
            return channel.read(data = buf.internalBuffer)
        }

        @Throws(IOException::class)
        private fun readSparse(pos: Long, buf: ByteBuffer, numToRead: Int): Int {
            // if there are no actual input streams, just read from the original archive
            val entrySparseInputStreams: List<SyncInputStream>? = sparseSources[entry.name]
            if (entrySparseInputStreams.isNullOrEmpty()) {
                return readArchive(entry.dataOffset + pos, buf)
            }
            if (currentSparseInputStreamIndex >= entrySparseInputStreams.size) {
                return -1
            }
            val currentInputStream: SyncInputStream = entrySparseInputStreams[currentSparseInputStreamIndex]
            val bufArray = ByteArray(numToRead)
            val readLen: Int = currentInputStream.read(bufArray)
            if (readLen != -1) {
                buf.write(bufArray, 0, readLen)
//                buf.put(bufArray, 0, readLen)
            }

            // if the current input stream is the last input stream,
            // just return the number of bytes read from current input stream
            if (currentSparseInputStreamIndex == entrySparseInputStreams.size - 1) {
                return readLen
            }

            // if EOF of current input stream is meet, open a new input stream and recursively call read
            if (readLen == -1) {
                currentSparseInputStreamIndex++
                return readSparse(pos, buf, numToRead)
            }

            // if the rest data of current input stream is not long enough, open a new input stream
            // and recursively call read
            if (readLen < numToRead) {
                currentSparseInputStreamIndex++
                val readLenOfNext = readSparse(pos + readLen, buf, numToRead - readLen)
                return if (readLenOfNext == -1) {
                    readLen
                } else readLen + readLenOfNext
            }

            // if the rest data of current input stream is enough(which means readLen == len), just return readLen
            return readLen
        }
    }

}
