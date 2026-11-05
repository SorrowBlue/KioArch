val updateBenchmarkCharts = tasks.register<UpdateBenchmarkChartsTask>("updateBenchmarkCharts") {
    group = "benchmark"
    description =
        "Generates XChart SVG benchmark graphs in docs/assets/ from JMH and Android JSON reports."
    benchmarkJsonFiles.from(
        layout.buildDirectory.asFileTree.matching {
            include(
                "reports/benchmarks/gc/jvm-gc.json",
                "reports/benchmarks/**/*.json",
                "outputs/connected_android_test_additional_output/**/" +
                    "com.sorrowblue.kioarch.benchmark.test-benchmarkData.json"
            )
        }
    )
    libraryHeapSvgFile.set(
        rootProject.layout.projectDirectory.file("docs/assets/benchmark-library-heap.svg")
    )
    libraryThroughputSvgFile.set(
        rootProject.layout.projectDirectory.file("docs/assets/benchmark-library-throughput.svg")
    )
    platformThroughputSvgFile.set(
        rootProject.layout.projectDirectory.file("docs/assets/benchmark-platform-throughput.svg")
    )
}

val jvmGcReportFile = layout.buildDirectory.file("reports/benchmarks/gc/jvm-gc.json")

tasks.register<JavaExec>("jvmGcBenchmark") {
    group = "benchmark"
    description =
        "Runs JniZeroCopyBenchmark with JMH GC profiler (-prof gc) to measure heap allocations."
    dependsOn("jvmBenchmarkCompile")
    val smokeTask = tasks.named<JavaExec>("jvmSmokeBenchmark")
    classpath(smokeTask.map { it.classpath })
    javaLauncher.set(smokeTask.flatMap { it.javaLauncher })
    mainClass.set("org.openjdk.jmh.Main")
    val reportFile = jvmGcReportFile
    outputs.file(reportFile)
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "JniZeroCopyBenchmark.extract",
                "-prof",
                "gc",
                "-wi",
                "1",
                "-i",
                "2",
                "-w",
                "500ms",
                "-r",
                "500ms",
                "-f",
                "1",
                "-bm",
                "avgt",
                "-tu",
                "ms",
                "-rf",
                "json",
                "-rff",
                reportFile.get().asFile.absolutePath
            )
        }
    )
    finalizedBy(updateBenchmarkCharts)
}

tasks.matching {
    it.name == "connectedAndroidDeviceTest" ||
        it.name == "jsSmokeBenchmark" ||
        it.name == "wasmJsSmokeBenchmark" ||
        it.name == "iosSimulatorArm64SmokeBenchmark" ||
        it.name == "jvmSmokeBenchmark"
}.configureEach {
    finalizedBy(updateBenchmarkCharts)
}
