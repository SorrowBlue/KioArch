import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

val generateTestFiles = tasks.register("generateTestFiles", JavaExec::class.java) {
    group = "verification"
    description = "Generates test archives for testing"
    dependsOn("jvmTestClasses")
    mainClass.set("com.sorrowblue.kioarch.TestFileGenerator")
    args(layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath)
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
    generateTestFiles.configure {
        classpath = kotlin.targets.getByName("jvm").compilations.getByName("test").let {
            files(it.output.allOutputs, it.runtimeDependencyFiles)
        }
    }
}

val copyJsTestAssets = tasks.register("copyJsTestAssets", Copy::class.java) {
    dependsOn(generateTestFiles)
    from(layout.buildDirectory.dir("tmp/large_tests"))
    into(
        project.rootProject.layout.buildDirectory.dir(
            "js/packages/KioArch-root-kioarch-test/kotlin"
        )
    )
}

val copyWasmTestAssets = tasks.register("copyWasmTestAssets", Copy::class.java) {
    dependsOn(generateTestFiles)
    from(layout.buildDirectory.dir("tmp/large_tests"))
    into(
        project.rootProject.layout.buildDirectory.dir(
            "wasm/packages/KioArch-root-kioarch-test/kotlin"
        )
    )
}

tasks.withType(KotlinNativeTest::class.java).configureEach {
    dependsOn(generateTestFiles)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    environment("LARGE_ZIP_PATH", "$testDir/large.zip")
    environment("LARGE_7Z_PATH", "$testDir/large.7z")
    environment("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    environment("LARGE_100M_7Z_PATH", "$testDir/large_100m.7z")
    environment("LARGE_100M_TARGZ_PATH", "$testDir/large_100m.tar.gz")
    environment("LARGE_100M_ZIP_PATH", "$testDir/large_100m.zip")
    environment("TEST_BZ2_PATH", "$testDir/test.bz2")
    environment("TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")

    // Pass environment variables to the iOS Simulator test runner
    environment("SIMCTL_CHILD_LARGE_ZIP_PATH", "$testDir/large.zip")
    environment("SIMCTL_CHILD_LARGE_7Z_PATH", "$testDir/large.7z")
    environment("SIMCTL_CHILD_LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    environment("SIMCTL_CHILD_LARGE_100M_7Z_PATH", "$testDir/large_100m.7z")
    environment("SIMCTL_CHILD_LARGE_100M_TARGZ_PATH", "$testDir/large_100m.tar.gz")
    environment("SIMCTL_CHILD_LARGE_100M_ZIP_PATH", "$testDir/large_100m.zip")
    environment("SIMCTL_CHILD_TEST_BZ2_PATH", "$testDir/test.bz2")
    environment("SIMCTL_CHILD_TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")
}

tasks.withType(KotlinJsTest::class.java).configureEach {
    dependsOn(generateTestFiles)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    environment("LARGE_ZIP_PATH", "$testDir/large.zip")
    environment("LARGE_7Z_PATH", "$testDir/large.7z")
    environment("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    environment("TEST_7Z_PATH", "$testDir/test.7z")
    environment("TEST_ZIP_PATH", "$testDir/test.zip")
    environment("TEST_SJIS_ZIP_PATH", "$testDir/test_sjis.zip")
    environment("TEST_PATH_NORMAL_ZIP_PATH", "$testDir/test_path_normal.zip")
    environment("TEST_BZ2_PATH", "$testDir/test.bz2")
    environment("TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")
}

tasks.withType(Test::class.java).configureEach {
    dependsOn(generateTestFiles)
    failOnNoDiscoveredTests.set(false)
    val testDir = layout.buildDirectory.dir("tmp/large_tests").get().asFile.absolutePath
    systemProperty("TEST_ZIP_PATH", "$testDir/test.zip")
    systemProperty("TEST_7Z_PATH", "$testDir/test.7z")
    systemProperty("TEST_TARGZ_PATH", "$testDir/test.tar.gz")
    systemProperty("TEST_BZ2_PATH", "$testDir/test.bz2")
    systemProperty("TEST_TARBZ2_PATH", "$testDir/test.tar.bz2")
    systemProperty("TEST_SJIS_ZIP_PATH", "$testDir/test_sjis.zip")
    systemProperty("TEST_PATH_NORMAL_ZIP_PATH", "$testDir/test_path_normal.zip")
    systemProperty("TEST_BULK_ZIP_PATH", "$testDir/test_bulk.zip")
    systemProperty("TEST_EXT_ZIP_PATH", "$testDir/test_ext.zip")
    systemProperty("LARGE_ZIP_PATH", "$testDir/large.zip")
    systemProperty("LARGE_7Z_PATH", "$testDir/large.7z")
    systemProperty("LARGE_TARGZ_PATH", "$testDir/large.tar.gz")
    systemProperty("LARGE_100M_7Z_PATH", "$testDir/large_100m.7z")
    systemProperty("LARGE_100M_TARGZ_PATH", "$testDir/large_100m.tar.gz")
    systemProperty("LARGE_100M_ZIP_PATH", "$testDir/large_100m.zip")
    systemProperty("LARGE_TARBZ2_PATH", "$testDir/large.tar.bz2")
    systemProperty("LARGE_100M_TARBZ2_PATH", "$testDir/large_100m.tar.bz2")
}

tasks.configureEach {
    if (name == "jsBrowserTest" || name == "jsNodeTest") {
        dependsOn(copyJsTestAssets)
    }
    if (name == "wasmJsBrowserTest" || name == "wasmJsNodeTest") {
        dependsOn(copyWasmTestAssets)
    }
}
