package archivers.tar

import archivers.ArchiveEntry
import archivers.ArchiveUtils
import archivers.EntryStreamOffsets
import archivers.tar.TarConstants.ATIMELEN_GNU
import archivers.tar.TarConstants.ATIMELEN_XSTAR
import archivers.tar.TarConstants.CHKSUMLEN
import archivers.tar.TarConstants.CTIMELEN_GNU
import archivers.tar.TarConstants.CTIMELEN_XSTAR
import archivers.tar.TarConstants.DEVLEN
import archivers.tar.TarConstants.FORMAT_OLDGNU
import archivers.tar.TarConstants.FORMAT_POSIX
import archivers.tar.TarConstants.FORMAT_XSTAR
import archivers.tar.TarConstants.GIDLEN
import archivers.tar.TarConstants.GNAMELEN
import archivers.tar.TarConstants.ISEXTENDEDLEN_GNU
import archivers.tar.TarConstants.LF_BLK
import archivers.tar.TarConstants.LF_CHR
import archivers.tar.TarConstants.LF_DIR
import archivers.tar.TarConstants.LF_FIFO
import archivers.tar.TarConstants.LF_GNUTYPE_LONGLINK
import archivers.tar.TarConstants.LF_GNUTYPE_LONGNAME
import archivers.tar.TarConstants.LF_GNUTYPE_SPARSE
import archivers.tar.TarConstants.LF_LINK
import archivers.tar.TarConstants.LF_MULTIVOLUME
import archivers.tar.TarConstants.LF_NORMAL
import archivers.tar.TarConstants.LF_OFFSET
import archivers.tar.TarConstants.LF_OLDNORM
import archivers.tar.TarConstants.LF_PAX_EXTENDED_HEADER_LC
import archivers.tar.TarConstants.LF_PAX_EXTENDED_HEADER_UC
import archivers.tar.TarConstants.LF_PAX_GLOBAL_EXTENDED_HEADER
import archivers.tar.TarConstants.LF_SYMLINK
import archivers.tar.TarConstants.LONGNAMESLEN_GNU
import archivers.tar.TarConstants.MAGICLEN
import archivers.tar.TarConstants.MAGIC_GNU
import archivers.tar.TarConstants.MAGIC_OFFSET
import archivers.tar.TarConstants.MAGIC_POSIX
import archivers.tar.TarConstants.MAGIC_XSTAR
import archivers.tar.TarConstants.MODELEN
import archivers.tar.TarConstants.MODTIMELEN
import archivers.tar.TarConstants.NAMELEN
import archivers.tar.TarConstants.OFFSETLEN_GNU
import archivers.tar.TarConstants.PAD2LEN_GNU
import archivers.tar.TarConstants.PREFIXLEN
import archivers.tar.TarConstants.PREFIXLEN_XSTAR
import archivers.tar.TarConstants.REALSIZELEN_GNU
import archivers.tar.TarConstants.SIZELEN
import archivers.tar.TarConstants.SPARSELEN_GNU
import archivers.tar.TarConstants.SPARSE_HEADERS_IN_OLDGNU_HEADER
import archivers.tar.TarConstants.UIDLEN
import archivers.tar.TarConstants.UNAMELEN
import archivers.tar.TarConstants.VERSIONLEN
import archivers.tar.TarConstants.VERSION_GNU_SPACE
import archivers.tar.TarConstants.VERSION_POSIX
import archivers.tar.TarConstants.XSTAR_ATIME_OFFSET
import archivers.tar.TarConstants.XSTAR_CTIME_OFFSET
import archivers.tar.TarConstants.XSTAR_MAGIC_LEN
import archivers.tar.TarConstants.XSTAR_MAGIC_OFFSET
import archivers.tar.TarConstants.XSTAR_MULTIVOLUME_OFFSET
import archivers.tar.TarConstants.XSTAR_PREFIX_OFFSET
import archivers.zip.ZipEncoding
import kotlinx.datetime.*
import okio.IOException
import okio.Path
import utils.*

class TarArchiveEntry : ArchiveEntry {

    var name: String = ""
        private set

    var linkName: String = ""
        private set

    private var userName: String = ""

    var userId: Long = 0
        private set

    var groupId: Long = 0
        private set

    private var groupName: String = ""

    private var filePath: Path? = null

    private var preserveAbsolutePath: Boolean = false

    /** The entry's permission mode.  */
    var mode = 0
        private set

    var linkFlag: Byte = 0
        private set

    private var symbolLinkTarget: Path? = null

    var size: Long = 0
        private set

    private var realSize: Long = 0

    /** is this entry a GNU sparse entry using one of the PAX formats?  */
    private var paxGNUSparse = false

    /** is this entry a GNU sparse entry using 1.X PAX formats?
     * the sparse headers of 1.x PAX Format is stored in file data block  */
    private var paxGNU1XSparse = false

    /** is this entry a star sparse entry using the PAX header?  */
    private var starSparse = false

    private var mTime: Instant = defaultInstant

    private var cTime: Instant = defaultInstant

    private var aTime: Instant = defaultInstant

    private var birthTime: Instant = defaultInstant

    /** If the header checksum is reasonably correct.  */
    private var checkSumOK = false

    /** The entry's magic tag.  */
    private var magic: String = MAGIC_POSIX

    /** The version of the format  */
    private var version: String = VERSION_POSIX

    /** If an extension sparse header follows.  */
    private var isExtended = false


    var devMinor: Int = 0
        private set

    var devMajor: Int = 0
        private set

    /** The sparse headers in tar  */
    var sparseHeaders: MutableList<TarArchiveStructSparse> = mutableListOf()
        private set

    private val extraPaxHeaders: MutableMap<String, String> = mutableMapOf()

    var dataOffset: Long = EntryStreamOffsets.OFFSET_UNKNOWN
        private set

    constructor(preserveAbsolutePath: Boolean) {
        var user = SystemUtil.getUserName()
        if (user.length > MAX_NAMELEN) {
            user = user.substring(0, MAX_NAMELEN)
        }
        userName = user
        filePath = null
        this.preserveAbsolutePath = preserveAbsolutePath
    }

    constructor(headerBuf: ByteArray) : this(false) {
        parseTarHeader(headerBuf)
    }

    /**
     * Construct an entry from an archive's header bytes. File is set
     * to null.
     *
     * @param headerBuf The header bytes from a tar archive entry.
     * @param encoding encoding to use for file names
     * @since 1.4
     * @throws IllegalArgumentException if any of the numeric fields have an invalid format
     * @throws IOException on error
     */
    @Throws(IOException::class)
    constructor(headerBuf: ByteArray, encoding: ZipEncoding?) : this(headerBuf, encoding, false)

    /**
     * Construct an entry from an archive's header bytes. File is set
     * to null.
     *
     * @param headerBuf The header bytes from a tar archive entry.
     * @param encoding encoding to use for file names
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [.UNKNOWN]. When set to false such illegal fields cause an exception instead.
     * @since 1.19
     * @throws IllegalArgumentException if any of the numeric fields have an invalid format
     * @throws IOException on error
     */
    @Throws(IOException::class)
    constructor(
        headerBuf: ByteArray,
        encoding: ZipEncoding?,
        lenient: Boolean,
    ) : this(emptyMap<String, String>(), headerBuf, encoding, lenient)

    /**
     * Construct an entry from an archive's header bytes for random access tar. File is set to null.
     * @param headerBuf the header bytes from a tar archive entry.
     * @param encoding encoding to use for file names.
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [.UNKNOWN]. When set to false such illegal fields cause an exception instead.
     * @param dataOffset position of the entry data in the random access file.
     * @since 1.21
     * @throws IllegalArgumentException if any of the numeric fields have an invalid format.
     * @throws IOException on error.
     */
    @Throws(IOException::class)
    constructor(
        headerBuf: ByteArray, encoding: ZipEncoding?, lenient: Boolean,
        dataOffset: Long,
    ) : this(headerBuf, encoding, lenient) {
        setDataOffset(dataOffset)
    }

    /**
     * Construct an entry from an archive's header bytes. File is set to null.
     *
     * @param globalPaxHeaders the parsed global PAX headers, or null if this is the first one.
     * @param headerBuf The header bytes from a tar archive entry.
     * @param encoding encoding to use for file names
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [.UNKNOWN]. When set to false such illegal fields cause an exception instead.
     * @since 1.22
     * @throws IllegalArgumentException if any of the numeric fields have an invalid format
     * @throws IOException on error
     */
    @Throws(IOException::class)
    constructor(
        globalPaxHeaders: Map<String, String>, headerBuf: ByteArray,
        encoding: ZipEncoding? = null, lenient: Boolean,
    ) : this(false) {
        parseTarHeader(globalPaxHeaders, headerBuf, encoding, false, lenient)
    }

    /**
     * Construct an entry from an archive's header bytes for random access tar. File is set to null.
     * @param globalPaxHeaders the parsed global PAX headers, or null if this is the first one.
     * @param headerBuf the header bytes from a tar archive entry.
     * @param encoding encoding to use for file names.
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to [.UNKNOWN]. When set to false such illegal fields cause an exception instead.
     * @param dataOffset position of the entry data in the random access file.
     * @since 1.22
     * @throws IllegalArgumentException if any of the numeric fields have an invalid format.
     * @throws IOException on error.
     */
    @Throws(IOException::class)
    constructor(
        globalPaxHeaders: Map<String, String>, headerBuf: ByteArray,
        encoding: ZipEncoding?, lenient: Boolean, dataOffset: Long,
    ) : this(globalPaxHeaders, headerBuf, encoding, lenient) {

        setDataOffset(dataOffset)
    }

    /**
     * Construct an entry for a file. File is set to file, and the
     * header is constructed from information from the file.
     * The name is set from the normalized file path.
     *
     *
     * The entry's name will be the value of the `file`'s
     * path with all file separators replaced by forward slashes and
     * leading slashes as well as Windows drive letters stripped. The
     * name will end in a slash if the `file` represents a
     * directory.
     *
     * @param file The file that the entry represents.
     * @throws IOException if an I/O error occurs
     * @since 1.21
     */
    @Throws(IOException::class)
    constructor(file: Path) : this(file, file.toString())

    /**
     * Construct an entry for a file. File is set to file, and the
     * header is constructed from information from the file.
     *
     *
     * The entry's name will be the value of the `fileName`
     * argument with all file separators replaced by forward slashes
     * and leading slashes as well as Windows drive letters stripped.
     * The name will end in a slash if the `file` represents a
     * directory.
     *
     * @param file     The file that the entry represents.
     * @param fileName the name to be used for the entry.
     * @throws IOException if an I/O error occurs
     * @since 1.21
     */
    @Throws(IOException::class)
    constructor(file: Path, fileName: String) {
        val normalizedName: String =
            normalizeFileName(fileName, false)
        filePath = file
        readFileMode(file, normalizedName)
        readFileMetadata(file)
        preserveAbsolutePath = false
    }

    /**
     * Construct an entry with only a name. This allows the programmer
     * to construct the entry's header "by hand". File is set to null.
     *
     *
     * The entry's name will be the value of the `name`
     * argument with all file separators replaced by forward slashes
     * and leading slashes as well as Windows drive letters stripped.
     *
     * @param name the entry name
     */
    constructor(name: String) : this(name, false)


    /**
     * Construct an entry with a name and a link flag.
     *
     *
     * The entry's name will be the value of the `name`
     * argument with all file separators replaced by forward slashes
     * and leading slashes as well as Windows drive letters
     * stripped.
     *
     * @param name the entry name
     * @param linkFlag the entry link flag.
     */
    constructor(name: String, linkFlag: Byte) : this(name, linkFlag, false)


    /**
     * Construct an entry with only a name. This allows the programmer
     * to construct the entry's header "by hand". File is set to null.
     *
     *
     * The entry's name will be the value of the `name`
     * argument with all file separators replaced by forward slashes.
     * Leading slashes and Windows drive letters are stripped if
     * `preserveAbsolutePath` is `false`.
     *
     * @param name the entry name
     * @param preserveAbsolutePath whether to allow leading slashes
     * or drive letters in the name.
     *
     * @since 1.1
     */
    constructor(name: String, preserveAbsolutePath: Boolean) : this(preserveAbsolutePath) {
        val name = normalizeFileName(name, preserveAbsolutePath)
        val isDir = name.endsWith("/")
        this.name = name
        mode =
            if (isDir) DEFAULT_DIR_MODE else DEFAULT_FILE_MODE
        linkFlag = if (isDir) LF_DIR else LF_NORMAL
        mTime = Clock.System.now() // java.nio.file.attribute.FileTime.from(java.time.Instant.now())
        userName = ""
    }

    /**
     * Construct an entry with a name and a link flag.
     *
     *
     * The entry's name will be the value of the `name`
     * argument with all file separators replaced by forward slashes.
     * Leading slashes and Windows drive letters are stripped if
     * `preserveAbsolutePath` is `false`.
     *
     * @param name the entry name
     * @param linkFlag the entry link flag.
     * @param preserveAbsolutePath whether to allow leading slashes
     * or drive letters in the name.
     *
     * @since 1.5
     */
    constructor(name: String, linkFlag: Byte, preserveAbsolutePath: Boolean) : this(name, preserveAbsolutePath) {
        this.linkFlag = linkFlag
        if (linkFlag == LF_GNUTYPE_LONGNAME) {
            magic = MAGIC_GNU
            version = VERSION_GNU_SPACE
        }
    }


    /**
     * add a PAX header to this entry. If the header corresponds to an existing field in the entry,
     * that field will be set; otherwise the header will be added to the extraPaxHeaders Map
     * @param name  The full name of the header to set.
     * @param value value of header.
     * @since 1.15
     */
    fun addPaxHeader(name: String, value: String) {
        try {
            processPaxHeader(name, value)
        } catch (ex: IOException) {
            throw IllegalArgumentException("Invalid input", ex)
        }
    }

    /**
     * clear all extra PAX headers.
     * @since 1.15
     */
    fun clearExtraPaxHeaders() {
        extraPaxHeaders.clear()
    }

    /**
     * Evaluate an entry's header format from a header buffer.
     *
     * @param header The tar entry header buffer to evaluate the format for.
     * @return format type
     */
    private fun evaluateType(globalPaxHeaders: Map<String, String>, header: ByteArray): Int {
        if (ArchiveUtils.matchAsciiBuffer(MAGIC_GNU, header, MAGIC_OFFSET, MAGICLEN)) {
            return FORMAT_OLDGNU
        }
        return if (ArchiveUtils.matchAsciiBuffer(MAGIC_POSIX, header, MAGIC_OFFSET, MAGICLEN)) {
            if (isXstar(globalPaxHeaders, header)) {
                FORMAT_XSTAR
            } else FORMAT_POSIX
        } else 0
    }

    private fun fill(value: Byte, offset: Int, outbuf: ByteArray, length: Int): Int {
        for (i in 0 until length) {
            outbuf[offset + i] = value
        }
        return offset + length
    }

    private fun fill(value: Int, offset: Int, outbuf: ByteArray, length: Int): Int {
        return fill(value.toByte(), offset, outbuf, length)
    }

    private fun parseOctalOrBinary(header: ByteArray, offset: Int, length: Int, lenient: Boolean): Long {
        return if (lenient) {
            try {
                TarUtil.parseOctalOrBinary(header, offset, length)
            } catch (ex: IllegalArgumentException) { //NOSONAR
                UNKNOWN
            }
        } else TarUtil.parseOctalOrBinary(header, offset, length)
    }

    /**
     * Parse an entry's header information from a header buffer.
     *
     * @param header The tar entry header buffer to get information from.
     * @throws IllegalArgumentException if any of the numeric fields have an invalid format
     */
    fun parseTarHeader(header: ByteArray) {
        try {
            parseTarHeader(header, null)
        } catch (ex: IOException) { // NOSONAR
            try {
                parseTarHeader(header, null, oldStyle = true, lenient = false)
            } catch (ex2: IOException) {
                // not really possible
                throw ex2 //NOSONAR
            }
        }
    }

    /**
     * Parse an entry's header information from a header buffer.
     *
     * @param header The tar entry header buffer to get information from.
     * @param encoding encoding to use for file names
     * @since 1.4
     * @throws IllegalArgumentException if any of the numeric fields
     * have an invalid format
     * @throws IOException on error
     */
    @Throws(IOException::class)
    fun parseTarHeader(header: ByteArray, encoding: ZipEncoding?) {
        parseTarHeader(header, encoding, oldStyle = false, lenient = false)
    }

    @Throws(IOException::class)
    private fun parseTarHeader(
        header: ByteArray, encoding: ZipEncoding?,
        oldStyle: Boolean, lenient: Boolean,
    ) {
        parseTarHeader(emptyMap(), header, encoding, oldStyle, lenient)
    }

    @Throws(IOException::class)
    private fun parseTarHeader(
        globalPaxHeaders: Map<String, String>, header: ByteArray,
        encoding: ZipEncoding?, oldStyle: Boolean, lenient: Boolean,
    ) {
        try {
            parseTarHeaderUnwrapped(globalPaxHeaders, header, encoding, oldStyle, lenient)
        } catch (ex: IllegalArgumentException) {
            throw IOException("Corrupted TAR archive.", ex)
        }
    }

    @Throws(IOException::class)
    private fun parseTarHeaderUnwrapped(
        globalPaxHeaders: Map<String, String>, header: ByteArray,
        encoding: ZipEncoding?, oldStyle: Boolean, lenient: Boolean,
    ) {
        var offset = 0
        val newName = if (oldStyle) TarUtil.parseName(header, offset, NAMELEN) else TarUtil.parseName(
            header,
            offset,
            NAMELEN,
            encoding
        )
        setName(newName)
        offset += NAMELEN
        mode = parseOctalOrBinary(header, offset, MODELEN, lenient).toInt()
        offset += MODELEN
        userId = parseOctalOrBinary(header, offset, UIDLEN, lenient).toInt().toLong()
        offset += UIDLEN
        groupId = parseOctalOrBinary(header, offset, GIDLEN, lenient).toInt().toLong()
        offset += GIDLEN
        size = TarUtil.parseOctalOrBinary(header, offset, SIZELEN)
        if (size < 0) {
            throw IOException("broken archive, entry with negative size")
        }
        offset += SIZELEN
        mTime = Instant.fromEpochSeconds(parseOctalOrBinary(header, offset, MODTIMELEN, lenient))
        offset += MODTIMELEN
        checkSumOK = TarUtil.verifyCheckSum(header)
        offset += CHKSUMLEN
        linkFlag = header[offset++]
        linkName = if (oldStyle) TarUtil.parseName(header, offset, NAMELEN) else TarUtil.parseName(
            header,
            offset,
            NAMELEN,
            encoding
        )
        offset += NAMELEN
        magic = TarUtil.parseName(header, offset, MAGICLEN)
        offset += MAGICLEN
        version = TarUtil.parseName(header, offset, VERSIONLEN)
        offset += VERSIONLEN
        userName = if (oldStyle) TarUtil.parseName(header, offset, UNAMELEN) else TarUtil.parseName(
            header,
            offset,
            UNAMELEN,
            encoding
        )
        offset += UNAMELEN
        groupName = if (oldStyle) TarUtil.parseName(header, offset, GNAMELEN) else TarUtil.parseName(
            header,
            offset,
            GNAMELEN,
            encoding
        )
        offset += GNAMELEN
        if (linkFlag == LF_CHR || linkFlag == LF_BLK) {
            devMajor = parseOctalOrBinary(header, offset, DEVLEN, lenient).toInt()
            offset += DEVLEN
            devMinor = parseOctalOrBinary(header, offset, DEVLEN, lenient).toInt()
            offset += DEVLEN
        } else {
            offset += 2 * DEVLEN
        }
        val type = evaluateType(globalPaxHeaders, header)
        when (type) {
            FORMAT_OLDGNU -> {
                aTime = fileTimeFromOptionalSeconds(
                    parseOctalOrBinary(header, offset, ATIMELEN_GNU, lenient)
                )
                offset += ATIMELEN_GNU
                cTime = fileTimeFromOptionalSeconds(
                    parseOctalOrBinary(header, offset, CTIMELEN_GNU, lenient)
                )
                offset += CTIMELEN_GNU
                offset += OFFSETLEN_GNU
                offset += LONGNAMESLEN_GNU
                offset += PAD2LEN_GNU
                sparseHeaders =
                    TarUtil.readSparseStructs(header, offset, SPARSE_HEADERS_IN_OLDGNU_HEADER).toMutableList()
                offset += SPARSELEN_GNU
                isExtended = TarUtil.parseBoolean(header, offset)
                offset += ISEXTENDEDLEN_GNU
                realSize = TarUtil.parseOctal(header, offset, REALSIZELEN_GNU)
                offset += REALSIZELEN_GNU // NOSONAR - assignment as documentation
            }

            FORMAT_XSTAR -> {
                val xstarPrefix: String =
                    if (oldStyle) TarUtil.parseName(header, offset, PREFIXLEN_XSTAR) else TarUtil.parseName(
                        header,
                        offset,
                        PREFIXLEN_XSTAR,
                        encoding
                    )
                offset += PREFIXLEN_XSTAR
                if (xstarPrefix.isNotEmpty()) {
                    name = "$xstarPrefix/$name"
                }
                val aTimeStamp = parseOctalOrBinary(header, offset, ATIMELEN_XSTAR, lenient)
                aTime = fileTimeFromOptionalSeconds(
                    aTimeStamp
                )
                offset += ATIMELEN_XSTAR
                val cTimeStamp = parseOctalOrBinary(header, offset, CTIMELEN_XSTAR, lenient)
                cTime = fileTimeFromOptionalSeconds(
                    cTimeStamp
                )
                offset += CTIMELEN_XSTAR // NOSONAR - assignment as documentation
            }

            FORMAT_POSIX -> {
                val prefix: String =
                    if (oldStyle) TarUtil.parseName(header, offset, PREFIXLEN) else TarUtil.parseName(
                        header,
                        offset,
                        PREFIXLEN,
                        encoding
                    )
                offset += PREFIXLEN // NOSONAR - assignment as documentation
                // SunOS tar -E does not add / to directory names, so fix
                // up to be consistent
                if (isDirectory() && !name.endsWith("/")) {
                    setName("$name/")
                }
                if (prefix.isNotEmpty()) {
                    setName("$prefix/$name")
                }
            }

            else -> {
                val prefix: String =
                    if (oldStyle) TarUtil.parseName(header, offset, PREFIXLEN) else TarUtil.parseName(
                        header,
                        offset,
                        PREFIXLEN,
                        encoding
                    )
                offset += PREFIXLEN
                if (isDirectory() && !name.endsWith("/")) {
                    setName("$name/")
                }
                if (prefix.isNotEmpty()) {
                    setName("$prefix/$name")
                }
            }
        }
    }


    /**
     * process one pax header, using the entries extraPaxHeaders map as source for extra headers
     * used when handling entries for sparse files.
     * @param key
     * @param value
     * @since 1.15
     */
    @Throws(IOException::class)
    private fun processPaxHeader(key: String, value: String) {
        processPaxHeader(key, value, extraPaxHeaders)
    }

    /**
     * Process one pax header, using the supplied map as source for extra headers to be used when handling
     * entries for sparse files
     *
     * @param key  the header name.
     * @param `val`  the header value.
     * @param headers  map of headers used for dealing with sparse file.
     * @throws NumberFormatException  if encountered errors when parsing the numbers
     * @since 1.15
     */
    @Throws(IOException::class)
    private fun processPaxHeader(key: String, value: String, headers: Map<String, String>) {
        /*
     * The following headers are defined for Pax.
     * charset: cannot use these without changing TarArchiveEntry fields
     * mtime
     * atime
     * ctime
     * LIBARCHIVE.creationtime
     * comment
     * gid, gname
     * linkpath
     * size
     * uid,uname
     * SCHILY.devminor, SCHILY.devmajor: don't have setters/getters for those
     *
     * GNU sparse files use additional members, we use
     * GNU.sparse.size to detect the 0.0 and 0.1 versions and
     * GNU.sparse.realsize for 1.0.
     *
     * star files use additional members of which we use
     * SCHILY.filetype in order to detect star sparse files.
     *
     * If called from addExtraPaxHeader, these additional headers must be already present .
     */
        when (key) {
            "path" -> setName(value)
            "linkpath" -> setLinkName(value)
            "gid" -> setGroupId(value.toLong())
            "gname" -> setGroupName(value)
            "uid" -> setUserId(value.toLong())
            "uname" -> setUserName(value)
            "size" -> {
                val size = value.toLong()
                if (size < 0) {
                    throw IOException("Corrupted TAR archive. Entry size is negative")
                }
                setSize(size)
            }

            "mtime" -> setLastModifiedTime(
                Instant.fromEpochSeconds(value.toLong())
            )

            "atime" -> setLastAccessTime(
                Instant.fromEpochSeconds(value.toLong())
            )

            "ctime" -> setStatusChangeTime(
                Instant.fromEpochSeconds(value.toLong())
            )

            "LIBARCHIVE.creationtime" -> setCreationTime(
                Instant.fromEpochSeconds(value.toLong())
            )

            "SCHILY.devminor" -> {
                val devMinor = value.toInt()
                if (devMinor < 0) {
                    throw IOException("Corrupted TAR archive. Dev-Minor is negative")
                }
                setDevMinor(devMinor)
            }

            "SCHILY.devmajor" -> {
                val devMajor = value.toInt()
                if (devMajor < 0) {
                    throw IOException("Corrupted TAR archive. Dev-Major is negative")
                }
                setDevMajor(devMajor)
            }

            TarGnuSparseKeys.SIZE -> fillGNUSparse0xData(headers)
            TarGnuSparseKeys.REALSIZE -> fillGNUSparse1xData(headers)
            "SCHILY.filetype" -> if ("sparse" == value) {
                fillStarSparseData(headers)
            }

            else -> extraPaxHeaders[key] = value
        }
    }

    private fun fillGNUSparse0xData(headers: Map<String, String>) {
        paxGNUSparse = true
        realSize = headers[TarGnuSparseKeys.SIZE]!!.toLong()

        headers[TarGnuSparseKeys.NAME]?.let {
            setName(it)
        }
    }

    @Throws(IOException::class)
    fun fillGNUSparse1xData(headers: Map<String, String>) {
        paxGNUSparse = true
        paxGNU1XSparse = true


        headers[TarGnuSparseKeys.NAME]?.let {
            setName(it)
        }

        headers[TarGnuSparseKeys.REALSIZE]?.let {
            try {
                realSize = it.toLong()
            } catch (ex: NumberFormatException) {
                throw IOException(
                    "Corrupted TAR archive. GNU.sparse.realsize header for "
                            + name + " contains non-numeric value"
                )
            }
        }
    }

    @Throws(IOException::class)
    fun fillStarSparseData(headers: Map<String, String>) {
        starSparse = true
        headers["SCHILY.realsize"]?.let {
            try {
                realSize = it.toLong()
            } catch (ex: NumberFormatException) {
                throw IOException(
                    ("Corrupted TAR archive. SCHILY.realsize header for "
                            + name + " contains non-numeric value")
                )
            }
        }
    }

    fun setName(name: String) {
        this.name = normalizeFileName(
            name,
            preserveAbsolutePath
        )
    }

    fun setLinkName(linkName: String) {
        this.linkName = linkName
    }

    fun setMode(mode: Int) {
        this.mode = mode
    }

    fun setUserId(userId: Long) {
        this.userId = userId
    }

    fun setUserName(userName: String) {
        this.userName = userName
    }

    fun setGroupName(groupName: String) {
        this.groupName = groupName
    }

    fun setGroupId(groupId: Long) {
        this.groupId = groupId
    }

    fun setSize(size: Long) {
        this.size = size
    }

    private fun setSymbolLinkTarget(symbolLinkTarget: Path) {
        this.symbolLinkTarget = symbolLinkTarget
    }

    /**
     * Set this entry's last access time.
     *
     * @param time This entry's new last access time.
     * @since 1.22
     */
    private fun setLastAccessTime(time: Instant) {
        aTime = time
    }

    /**
     * Set this entry's modification time.
     *
     * @param time This entry's new modification time.
     * @since 1.22
     */
    fun setLastModifiedTime(time: Instant) {
        mTime = time
    }

    /**
     * Set this entry's modification time.
     *
     * @param time This entry's new modification time.
     * @see TarArchiveEntry.setLastModifiedTime
     */
    fun setModTime(time: LocalDateTime) {
        setLastModifiedTime(time.toInstant(TimeZone.UTC))
    }

    /**
     * Set this entry's modification time.
     *
     * @param time This entry's new modification time.
     * @since 1.21
     * @see TarArchiveEntry.setLastModifiedTime
     */
    fun setModTime(time: Instant) {
        setLastModifiedTime(time)
    }


    /**
     * Set this entry's modification time. The parameter passed
     * to this method is in "Java time".
     *
     * @param time This entry's new modification time.
     * @see TarArchiveEntry.setLastModifiedTime
     */
    fun setModTime(time: Long) {
        setLastModifiedTime(Instant.fromEpochMilliseconds(time))
    }

    /**
     * Set this entry's status change time.
     *
     * @param time This entry's new status change time.
     * @since 1.22
     */
    private fun setStatusChangeTime(time: Instant) {
        cTime = time
    }

    /**
     * Set this entry's creation time.
     *
     * @param time This entry's new creation time.
     * @since 1.22
     */
    private fun setCreationTime(time: Instant) {
        birthTime = time
    }

    /**
     * Set the offset of the data for the tar entry.
     * @param dataOffset the position of the data in the tar.
     * @since 1.21
     */
    fun setDataOffset(dataOffset: Long) {
        if (dataOffset < 0) {
            throw IllegalArgumentException("The offset can not be smaller than 0")
        }
        this.dataOffset = dataOffset
    }


    /**
     * Set this entry's major device number.
     *
     * @param devNo This entry's major device number.
     * @throws IllegalArgumentException if the devNo is &lt; 0.
     * @since 1.4
     */
    private fun setDevMajor(devNo: Int) {
        if (devNo < 0) {
            throw IllegalArgumentException(
                "Major device number is out of "
                        + "range: " + devNo
            )
        }
        devMajor = devNo
    }

    /**
     * Set this entry's minor device number.
     *
     * @param devNo This entry's minor device number.
     * @throws IllegalArgumentException if the devNo is &lt; 0.
     * @since 1.4
     */
    private fun setDevMinor(devNo: Int) {
        if (devNo < 0) {
            throw IllegalArgumentException(
                ("Minor device number is out of "
                        + "range: " + devNo)
            )
        }
        devMinor = devNo
    }

    fun setSparseHeaders(sparseHeaders: List<TarArchiveStructSparse>) {
        this.sparseHeaders = sparseHeaders.toMutableList()
    }


    /**
     * Update the entry using a map of pax headers.
     * @param headers
     * @since 1.15
     */
    @Throws(IOException::class)
    fun updateEntryFromPaxHeaders(headers: Map<String, String>) {
        for ((key, value) in headers) {
            processPaxHeader(key, value, headers)
        }
    }

    /**
     * Write an entry's header information to a header buffer.
     *
     *
     * This method does not use the star/GNU tar/BSD tar extensions.
     *
     * @param outbuf The tar entry header buffer to fill in.
     */
    fun writeEntryHeader(outbuf: ByteArray) {
        try {
            writeEntryHeader(outbuf, null, false)
        } catch (ex: IOException) { // NOSONAR
            try {
                writeEntryHeader(outbuf, TarUtil.fallbackEncoding, false)
            } catch (ex2: IOException) {
                // impossible
                throw ex2 //NOSONAR
            }
        }
    }

    /**
     * Write an entry's header information to a header buffer.
     *
     * @param outbuf The tar entry header buffer to fill in.
     * @param encoding encoding to use when writing the file name.
     * @param starMode whether to use the star/GNU tar/BSD tar
     * extension for numeric fields if their value doesn't fit in the
     * maximum size of standard tar archives
     * @since 1.4
     * @throws IOException on error
     */
    @Throws(IOException::class)
    fun writeEntryHeader(
        outbuf: ByteArray, encoding: ZipEncoding?,
        starMode: Boolean,
    ) {
        var offset = 0
        offset = TarUtil.formatNameBytes(
            name, outbuf, offset, NAMELEN,
            encoding
        )
        offset = writeEntryHeaderField(mode.toLong(), outbuf, offset, MODELEN, starMode)
        offset = writeEntryHeaderField(
            userId, outbuf, offset, UIDLEN,
            starMode
        )
        offset = writeEntryHeaderField(
            groupId, outbuf, offset, GIDLEN,
            starMode
        )
        offset = writeEntryHeaderField(size, outbuf, offset, SIZELEN, starMode)
        offset = writeEntryHeaderField(
            mTime.epochSeconds, outbuf, offset,
            MODTIMELEN, starMode
        )
        val csOffset = offset
        offset = fill(' '.code.toByte(), offset, outbuf, CHKSUMLEN)
        outbuf[offset++] = linkFlag
        offset = TarUtil.formatNameBytes(
            linkName, outbuf, offset, NAMELEN,
            encoding
        )
        offset = TarUtil.formatNameBytes(magic, outbuf, offset, MAGICLEN)
        offset = TarUtil.formatNameBytes(version, outbuf, offset, VERSIONLEN)
        offset = TarUtil.formatNameBytes(
            userName, outbuf, offset, UNAMELEN,
            encoding
        )
        offset = TarUtil.formatNameBytes(
            groupName, outbuf, offset, GNAMELEN,
            encoding
        )
        offset = writeEntryHeaderField(
            devMajor.toLong(), outbuf, offset, DEVLEN,
            starMode
        )
        offset = writeEntryHeaderField(
            devMinor.toLong(), outbuf, offset, DEVLEN,
            starMode
        )
        if (starMode) {
            // skip prefix
            offset = fill(0, offset, outbuf, PREFIXLEN_XSTAR)
            offset = writeEntryHeaderOptionalTimeField(aTime, offset, outbuf, ATIMELEN_XSTAR)
            offset = writeEntryHeaderOptionalTimeField(cTime, offset, outbuf, CTIMELEN_XSTAR)
            // 8-byte fill
            offset = fill(0, offset, outbuf, 8)
            // Do not write MAGIC_XSTAR because it causes issues with some TAR tools
            // This makes it effectively XUSTAR, which guarantees compatibility with USTAR
            offset = fill(0, offset, outbuf, XSTAR_MAGIC_LEN)
        }
        offset = fill(0, offset, outbuf, outbuf.size - offset) // NOSONAR - assignment as documentation
        val chk: Long = TarUtil.computeCheckSum(outbuf)
        TarUtil.formatCheckSumOctalBytes(chk, outbuf, csOffset, CHKSUMLEN)
    }

    private fun writeEntryHeaderField(
        value: Long, outbuf: ByteArray, offset: Int,
        length: Int, starMode: Boolean,
    ): Int {
        return if (!starMode && (value < 0
                    || value >= 1L shl 3 * (length - 1))
        ) {
            // value doesn't fit into field when written as octal
            // number, will be written to PAX header or causes an
            // error
            TarUtil.formatLongOctalBytes(0, outbuf, offset, length)
        } else TarUtil.formatLongOctalOrBinaryBytes(
            value, outbuf, offset,
            length
        )
    }

    private fun writeEntryHeaderOptionalTimeField(
        time: Instant?,
        offset: Int,
        outbuf: ByteArray,
        fieldLength: Int,
    ): Int {
        var offset = offset
        offset = if (time != null) {
            writeEntryHeaderField(time.epochSeconds, outbuf, offset, fieldLength, true)
        } else {
            fill(0, offset, outbuf, fieldLength)
        }
        return offset
    }

    /**
     * Get this entry's real file size in case of a sparse file.
     *
     *
     * This is the size a file would take on disk if the entry was expanded.
     *
     *
     * If the file is not a sparse file, return size instead of realSize.
     *
     * @return This entry's real file size, if the file is not a sparse file, return size instead of realSize.
     */
    fun getRealSize(): Long {
        return if (!isSparse()) {
            size
        } else realSize
    }

    fun getCreationTime() = birthTime

    fun getLastAccessTime() = aTime

    fun getStatusChangeTime() = cTime

    fun getLastModifiedTime() = mTime

    /**
     * get extra PAX Headers
     * @return read-only map containing any extra PAX Headers
     * @since 1.15
     */
    fun getExtraPaxHeaders(): Map<String, String> {
        return extraPaxHeaders.toMap()
    }

    /**
     * If this entry represents a file, and the file is a directory, return
     * an array of TarEntries for this entry's children.
     *
     *
     * This method is only useful for entries created from a `File` or `Path` but not for entries read from an archive.
     *
     * @return An array of TarEntry's for this entry's children.
     */
    fun getDirectoryEntries(): List<TarArchiveEntry> {
        val file = filePath
        if (file == null || !isDirectory()) {
            return EMPTY_TAR_ARCHIVE_ENTRY_List
        }
        val entries: MutableList<TarArchiveEntry> =
            mutableListOf()
        try {
            FileUtil.fileSystem.list(file).forEach { subPath ->
                entries.add(TarArchiveEntry(subPath))
            }
        } catch (e: IOException) {
            return EMPTY_TAR_ARCHIVE_ENTRY_List
        }
        return entries
    }

    /**
     * Get this entry's sparse headers ordered by offset with all empty sparse sections at the start filtered out.
     *
     * @return immutable list of this entry's sparse headers, never null
     * @since 1.21
     * @throws IOException if the list of sparse headers contains blocks that overlap
     */
    @Throws(IOException::class)
    fun getOrderedSparseHeaders(): List<TarArchiveStructSparse> {
        if (sparseHeaders.isEmpty()) {
            return emptyList()
        }

        val orderedAndFiltered: List<TarArchiveStructSparse> = sparseHeaders.filter {
            it.offset > 0 || it.numbytes > 0
        }.sortedBy { it.offset }

        val numberOfHeaders = orderedAndFiltered.size
        for (i in 0 until numberOfHeaders) {
            val str = orderedAndFiltered[i]
            if (i + 1 < numberOfHeaders
                && str.offset + str.numbytes > orderedAndFiltered[i + 1].offset
            ) {
                throw IOException(
                    "Corrupted TAR archive. Sparse blocks for "
                            + name + " overlap each other."
                )
            }
            if (str.offset + str.numbytes < 0) {
                // integer overflow?
                throw IOException(
                    ("Unreadable TAR archive. Offset and numbytes for sparse block in "
                            + name + " too large.")
                )
            }
        }
        if (!orderedAndFiltered.isEmpty()) {
            val last = orderedAndFiltered[numberOfHeaders - 1]
            if (last.offset + last.numbytes > realSize) {
                throw IOException("Corrupted TAR archive. Sparse block extends beyond real size of the entry")
            }
        }
        return orderedAndFiltered
    }

    private fun readFileMode(file: Path, normalizedName: String) {
        val fileMetadata = FileUtil.fileSystem.metadata(file)
        if (fileMetadata.isDirectory) {
            mode = DEFAULT_DIR_MODE
            linkFlag = LF_DIR

            val nameLength = normalizedName.length
            setName(
                if (nameLength == 0 || normalizedName[nameLength - 1] != '/') {
                    "$normalizedName/"
                } else {
                    normalizedName
                }
            )
        } else {
            mode = DEFAULT_FILE_MODE
            linkFlag = LF_NORMAL
            setName(normalizedName)
            size = FileUtil.getFileSizeByPath(file)
        }
    }

    private fun readFileMetadata(file: Path) {
        val fileMetadata = FileUtil.fileSystem.metadata(file)
        fileMetadata.run {
            createdAtMillis?.let {
                setCreationTime(Instant.fromEpochMilliseconds(it))
            }
            lastAccessedAtMillis?.let {
                setLastAccessTime(Instant.fromEpochMilliseconds(it))
            }
            lastModifiedAtMillis?.let {
                setLastModifiedTime(Instant.fromEpochMilliseconds(it))
            }
            symlinkTarget?.let {
                setSymbolLinkTarget(it)
            }
        }
    }

    override fun isDirectory(): Boolean {
        filePath?.let {
            val metadata = FileUtil.fileSystem.metadata(it)
            return metadata.isDirectory
        }

        return if (linkFlag == LF_DIR) {
            true
        } else !isPaxHeader() && !isGlobalPaxHeader() && name.endsWith("/")

    }

    /**
     * Check if this is a link entry.
     *
     * @since 1.2
     * @return whether this is a link entry
     */
    fun isLink(): Boolean {
        return linkFlag == LF_LINK
    }

    /**
     * Indicates in case of an oldgnu sparse file if an extension
     * sparse header follows.
     *
     * @return true if an extension oldgnu sparse header follows.
     */
    fun isExtended(): Boolean {
        return isExtended
    }

    /**
     * Check if this is a FIFO (pipe) entry.
     *
     * @since 1.2
     * @return whether this is a FIFO entry
     */
    fun isFIFO(): Boolean {
        return linkFlag == LF_FIFO
    }

    /**
     * Check if this is a "normal file"
     *
     * @since 1.2
     * @return whether this is a "normal file"
     */
    fun isFile(): Boolean {
        val file = filePath
        if (file != null) {
            val metadata = FileUtil.fileSystem.metadata(file)
            return metadata.isRegularFile
        }
        return if (linkFlag == LF_OLDNORM || linkFlag == LF_NORMAL) {
            true
        } else !name.endsWith("/")
    }

    /**
     * Check if this is a Pax header.
     *
     * @return `true` if this is a Pax header.
     *
     * @since 1.1
     */
    fun isGlobalPaxHeader(): Boolean {
        return linkFlag == LF_PAX_GLOBAL_EXTENDED_HEADER
    }

    /**
     * Indicate if this entry is a GNU long linkname block
     *
     * @return true if this is a long name extension provided by GNU tar
     */
    fun isGNULongLinkEntry(): Boolean {
        return linkFlag == LF_GNUTYPE_LONGLINK
    }

    /**
     * Indicate if this entry is a GNU long name block
     *
     * @return true if this is a long name extension provided by GNU tar
     */
    fun isGNULongNameEntry(): Boolean {
        return linkFlag == LF_GNUTYPE_LONGNAME
    }

    /**
     * Indicate if this entry is a GNU sparse block.
     *
     * @return true if this is a sparse extension provided by GNU tar
     */
    fun isGNUSparse(): Boolean {
        return isOldGNUSparse() || isPaxGNUSparse()
    }

    /**
     * Check if this is a Pax header.
     *
     * @return `true` if this is a Pax header.
     *
     * @since 1.1
     */
    fun isPaxHeader(): Boolean {
        return (linkFlag == LF_PAX_EXTENDED_HEADER_LC
                || linkFlag == LF_PAX_EXTENDED_HEADER_UC)
    }

    /**
     * Check whether this is a sparse entry.
     *
     * @return whether this is a sparse entry
     * @since 1.11
     */
    fun isSparse(): Boolean {
        return isGNUSparse() || isStarSparse()
    }

    /**
     * Indicate if this entry is a star sparse block using PAX headers.
     *
     * @return true if this is a sparse extension provided by star
     * @since 1.11
     */
    fun isStarSparse(): Boolean {
        return starSparse
    }

    /**
     * {@inheritDoc}
     * @since 1.21
     */
    fun isStreamContiguous(): Boolean {
        return true
    }

    /**
     * Check if this is a symbolic link entry.
     *
     * @since 1.2
     * @return whether this is a symbolic link
     */
    fun isSymbolicLink(): Boolean {
        return linkFlag == LF_SYMLINK
    }


    /**
     * Indicate if this entry is a GNU or star sparse block using the
     * oldgnu format.
     *
     * @return true if this is a sparse extension provided by GNU tar or star
     * @since 1.11
     */
    fun isOldGNUSparse(): Boolean {
        return linkFlag == LF_GNUTYPE_SPARSE
    }

    /**
     * Get if this entry is a sparse file with 1.X PAX Format or not
     *
     * @return True if this entry is a sparse file with 1.X PAX Format
     * @since 1.20
     */
    fun isPaxGNU1XSparse(): Boolean {
        return paxGNU1XSparse
    }

    /**
     * Indicate if this entry is a GNU sparse block using one of the
     * PAX formats.
     *
     * @return true if this is a sparse extension provided by GNU tar
     * @since 1.11
     */
    fun isPaxGNUSparse(): Boolean {
        return paxGNUSparse
    }

    /**
     * Check for XSTAR / XUSTAR format.
     *
     * Use the same logic found in star version 1.6 in `header.c`, function `isxmagic(TCB *ptb)`.
     */
    private fun isXstar(globalPaxHeaders: Map<String, String>, header: ByteArray): Boolean {
        // Check if this is XSTAR
        if (ArchiveUtils.matchAsciiBuffer(MAGIC_XSTAR, header, XSTAR_MAGIC_OFFSET, XSTAR_MAGIC_LEN)) {
            return true
        }

        /*
        If SCHILY.archtype is present in the global PAX header, we can use it to identify the type of archive.

        Possible values for XSTAR:
        - xustar: 'xstar' format without "tar" signature at header offset 508.
        - exustar: 'xustar' format variant that always includes x-headers and g-headers.
         */
        val archType = globalPaxHeaders["SCHILY.archtype"]
        if (archType != null) {
            return "xustar" == archType || "exustar" == archType
        }

        // Check if this is XUSTAR
        if (isInvalidPrefix(header)) {
            return false
        }
        if (isInvalidXtarTime(header, XSTAR_ATIME_OFFSET, ATIMELEN_XSTAR)) {
            return false
        }
        return !isInvalidXtarTime(header, XSTAR_CTIME_OFFSET, CTIMELEN_XSTAR)
    }

    private fun isInvalidPrefix(header: ByteArray): Boolean {
        // prefix[130] is is guaranteed to be '\0' with XSTAR/XUSTAR
        if (header[XSTAR_PREFIX_OFFSET + 130].toInt() != 0) {
            // except when typeflag is 'M'
            if (header[LF_OFFSET] != LF_MULTIVOLUME) {
                return true
            }
            // We come only here if we try to read in a GNU/xstar/xustar multivolume archive starting past volume #0
            // As of 1.22, commons-compress does not support multivolume tar archives.
            // If/when it does, this should work as intended.
            if (header[XSTAR_MULTIVOLUME_OFFSET].toInt() and 0x80 == 0
                && header[XSTAR_MULTIVOLUME_OFFSET + 11] != ' '.code.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun isInvalidXtarTime(buffer: ByteArray, offset: Int, length: Int): Boolean {
        // If atime[0]...atime[10] or ctime[0]...ctime[10] is not a POSIX octal number it cannot be 'xstar'.
        if (buffer[offset].toInt() and 0x80 == 0) {
            val lastIndex = length - 1
            for (i in 0 until lastIndex) {
                val b = buffer[offset + i]
                if (b < '0'.code.toByte() || b > '7'.code.toByte()) {
                    return true
                }
            }
            // Check for both POSIX compliant end of number characters if not using base 256
            val b = buffer[offset + lastIndex]
            if (b != ' '.code.toByte() && b.toInt() != 0) {
                return true
            }
        }
        return false
    }

    override fun toString(): String {
        return "TarArchiveEntry: name $name size $size realSize ${getRealSize()} aTime: $aTime cTime: $cTime mTime: $mTime userName: $userName"
    }

    companion object {
        private val EMPTY_TAR_ARCHIVE_ENTRY_List: List<TarArchiveEntry> = emptyList()

        /** Maximum length of a user's name in the tar file  */
        private const val MAX_NAMELEN = 31

        /**
         * Value used to indicate unknown mode, user/groupids, device numbers and modTime when parsing a file in lenient
         * mode and the archive contains illegal fields.
         * @since 1.19
         */
        private const val UNKNOWN = -1L


        /** Default permissions bits for directories  */
        private const val DEFAULT_DIR_MODE = 16877

        /** Default permissions bits for files  */
        private const val DEFAULT_FILE_MODE = 33188

        /**
         * Convert millis to seconds
         */
        @Deprecated("Unused.")
        val MILLIS_PER_SECOND = 1000

        val defaultInstant = Instant.fromEpochSeconds(0)

        private fun fileTimeFromOptionalSeconds(seconds: Long): Instant {
            return if (seconds <= 0) {
                defaultInstant
            } else Instant.fromEpochSeconds(seconds)
        }

        /**
         * Strips Windows' drive letter as well as any leading slashes, turns path separators into forward slashes.
         */
        private fun normalizeFileName(fileName: String, preserveAbsolutePath: Boolean): String {
            var fileName = fileName
            if (!preserveAbsolutePath) {
                val property: String = SystemUtil.getUserName()
                val osName = property.lowercase()

                // Strip off drive letters!
                // REVIEW Would a better check be "(File.separator == '\')"?
                if (osName.startsWith("windows")) {
                    if (fileName.length > 2) {
                        val ch1 = fileName[0]
                        val ch2 = fileName[1]
                        if (ch2 == ':' && (ch1 in 'a'..'z' || ch1 in 'A'..'Z')) {
                            fileName = fileName.substring(2)
                        }
                    }
                } else if (osName.contains("netware")) {
                    val colon = fileName.indexOf(':')
                    if (colon != -1) {
                        fileName = fileName.substring(colon + 1)
                    }
                }
            }
            fileName = fileName.replace(Path.DIRECTORY_SEPARATOR, "/")

            // No absolute pathnames
            // Windows (and Posix?) paths can start with "\\NetworkDrive\",
            // so we loop on starting /'s.
            while (!preserveAbsolutePath && fileName.startsWith("/")) {
                fileName = fileName.substring(1)
            }
            return fileName
        }
    }

}  