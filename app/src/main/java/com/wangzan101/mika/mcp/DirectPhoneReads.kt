package com.wangzan101.mika.mcp

import com.darlisonfarich.gadgetlink.commands.CommandException
import com.darlisonfarich.gadgetlink.commands.ErrorCode
import com.wangzan101.mika.cap.phone.a11y.VirtualScreenshot
import com.wangzan101.mika.cap.phone.shell.VirtualWindowProtection
import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.uistate.OtpRedaction
import com.wangzan101.mika.core.uistate.ScreenJson
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Same passive surface and sensitive-screen policy for the direct host and Gadget Link. */
internal class DirectPhoneReads(
    private val screen: ScreenPort,
    private val generation: () -> Long,
    private val windowProtection: suspend () -> VirtualWindowProtection,
    private val inUseByUser: suspend (ScreenSnapshot) -> Boolean,
    private val capture: suspend () -> VirtualScreenshot,
    private val compress: (ByteArray, Int) -> EncodedPhoneScreenshot?,
) {
    suspend fun tree(full: Boolean): JsonObject = guarded { snapshot, protection ->
        requireReadable(snapshot, protection)
        ScreenJson.passivePhoneTree(snapshot, full)
    }

    suspend fun screenshot(): JsonObject = guarded { snapshot, protection ->
        requireReadable(snapshot, protection)
        if (!snapshot.hasWindow && snapshot.packageName == null && snapshot.unreadableApp == null)
            throw CommandException(ErrorCode.UNAVAILABLE)
        when (val image = capture()) {
            VirtualScreenshot.Stale -> throw CommandException(ErrorCode.STALE_SNAPSHOT)
            VirtualScreenshot.Failed -> throw CommandException(ErrorCode.EXECUTOR_ERROR)
            is VirtualScreenshot.Png -> when (val encoded = compress(image.bytes, GADGET_SCREENSHOT_BYTES)) {
                null -> throw CommandException(ErrorCode.EXECUTOR_ERROR)
                EncodedPhoneScreenshot.TooLarge -> throw CommandException(ErrorCode.TOO_LARGE)
                is EncodedPhoneScreenshot.Image -> encoded.json()
            }
        }
    }

    private suspend fun guarded(payload: suspend (ScreenSnapshot, VirtualWindowProtection) -> JsonObject): JsonObject = try {
        val revision = generation()
        val (first, protection) = checkedPeek()
        // Hold the outcome locally until the final checks, even for capture/size failures.
        val result = try {
            Result.success(payload(first, protection))
        } catch (e: CommandException) {
            Result.failure(e)
        }
        val (last, _) = checkedPeek()
        if (generation() != revision || first.copy(snapshotId = last.snapshotId) != last)
            throw CommandException(ErrorCode.STALE_SNAPSHOT)
        result.getOrThrow()
    } catch (e: AppAccessDenied) {
        throw CommandException(ErrorCode.LOCAL_GATE_DENIED, e.code)
    }

    suspend fun checkActionSurface() { guarded { _, _ -> buildJsonObject {} } }

    /**
     * A passive read of a window that exists but cannot be read is not an answer: the window manager shows an app
     * there, accessibility gives no content. Reported as unstable first, so the executor's bounded retry can give a
     * window that is only still opening time to settle; the caller turns the leftover into a specific error. Actions
     * are not held to this: a coordinate tap does not need to read the screen.
     */
    private fun requireReadable(snapshot: ScreenSnapshot, protection: VirtualWindowProtection) {
        if (unreadableWindow(snapshot, protection)) throw CommandException(ErrorCode.STALE_SNAPSHOT, WINDOW_UNREADABLE)
    }

    private suspend fun checkedPeek(): Pair<ScreenSnapshot, VirtualWindowProtection> {
        val protection = windowProtection()
        when (protection) {
            VirtualWindowProtection.Secure -> throw CommandException(ErrorCode.LOCAL_GATE_DENIED, "needs_user")
            VirtualWindowProtection.Unknown -> throw CommandException(ErrorCode.EXECUTOR_ERROR)
            else -> Unit
        }
        val snapshot = screen.peek()
        if (sensitive(screen, snapshot)) throw CommandException(ErrorCode.LOCAL_GATE_DENIED, "needs_user")
        if (inUseByUser(snapshot)) throw CommandException(ErrorCode.APP_IN_USE_BY_USER)
        if (snapshot.changedDuringRead || protection == VirtualWindowProtection.Empty &&
            (snapshot.hasWindow || snapshot.unreadableApp != null || snapshot.windows.isNotEmpty())
        ) throw CommandException(ErrorCode.STALE_SNAPSHOT)
        return snapshot to protection
    }

    companion object {
        const val GADGET_SCREENSHOT_BYTES = 170 * 1024
        const val MCP_SCREENSHOT_BYTES = 600_000

        suspend fun sensitive(screen: ScreenPort): Boolean = sensitive(screen, screen.peek())

        /** Detail of the `stale_snapshot` raised for, and of the final error about, a window that cannot be read. */
        const val WINDOW_UNREADABLE = "window_unreadable"

        /**
         * True when the window manager shows windows on the display (a clear verdict), accessibility enumerates an
         * app window, and no readable content of it came back. A readable window with no labelled node (a game
         * canvas) is still an answer and stays one.
         */
        fun unreadableWindow(snapshot: ScreenSnapshot, protection: VirtualWindowProtection): Boolean =
            protection == VirtualWindowProtection.Clear && snapshot.hasWindow &&
                snapshot.packageName == null && snapshot.elements.isEmpty()

        private suspend fun sensitive(screen: ScreenPort, snapshot: ScreenSnapshot): Boolean =
            snapshot.protected || snapshot.captureError != null ||
                OtpRedaction.hasPasswordField(snapshot) || screen.isProtected()
    }
}

internal sealed interface EncodedPhoneScreenshot {
    data object TooLarge : EncodedPhoneScreenshot
    class Image(val bytes: ByteArray, private val mime: String) : EncodedPhoneScreenshot {
        fun json(): JsonObject = buildJsonObject {
            put("ok", true)
            put("mimeType", mime)
            put("data", Base64.getEncoder().encodeToString(bytes))
            put("sizeBytes", bytes.size)
        }
    }
}

/** Pure policy shared by MCP and Gadget Link. Android owns decoding, scaling and bitmap lifetimes. */
internal inline fun encodePhoneScreenshot(
    png: ByteArray,
    maxBytes: Int,
    jpeg: (quality: Int, maxBytes: Int) -> ByteArray?,
): EncodedPhoneScreenshot {
    if (png.size <= maxBytes) return EncodedPhoneScreenshot.Image(png, "image/png")
    var quality = 78
    repeat(9) {
        jpeg(quality, maxBytes)?.takeIf { it.size <= maxBytes }?.let { return EncodedPhoneScreenshot.Image(it, "image/jpeg") }
        quality = (quality - 8).coerceAtLeast(30)
    }
    return EncodedPhoneScreenshot.TooLarge
}
