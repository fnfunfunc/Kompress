import archivers.sevenzip.Lzma
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import utils.FileUtil
import kotlin.test.Test

class SevenZipTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun singleFileSevenZipTest() = runTest {
        val fileSystem = FileUtil.fileSystem
        val inputFile =
            fileSystem.openReadOnly("/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/txt/large_test.txt".toPath())
        val inputSource = inputFile.source().buffer()
        val outputSink =
            fileSystem.openReadWrite("/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/sevenzip/singleFileSevenZipTest.7z".toPath())
                .sink().buffer()
        Lzma.compress(inputSource, outputSink, inputFile.size())
    }

    @Test
    fun fileMetadata() {
        val desc = FileUtil.fileSystem.metadata("/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/jpg/dontpanic.jpg".toPath()).toString()
        println(desc)
    }
}