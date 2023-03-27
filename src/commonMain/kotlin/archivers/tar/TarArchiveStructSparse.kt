package archivers.tar

/**
 * This class represents struct sparse in a Tar archive.
 *
 *
 * Whereas, "struct sparse" is:
 * <pre>
 * struct sparse {
 * char offset[12];   // offset 0
 * char numbytes[12]; // offset 12
 * };
</pre> *
 */
data class TarArchiveStructSparse(val offset: Long, val numbytes: Long) {

    init {
        if (offset < 0) {
            throw IllegalArgumentException("offset must not be negative")
        }
        if (numbytes < 0) {
            throw IllegalArgumentException("numbytes must not be negative")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || other !is TarArchiveStructSparse) {
            return false
        }
        return offset == other.offset &&
                numbytes == other.numbytes
    }

    override fun hashCode(): Int {
        return utils.hashCode(arrayOf(offset, numbytes))
    }
}

