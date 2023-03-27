import utils.SystemUtil
import utils.getOsName
import utils.getUserName
import kotlin.test.Test

class SystemUtilTest {

    @Test
    fun getOsName() {
        println(SystemUtil.getOsName())
    }

    @Test
    fun getUserName() {
        println(SystemUtil.getUserName())
    }

}