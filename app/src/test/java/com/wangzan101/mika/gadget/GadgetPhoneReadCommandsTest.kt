package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.commands.CommandCatalog
import com.darlisonfarich.gadgetlink.commands.CommandDispatcher
import com.darlisonfarich.gadgetlink.commands.LocalGate
import com.darlisonfarich.gadgetlink.noise.LinkWire
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.VirtualScreenshot
import com.wangzan101.mika.cap.phone.shell.VirtualWindowProtection
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.mcp.DirectPhoneReads
import com.wangzan101.mika.mcp.EncodedPhoneScreenshot
import com.wangzan101.mika.mcp.encodePhoneScreenshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GadgetPhoneReadCommandsTest {
    private val bounds = listOf(0, 0, 30, 30)

    private fun sample(text: String = "hello", password: Boolean = false) = ScreenSnapshot(
        snapshotId = "snapshot",
        packageName = "test.app",
        elements = listOf(UiElement("@e1", "TextView", text, 2, 3, bounds, null, false, false, false)),
        width = 100,
        height = 200,
        windows = listOf(UiWindowTree(1, "application", 0, null, "test.app", true, true, listOf(
            UiTreeNode("TextView", bounds, text = text, secret = password, resourceId = "test.app:id/label_text"),
        ))),
    )

    private class TestPhone {
        val peeks = AtomicInteger()
        val captures = AtomicInteger()
        @Volatile var scope: String? = "configured-owner-session"
        @Volatile var denied: String? = null
        var snapshot: (Int) -> ScreenSnapshot = { ScreenSnapshot("peek-$it", null, emptyList(), 100, 200, hasWindow = false) }
        var generation: () -> Long = { 1 }
        var protection: () -> VirtualWindowProtection = { VirtualWindowProtection.Clear }
        var occupied = false
        var capture: () -> VirtualScreenshot = { VirtualScreenshot.Png("png".toByteArray()) }
        var encode: (ByteArray, Int) -> EncodedPhoneScreenshot? = { data, limit -> encodePhoneScreenshot(data, limit) { _, _ -> null } }

        val screen = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, method, _ ->
            when (method.name) {
                "peek" -> snapshot(peeks.incrementAndGet())
                "isProtected" -> false
                else -> throw AssertionError("unexpected screen operation ${method.name}")
            }
        } as ScreenPort

        fun reads(port: ScreenPort = screen) = DirectPhoneReads(
            port, { generation() }, { protection() }, { occupied },
            { captures.incrementAndGet(); capture() },
            { data, limit -> encode(data, limit) },
        )

        fun dispatcher(reader: DirectPhoneReads = reads()) = makeDispatcher { screenshot, full ->
            if (screenshot) reader.screenshot() else reader.tree(full)
        }

        fun makeDispatcher(read: suspend (Boolean, Boolean) -> JsonObject) = CommandDispatcher(
            CommandCatalog.museManus(readCommands = GadgetPhoneReadCommands.specs({ scope }, read)),
            LocalGate { denied },
        )
    }

    private fun message(id: String, command: String, detail: String?) = buildJsonObject {
        put("method", "link.invoke")
        put("id", id)
        put("command", command)
        put("params", buildJsonObject { detail?.let { put("detail", it) } })
    }

    private inline fun CommandDispatcher.withDispatcher(test: (CommandDispatcher) -> Unit) {
        try { test(this) } finally { close() }
    }

    private fun CommandDispatcher.invoke(
        id: String = "id-1", command: String = "phone.get_tree", detail: String? = null, noise: String = "noise-1",
    ): JsonObject {
        val replies = LinkedBlockingQueue<JsonObject>()
        onInvokeFromSession(noise, message(id, command, detail)) { replies.offer(it) }
        return checkNotNull(replies.poll(5, TimeUnit.SECONDS))
    }

    private fun JsonObject.payload() = getValue("payload").jsonObject
    private fun JsonObject.error() = getValue("error").jsonPrimitive.content
    private fun JsonObject.detail() = getValue("detail").jsonPrimitive.content

    @Test fun catalogAdvertisesOnlyTwoPhoneReadsByDefaultAndKeepsWriteStubs() {
        val reads = GadgetPhoneReadCommands.specs({ "scope" }) { _, _ -> error("catalog inspection must be passive") }
        val catalog = CommandCatalog.museManus(readCommands = reads)
        assertEquals(listOf("phone.get_tree", "phone.get_screenshot"),
            catalog.announced.filter { it.name.startsWith("phone.") }.map { it.name })
        assertEquals(setOf("detail"), catalog.find("phone.get_tree")!!.optional.keys)
        assertTrue(reads.all { it.implemented && !it.writes && it.timeoutMs == 30_000L })
        assertNull(catalog.find("phone.tap"))
        val measured = CommandCatalog.museManus(includeStubs = true, readCommands = reads)
        assertEquals(27, measured.announcedStubs)
        assertTrue(measured.find("phone.tap")!!.writes)
        assertFalse(measured.find("phone.tap")!!.implemented)
    }

    @Test fun serializesElementsAndFullPassivelyAndRejectsUnknownDetail() {
        val phone = TestPhone().apply { snapshot = { sample().copy(snapshotId = "peek-$it") } }
        phone.dispatcher().withDispatcher { dispatcher ->
            val basic = dispatcher.invoke().payload()
            val full = dispatcher.invoke("id-2", detail = "full").payload()
            assertEquals("peek-1", basic.getValue("snapshotId").jsonPrimitive.content)
            assertEquals("peek-3", full.getValue("snapshotId").jsonPrimitive.content)
            assertEquals(basic["elements"], full["elements"])
            assertFalse("tree" in basic)
            assertTrue(full.getValue("tree").toString().contains("label_text"))
            assertFalse(full.toString().contains("test.app:id/"))
            assertEquals("executor_error", dispatcher.invoke("id-3", detail = "typo").error())
            assertEquals(4, phone.peeks.get())
            assertEquals(0, phone.captures.get())
        }
    }

    @Test fun emptySessionTreeDoesNotAcquireAnythingAndScreenshotIsUnavailable() {
        val attempts = AtomicInteger()
        val lazy = LazyPhoneScreen(100, 200, listApps = { emptyList() }) { attempts.incrementAndGet(); error("acquire") }
        val phone = TestPhone().apply { protection = { VirtualWindowProtection.Empty } }
        phone.dispatcher(phone.reads(lazy)).withDispatcher { dispatcher ->
            val output = dispatcher.invoke(detail = "full").payload()
            assertEquals("false", output.getValue("hasWindow").jsonPrimitive.content)
            assertEquals("[]", output.getValue("elements").toString())
            assertEquals("[]", output.getValue("tree").toString())
            assertEquals("unavailable", dispatcher.invoke("id-2", "phone.get_screenshot").error())
            assertEquals(0, attempts.get())
            assertEquals(0, phone.captures.get())
        }
    }

    @Test fun protectsPasswordFlagSecureAndBlockedApps() {
        val phone = TestPhone().apply { snapshot = { sample("OTP 827364", password = true) } }
        phone.dispatcher().withDispatcher { dispatcher ->
            for ((id, command, detail) in listOf(
                Triple("password-elements", "phone.get_tree", "elements"),
                Triple("password-full", "phone.get_tree", "full"),
                Triple("password-shot", "phone.get_screenshot", null),
            )) {
                val result = dispatcher.invoke(id, command, detail)
                assertEquals("local_gate_denied", result.error())
                assertEquals("needs_user", result.detail())
                assertFalse(result.toString().contains("827364"))
            }
            assertEquals(0, phone.captures.get())
            phone.protection = { VirtualWindowProtection.Secure }
            phone.snapshot = { sample("protected text") }
            for (command in listOf("phone.get_tree", "phone.get_screenshot")) {
                val result = dispatcher.invoke(command, command)
                assertEquals("local_gate_denied", result.error())
                assertEquals("needs_user", result.detail())
                assertFalse(result.toString().contains("protected text"))
            }
            assertEquals(3, phone.peeks.get())
        }
        phone.protection = { VirtualWindowProtection.Clear }
        val policy = AppAccessScreenPort(phone.screen, AppAccessPolicy { AppAccessState(blocked = setOf("test.app")) },
            packages = { listOf("test.app") })
        phone.dispatcher(phone.reads(policy)).withDispatcher { dispatcher ->
            val result = dispatcher.invoke(detail = "full")
            assertEquals("local_gate_denied", result.error())
            assertEquals("app_blocked", result.detail())
            assertFalse(result.toString().contains("test.app"))
        }
    }

    @Test fun mapsPhysicalOccupancyUnknownProtectionAndCaptureFailures() {
        val phone = TestPhone().apply { snapshot = { sample() } }
        phone.dispatcher().withDispatcher { dispatcher ->
            phone.occupied = true
            assertEquals("app_in_use_by_user", dispatcher.invoke("busy", "phone.get_screenshot").error())
            assertEquals(0, phone.captures.get())
            phone.occupied = false
            phone.protection = { VirtualWindowProtection.Unknown }
            assertEquals("executor_error", dispatcher.invoke("unknown").error())
            phone.protection = { VirtualWindowProtection.Clear }
            phone.capture = { VirtualScreenshot.Stale }
            assertEquals("stale_snapshot", dispatcher.invoke("stale", "phone.get_screenshot").error())
            phone.capture = { VirtualScreenshot.Failed }
            assertEquals("executor_error", dispatcher.invoke("failed", "phone.get_screenshot").error())
            phone.capture = { error("private failure") }
            val exception = dispatcher.invoke("exception", "phone.get_screenshot")
            assertEquals("executor_error", exception.error())
            assertFalse(exception.toString().contains("private failure"))
        }
    }

    @Test fun rejectsChangedSnapshotAndRechecksPrivacyAfterEncoding() {
        val phone = TestPhone().apply { snapshot = { sample("value-$it") } }
        phone.dispatcher().withDispatcher { dispatcher ->
            assertEquals("stale_snapshot", dispatcher.invoke("tree").error())
            assertEquals("stale_snapshot", dispatcher.invoke("changed", "phone.get_screenshot").error())
            phone.snapshot = { sample().copy(changedDuringRead = true) }
            assertEquals("stale_snapshot", dispatcher.invoke("during-read").error())
            phone.snapshot = { sample() }
            phone.generation = { phone.peeks.get().toLong() }
            assertEquals("stale_snapshot", dispatcher.invoke("generation").error())
            phone.generation = { 1 }
            phone.encode = { data, _ ->
                phone.protection = { VirtualWindowProtection.Secure }
                EncodedPhoneScreenshot.Image(data, "image/png")
            }
            assertEquals("needs_user", dispatcher.invoke("secure-after-encode", "phone.get_screenshot").detail())
        }
    }

    @Test fun fullWireBudgetRejectsLargeTreeAndIncludesBase64AndInvocationId() {
        val phone = TestPhone().apply { snapshot = { sample("é".repeat(123_000)) } }
        phone.dispatcher().withDispatcher { dispatcher ->
            val oversized = dispatcher.invoke("oversized", detail = "full")
            assertEquals("too_large", oversized.error())
            assertFalse(oversized.toString().contains("é"))
            phone.snapshot = { sample() }
            val target = DirectPhoneReads.GADGET_SCREENSHOT_BYTES
            val image = ByteArray(target) { it.toByte() }
            val attempts = mutableListOf<Int>()
            phone.capture = { VirtualScreenshot.Png(ByteArray(target + 1)) }
            phone.encode = { png, maximum ->
                assertEquals(target, maximum)
                encodePhoneScreenshot(png, maximum) { quality, _ ->
                    attempts += quality
                    if (attempts.size == 3) image else null
                }
            }
            val shot = dispatcher.invoke("á".repeat(126), "phone.get_screenshot")
            assertEquals("link.result", shot.getValue("method").jsonPrimitive.content)
            assertEquals(listOf(78, 70, 62), attempts)
            assertEquals("image/jpeg", shot.payload().getValue("mimeType").jsonPrimitive.content)
            assertArrayEquals(image, Base64.getDecoder().decode(shot.payload().getValue("data").jsonPrimitive.content))
            assertTrue(LinkWire.encodeMessage(shot).size <= CommandDispatcher.MAX_RESULT_BYTES)
            phone.encode = { png, maximum -> encodePhoneScreenshot(png, maximum) { _, limit -> ByteArray(limit + 1) } }
            assertEquals("too_large", dispatcher.invoke("quality-floor", "phone.get_screenshot").error())
        }
    }

    @Test fun dedupCrossesNoiseReconnectButDoesNotCrossSessionOrGateRevocation() {
        val phone = TestPhone().apply { snapshot = { sample().copy(snapshotId = "peek-$it") } }
        phone.dispatcher().withDispatcher { dispatcher ->
            val first = dispatcher.invoke().payload()
            assertEquals(first, dispatcher.invoke(noise = "noise-2").payload())
            assertEquals(2, phone.peeks.get())
            phone.denied = "remote_access_disabled"
            assertEquals("local_gate_denied", dispatcher.invoke().error())
            phone.denied = null
            phone.scope = null
            assertEquals("unavailable", dispatcher.invoke().error())
            phone.scope = "new-owner-session"
            assertNotEquals(first, dispatcher.invoke().payload())
            assertEquals(4, phone.peeks.get())
        }
    }

    @Test fun revokedInFlightSessionCannotDeliverItsResultOrJoinTheNewScope() {
        val phone = TestPhone()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val replies = LinkedBlockingQueue<JsonObject>()
        phone.makeDispatcher { _, _ ->
            started.countDown()
            release.await()
            buildJsonObject { put("secretPayload", "old") }
        }.withDispatcher { dispatcher ->
            dispatcher.onInvokeFromSession("old-noise", message("same", "phone.get_tree", null)) { replies.offer(it) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            phone.scope = null
            assertEquals("unavailable", dispatcher.invoke("same").error())
            release.complete(Unit)
            val oldResult = checkNotNull(replies.poll(5, TimeUnit.SECONDS))
            assertEquals("unavailable", oldResult.error())
            assertFalse(oldResult.toString().contains("secretPayload"))
        }
    }
}
