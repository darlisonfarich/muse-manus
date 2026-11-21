package com.wangzan101.mika.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.remote.RemoteOwnership
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.McpSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class GadgetDirectSessionTest {
    @Test
    fun registrationOwnsItsSessionWithoutMcpAndReleasesOnlyItsSlot() = runBlocking<Unit> {
        DeviceSupport.shell()
        DeviceSupport.service()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gadget-session-test").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = McpSettings(directory, AesGcmKeyCipher { key }).apply { setEnabled(true) }
        val host = AndroidDirectPhoneHost(context, settings)
        val args = JsonObject(emptyMap())
        val link = GadgetPhoneLease("device", "host", "account", "vm").apply { connected = true }
        val other = GadgetPhoneLease("device", "host", "account", "other-vm").apply { connected = true }
        try {
            assertTrue(host.maintainGadget(link))
            assertNotNull(host.gadgetReadScope(link))
            assertNull(host.gadgetReadScope(other))
            assertNull(settings.existingToken())
            assertFalse(host.readForGadget(link, false, true).getValue("hasWindow").jsonPrimitive.boolean)
            assertTrue(host.call(RemoteOwnership.USB, "phone_observe", args).getValue("ok").jsonPrimitive.boolean)
            host.close() // stopping the bridge must leave the registered gadget usable
            assertNotNull(host.gadgetReadScope(link))
            assertFalse(host.call(RemoteOwnership.USB, "phone_status", args).getValue("active").jsonPrimitive.boolean)
            link.connected = false
            assertNull(host.gadgetReadScope(link))
            assertEquals(ErrorCode.UNAVAILABLE, runCatching { host.readForGadget(link, false, false) }
                .exceptionOrNull().let { (it as CommandException).code })
            host.releaseGadget(link)
            assertTrue(host.maintainGadget(other))
            assertNull(host.gadgetReadScope(link))
            settings.setEnabled(false)
            assertFalse(host.maintainGadget(other))
        } finally {
            host.releaseGadget(link)
            host.releaseGadget(other)
            host.close()
            directory.deleteRecursively()
        }
    }
}
