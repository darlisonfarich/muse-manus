package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.SecretRegions
import com.wangzan101.mika.core.uistate.normalize
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenText
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * 截下用户此刻在主屏上看着的那一屏,连同前台 App 和屏上的字(见 [UserScreenText]:先读无障碍树,不够用时认画面上的字)。
 * 屏上有防截屏窗口(截图报错,或截出来几乎全黑而屏上有 App 窗口)时不留画面,只记下是哪个 App、截不到。
 * Mika 自己的浮层要不要先藏起来由调用方定(见 [OverlayWindow.hiddenWhile]);读树时浮层、输入法本来就不算。
 */
internal object MainScreenCapture {
    private const val TAG = "MikaScreenCapture"
    private const val JPEG_QUALITY = 88
    /** 认字最多等这么久:超时就只用树里的字。 */
    private const val OCR_WAIT_MS = 4_000L

    sealed interface Result {
        class Captured(val shot: CapturedScreen) : Result
        /** 前台是 Mika 自己:不截。 */
        data object Self : Result
        data object Unavailable : Result
    }

    /** 只查看窗口根的归属,不遍历文本;有未知归属时拒绝分享。 */
    fun sourcePackages(service: ScreenAccessibility): Set<String>? {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (windows.isEmpty()) return null
        val packages = windows.map { it.root?.packageName?.toString() }
        if (packages.any { it.isNullOrBlank() }) return null
        return packages.filterNotNull().toSet()
    }

    /** 截图(调用方已把浮层藏好)。读树要在截图的同一时刻:截完马上读。 */
    suspend fun shoot(service: ScreenAccessibility): Pair<ScreenAccessibility.MainShot, ScreenRead?> {
        // A frame captured during a tree/window transition cannot safely be redacted.
        val generation = ScreenAccessibility.windowGeneration()
        val before = runCatching { readTree(service) }.getOrNull()
        val shot = service.takeMainScreenshot()
        val size = (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap
        // Use the ACTUAL screenshot dimensions for normalized rectangles, not overlay inset sizes.
        val after = runCatching { readTree(service, size?.width, size?.height) }.getOrNull()
        val stable = generation == ScreenAccessibility.windowGeneration() && before != null && after != null &&
            before.hash == after.hash && before.passwordField == after.passwordField && after.hasAppWindow &&
            SecretRegions.collect(before.entries.map { it.element }, before.windows) != null &&
            SecretRegions.collect(after.entries.map { it.element }, after.windows) != null
        return shot to after.takeIf { stable }
    }

    /** 截下的画面和树整理成一屏(在后台:编码、认字);[topInset]/[bottomInset] 是状态栏、导航条占掉的像素。 */
    suspend fun build(
        service: ScreenAccessibility, shot: ScreenAccessibility.MainShot, read: ScreenRead?,
        topInset: Int, bottomInset: Int, appLabel: (String) -> String,
    ): Result = withContext(Dispatchers.Default) {
        val pkg = read?.topPackage
        if (pkg == service.packageName) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return@withContext Result.Self
        }
        val now = System.currentTimeMillis()
        val label = pkg?.let(appLabel)
        when (shot) {
            is ScreenAccessibility.MainShot.Failed -> {
                Log.w(TAG, "screenshot failed: ${shot.code}")
                Result.Unavailable
            }
            ScreenAccessibility.MainShot.Secure -> Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
            is ScreenAccessibility.MainShot.Ok -> {
                val bmp = shot.bitmap
                fun blocked() = Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
                try {
                    // No readable, stable accessibility tree => no screenshot and no OCR.
                    val tree = read?.takeIf { it.hasAppWindow } ?: return@withContext blocked()
                    val areas = SecretRegions.collect(tree.entries.map { it.element }, tree.windows)
                        ?: return@withContext blocked()
                    val safe = SecretBitmap.redact(bmp, areas) ?: return@withContext blocked()
                    try {
                        val jpeg = ByteArrayOutputStream().also { safe.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                        // Treat a fully black/secure image as unavailable; never send the raw frame.
                        if (BitmapImageOps.isMostlyBlack(jpeg)) return@withContext blocked()
                        val snap = snapshotOf(tree, bmp.width, bmp.height)
                        val ocr = if (UserScreenText.needsOcr(snap)) recognize(safe, areas) else null
                        val (lines, fromOcr) = UserScreenText.lines(snap, ocr,
                            top = normalize(topInset, bmp.height),
                            bottom = normalize(bmp.height - bottomInset, bmp.height))
                        Result.Captured(CapturedScreen(jpeg, UserScreen("", now, pkg, label, lines = lines, fromOcr = fromOcr)))
                    } finally {
                        if (safe !== bmp) safe.recycle()
                    }
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** 主屏上属于 App 的窗口(状态栏、导航栏、输入法、浮层不算)读成元素。 */
    private fun readTree(service: ScreenAccessibility, width: Int? = null, height: Int? = null): ScreenRead {
        // Include system windows so password fields inside system dialogs are redacted too.
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
        val m = service.resources.displayMetrics
        val size = runCatching { service.overlays().area() }.getOrNull()
        return ScreenReader.read(windows, width ?: size?.width ?: m.widthPixels,
            height ?: size?.height ?: m.heightPixels)
    }

    private fun snapshotOf(r: ScreenRead, width: Int, height: Int) = ScreenSnapshot(
        snapshotId = "user-screen", packageName = r.topPackage, elements = r.entries.map { it.element },
        width = width, height = height, hasWindow = r.anyAppWindow, windows = r.windows,
    )

    private suspend fun recognize(bmp: Bitmap, areas: List<SecretRegions.Box>) = try {
        withTimeoutOrNull(OCR_WAIT_MS) { PhoneOcr.read(bmp.copy(Bitmap.Config.ARGB_8888, false), areas) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "text recognition failed", e)
        null
    }
}
