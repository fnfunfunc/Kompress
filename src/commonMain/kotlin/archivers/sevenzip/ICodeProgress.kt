package archivers.sevenzip

interface ICodeProgress {
    fun setProgress(inSize: Long, outSize: Long)
}
