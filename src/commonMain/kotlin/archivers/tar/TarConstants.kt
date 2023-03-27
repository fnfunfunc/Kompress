package archivers.tar

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/**
 * This interface contains all the definitions used in the package.
 *
 * For tar formats (FORMAT_OLDGNU, FORMAT_POSIX, etc.) see GNU tar
 * <I>tar.h</I> type <I>enum archive_format</I>
 */
// CheckStyle:InterfaceIsTypeCheck OFF (bc)
object TarConstants {
    /** Default record size  */
    const val DEFAULT_RCDSIZE = 512

    /** Default block size  */
    const val DEFAULT_BLKSIZE: Int = DEFAULT_RCDSIZE * 20

    /**
     * GNU format as per before tar 1.12.
     */
    const val FORMAT_OLDGNU = 2

    /**
     * Pure Posix format.
     */
    const val FORMAT_POSIX = 3

    /**
     * xstar format used by Jörg Schilling's star.
     */
    const val FORMAT_XSTAR = 4

    /**
     * The length of the name field in a header buffer.
     */
    const val NAMELEN = 100

    /**
     * The length of the mode field in a header buffer.
     */
    const val MODELEN = 8

    /**
     * The length of the user id field in a header buffer.
     */
    const val UIDLEN = 8

    /**
     * The length of the group id field in a header buffer.
     */
    const val GIDLEN = 8

    /**
     * The maximum value of gid/uid in a tar archive which can be expressed in octal char notation (that's 7 sevens, octal).
     */
    const val MAXID = 2097151L

    /**
     * The length of the checksum field in a header buffer.
     */
    const val CHKSUMLEN = 8

    /**
     * Offset of the checksum field within header record.
     *
     * @since 1.5
     */
    const val CHKSUM_OFFSET = 148

    /**
     * The length of the size field in a header buffer. Includes the trailing space or NUL.
     */
    const val SIZELEN = 12

    /**
     * The maximum size of a file in a tar archive which can be expressed in octal char notation (that's 11 sevens, octal).
     */
    const val MAXSIZE = 8589934591L

    /** Offset of start of magic field within header record  */
    const val MAGIC_OFFSET = 257

    /**
     * The length of the magic field in a header buffer.
     */
    const val MAGICLEN = 6

    /** Offset of start of magic field within header record  */
    const val VERSION_OFFSET = 263

    /**
     * Previously this was regarded as part of "magic" field, but it is separate.
     */
    const val VERSIONLEN = 2

    /**
     * The length of the modification time field in a header buffer.
     */
    const val MODTIMELEN = 12

    /**
     * The length of the user name field in a header buffer.
     */
    const val UNAMELEN = 32

    /**
     * The length of the group name field in a header buffer.
     */
    const val GNAMELEN = 32

    /**
     * The length of each of the device fields (major and minor) in a header buffer.
     */
    const val DEVLEN = 8

    /**
     * Length of the prefix field.
     *
     */
    const val PREFIXLEN = 155

    /**
     * The length of the access time field in an old GNU header buffer.
     *
     */
    const val ATIMELEN_GNU = 12

    /**
     * The length of the created time field in an old GNU header buffer.
     *
     */
    const val CTIMELEN_GNU = 12

    /**
     * The length of the multivolume start offset field in an old GNU header buffer.
     *
     */
    const val OFFSETLEN_GNU = 12

    /**
     * The length of the long names field in an old GNU header buffer.
     *
     */
    const val LONGNAMESLEN_GNU = 4

    /**
     * The length of the padding field in an old GNU header buffer.
     *
     */
    const val PAD2LEN_GNU = 1

    /**
     * The sum of the length of all sparse headers in an old GNU header buffer.
     *
     */
    const val SPARSELEN_GNU = 96

    /**
     * The length of the is extension field in an old GNU header buffer.
     *
     */
    const val ISEXTENDEDLEN_GNU = 1

    /**
     * The length of the real size field in an old GNU header buffer.
     *
     */
    const val REALSIZELEN_GNU = 12

    /**
     * The length of offset in struct sparse
     *
     * @since 1.20
     */
    const val SPARSE_OFFSET_LEN = 12

    /**
     * The length of numbytes in struct sparse
     *
     * @since 1.20
     */
    const val SPARSE_NUMBYTES_LEN = 12

    /**
     * The number of sparse headers in an old GNU header
     *
     * @since 1.20
     */
    const val SPARSE_HEADERS_IN_OLDGNU_HEADER = 4

    /**
     * The number of sparse headers in an extension header
     *
     * @since 1.20
     */
    const val SPARSE_HEADERS_IN_EXTENSION_HEADER = 21

    /**
     * The sum of the length of all sparse headers in a sparse header buffer.
     *
     */
    const val SPARSELEN_GNU_SPARSE = 504

    /**
     * The length of the is extension field in a sparse header buffer.
     *
     */
    const val ISEXTENDEDLEN_GNU_SPARSE = 1

    /**
     * LF_ constants represent the "link flag" of an entry, or more commonly, the "entry type". This is the "old way" of
     * indicating a normal file.
     */
    const val LF_OLDNORM: Byte = 0

    /**
     * Offset inside the header for the "link flag" field.
     *
     * @since 1.22
     * @see TarArchiveEntry
     */
    const val LF_OFFSET = 156

    /**
     * Normal file type.
     */
    const val LF_NORMAL = '0'.code.toByte()

    /**
     * Link file type.
     */
    const val LF_LINK = '1'.code.toByte()

    /**
     * Symbolic link file type.
     */
    const val LF_SYMLINK = '2'.code.toByte()

    /**
     * Character device file type.
     */
    const val LF_CHR = '3'.code.toByte()

    /**
     * Block device file type.
     */
    const val LF_BLK = '4'.code.toByte()

    /**
     * Directory file type.
     */
    const val LF_DIR = '5'.code.toByte()

    /**
     * FIFO (pipe) file type.
     */
    const val LF_FIFO = '6'.code.toByte()

    /**
     * Contiguous file type.
     */
    const val LF_CONTIG = '7'.code.toByte()

    /**
     * Identifies the *next* file on the tape as having a long linkname.
     */
    const val LF_GNUTYPE_LONGLINK = 'K'.code.toByte()

    /**
     * Identifies the *next* file on the tape as having a long name.
     */
    const val LF_GNUTYPE_LONGNAME = 'L'.code.toByte()

    /**
     * Sparse file type.
     * @since 1.1.1
     */
    const val LF_GNUTYPE_SPARSE = 'S'.code.toByte()
    // See "http://www.opengroup.org/onlinepubs/009695399/utilities/pax.html#tag_04_100_13_02"
    /**
     * Identifies the entry as a Pax extended header.
     * @since 1.1
     */
    const val LF_PAX_EXTENDED_HEADER_LC = 'x'.code.toByte()

    /**
     * Identifies the entry as a Pax extended header (SunOS tar -E).
     *
     * @since 1.1
     */
    const val LF_PAX_EXTENDED_HEADER_UC = 'X'.code.toByte()

    /**
     * Identifies the entry as a Pax global extended header.
     *
     * @since 1.1
     */
    const val LF_PAX_GLOBAL_EXTENDED_HEADER = 'g'.code.toByte()

    /**
     * Identifies the entry as a multi-volume past volume #0
     *
     * @since 1.22
     */
    const val LF_MULTIVOLUME = 'M'.code.toByte()

    /**
     * The magic tag representing a POSIX tar archive.
     */
    const val MAGIC_POSIX = "ustar\u0000"
    const val VERSION_POSIX = "00"

    /**
     * The magic tag representing a GNU tar archive.
     */
    const val MAGIC_GNU = "ustar "

    /**
     * One of two two possible GNU versions
     */
    const val VERSION_GNU_SPACE = " \u0000"

    /**
     * One of two two possible GNU versions
     */
    const val VERSION_GNU_ZERO = "0\u0000"

    /**
     * The magic tag representing an Ant tar archive.
     *
     * @since 1.1
     */
    const val MAGIC_ANT = "ustar\u0000"

    /**
     * The "version" representing an Ant tar archive.
     *
     * @since 1.1
     */
    // Does not appear to have a version, however Ant does write 8 bytes,
    // so assume the version is 2 nulls
    const val VERSION_ANT = "\u0000\u0000"

    /**
     * The name of the GNU tar entry which contains a long name.
     */
    const val GNU_LONGLINK = "././@LongLink" // TODO rename as LONGLINK_GNU ?

    /**
     * The magix string used in the last four bytes of the header to
     * identify the xstar format.
     * @since 1.11
     */
    const val MAGIC_XSTAR = "tar\u0000"

    /**
     * Offset inside the header for the xtar multivolume data
     *
     * @since 1.22
     * @see TarArchiveEntry
     */
    const val XSTAR_MULTIVOLUME_OFFSET = 464

    /**
     * Offset inside the header for the xstar magic bytes.
     * @since 1.11
     */
    const val XSTAR_MAGIC_OFFSET = 508

    /**
     * Length of the XSTAR magic.
     * @since 1.11
     */
    const val XSTAR_MAGIC_LEN = 4

    /**
     * Length of the prefix field in xstar archives.
     *
     * @since 1.11
     */
    const val PREFIXLEN_XSTAR = 131

    /**
     * Offset inside the header for the prefix field in xstar archives.
     *
     * @since 1.22
     * @see TarArchiveEntry
     */
    const val XSTAR_PREFIX_OFFSET = 345

    /**
     * Offset inside the header for the atime field in xstar archives.
     *
     * @since 1.22
     * @see TarArchiveEntry
     */
    const val XSTAR_ATIME_OFFSET = 476

    /**
     * The length of the access time field in a xstar header buffer.
     *
     * @since 1.11
     */
    const val ATIMELEN_XSTAR = 12

    /**
     * Offset inside the header for the ctime field in xstar archives.
     *
     * @since 1.22
     * @see TarArchiveEntry
     */
    const val XSTAR_CTIME_OFFSET = 488

    /**
     * The length of the created time field in a xstar header buffer.
     *
     * @since 1.11
     */
    const val CTIMELEN_XSTAR = 12

}
