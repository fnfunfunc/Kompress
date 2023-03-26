package archivers.sevenzip

interface ICodeProgress {
    fun SetProgress(inSize: Long, outSize: Long)
}
