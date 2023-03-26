package utils

import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserName

actual fun SystemUtil.getUserName(): String = NSUserName()
actual fun SystemUtil.getOsName(): String =
    NSProcessInfo.processInfo.operatingSystemName()