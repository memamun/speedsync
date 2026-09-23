package com.memamun.speedsync.network

import com.memamun.speedsync.model.SpeedTestPhase
import com.memamun.speedsync.model.SpeedTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit
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

    @Volatile
    private var activeCall: Call? = null

    suspend fun runSpeedTest() = withContext(Dispatchers.IO) {
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
            if (!currentCoroutineContext().isActive) break
            val start = System.currentTimeMillis()
            try {
                val req = Request.Builder().url(url).head().build()
                val call = client.newCall(req)
                activeCall = call
                call.execute().use { resp ->
                    if (resp.isSuccessful) {
                        val duration = System.currentTimeMillis() - start
                        pingTimes.add(duration)
                    } else {
                        failedPings++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedPings++
            } finally {
                activeCall = null
            }
            val progress = 0.05f + (0.15f * (index + 1) / pingEndpoints.size)
            _testResult.value = _testResult.value.copy(
                progress = progress,
                pingMs = if (pingTimes.isNotEmpty()) pingTimes.average().roundToLong() else 0L
            )
            delay(120)
        }

        if (!currentCoroutineContext().isActive) return@withContext

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

        // Phase 2: Download Speed
        var totalBytesReceived = 0L
        val downloadStart = System.currentTimeMillis()
        var lastReportTime = downloadStart
        val downloadUrl = "https://speed.cloudflare.com/__down?bytes=25000000"

        try {
            val req = Request.Builder().url(downloadUrl).build()
            val call = client.newCall(req)
            activeCall = call
            call.execute().use { response ->
                val body = response.body
                if (response.isSuccessful && body != null) {
                    val stream: InputStream = body.byteStream()
                    val buffer = ByteArray(16384)
                    var bytesRead = 0
                    val maxTestDuration = 7000L // 7 seconds

                    while (currentCoroutineContext().isActive && stream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesReceived += bytesRead
                        val now = System.currentTimeMillis()
                        val elapsed = now - downloadStart

                        if (now - lastReportTime >= 150) {
                            val curMbps = if (elapsed > 0) (totalBytesReceived * 8.0) / (elapsed * 1000.0) else 0.0
                            val progress = 0.25f + (0.45f * (elapsed.toFloat() / maxTestDuration)).coerceAtMost(0.45f)
                            _testResult.value = _testResult.value.copy(
                                currentSpeedMbps = curMbps,
                                downloadSpeedMbps = curMbps,
                                progress = progress
                            )
                            lastReportTime = now
                        }

                        if (elapsed >= maxTestDuration) {
                            break
                        }
                    }

                    val totalElapsed = (System.currentTimeMillis() - downloadStart).coerceAtLeast(100)
                    val finalCalculatedDownload = (totalBytesReceived * 8.0) / (totalElapsed * 1000.0)
                    _testResult.value = _testResult.value.copy(
                        currentSpeedMbps = finalCalculatedDownload,
                        downloadSpeedMbps = finalCalculatedDownload,
                        progress = 0.70f
                    )
                } else {
                    throw IllegalStateException("Download server returned HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) return@withContext
            _testResult.value = _testResult.value.copy(
                phase = SpeedTestPhase.ERROR,
                errorMessage = "Download test failed: ${e.localizedMessage ?: "Connection error"}",
                testFinished = true
            )
            return@withContext
        } finally {
            activeCall = null
        }

        if (!currentCoroutineContext().isActive) return@withContext

        val finalDownloadSpeed = _testResult.value.downloadSpeedMbps

        // Phase 3: Upload Speed
        _testResult.value = _testResult.value.copy(
            phase = SpeedTestPhase.UPLOAD,
            progress = 0.70f
        )

        var totalBytesUploaded = 0L
        val uploadStart = System.currentTimeMillis()
        val measuredSpeeds = mutableListOf<Double>()
        val uploadUrl = "https://speed.cloudflare.com/__up"
        val maxUploadDuration = 6000L // 6 seconds max

        try {
            val chunk = ByteArray(262144) { 0x41 } // 256 KB chunk
            for (i in 0 until 12) {
                if (!currentCoroutineContext().isActive) break
                val callStart = System.currentTimeMillis()
                try {
                    val reqBody = chunk.toRequestBody()
                    val req = Request.Builder().url(uploadUrl).post(reqBody).build()
                    val call = client.newCall(req)
                    activeCall = call
                    call.execute().use { resp ->
                        if (resp.isSuccessful) {
                            val callDuration = (System.currentTimeMillis() - callStart).coerceAtLeast(1)
                            totalBytesUploaded += chunk.size
                            val currentSpeed = (chunk.size * 8.0) / (callDuration * 1000.0)
                            measuredSpeeds.add(currentSpeed)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // individual chunk fail - continue or abort
                } finally {
                    activeCall = null
                }

                val totalElapsed = (System.currentTimeMillis() - uploadStart).coerceAtLeast(100)
                val currentAvgSpeed = if (measuredSpeeds.isNotEmpty()) {
                    (totalBytesUploaded * 8.0) / (totalElapsed * 1000.0)
                } else {
                    0.0
                }

                val progress = 0.70f + (0.28f * (totalElapsed.toFloat() / maxUploadDuration)).coerceAtMost(0.28f)
                _testResult.value = _testResult.value.copy(
                    currentSpeedMbps = currentAvgSpeed,
                    uploadSpeedMbps = currentAvgSpeed,
                    progress = progress
                )

                if (totalElapsed >= maxUploadDuration) {
                    break
                }
            }

            if (measuredSpeeds.isEmpty()) {
                throw IllegalStateException("Upload server unreachable or request failed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) return@withContext
            _testResult.value = _testResult.value.copy(
                phase = SpeedTestPhase.ERROR,
                errorMessage = "Upload test failed: ${e.localizedMessage ?: "Connection error"}",
                testFinished = true
            )
            return@withContext
        } finally {
            activeCall = null
        }

        if (!currentCoroutineContext().isActive) return@withContext

        val finalUploadElapsed = (System.currentTimeMillis() - uploadStart).coerceAtLeast(100)
        val finalUploadSpeed = if (totalBytesUploaded > 0) {
            (totalBytesUploaded * 8.0) / (finalUploadElapsed * 1000.0)
        } else {
            0.0
        }

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
        try {
            activeCall?.cancel()
        } catch (_: Exception) {}
        activeCall = null
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
