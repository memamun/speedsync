package com.memamun.speedsync.network

import com.memamun.speedsync.model.SpeedTestPhase
import com.memamun.speedsync.model.SpeedTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToLong

data class SpeedTestEndpoints(
    val pingEndpoints: List<String> = listOf(
        "https://www.google.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace",
        "https://www.gstatic.com/generate_204",
        "https://www.google.com/generate_204"
    ),
    val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=50000000",
    val uploadUrl: String = "https://speed.cloudflare.com/__up"
)

class SpeedTestEngine(
    private val client: OkHttpClient = defaultClient,
    private val endpoints: SpeedTestEndpoints = SpeedTestEndpoints(),
    private val downloadDurationMs: Long = 7000L,
    private val uploadDurationMs: Long = 6000L
) {

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }

    private val _testResult = MutableStateFlow(SpeedTestResult())
    val testResult: StateFlow<SpeedTestResult> = _testResult.asStateFlow()

    private val sessionCounter = AtomicLong(0L)
    private val activeSession = AtomicReference<TestSession?>(null)

    /**
     * Dedicated isolated test session holding its own request registry and cancellation scope.
     */
    internal class TestSession(
        val id: Long,
        parentJob: Job?
    ) {
        val isCancelled = AtomicBoolean(false)
        val requestRegistry = CopyOnWriteArrayList<Call>()
        val job = SupervisorJob()
        val sessionScope = CoroutineScope(job + Dispatchers.IO)

        init {
            parentJob?.invokeOnCompletion {
                cancelSession()
            }
        }

        fun registerCall(call: Call): Boolean {
            if (isCancelled.get() || !sessionScope.isActive) {
                try {
                    call.cancel()
                } catch (_: Exception) {}
                return false
            }
            requestRegistry.add(call)
            return true
        }

        fun unregisterCall(call: Call) {
            requestRegistry.remove(call)
        }

        fun cancelAllRequests() {
            for (call in requestRegistry) {
                try {
                    call.cancel()
                } catch (_: Exception) {}
            }
            requestRegistry.clear()
        }

        fun cancelSession() {
            if (isCancelled.compareAndSet(false, true)) {
                cancelAllRequests()
                job.cancel()
            }
        }
    }

    private fun isSessionActive(session: TestSession): Boolean {
        return activeSession.get() === session && !session.isCancelled.get()
    }

    private fun publishState(session: TestSession, transform: (SpeedTestResult) -> SpeedTestResult) {
        if (isSessionActive(session)) {
            _testResult.value = transform(_testResult.value)
        }
    }

    suspend fun runSpeedTest() = withContext(Dispatchers.IO) {
        // Cancel and clean up any prior active session
        val previousSession = activeSession.getAndSet(null)
        previousSession?.cancelSession()

        val parentJob = currentCoroutineContext()[Job]
        val session = TestSession(sessionCounter.incrementAndGet(), parentJob)
        activeSession.set(session)

        publishState(session) {
            SpeedTestResult(
                phase = SpeedTestPhase.PING,
                progress = 0.05f
            )
        }

        try {
            // Phase 1: Ping & Jitter
            val pingTimes = mutableListOf<Long>()
            var failedPings = 0
            val pingEndpoints = endpoints.pingEndpoints

            for ((index, url) in pingEndpoints.withIndex()) {
                if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext
                val startNano = System.nanoTime()
                try {
                    val req = Request.Builder().url(url).head().build()
                    val call = client.newCall(req)
                    if (session.registerCall(call)) {
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
                            session.unregisterCall(call)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failedPings++
                }

                if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext

                val progress = 0.05f + (0.15f * (index + 1) / pingEndpoints.size)
                publishState(session) { current ->
                    current.copy(
                        progress = progress,
                        pingMs = if (pingTimes.isNotEmpty()) pingTimes.average().roundToLong() else 0L
                    )
                }
                delay(100)
            }

            if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext

            if (pingTimes.isEmpty()) {
                publishState(session) { current ->
                    current.copy(
                        phase = SpeedTestPhase.ERROR,
                        progress = 0f,
                        errorMessage = "Network unreachable. Check your internet connection.",
                        testFinished = true
                    )
                }
                return@withContext
            }

            val avgPing = pingTimes.average().roundToLong()
            var jitterSum = 0L
            for (i in 0 until pingTimes.size - 1) {
                jitterSum += abs(pingTimes[i] - pingTimes[i + 1])
            }
            val jitter = if (pingTimes.size > 1) jitterSum / (pingTimes.size - 1) else 0L
            val packetLoss = if (pingEndpoints.isNotEmpty()) (failedPings.toDouble() / pingEndpoints.size) * 100.0 else 0.0

            publishState(session) { current ->
                current.copy(
                    pingMs = avgPing.coerceAtLeast(1L),
                    jitterMs = jitter,
                    packetLossPercent = packetLoss,
                    phase = SpeedTestPhase.DOWNLOAD,
                    progress = 0.25f
                )
            }

            // Phase 2: Download Speed (sustained transfer bounded by deadline, cancelling active calls)
            var totalBytesReceived = 0L
            val downloadStartNano = System.nanoTime()
            var lastReportNano = downloadStartNano
            val maxDownloadDurationNano = downloadDurationMs * 1_000_000L
            val downloadUrl = endpoints.downloadUrl

            // Watchdog: enforces download phase deadline by actively cancelling blocked calls
            val downloadDeadlineJob = session.sessionScope.launch {
                delay(downloadDurationMs)
                if (isSessionActive(session)) {
                    session.cancelAllRequests()
                }
            }

            try {
                while (isSessionActive(session) &&
                    currentCoroutineContext().isActive &&
                    (System.nanoTime() - downloadStartNano) < maxDownloadDurationNano
                ) {
                    val req = Request.Builder().url(downloadUrl).build()
                    val call = client.newCall(req)
                    if (!session.registerCall(call)) break

                    try {
                        call.execute().use { response ->
                            val body = response.body
                            if (response.isSuccessful && body != null) {
                                val stream: InputStream = body.byteStream()
                                val buffer = ByteArray(32768)
                                var bytesRead = 0

                                while (isSessionActive(session) &&
                                    currentCoroutineContext().isActive &&
                                    stream.read(buffer).also { bytesRead = it } != -1
                                ) {
                                    totalBytesReceived += bytesRead
                                    val nowNano = System.nanoTime()
                                    val elapsedNano = nowNano - downloadStartNano

                                    if (nowNano - lastReportNano >= 150_000_000L) { // 150ms
                                        val elapsedSec = elapsedNano / 1_000_000_000.0
                                        val curMbps = if (elapsedSec > 0) (totalBytesReceived * 8.0) / (elapsedSec * 1_000_000.0) else 0.0
                                        val progress = 0.25f + (0.45f * (elapsedNano.toFloat() / maxDownloadDurationNano)).coerceAtMost(0.45f)
                                        publishState(session) { current ->
                                            current.copy(
                                                currentSpeedMbps = curMbps,
                                                downloadSpeedMbps = curMbps,
                                                progress = progress
                                            )
                                        }
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
                    } catch (e: IOException) {
                        // If calls were cancelled by the deadline watchdog and we have data, we finished sustained transfer
                        val elapsed = System.nanoTime() - downloadStartNano
                        if (elapsed < maxDownloadDurationNano && totalBytesReceived == 0L) {
                            throw e
                        }
                        break
                    } finally {
                        session.unregisterCall(call)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (totalBytesReceived == 0L) {
                    if (isSessionActive(session) && currentCoroutineContext().isActive) {
                        publishState(session) { current ->
                            current.copy(
                                phase = SpeedTestPhase.ERROR,
                                errorMessage = "Download test failed: ${e.localizedMessage ?: "Connection error"}",
                                testFinished = true
                            )
                        }
                    }
                    return@withContext
                }
            } finally {
                downloadDeadlineJob.cancel()
                session.cancelAllRequests()
            }

            if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext

            val totalElapsedSec = ((System.nanoTime() - downloadStartNano).coerceAtLeast(100_000_000L)) / 1_000_000_000.0
            val finalCalculatedDownload = (totalBytesReceived * 8.0) / (totalElapsedSec * 1_000_000.0)
            publishState(session) { current ->
                current.copy(
                    currentSpeedMbps = finalCalculatedDownload,
                    downloadSpeedMbps = finalCalculatedDownload,
                    progress = 0.70f
                )
            }

            val finalDownloadSpeed = _testResult.value.downloadSpeedMbps

            // Phase 3: Upload Speed (sustained with controlled concurrency = 2 and deadline enforcement)
            publishState(session) { current ->
                current.copy(
                    phase = SpeedTestPhase.UPLOAD,
                    progress = 0.70f
                )
            }

            val totalBytesUploaded = AtomicLong(0L)
            val uploadStartNano = System.nanoTime()
            val uploadUrl = endpoints.uploadUrl
            val maxUploadDurationNano = uploadDurationMs * 1_000_000L
            val uploadChunk = ByteArray(524288) { 0x41 } // 512 KB chunk
            val concurrency = 2

            // Watchdog: enforces upload phase deadline by actively cancelling blocked calls
            val uploadDeadlineJob = session.sessionScope.launch {
                delay(uploadDurationMs)
                if (isSessionActive(session)) {
                    session.cancelAllRequests()
                }
            }

            try {
                // Reporter loop
                val reporter = session.sessionScope.launch {
                    while (isActive && isSessionActive(session)) {
                        delay(150)
                        val elapsedNano = (System.nanoTime() - uploadStartNano).coerceAtLeast(100_000_000L)
                        val elapsedSec = elapsedNano / 1_000_000_000.0
                        val currentAvgSpeed = (totalBytesUploaded.get() * 8.0) / (elapsedSec * 1_000_000.0)
                        val progress = 0.70f + (0.28f * (elapsedNano.toFloat() / maxUploadDurationNano)).coerceAtMost(0.28f)
                        publishState(session) { current ->
                            current.copy(
                                currentSpeedMbps = currentAvgSpeed,
                                uploadSpeedMbps = currentAvgSpeed,
                                progress = progress
                            )
                        }
                    }
                }

                // Parallel upload streams
                val workers = (1..concurrency).map {
                    session.sessionScope.launch {
                        while (isActive &&
                            isSessionActive(session) &&
                            (System.nanoTime() - uploadStartNano) < maxUploadDurationNano
                        ) {
                            try {
                                val reqBody = uploadChunk.toRequestBody()
                                val req = Request.Builder().url(uploadUrl).post(reqBody).build()
                                val call = client.newCall(req)
                                if (!session.registerCall(call)) break
                                try {
                                    call.execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            totalBytesUploaded.addAndGet(uploadChunk.size.toLong())
                                        }
                                    }
                                } finally {
                                    session.unregisterCall(call)
                                }
                            } catch (_: CancellationException) {
                                break
                            } catch (_: IOException) {
                                // Deadline cancellation or network error
                                break
                            } catch (_: Exception) {
                                delay(50)
                            }
                        }
                    }
                }

                // Wait for workers to finish or deadline to pass
                while (workers.any { it.isActive } &&
                    isSessionActive(session) &&
                    (System.nanoTime() - uploadStartNano) < maxUploadDurationNano
                ) {
                    delay(100)
                }

                uploadDeadlineJob.cancel()
                session.cancelAllRequests()
                reporter.cancel()
                workers.forEach { it.cancel() }

                if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext

                if (totalBytesUploaded.get() == 0L) {
                    throw IllegalStateException("Upload server unreachable or request failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext
                publishState(session) { current ->
                    current.copy(
                        phase = SpeedTestPhase.ERROR,
                        errorMessage = "Upload test failed: ${e.localizedMessage ?: "Connection error"}",
                        testFinished = true
                    )
                }
                return@withContext
            } finally {
                uploadDeadlineJob.cancel()
                session.cancelAllRequests()
            }

            if (!isSessionActive(session) || !currentCoroutineContext().isActive) return@withContext

            val finalUploadElapsedSec = ((System.nanoTime() - uploadStartNano).coerceAtLeast(100_000_000L)) / 1_000_000_000.0
            val finalUploadSpeed = (totalBytesUploaded.get() * 8.0) / (finalUploadElapsedSec * 1_000_000.0)

            publishState(session) { current ->
                current.copy(
                    phase = SpeedTestPhase.COMPLETED,
                    progress = 1.0f,
                    currentSpeedMbps = finalDownloadSpeed,
                    downloadSpeedMbps = finalDownloadSpeed,
                    uploadSpeedMbps = finalUploadSpeed,
                    testFinished = true,
                    errorMessage = null
                )
            }
        } finally {
            session.cancelSession()
        }
    }

    fun cancel() {
        val session = activeSession.getAndSet(null)
        session?.cancelSession()
        _testResult.value = SpeedTestResult(
            phase = SpeedTestPhase.IDLE,
            progress = 0f,
            testFinished = false
        )
    }

    fun reset() {
        cancel()
    }

    internal fun getActiveSessionForTesting(): TestSession? = activeSession.get()
}
