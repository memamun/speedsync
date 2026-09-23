package com.memamun.speedsync.network

import com.memamun.speedsync.model.SpeedTestPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SpeedTestEngineTest {

    @Test
    fun testCancelImmediatelyResetsStateAndCancelsRequests() = runBlocking(Dispatchers.Default) {
        val requestStartedLatch = CountDownLatch(1)
        val callCancelled = AtomicBoolean(false)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                requestStartedLatch.countDown()
                while (!call.isCanceled()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                callCancelled.set(true)
                throw IOException("Canceled")
            }
            .build()

        val engine = SpeedTestEngine(
            client = client,
            downloadDurationMs = 200L,
            uploadDurationMs = 200L
        )

        val job = launch {
            engine.runSpeedTest()
        }

        // Wait until the first request actually starts and registers in the session
        assertTrue("Request should start", requestStartedLatch.await(3, TimeUnit.SECONDS))
        val session = engine.getActiveSessionForTesting()
        assertNotNull("Session should be active", session)
        assertFalse("Session should not be cancelled yet", session!!.isCancelled.get())

        // Cancel test
        engine.cancel()
        job.join()

        assertTrue("Session must be cancelled", session.isCancelled.get())
        assertTrue("Call must have received cancellation", callCancelled.get())
        assertEquals(SpeedTestPhase.IDLE, engine.testResult.value.phase)
        assertEquals(0f, engine.testResult.value.progress, 0.001f)
    }

    @Test
    fun testStalledResponsesEnforceDeadlineByCancellingRequests() = runBlocking(Dispatchers.Default) {
        val requestStartedLatch = CountDownLatch(1)
        val callCancelled = AtomicBoolean(false)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                val url = chain.request().url.toString()
                if (url.contains("generate_204") || url.contains("ping")) {
                    return@addInterceptor Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }

                // Simulate stalled download
                requestStartedLatch.countDown()
                while (!call.isCanceled()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                callCancelled.set(true)
                throw IOException("Canceled by deadline")
            }
            .build()

        // 300ms download deadline
        val engine = SpeedTestEngine(
            client = client,
            endpoints = SpeedTestEndpoints(
                pingEndpoints = listOf("https://fake.ping/1"),
                downloadUrl = "https://fake.download/stream",
                uploadUrl = "https://fake.upload/"
            ),
            downloadDurationMs = 300L,
            uploadDurationMs = 200L
        )

        val startTime = System.currentTimeMillis()
        val job = launch {
            engine.runSpeedTest()
        }

        assertTrue("Download request should start", requestStartedLatch.await(3, TimeUnit.SECONDS))
        job.join()
        val totalDuration = System.currentTimeMillis() - startTime

        assertTrue("Stalled request should have been cancelled by deadline", callCancelled.get())
        assertTrue("Total execution must terminate promptly when deadline triggers (took ${totalDuration}ms)", totalDuration < 4000L)
    }

    @Test
    fun testOverlappingRunsCancelPreviousSessionAndDoNotCorruptNewState() = runBlocking(Dispatchers.Default) {
        val activeRunNumber = AtomicInteger(0)
        val run1StartedLatch = CountDownLatch(1)
        val run2StartedLatch = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                val currentRun = activeRunNumber.get()
                if (currentRun == 1) {
                    run1StartedLatch.countDown()
                } else if (currentRun == 2) {
                    run2StartedLatch.countDown()
                }

                while (!call.isCanceled()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                throw IOException("Canceled")
            }
            .build()

        val engine = SpeedTestEngine(
            client = client,
            downloadDurationMs = 500L,
            uploadDurationMs = 500L
        )

        // Launch Run 1
        activeRunNumber.set(1)
        val job1 = launch { engine.runSpeedTest() }
        assertTrue("Run 1 must start", run1StartedLatch.await(3, TimeUnit.SECONDS))
        val session1 = engine.getActiveSessionForTesting()
        assertNotNull("Session 1 must exist", session1)
        assertFalse("Session 1 must not be cancelled yet", session1!!.isCancelled.get())

        // Launch Run 2 while Run 1 is running
        activeRunNumber.set(2)
        val job2 = launch { engine.runSpeedTest() }
        assertTrue("Run 2 must start", run2StartedLatch.await(3, TimeUnit.SECONDS))
        val session2 = engine.getActiveSessionForTesting()
        assertNotNull("Session 2 must exist", session2)

        assertNotEquals("Run 2 must have a distinct session ID", session1.id, session2!!.id)
        assertTrue("Session 1 must be cancelled by the launch of Run 2", session1.isCancelled.get())
        assertEquals("Active session must now be session 2", session2.id, engine.getActiveSessionForTesting()?.id)

        // Cancel engine cleanly
        engine.cancel()
        job1.join()
        job2.join()

        assertTrue(session2.isCancelled.get())
        assertEquals(SpeedTestPhase.IDLE, engine.testResult.value.phase)
    }
}
