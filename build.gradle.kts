plugins {
    kotlin("multiplatform") version "1.8.10"
}


group = "me.eternal"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        jvmToolchain(8)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    val hostOs = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    println(arch)
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> {
            when(arch) {
                "aarch64" -> macosArm64("native")
                "armv71" -> throw GradleException("macOS is not supported arm32")
                "x86_64" -> macosX64("native")
                "i686" -> throw GradleException("macOS is not support 32-bit x86 equipment")
                else -> macosArm64("native") // default
            }
        }
        hostOs == "Linux" -> {
            when(arch) {
                "aarch64" -> linuxArm64("native")
                "armv71" -> linuxArm32Hfp("native") // will be removed in Kotlin 1.9.20
                "x86_64" -> linuxX64("native")
                "i686" -> throw GradleException("linux is not support 32-bit x86 equipment")
                else -> linuxX64("native") // default
            }
        }
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    macosArm64()
    macosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.bignum)
                implementation(libs.kotlinx.datetime)
                implementation(libs.okio)
                implementation("com.ditchoom:buffer:1.2.2")
                implementation(libs.korge.korio)
                implementation(libs.korge.kmem)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutine.test)
            }
        }
        val jvmMain by getting
        val jvmTest by getting

        val nativeMain by getting {
            dependsOn(commonMain)
        }
        val nativeTest by getting {
            dependsOn(commonTest)
        }

        val macosArm64Main by getting
        val macosArm64Test by getting
        val macosX64Main by getting
        val macosX64Test by getting

        val macosMain by creating {
            dependsOn(nativeMain)
            macosArm64Main.dependsOn(this)
            macosX64Main.dependsOn(this)
        }

        val macosTest by creating {
            dependsOn(nativeTest)
            macosArm64Test.dependsOn(this)
            macosX64Test.dependsOn(this)
        }

    }
}
