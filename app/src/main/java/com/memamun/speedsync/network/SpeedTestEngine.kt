package com.memamun.speedsync.network

import com.memamun.speedsync.model.SpeedTestPhase
import com.memamun.speedsync.model.SpeedTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong

class SpeedTestEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _testResult = MutableStateFlow(SpeedTestResult())
    val testResult: StateFlow<SpeedTestResult> = _testResult.asStateFlow()

    private val activeCalls = CopyOnWriteArrayList<Call>()
    private val activeTestId = AtomicLong(0L)

    private fun registerCall(call: Call) {
        activeCalls.add(call)
    }

    private fun unregisterCall(call: Call) {
        activeCalls.remove(call)
    }

    private fun cancelAllCalls() {
        for (call in activeCalls) {
            try {
                call.cancel()
            } catch (_: Exception) {}
        }
        activeCalls.clear()
    }

    suspend fun runSpeedTest() = withContext(Dispatchers.IO) {
        val testId = activeTestId.incrementAndGet()
        cancelAllCalls()

        _testResult.value = SpeedTestResult(
            phase = SpeedTestPhase.PING,
            progress = 0.05f
        )

        // Phase 1: Ping & Jitter
        val pingTimes = mutableListOf<Long>()
        var failedPings = 0
        val pingEndpoints = listOf(
            "https://www.google.com/generate_204",
            "https://cloudflare.com/cdn-cgi/trace",
            "https://www.gstatic.com/generate_204",
            "https://www.google.com/generate_204"
        )

        for ((index, url) in pingEndpoints.withIndex()) {
            if (!currentCoroutineContext().isActive || activeTestId.get() != testId) return@withContext
            val startNano = System.nanoTime()
            try {
                val req = Request.Builder().url(url).head().build()
                val call = client.newCall(req)
                registerCall(call)
                try {
                    call.execute().use { resp ->
                        if (resp.isSuccessful) {
                            val durationMs = (System.nanoTime() - startNano) / 1_000_000L
                            pingTimes.add(durationMs)
                        } else {
                            failedPings++
                        }
                    }
                } finally {
                    unregisterCall(call)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedPings++
            }

            if (activeTestId.get() != testId) return@withContext

            val progress = 0.05f + (0.15f * (index + 1) / pingEndpoints.size)
            _testResult.value = _testResult.value.copy(
                progress = progress,
                pingMs = if (pingTimes.isNotEmpty()) pingTimes.average().roundToLong() else 0L
            )
            delay(100)
        }

        if (!currentCoroutineContext().isActive || activeTestId.get() != testId) return@withContext

        if (pingTimes.isEmpty()) {
            _testResult.value = _testResult.value.copy(
                phase = SpeedTestPhase.ERROR,
                progress = 0f,
                errorMessage = "Network unreachable. Check your internet connection.",
                testFinished = true
            )
            return@withContext
        }

        val avgPing = pingTimes.average().roundToLong()
        var jitterSum = 0L
        for (i in 0 until pingTimes.size - 1) {
            jitterSum += abs(pingTimes[i] - pingTimes[i + 1])
        }
        val jitter = if (pingTimes.size > 1) jitterSum / (pingTimes.size - 1) else 0L
        val packetLoss = if (pingEndpoints.isNotEmpty()) (failedPings.toDouble() / pingEndpoints.size) * 100.0 else 0.0

        _testResult.value = _testResult.value.copy(
            pingMs = avgPing.coerceAtLeast(1L),
            jitterMs = jitter,
            packetLossPercent = packetLoss,
            phase = SpeedTestPhase.DOWNLOAD,
            progress = 0.25f
        )

        // Phase 2: Download Speed (sustained transfer up to 7 seconds using monotonic time)
        var totalBytesReceived = 0L
        val downloadStartNano = System.nanoTime()
        var lastReportNano = downloadStartNano
        val maxDownloadDurationNano = 7_000_000_000L // 7 seconds in nanoseconds
        val downloadUrl = "https://speed.cloudflare.com/__down?bytes=50000000"

        try {
            while (currentCoroutineContext().isActive &&
                activeTestId.get() == testId &&
                (System.nanoTime() - downloadStartNano) < maxDownloadDurationNano
            ) {
                val req = Request.Builder().url(downloadUrl).build()
                val call = client.newCall(req)
                registerCall(call)
                try {
                    call.execute().use { response ->
                        val body = response.body
                        if (response.isSuccessful && body != null) {
                            val stream: InputStream = body.byteStream()
                            val buffer = ByteArray(32768)
                            var bytesRead = 0

                            while (currentCoroutineContext().isActive &&
                                activeTestId.get() == testId &&
                                stream.read(buffer).also { bytesRead = it } != -1
                            ) {
                                totalBytesReceived += bytesRead
                                val nowNano = System.nanoTime()
                                val elapsedNano = nowNano - downloadStartNano

                                if (nowNano - lastReportNano >= 150_000_000L) { // 150ms
                                    val elapsedSec = elapsedNano / 1_000_000_000.0
                                    val curMbps = if (elapsedSec > 0) (totalBytesReceived * 8.0) / (elapsedSec * 1_000_000.0) else 0.0
                                    val progress = 0.25f + (0.45f * (elapsedNano.toFloat() / maxDownloadDurationNano)).coerceAtMost(0.45f)
                                    _testResult.value = _testResult.value.copy(
                                        currentSpeedMbps = curMbps,
                                        downloadSpeedMbps = curMbps,
                                        progress = progress
                                    )
                                    lastReportNano = nowNano
                                }

                                if (elapsedNano >= maxDownloadDurationNano) {
                                    break
                                }
                            }
                        } else {
                            if (totalBytesReceived == 0L) {
                                throw IllegalStateException("Download server returned HTTP ${response.code}")
                            }
                        }
                    }
                } finally {
                    unregisterCall(call)
                }
            }

            if (activeTestId.get() != testId) return@withContext

            val totalElapsedSec = ((System.nanoTime() - downloadStartNano).coerceAtLeast(100_000_000L)) / 1_000_000_000.0
            val finalCalculatedDownload = (totalBytesReceived * 8.0) / (totalElapsedSec * 1_000_000.0)
            _testResult.value = _testResult.value.copy(
                currentSpeedMbps = finalCalculatedDownload,
                downloadSpeedMbps = finalCalculatedDownload,
                progress = 0.70f
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive || activeTestId.get() != testId) return@withContext
            _testResult.value = _testResult.value.copy(
                phase = SpeedTestPhase.ERROR,
                errorMessage = "Download test failed: ${e.localizedMessage ?: "Connection error"}",
                testFinished = true
            )
            return@withContext
        } finally {
            cancelAllCalls()
        }

        if (!currentCoroutineContext().isActive || activeTestId.get() != testId) return@withContext

        val finalDownloadSpeed = _testResult.value.downloadSpeedMbps

        // Phase 3: Upload Speed (sustained with controlled concurrency = 2)
        _testResult.value = _testResult.value.copy(
            phase = SpeedTestPhase.UPLOAD,
            progress = 0.70f
        )

        val totalBytesUploaded = AtomicLong(0L)
        val uploadStartNano = System.nanoTime()
        val uploadUrl = "https://speed.cloudflare.com/__up"
        val maxUploadDurationNano = 6_000_000_000L // 6 seconds
        val uploadChunk = ByteArray(524288) { 0x41 } // 512 KB chunk
        val concurrency = 2

        try {
            coroutineScope {
                // Reporter loop
                val reporter = launch {
                    while (isActive && activeTestId.get() == testId) {
                        delay(150)
                        val elapsedNano = (System.nanoTime() - uploadStartNano).coerceAtLeast(100_000_000L)
                        val elapsedSec = elapsedNano / 1_000_000_000.0
                        val currentAvgSpeed = (totalBytesUploaded.get() * 8.0) / (elapsedSec * 1_000_000.0)
                        val progress = 0.70f + (0.28f * (elapsedNano.toFloat() / maxUploadDurationNano)).coerceAtMost(0.28f)
                        _testResult.value = _testResult.value.copy(
                            currentSpeedMbps = currentAvgSpeed,
                            uploadSpeedMbps = currentAvgSpeed,
                            progress = progress
                        )
                    }
                }

                // Parallel upload streams
                val workers = (1..concurrency).map {
                    launch {
                        while (isActive &&
                            activeTestId.get() == testId &&
                            (System.nanoTime() - uploadStartNano) < maxUploadDurationNano
                        ) {
                            try {
                                val reqBody = uploadChunk.toRequestBody()
                                val req = Request.Builder().url(uploadUrl).post(reqBody).build()
                                val call = client.newCall(req)
                                registerCall(call)
                                try {
                                    call.execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            totalBytesUploaded.addAndGet(uploadChunk.size.toLong())
                                        }
                                    }
                                } finally {
                                    unregisterCall(call)
                                }
                            } catch (e: CancellationException) {
                                break
                            } catch (_: Exception) {
                                delay(50)
                            }
                        }
                    }
                }

                while (workers.any { it.isActive } && (System.nanoTime() - uploadStartNano) < maxUploadDurationNano) {
                    delay(100)
                }

                cancelAllCalls()
                reporter.cancel()
                workers.forEach { it.cancel() }
            }

            if (activeTestId.get() != testId) return@withContext

            if (totalBytesUploaded.get() == 0L) {
                throw IllegalStateException("Upload server unreachable or request failed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive || activeTestId.get() != testId) return@withContext
            _testResult.value = _testResult.value.copy(
                phase = SpeedTestPhase.ERROR,
                errorMessage = "Upload test failed: ${e.localizedMessage ?: "Connection error"}",
                testFinished = true
            )
            return@withContext
        } finally {
            cancelAllCalls()
        }

        if (!currentCoroutineContext().isActive || activeTestId.get() != testId) return@withContext

        val finalUploadElapsedSec = ((System.nanoTime() - uploadStartNano).coerceAtLeast(100_000_000L)) / 1_000_000_000.0
        val finalUploadSpeed = (totalBytesUploaded.get() * 8.0) / (finalUploadElapsedSec * 1_000_000.0)

        _testResult.value = _testResult.value.copy(
            phase = SpeedTestPhase.COMPLETED,
            progress = 1.0f,
            currentSpeedMbps = finalDownloadSpeed,
            downloadSpeedMbps = finalDownloadSpeed,
            uploadSpeedMbps = finalUploadSpeed,
            testFinished = true,
            errorMessage = null
        )
    }

    fun cancel() {
        activeTestId.incrementAndGet()
        cancelAllCalls()
        _testResult.value = SpeedTestResult(
            phase = SpeedTestPhase.IDLE,
            progress = 0f,
            testFinished = false
        )
    }

    fun reset() {
        cancel()
    }
}
