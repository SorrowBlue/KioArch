package com.sorrowblue.kioarch.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AndroidJniZeroCopyBenchmarkTest(private val format: String, private val profile: String) {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun extractKioArch() {
        val benchmark = JniZeroCopyBenchmark()
        benchmark.format = format
        benchmark.profile = profile
        benchmark.setup()
        try {
            benchmarkRule.measureRepeated {
                benchmark.extractKioArch()
            }
        } finally {
            benchmark.tearDown()
        }
    }

    @Test
    fun extractCommonsCompress() {
        val benchmark = JniZeroCopyBenchmark()
        benchmark.format = format
        benchmark.profile = profile
        benchmark.setup()
        try {
            benchmarkRule.measureRepeated {
                benchmark.extractCommonsCompress()
            }
        } finally {
            benchmark.tearDown()
        }
    }

    @Test
    fun extractSevenZipJBinding() {
        val benchmark = JniZeroCopyBenchmark()
        benchmark.format = format
        benchmark.profile = profile
        benchmark.setup()
        try {
            benchmarkRule.measureRepeated {
                benchmark.extractSevenZipJBinding()
            }
        } finally {
            benchmark.tearDown()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}_{1}")
        fun parameters(): List<Array<String>> = listOf(
            arrayOf("zip", "small"),
            arrayOf("zip", "medium"),
            arrayOf("zip", "large"),
            arrayOf("7z", "small"),
            arrayOf("7z", "medium"),
            arrayOf("7z", "large"),
            arrayOf("targz", "small"),
            arrayOf("targz", "medium"),
            arrayOf("targz", "large")
        )
    }
}
