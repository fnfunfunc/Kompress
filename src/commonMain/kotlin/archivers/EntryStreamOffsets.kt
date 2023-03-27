package archivers

/**
 * Provides information about ArchiveEntry stream offsets.
 */
interface EntryStreamOffsets {
    /**
     * Gets the offset of data stream within the archive file,
     *
     * @return
     * the offset of entry data stream, `OFFSET_UNKNOWN` if not known.
     */
    val dataOffset: Long

    /**
     * Indicates whether the stream is contiguous, i.e. not split among
     * several archive parts, interspersed with control blocks, etc.
     *
     * @return
     * true if stream is contiguous, false otherwise.
     */
    val isStreamContiguous: Boolean

    companion object {
        /** Special value indicating that the offset is unknown.  */
        const val OFFSET_UNKNOWN: Long = -1
    }
}

