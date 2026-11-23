package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hard rule of the owner: no path of the app turns Wi-Fi (or any radio) on or off by itself.
 * This scans the production sources (app + core) for the APIs and commands that would do it, and pins the
 * only system-setting writes that exist, so a new one cannot slip in without this test being changed on purpose.
 */
class NoRadioChangeTest {
    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("repo.root") ?: System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return requireNotNull(d) { "repo root not found" }
    }

    private fun sources(): List<File> {
        val root = repoRoot()
        return listOf("app/src/main", "core/src/main").map { File(root, it) }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml", "sh") }.toList()
        }
    }

    /** Code only: whole-line comments dropped, so prose about Wi-Fi in a doc comment is not a finding. */
    private fun code(f: File): List<Pair<Int, String>> = f.readLines().mapIndexed { i, l -> (i + 1) to l }
        .filter { (_, l) -> val t = l.trim(); !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("<!--")) }

    private val forbidden = listOf(
        "setWifiEnabled", "WifiManager", "BluetoothAdapter", "NfcAdapter", "setMobileDataEnabled", "AIRPLANE_MODE",
        "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE", "NETWORK_SETTINGS", "BLUETOOTH_PRIVILEGED",
    ).map { Regex("\\b${Regex.escape(it)}\\b") } + listOf(
        Regex("svc\\s+(wifi|bluetooth|data|nfc)"),
        Regex("cmd\\s+(wifi|bluetooth_manager|connectivity)"),
        Regex("settings\\s+put\\s+global\\s+(wifi|bluetooth|airplane|mobile|nfc)"),
        Regex("Settings\\.Panel\\.ACTION_(WIFI|INTERNET_CONNECTIVITY|NFC)"),
    )

    @Test fun noSourceUsesAnApiOrCommandThatChangesAWifiOrRadioState() {
        val findings = sources().flatMap { f ->
            code(f).filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }.map { (n, l) -> "${f.name}:$n: ${l.trim()}" }
        }
        assertEquals("radio-changing API found:\n" + findings.joinToString("\n"), emptyList<String>(), findings)
    }

    @Test fun theOnlySystemSettingWritesAreTheDebuggingSwitchAndTheAccessibilityList() {
        val writes = sources().filter { it.extension == "kt" }.flatMap { f ->
            code(f).filter { (_, l) -> Regex("Settings\\.(Global|Secure|System)\\.put").containsMatchIn(l) }
                .map { (_, l) -> f.name to l.trim() }
        }
        assertTrue("expected the known writers", writes.isNotEmpty())
        // adb_wifi_enabled is the wireless-DEBUGGING switch (an adbd service flag); it does not turn the Wi-Fi radio on or off.
        val allowedKeys = listOf("ADB_WIFI_ENABLED", "ENABLED_ACCESSIBILITY_SERVICES", "ACCESSIBILITY_ENABLED")
        val offenders = writes.filter { (_, l) -> allowedKeys.none { l.contains(it) } }
        assertEquals("unexpected settings write: $offenders", emptyList<Pair<String, String>>(), offenders)
    }

    @Test fun theManifestDeclaresNoPermissionToChangeWifiOrNetworkState() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        for (p in listOf("CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE", "CHANGE_WIFI_MULTICAST_STATE", "NETWORK_SETTINGS", "WRITE_SETTINGS")) {
            assertTrue("manifest must not declare $p", !Regex("android\\.permission\\.$p\"").containsMatchIn(manifest))
        }
    }
}
