package mu.proxima.ai.triage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmBackendTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val logs = mutableListOf<String>()

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private class FakeModel(val tag: String, val workMs: Long = 0) : LiteRtLmBackend.LoadedModel {
        val running = AtomicInteger(0)
        var maxParallel = 0
        override fun generate(system: String, user: String): String {
            maxParallel = maxOf(maxParallel, running.incrementAndGet())
            Thread.sleep(workMs)
            running.decrementAndGet()
            return "$tag:$user"
        }
        override fun close() {}
    }

    private fun backend(loader: (File) -> LiteRtLmBackend.LoadedModel) =
        LiteRtLmBackend(dir, scope, { synchronized(logs) { logs += it } }, loader)

    private suspend fun LlmBackend.awaitSettled() = runCatching { generate("s", "probe") }

    @Test fun noModelFile_neverReady_andGenerateFails() = runBlocking {
        var loaded = false
        val b = backend { loaded = true; FakeModel("x") }
        assertTrue(b.awaitSettled().isFailure)
        assertFalse(b.isReady())
        assertFalse(loaded)
        assertTrue(logs.any { "no *.litertlm" in it })
    }

    @Test fun loadsLargestModelFile_thenReady() = runBlocking {
        File(dir, "small.litertlm").writeBytes(ByteArray(10))
        File(dir, "gemma.litertlm").writeBytes(ByteArray(100))
        File(dir, "notes.txt").writeBytes(ByteArray(1000))
        val b = backend { file -> FakeModel(file.name) }
        assertEquals("gemma.litertlm:hello", b.generate("sys", "hello"))
        assertTrue(b.isReady())
    }

    @Test fun loadFailure_notReady_rulesKeepWorking() = runBlocking {
        File(dir, "gemma.litertlm").writeBytes(ByteArray(1))
        val b = backend { error("bad model") }
        assertTrue(b.awaitSettled().isFailure)
        assertFalse(b.isReady())
        assertTrue(logs.any { "failed to load" in it && "bad model" in it })
    }

    @Test fun notReadyWhileLoading() = runBlocking {
        File(dir, "gemma.litertlm").writeBytes(ByteArray(1))
        val b = backend { Thread.sleep(300); FakeModel("g") }
        assertFalse(b.isReady())
        withTimeout(5_000) { while (!b.isReady()) delay(20) }
    }

    @Test fun triageAndGuideShareOneEngine_oneCallAtATime() = runBlocking {
        File(dir, "gemma.litertlm").writeBytes(ByteArray(1))
        val model = FakeModel("g", workMs = 30)
        val b = backend { model }
        (1..4).map { i -> async { b.generate("s", "u$i") } }.awaitAll()
        assertEquals(1, model.maxParallel)
    }

    private class Stub(override val name: String, val ready: () -> Boolean) : LlmBackend {
        override suspend fun isReady() = ready()
        override suspend fun generate(system: String, user: String) = name
    }

    @Test fun firstReady_prefersOrder_andFallsBack() = runBlocking {
        var gemmaReady = false
        val b = FirstReadyBackend(listOf(Stub("gemma") { gemmaReady }, Stub("nano") { true }))
        assertEquals("nano", b.generate("s", "u"))
        gemmaReady = true
        assertEquals("gemma", b.generate("s", "u"))
    }

    @Test fun firstReady_noneReady_orThrowing_isNotReady() = runBlocking {
        val b = FirstReadyBackend(listOf(Stub("a") { error("aicore crash") }, Stub("b") { false }))
        assertFalse(b.isReady())
        assertTrue(runCatching { b.generate("s", "u") }.isFailure)
    }
}
