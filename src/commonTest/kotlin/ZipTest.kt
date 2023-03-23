import archivers.zip.ZipBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test

class ZipTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun zipTest() = runTest {
        ZipBuilder.createZipFromTree(
            file = "/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/jpg/dontpanic.jpg".toPath(normalize = false),
            "/Users/eternal/Program/Kotlin_Projects/Kompress/src/commonTest/resources/zip/dontpanic.zip".toPath(
                normalize = true
            )
        )
    }
}