package archivers

import com.soywiz.korio.lang.IOException
import com.soywiz.korio.stream.SyncInputStream


/**
 * Archive input streams **MUST** override the
 * [.read] - or [.read] -
 * method so that reading from the stream generates EOF for the end of
 * data in each entry as well as at the end of the file proper.
 *
 *
 * The [.getNextEntry] method is used to reset the input stream
 * ready for reading the data from the next entry.
 *
 *
 * The input stream classes must also implement a method with the signature:
 * <pre>
 * public static boolean matches(byte[] signature, int length)
</pre> *
 * which is used by the [ArchiveStreamFactory] to autodetect
 * the archive type from the first few bytes of a stream.
 */
abstract class ArchiveInputStream : SyncInputStream {
    private val single = ByteArray(1)
    /**
     * Returns the current number of bytes read from this stream.
     * @return the number of read bytes
     * @since 1.1
     */
    /** holds the number of bytes read in this stream  */
    var bytesRead: Long = 0
        private set

    /**
     * Whether this stream is able to read the given entry.
     *
     *
     *
     * Some archive formats support variants or details that are not supported (yet).
     *
     *
     * @param archiveEntry
     * the entry to test
     * @return This implementation always returns true.
     *
     * @since 1.1
     */
    fun canReadEntryData(archiveEntry: ArchiveEntry?): Boolean {
        return true
    }
    /*
     * Note that subclasses also implement specific get() methods which
     * return the appropriate class without need for a cast.
     * See SVN revision r743259
     * @return
     * @throws IOException
     */
    // public abstract XXXArchiveEntry getNextXXXEntry() throws IOException;
    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     */
    protected fun count(read: Int) {
        count(read.toLong())
    }

    /**
     * Increments the counter of already read bytes.
     * Doesn't increment if the EOF has been hit (read == -1)
     *
     * @param read the number of bytes read
     * @since 1.1
     */
    protected fun count(read: Long) {
        if (read != -1L) {
            bytesRead += read
        }
    }

    @get:Deprecated(
        """this method may yield wrong results for large
      archives, use #getBytesRead instead""", ReplaceWith("bytesRead.toInt()")
    )
    val count: Int
        /**
         * Returns the current number of bytes read from this stream.
         * @return the number of read bytes
         */
        get() = bytesRead.toInt()

    @Throws(IOException::class)
    abstract fun getNextEntry(): ArchiveEntry?


    /**
     * Decrements the counter of already read bytes.
     *
     * @param pushedBack the number of bytes pushed back.
     * @since 1.1
     */
    protected fun pushedBackBytes(pushedBack: Long) {
        bytesRead -= pushedBack
    }

    /**
     * Reads a byte of data. This method will block until enough input is
     * available.
     *
     * Simply calls the [.read] method.
     *
     * MUST be overridden if the [.read] method
     * is not overridden; may be overridden otherwise.
     *
     * @return the byte read, or -1 if end of input is reached
     * @throws IOException
     * if an I/O error has occurred
     */
    override fun read(): Int {
        val num = read(single, 0, 1)
        return if (num == -1) -1 else single[0].toInt() and BYTE_MASK
    }

    companion object {
        private const val BYTE_MASK = 0xFF
    }
}

