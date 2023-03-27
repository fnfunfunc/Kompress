import archivers.zip.ZipBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import kotlin.test.Test

class ZipTest {

    @Test
    fun singleFileZipTest() =
        ZipBuilder.createZipFromTree(
            inputPath = "/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/jpg/dontpanic.jpg".toPath(normalize = false),
            "/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/zip/singleFileZipTest.zip".toPath(
                normalize = true
            )
        )

    @Test
    fun directoryZipTest() =
        ZipBuilder.createZipFromTree(
            inputPath = "/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/jpg".toPath(normalize = false),
            "/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/zip/directoryZipTest.zip".toPath(
                normalize = true
            )
        )

    @Test
    fun peekTest() {
        val buffer = Buffer()
        buffer.write("Hello".encodeUtf8())
        val s = buffer.peek()
        println(s.readByteString())
        println(buffer.readByteString())
    }
}