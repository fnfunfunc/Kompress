import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
                "i686" -> throw GradleException("Not support 32-bit x86 equipment")
                else -> linuxX64("native") // default
            }
        }
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin Multiplatform.")
    }

    macosArm64()
    macosX64()
    iosArm64()
    iosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.bignum)
                implementation(libs.kotlinx.datetime)
                implementation(libs.okio)
                implementation(libs.ditchoom.buffer)
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


        val darwinMain by creating {
            dependsOn(nativeMain)
        }

        val darwinTest by creating {
            dependsOn(nativeTest)
        }

        val iosMain by creating {
            dependsOn(darwinMain)
        }

        val iosTest by creating {
            dependsOn(darwinTest)
        }

        val macosMain by creating {
            dependsOn(darwinMain)
        }

        val macosTest by creating {
            dependsOn(darwinTest)
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }
        val iosArm64Test by getting {
            dependsOn(iosTest)
        }
        val iosX64Main by getting {
            dependsOn(iosMain)
        }
        val iosX64Test by getting {
            dependsOn(iosTest)
        }

        val macosArm64Main by getting {
            dependsOn(macosMain)
        }
        val macosArm64Test by getting {
            dependsOn(macosTest)
        }
        val macosX64Main by getting {
            dependsOn(macosMain)
        }
        val macosX64Test by getting {
            dependsOn(macosTest)
        }
    }
}