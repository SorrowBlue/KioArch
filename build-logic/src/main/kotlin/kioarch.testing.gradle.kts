import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

val testGeneratorClasspath = configurations.register("testGeneratorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(testGeneratorClasspath.name, libs.commons.compress)
    add(testGeneratorClasspath.name, libs.xz)
}

val generateTestFiles = tasks.register<GenerateTestFilesTask>("generateTestFiles") {
    group = "verification"
    description = "Generates test archives for testing"
    generatorClasspath.from(testGeneratorClasspath)
    outputDir.set(layout.buildDirectory.dir("tmp/large_tests"))
}

fun testEnvVars(testDir: String): Map<String, String> = mapOf(
    "TEST_ZIP_PATH" to "$testDir/test.zip",
    "TEST_7Z_PATH" to "$testDir/test.7z",
    "TEST_TARGZ_PATH" to "$testDir/test.tar.gz",
    "TEST_BZ2_PATH" to "$testDir/test.bz2",
    "TEST_TARBZ2_PATH" to "$testDir/test.tar.bz2",
    "TEST_SJIS_ZIP_PATH" to "$testDir/test_sjis.zip",
    "TEST_PATH_NORMAL_ZIP_PATH" to "$testDir/test_path_normal.zip",
    "TEST_BULK_ZIP_PATH" to "$testDir/test_bulk.zip",
    "TEST_EXT_ZIP_PATH" to "$testDir/test_ext.zip",
    "LARGE_ZIP_PATH" to "$testDir/large.zip",
    "LARGE_7Z_PATH" to "$testDir/large.7z",
    "LARGE_TARGZ_PATH" to "$testDir/large.tar.gz"
)

tasks.withType<KotlinNativeTest>().configureEach {
    val outputDirProvider = generateTestFiles.flatMap { it.outputDir }
    inputs.dir(outputDirProvider)
    val testDir = outputDirProvider.get().asFile.absolutePath
    testEnvVars(testDir).forEach { (key, value) ->
        environment(key, value)
        environment("SIMCTL_CHILD_$key", value)
    }
}

tasks.withType<KotlinJsTest>().configureEach {
    val outputDirProvider = generateTestFiles.flatMap { it.outputDir }
    inputs.dir(outputDirProvider)
    val testDir = outputDirProvider.get().asFile.absolutePath
    testEnvVars(testDir).forEach { (key, value) ->
        environment(key, value)
    }
}

tasks.withType<Test>().configureEach {
    val outputDirProvider = generateTestFiles.flatMap { it.outputDir }
    inputs.dir(outputDirProvider)
    failOnNoDiscoveredTests.set(false)
    val testDir = outputDirProvider.get().asFile.absolutePath
    testEnvVars(testDir).forEach { (key, value) ->
        systemProperty(key, value)
    }
}
