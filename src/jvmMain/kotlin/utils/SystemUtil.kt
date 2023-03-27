package utils

actual fun SystemUtil.getUserName(): String =
    System.getProperty("user.name", "")

actual fun SystemUtil.getOsName(): String =
    System.getProperty("os.name", "")