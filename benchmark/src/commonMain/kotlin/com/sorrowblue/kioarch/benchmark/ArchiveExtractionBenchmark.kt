/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sorrowblue.kioarch.benchmark

import com.sorrowblue.kioarch.KioArch
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered

/**
 * Multiplatform benchmark measuring archive listing and extraction throughput across ZIP, 7z, and TAR.GZ.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
public open class ArchiveExtractionBenchmark {

    @Param("zip", "7z", "targz")
    public var format: String = "zip"

    @Param("small", "medium", "large")
    public var profile: String = "medium"

    private var archiveBytes: ByteArray = ByteArray(0)

    private val discardingSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            source.skip(byteCount)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    @Setup
    public fun setup() {
        initBenchmarkPlatform()
        archiveBytes = BenchmarkArchiveGenerator.generate(format, profile)
    }

    @Benchmark
    public fun listEntries(): Int = KioArch.createReader(archiveBytes).use { reader ->
        reader.getEntries().size
    }

    @Benchmark
    public fun extractAllEntries(): Long {
        var totalBytes = 0L
        KioArch.createReader(archiveBytes).use { reader ->
            val sink = discardingSink.buffered()
            for (entry in reader.getEntries()) {
                if (!entry.isDirectory) {
                    reader.extractEntry(entry, sink)
                    sink.flush()
                    totalBytes += entry.size
                }
            }
        }
        return totalBytes
    }
}
