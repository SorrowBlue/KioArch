import groovy.json.JsonSlurper
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToInt
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.knowm.xchart.CategoryChart
import org.knowm.xchart.CategoryChartBuilder
import org.knowm.xchart.ChartEncoder
import org.knowm.xchart.style.Styler

@CacheableTask
@Suppress("MagicNumber")
abstract class UpdateBenchmarkChartsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val benchmarkJsonFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val libraryHeapSvgFile: RegularFileProperty

    @get:OutputFile
    abstract val libraryThroughputSvgFile: RegularFileProperty

    @get:OutputFile
    abstract val platformThroughputSvgFile: RegularFileProperty

    @TaskAction
    fun generateCharts() {
        System.setProperty("java.awt.headless", "true")
        val files = benchmarkJsonFiles.files

        val jvmGcJson = files.firstOrNull { it.name == "jvm-gc.json" && it.exists() }
        if (jvmGcJson != null) {
            val outFile = libraryHeapSvgFile.get().asFile
            outFile.parentFile.mkdirs()
            generateLibraryHeapChart(jvmGcJson, outFile)
            logger.lifecycle("Generated XChart library heap SVG: ${outFile.absolutePath}")
        }

        val androidJson = files
            .filter { it.name.endsWith("-benchmarkData.json") && it.exists() }
            .maxByOrNull { it.lastModified() }
        if (androidJson != null) {
            val outFile = libraryThroughputSvgFile.get().asFile
            outFile.parentFile.mkdirs()
            generateLibraryThroughputChart(androidJson, outFile)
            logger.lifecycle("Generated XChart library throughput SVG: ${outFile.absolutePath}")
        }

        val platformOutFile = platformThroughputSvgFile.get().asFile
        platformOutFile.parentFile.mkdirs()
        if (generatePlatformThroughputChart(files, jvmGcJson, androidJson, platformOutFile)) {
            logger.lifecycle(
                "Generated XChart platform throughput SVG: ${platformOutFile.absolutePath}"
            )
        }
    }

    private fun applyCommonStyle(chart: CategoryChart, colors: Array<Color>) {
        val styler = chart.styler
        styler.legendPosition = Styler.LegendPosition.OutsideS
        styler.legendLayout = Styler.LegendLayout.Horizontal
        styler.isOverlapped = false
        styler.availableSpaceFill = 0.76
        styler.seriesColors = colors
        styler.chartBackgroundColor = Color.WHITE
        styler.plotBackgroundColor = Color(0xF8, 0xFA, 0xFC)
        styler.plotBorderColor = Color(0xE2, 0xE8, 0xF0)
        styler.plotGridLinesColor = Color(0xE2, 0xE8, 0xF0)
        styler.chartFontColor = Color(0x1E, 0x29, 0x3B)
        styler.axisTickLabelsColor = Color(0x33, 0x41, 0x55)
        styler.legendBackgroundColor = Color.WHITE
        styler.legendBorderColor = Color(0xCBD5E1)
        styler.chartTitleFont = Font(Font.SANS_SERIF, Font.BOLD, 16)
        styler.axisTitleFont = Font(Font.SANS_SERIF, Font.BOLD, 13)
        styler.axisTickLabelsFont = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        styler.legendFont = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        styler.isPlotGridVerticalLinesVisible = false
        styler.plotGridLinesStroke = BasicStroke(1.0f)
        styler.chartPadding = 16
    }

    private fun generateLibraryHeapChart(jsonFile: File, outFile: File) {
        val kibMap = parseLibraryHeapMetrics(jsonFile)
        if (kibMap.isEmpty()) return

        val kioSeries = PROFILE_ORDER.map { (f, p) ->
            log10((kibMap["kioarch_${f}_$p"] ?: 0.1).coerceAtLeast(0.1) / 0.1)
        }
        val commonsSeries = PROFILE_ORDER.map { (f, p) ->
            log10((kibMap["commons_${f}_$p"] ?: 0.1).coerceAtLeast(0.1) / 0.1)
        }
        val sevenZipSeries = PROFILE_ORDER.map { (f, p) ->
            log10((kibMap["sevenzip_${f}_$p"] ?: 0.1).coerceAtLeast(0.1) / 0.1)
        }

        val chart = CategoryChartBuilder()
            .width(960)
            .height(480)
            .title("Heap Memory Allocated per Extraction (KiB/op, Log Scale — Lower is Better)")
            .xAxisTitle("Archive Format & Size Profile")
            .yAxisTitle("Allocated Heap Memory (KiB/op)")
            .build()

        applyCommonStyle(chart, LIBRARY_COLORS)
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 6.0
        chart.styler.yAxisTickMarkSpacingHint = 45
        val logLabels = mapOf(
            0 to "0.1",
            1 to "1",
            2 to "10",
            3 to "100",
            4 to "1,000",
            5 to "10,000",
            6 to "100,000"
        )
        chart.styler.setYAxisTickLabelsFormattingFunction { value ->
            val rounded = value.roundToInt()
            if (abs(value - rounded) < 1e-4) logLabels[rounded].orEmpty() else ""
        }

        chart.addSeries("KioArch", CATEGORIES, kioSeries)
        chart.addSeries("Apache Commons Compress", CATEGORIES, commonsSeries)
        chart.addSeries("7-Zip-JBinding", CATEGORIES, sevenZipSeries)

        ChartEncoder.saveChart(chart, outFile.absolutePath.removeSuffix(".svg"), "svg")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLibraryHeapMetrics(jsonFile: File): Map<String, Double> {
        val parsed = JsonSlurper().parse(jsonFile) as? List<Map<String, Any?>> ?: return emptyMap()
        return parsed.mapNotNull { entry ->
            val benchmarkName = entry["benchmark"] as? String
            val lib = when {
                benchmarkName?.endsWith("extractKioArch") == true -> "kioarch"
                benchmarkName?.endsWith("extractCommonsCompress") == true -> "commons"
                benchmarkName?.endsWith("extractSevenZipJBinding") == true -> "sevenzip"
                else -> null
            }
            val params = entry["params"] as? Map<String, Any?>
            val format = params?.get("format") as? String
            val profile = params?.get("profile") as? String
            val secondary = entry["secondaryMetrics"] as? Map<String, Map<String, Any?>>
            val gcNorm = secondary?.entries
                ?.firstOrNull { it.key.endsWith("gc.alloc.rate.norm") }
                ?.value
            val bytesPerOp = (gcNorm?.get("score") as? Number)?.toDouble()
            val key = if (lib != null && format != null && profile != null) {
                "${lib}_${format}_$profile"
            } else {
                null
            }
            if (key != null && bytesPerOp != null) key to (bytesPerOp / 1024.0) else null
        }.toMap()
    }

    private fun generateLibraryThroughputChart(jsonFile: File, outFile: File) {
        val mibMap = parseAndroidLibraryThroughput(jsonFile)
        if (mibMap.isEmpty()) return

        val kioSeries = PROFILE_ORDER.map { (f, p) -> mibMap["kioarch_${f}_$p"] ?: 0.0 }
        val commonsSeries = PROFILE_ORDER.map { (f, p) -> mibMap["commons_${f}_$p"] ?: 0.0 }
        val sevenZipSeries = PROFILE_ORDER.map { (f, p) -> mibMap["sevenzip_${f}_$p"] ?: 0.0 }
        val maxVal = (kioSeries + commonsSeries + sevenZipSeries).maxOrNull() ?: 350.0

        val chart = CategoryChartBuilder()
            .width(960)
            .height(480)
            .title("Android Extraction Throughput on Google Pixel 9 (MiB/s — Higher is Better)")
            .xAxisTitle("Archive Format & Size Profile")
            .yAxisTitle("Throughput (MiB/s)")
            .build()

        applyCommonStyle(chart, LIBRARY_COLORS)
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = ceil(maxVal / 50.0) * 50.0

        chart.addSeries("KioArch", CATEGORIES, kioSeries)
        chart.addSeries("Apache Commons Compress", CATEGORIES, commonsSeries)
        chart.addSeries("7-Zip-JBinding-4Android", CATEGORIES, sevenZipSeries)

        ChartEncoder.saveChart(chart, outFile.absolutePath.removeSuffix(".svg"), "svg")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAndroidLibraryThroughput(jsonFile: File): Map<String, Double> {
        val root = JsonSlurper().parse(jsonFile) as? Map<String, Any?>
        val benchmarks = root?.get("benchmarks") as? List<Map<String, Any?>> ?: return emptyMap()
        val nameRegex = Regex(
            "extract(KioArch|CommonsCompress|SevenZipJBinding)" +
                """\[(zip|7z|targz)_(small|medium|large)]"""
        )

        return benchmarks.mapNotNull { bm ->
            val name = bm["name"] as? String
            val match = name?.let { nameRegex.find(it) }
            val metrics = bm["metrics"] as? Map<String, Any?>
            val timeNs = metrics?.get("timeNs") as? Map<String, Any?>
            val medianNs = (timeNs?.get("median") as? Number)?.toDouble()
            if (match != null && medianNs != null) {
                val lib = when (match.groupValues[1]) {
                    "KioArch" -> "kioarch"
                    "CommonsCompress" -> "commons"
                    else -> "sevenzip"
                }
                val format = match.groupValues[2]
                val profile = match.groupValues[3]
                val totalMib = TOTAL_MIB_BY_PROFILE[profile]
                totalMib?.let {
                    "${lib}_${format}_$profile" to (it / (medianNs / 1_000_000_000.0))
                }
            } else {
                null
            }
        }.toMap()
    }

    private fun generatePlatformThroughputChart(
        files: Set<File>,
        jvmGcJson: File?,
        androidJson: File?,
        outFile: File
    ): Boolean {
        val seriesByPlatform = collectPlatformSeries(files, jvmGcJson, androidJson)
        if (seriesByPlatform.isEmpty()) return false
        val maxVal = seriesByPlatform.values.flatten().maxOrNull() ?: 400.0

        val chart = CategoryChartBuilder()
            .width(960)
            .height(480)
            .title("KioArch Extraction Throughput by Platform (MiB/s — Higher is Better)")
            .xAxisTitle("Archive Format & Size Profile")
            .yAxisTitle("Throughput (MiB/s)")
            .build()

        applyCommonStyle(chart, PLATFORM_COLORS.take(seriesByPlatform.size).toTypedArray())
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = ceil(maxVal / 50.0) * 50.0

        seriesByPlatform.forEach { (platformName, series) ->
            chart.addSeries(platformName, CATEGORIES, series)
        }

        ChartEncoder.saveChart(chart, outFile.absolutePath.removeSuffix(".svg"), "svg")
        return true
    }

    private fun collectPlatformSeries(
        files: Set<File>,
        jvmGcJson: File?,
        androidJson: File?
    ): Map<String, List<Double>> {
        val seriesByPlatform = linkedMapOf<String, List<Double>>()

        addJmhPlatformSeries(seriesByPlatform, "JVM (JDK 21)", jvmGcJson, "extractKioArch")

        val androidMap = androidJson?.let { parseAndroidLibraryThroughput(it) }.orEmpty()
        if (androidMap.isNotEmpty()) {
            seriesByPlatform["Android (Pixel 9)"] = PROFILE_ORDER.map { (f, p) ->
                androidMap["kioarch_${f}_$p"] ?: 0.0
            }
        }

        val jsJson = files.filter {
            it.exists() && it.name == "js.json"
        }.maxByOrNull { it.lastModified() }
        addJmhPlatformSeries(seriesByPlatform, "JS (Node.js)", jsJson, "extractAllEntries")

        val wasmJson = files.filter {
            it.exists() && it.name == "wasmJs.json"
        }.maxByOrNull { it.lastModified() }
        addJmhPlatformSeries(seriesByPlatform, "WasmJs (Node.js)", wasmJson, "extractAllEntries")

        val iosJson = files
            .filter { it.exists() && it.name.startsWith("ios") && it.name.endsWith(".json") }
            .maxByOrNull { it.lastModified() }
        addJmhPlatformSeries(seriesByPlatform, "iOS", iosJson, "extractAllEntries")

        return seriesByPlatform
    }

    private fun addJmhPlatformSeries(
        target: MutableMap<String, List<Double>>,
        label: String,
        jsonFile: File?,
        methodSuffix: String
    ) {
        val map = jsonFile?.let { parseJmhThroughput(it, methodSuffix) }.orEmpty()
        if (map.isNotEmpty()) {
            target[label] = PROFILE_ORDER.map { (f, p) -> map["${f}_$p"] ?: 0.0 }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJmhThroughput(jsonFile: File, methodSuffix: String): Map<String, Double> {
        val parsed = JsonSlurper().parse(jsonFile) as? List<Map<String, Any?>> ?: return emptyMap()
        return parsed.mapNotNull { entry ->
            parseSingleJmhThroughput(entry, methodSuffix)
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST", "ReturnCount")
    private fun parseSingleJmhThroughput(
        entry: Map<String, Any?>,
        methodSuffix: String
    ): Pair<String, Double>? {
        val benchmarkName = entry["benchmark"] as? String ?: return null
        if (!benchmarkName.endsWith(methodSuffix)) return null
        val params = entry["params"] as? Map<String, Any?> ?: return null
        val format = params["format"] as? String ?: return null
        val profile = params["profile"] as? String ?: return null
        val totalMib = TOTAL_MIB_BY_PROFILE[profile] ?: return null
        val primary = entry["primaryMetric"] as? Map<String, Any?> ?: return null
        val scoreMs = (primary["score"] as? Number)?.toDouble() ?: return null
        if (scoreMs <= 0.0) return null
        return "${format}_$profile" to (totalMib / (scoreMs / 1000.0))
    }

    companion object {
        private val CATEGORIES = listOf(
            "ZIP (S)",
            "ZIP (M)",
            "ZIP (L)",
            "7z (S)",
            "7z (M)",
            "7z (L)",
            "TAR.GZ (S)",
            "TAR.GZ (M)",
            "TAR.GZ (L)"
        )
        private val PROFILE_ORDER = listOf(
            "zip" to "small",
            "zip" to "medium",
            "zip" to "large",
            "7z" to "small",
            "7z" to "medium",
            "7z" to "large",
            "targz" to "small",
            "targz" to "medium",
            "targz" to "large"
        )
        private val TOTAL_MIB_BY_PROFILE = mapOf(
            "small" to (80.0 / 1024.0),
            "medium" to 2.5,
            "large" to 20.0
        )
        private val LIBRARY_COLORS = arrayOf(
            Color(0x38, 0x79, 0xE4), // KioArch (Primary Blue)
            Color(0x64, 0x74, 0x8B), // Apache Commons Compress (Slate)
            Color(0xF5, 0x9E, 0x0B) // 7-Zip-JBinding (Amber)
        )
        private val PLATFORM_COLORS = listOf(
            Color(0x38, 0x79, 0xE4), // JVM (Primary Blue)
            Color(0x10, 0xB9, 0x81), // Android (Emerald)
            Color(0xF5, 0x9E, 0x0B), // JS (Amber)
            Color(0x8B, 0x5C, 0xF6), // WasmJs (Purple)
            Color(0xEC, 0x48, 0x99) // iOS (Pink)
        )
    }
}
