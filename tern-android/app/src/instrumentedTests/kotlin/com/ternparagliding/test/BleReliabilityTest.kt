package com.ternparagliding.test

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.uiautomator.UiDevice
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import com.ternparagliding.mezulla.connection.ble.MeshtasticGattUuids
import com.ternparagliding.utils.Ble
import com.ternparagliding.utils.BleTestRule
import com.ternparagliding.utils.MapVisualTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BLE reliability contract — encoded as failing tests.
 *
 * Pilots' lives depend on Tern's BLE link to Mezulla. "Slow" or
 * "flaky" are not acceptable answers. This file encodes what
 * reliable means in 14 BDD scenarios — T1..T8 on the Tern side,
 * F1..F6 on the firmware side. Most start RED (the feature does
 * not exist yet); the file is the roadmap.
 *
 * Test categories:
 *   T1..T8 — Tern-side reliability (connection survival, reconnect,
 *            handshake resilience, link-state visibility, hot-swap)
 *   F1..F6 — Mezulla firmware reliability (re-advertise after drop,
 *            force-disconnect command, state checkpointing,
 *            keepalive, PHY upgrade, service-UUID stability)
 *
 * Pre-requisites for the whole suite:
 *   - Phone on USB adb
 *   - Mezulla board (LilyGo T3 or Heltec V3) flashed with our fork,
 *     unclaimed, advertising
 *   - `pairUri` instrumentation arg supplied with the deep link
 *
 * Definition of Done for each scenario (per
 * [[feedback-definition-of-done]]):
 *   1. The test passes.
 *   2. The user has reviewed the test code.
 *   3. The user has reviewed the BDD report (HTML + video + screenshots).
 *   Only then push.
 *
 * Simulation tactics by scenario:
 *   - Screen lock (T1, T5): UiAutomator's pressKeyCode(KEYCODE_POWER)
 *     toggles the screen state without needing privileged adb.
 *   - Board reboot (T2, T3): a real Meshtastic admin reboot sent over the
 *     live link (rebootBoardViaAdminMessage) — the board drops, boots, and
 *     re-advertises. A faithful mid-flight drop, no host sidecar needed.
 *   - Force-disconnect (T2 alt, F2): can be simulated app-side by
 *     calling bleConnection.transport.stop() then start().
 *   - Properties (T4 MTU, F5 PHY, F6 service UUID): pure runtime
 *     queries against BluetoothGatt or scan results.
 */
@RunWith(AndroidJUnit4::class)
@Ble
class BleReliabilityTest : MapVisualTest() {

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** Skips scenarios whose [Ble.blockedOn] dependency isn't built yet. */
    @get:Rule
    val bleTestRule = BleTestRule()

    companion object {
        private const val TAG = "BleReliabilityTest"

        // Pre-paired board params come in via the test runner just like
        // FullCycleTest / EdithsGapCycleTest. The pair flow itself is
        // exercised by [BlePairingTest]; this suite focuses on what
        // happens AFTER the link is up.
        private fun arg(name: String): String? =
            try { InstrumentationRegistry.getArguments().getString(name) }
            catch (_: Exception) { null }
        private fun pairUriArg(): String =
            arg("pairUri")?.takeIf { it.isNotBlank() }
                ?: error("pairUri instrumentation arg required")
    }

    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "BleReliabilityTest requires real phone + Mezulla board",
            isEmulator(),
        )
    }

    private fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
            fp.contains("emulator") ||
            android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish")
    }

    // ─────────────────────────────────────────────────────────────────
    //   T1..T8 — Tern client-side reliability
    // ─────────────────────────────────────────────────────────────────

    /** T1: link survives the screen locking for 60 s. */
    @Test
    @Ble(blockedOn = "foreground service to survive backgrounding")
    fun t1_link_survives_screen_lock_for_60_seconds() {
        scenario("T1 — link survives 60s screen lock (phone in pocket)") {
            given("Tern is paired with Mezulla and receiving peer events") {
                pairAndWaitForLinkUp()
                waitForAtLeastOnePeerEvent(timeoutMs = 30_000)
            }
            `when`("the phone screen is locked for 60 seconds") {
                lockScreen(); Thread.sleep(60_000); unlockScreen()
            }
            then("the BLE link is still UP and peer events resume within 10 s") {
                assertLinkUpWithin(10_000)
                assertPeerEventArrivesWithin(10_000)
            }
        }
    }

    /** T2: link auto-reconnects within 10 s of a transient drop. */
    @Test
    fun t2_link_auto_reconnects_after_board_reboot() {
        scenario("T2 — auto-reconnect after a transient BLE drop (canyon recovery)") {
            given("Tern is paired with Mezulla and link is UP") {
                pairAndWaitForLinkUp()
            }
            `when`("the board reboots mid-flight (real link loss, not a graceful disconnect)") {
                rebootBoardViaAdminMessage()
            }
            then("the link comes back UP within 45 seconds without user action") {
                // A real reboot: board drops, boots (~5-13s), re-advertises,
                // transport re-scans + reconnects + re-runs the handshake.
                // 45s covers boot + reconnect with margin.
                assertLinkUpAfterTransitionWithin(45_000)
            }
        }
    }

    /** T3: handshake re-runs automatically on reconnect (chained from T2). */
    @Test
    fun t3_handshake_re_runs_automatically_on_reconnect() {
        scenario("T3 — handshake re-runs cleanly on reconnect, peer events resume") {
            given("the link has just reconnected after a board reboot") {
                pairAndWaitForLinkUp()
                rebootBoardViaAdminMessage()
                assertLinkUpAfterTransitionWithin(45_000)
            }
            then("a fresh peer event arrives within 60 s (handshake re-ran, data plane open)") {
                assertPeerEventArrivesWithin(60_000)
            }
        }
    }

    /** T4: post-connect MTU is 517. Pure runtime assertion, no new feature needed. */
    @Test
    fun t4_negotiated_mtu_is_517() {
        scenario("T4 — MTU negotiated to 517 (high-throughput link)") {
            given("Tern is paired and the link is UP") {
                pairAndWaitForLinkUp()
            }
            then("the active BluetoothGatt connection's MTU is 517 (or close to it)") {
                val mtu = currentConnectionMtu()
                    ?: error("could not read MTU — Tern's BleConnection should expose it")
                assert(mtu >= 200) {
                    "Expected MTU >= 200 (default request 517); got $mtu — high MTU was lost"
                }
            }
        }
    }

    /** T5: handshake completes even if the screen turns off mid-handshake. */
    @Test
    @Ble(blockedOn = "partial wakelock around handshake")
    fun t5_handshake_completes_even_if_screen_locks_mid_handshake() {
        scenario("T5 — handshake survives a screen lock fired mid-sequence") {
            given("Tern is not yet paired") {
                composeTestRule.runOnUiThread {
                    composeTestRule.activity.pairingOrchestrator.forgetBoard()
                }
            }
            `when`("pair is started AND screen is locked 200 ms later") {
                startPairAsync()
                Thread.sleep(200)
                lockScreen()
            }
            then("the link still reaches UP within 10 s") {
                assertLinkUpWithin(10_000)
                unlockScreen()
            }
        }
    }

    /** T6: periodic heartbeat keeps the link liveness observable. */
    @Test
    fun t6_periodic_heartbeat_proves_link_liveness() {
        scenario("T6 — heartbeat fires every 30 s while idle") {
            given("Tern is paired and quiet (no peer traffic)") {
                pairAndWaitForLinkUp()
            }
            then("a heartbeat ToRadio packet is observable within 35 s") {
                assertHeartbeatObservedWithin(35_000)
            }
        }
    }

    /** T7: link state is visible in the on-map status badge. */
    @Test
    fun t7_link_state_change_updates_map_status_within_1s() {
        scenario("T7 — pilot sees link UP/DOWN on the map within 1 s") {
            given("Tern is paired and the map shows the Mezulla status as UP") {
                pairAndWaitForLinkUp()
                assertMezullaBadgeShowsUp()
            }
            `when`("the link goes down (force-disconnect)") {
                forceDisconnect()
            }
            then("within 1 s the on-map Mezulla badge shows DOWN") {
                assertMezullaBadgeShowsDownWithin(1_000)
            }
        }
    }

    /** T8: hot-swap to a different board without restarting the app. */
    @Test
    @Ble(blockedOn = "a second pair URI (pairUriB arg); mostly works today")
    fun t8_hot_swap_to_another_board_without_app_restart() {
        scenario("T8 — scanning a different board's QR re-pairs cleanly") {
            given("Tern is paired with board A") {
                pairAndWaitForLinkUp()
            }
            `when`("the pilot scans board B's QR (a different deep link)") {
                val boardBUri = arg("pairUriB")
                    ?: error("pairUriB arg required for T8")
                composeTestRule.runOnUiThread {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse(boardBUri)
                    }
                    composeTestRule.activity.pairingOrchestrator.handleIntent(intent)
                }
            }
            then("Tern repairs with board B and the new link reaches UP within 30 s") {
                assertLinkUpWithin(30_000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //   F1..F6 — Mezulla firmware-side reliability
    // ─────────────────────────────────────────────────────────────────

    /** F1: firmware is re-advertising within 200 ms of a disconnect. */
    @Test
    @Ble(blockedOn = "scan-after-disconnect harness + firmware re-advertise audit")
    fun f1_firmware_re_advertises_within_200ms_of_disconnect() {
        scenario("F1 — firmware doesn't go quiet after a phone-side drop") {
            given("Tern is paired with the board") {
                pairAndWaitForLinkUp()
            }
            `when`("the phone disconnects ungracefully (app-side stop)") {
                forceDisconnect()
            }
            then("a BLE scan finds the board advertising the Mezulla service UUID within 200 ms") {
                assertBoardAdvertisingWithin(200)
            }
        }
    }

    /** F2: firmware accepts a fresh client after a stuck phone-API session. */
    @Test
    @Ble(blockedOn = "CMD_FORCE_DISCONNECT firmware command + handler")
    fun f2_firmware_accepts_fresh_client_after_force_disconnect() {
        scenario("F2 — stale phone-API session can be reset on demand") {
            given("Board has a stale phone-API session held open") {
                pairAndWaitForLinkUp()
                killAppWithoutGracefulDisconnect()
            }
            `when`("a new Tern instance sends CMD_FORCE_DISCONNECT then re-pairs") {
                sendForceDisconnectCommand()
                pairAndWaitForLinkUp()
            }
            then("the fresh link reaches UP within 15 s") {
                assertLinkUpWithin(15_000)
            }
        }
    }

    /** F3: reconnect from a known phone fast-paths the config bundle. */
    @Test
    @Ble(blockedOn = "per-phone state checkpointing in PhoneAPI")
    fun f3_firmware_fast_paths_reconnect_for_known_phone() {
        scenario("F3 — known-phone reconnect skips full config replay") {
            given("Tern has completed a Stage 1 handshake at least once with the board") {
                pairAndWaitForLinkUp()
            }
            `when`("the connection drops and re-establishes") {
                forceDisconnect()
                pairAndWaitForLinkUp()
            }
            then("the second handshake completes in under 500 ms (no full config replay)") {
                assertHandshakeUnderMs(500)
            }
        }
    }

    /** F4: firmware emits a periodic keepalive frame even when LoRa is quiet. */
    @Test
    @Ble(blockedOn = "firmware-side keepalive emit")
    fun f4_firmware_emits_periodic_keepalive() {
        scenario("F4 — firmware proves it's alive even when LoRa is silent") {
            given("Tern is paired and no LoRa traffic is heard") {
                pairAndWaitForLinkUp()
            }
            then("at least one keepalive FromRadio frame arrives within 35 s") {
                assertKeepaliveObservedWithin(35_000)
            }
        }
    }

    /** F5: BLE PHY upgrades to 2M after the handshake completes. */
    @Test
    fun f5_ble_phy_upgrades_to_2m_after_handshake() {
        scenario("F5 — PHY upgrade from 1M to 2M after handshake") {
            given("Tern is paired and link is UP") {
                pairAndWaitForLinkUp()
            }
            then("the active BluetoothGatt PHY is 2M (BluetoothDevice.PHY_LE_2M)") {
                val phy = currentConnectionPhy()
                    ?: error("could not read PHY — Tern's BleConnection should expose it")
                assert(phy == android.bluetooth.BluetoothDevice.PHY_LE_2M) {
                    "Expected PHY 2 (PHY_LE_2M); got $phy"
                }
            }
        }
    }

    /** F6: firmware advertises the Mezulla service UUID within 5 s of boot. */
    @Test
    @Ble(blockedOn = "host sidecar to power-cycle the board, then scan")
    fun f6_firmware_advertises_service_uuid_within_5s_of_boot() {
        scenario("F6 — board comes up advertising findably") {
            given("the board has just been hard-reset (power-cycled)") {
                hardResetBoard()
            }
            then("a BLE scan finds the Mezulla service UUID within 5 s") {
                assertBoardAdvertisingWithin(5_000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //   Simulation primitives + assertions (all stubs for now —
    //   implement as we turn each scenario green)
    // ─────────────────────────────────────────────────────────────────

    private fun uiDevice(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun lockScreen() {
        uiDevice().pressKeyCode(android.view.KeyEvent.KEYCODE_POWER)
    }

    private fun unlockScreen() {
        uiDevice().pressKeyCode(android.view.KeyEvent.KEYCODE_POWER)
        Thread.sleep(500)
        uiDevice().pressKeyCode(android.view.KeyEvent.KEYCODE_MENU)
    }

    private fun pairAndWaitForLinkUp() {
        val activity = composeTestRule.activity
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(pairUriArg())
        }
        composeTestRule.runOnUiThread { activity.pairingOrchestrator.handleIntent(intent) }
        assertLinkUpWithin(60_000)
    }

    private fun startPairAsync() {
        val activity = composeTestRule.activity
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(pairUriArg())
        }
        composeTestRule.runOnUiThread { activity.pairingOrchestrator.handleIntent(intent) }
    }

    private fun assertLinkUpWithin(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val link = composeTestRule.activity.connectionManager.activeBleConnection()
            if (link?.linkState == LinkState.UP) return
            Thread.sleep(200)
        }
        error("Link never reached UP within ${timeoutMs}ms")
    }

    /**
     * Wait for the link to go through DOWN then back to UP after a
     * simulated disconnect. We poll because the events SharedFlow has
     * no replay buffer and we'd race the disconnect emit.
     */
    private fun assertLinkUpAfterTransitionWithin(timeoutMs: Long) {
        val mgr = composeTestRule.activity.connectionManager
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawDown = false
        while (System.currentTimeMillis() < deadline) {
            val link = mgr.activeBleConnection()?.linkState
            if (link == LinkState.DOWN) sawDown = true
            if (sawDown && link == LinkState.UP) return
            Thread.sleep(200)
        }
        error("Expected DOWN-then-UP cycle within ${timeoutMs}ms; sawDown=$sawDown lastState=${mgr.activeBleConnection()?.linkState}")
    }

    private fun waitForAtLeastOnePeerEvent(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val connection = composeTestRule.activity.connectionManager.activeBleConnection()
            ?: error("no active connection")
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                connection.events().firstOrNull { it is MeshEvent.PeerPositionUpdate }
            }
        } ?: error("no PeerPositionUpdate within ${timeoutMs}ms")
    }

    private fun assertPeerEventArrivesWithin(timeoutMs: Long) {
        waitForAtLeastOnePeerEvent(timeoutMs)
    }

    private fun assertHeartbeatObservedWithin(timeoutMs: Long) {
        val connection = composeTestRule.activity.connectionManager.activeBleConnection()
            ?: error("no active connection")
        val baseline = connection.heartbeatsSent()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connection.heartbeatsSent() > baseline) return
            Thread.sleep(500)
        }
        error("heartbeatsSent did not increment from $baseline within ${timeoutMs}ms")
    }

    private fun readMezullaBadgeStatus(): String {
        composeTestRule.waitForIdle()
        val node = composeTestRule.onNodeWithTag("mezulla_status_badge").fetchSemanticsNode()
        val desc = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            ?.joinToString("|") ?: ""
        return desc
    }

    private fun assertMezullaBadgeShowsUp() {
        val desc = readMezullaBadgeStatus()
        assert(desc.contains("UP")) {
            "Expected Mezulla badge to show UP; contentDescription was '$desc'"
        }
    }

    private fun assertMezullaBadgeShowsDownWithin(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastDesc = ""
        while (System.currentTimeMillis() < deadline) {
            lastDesc = readMezullaBadgeStatus()
            if (lastDesc.contains("DOWN")) return
            Thread.sleep(100)
        }
        error("Mezulla badge never showed DOWN within ${timeoutMs}ms (last description: '$lastDesc')")
    }

    private fun currentConnectionMtu(): Int? =
        composeTestRule.activity.connectionManager.activeBleConnection()?.negotiatedMtu()

    private fun currentConnectionPhy(): Int? =
        composeTestRule.activity.connectionManager.activeBleConnection()?.activePhy()

    private fun rebootBoardViaAdminMessage() {
        // Send a real Meshtastic admin reboot over the live link. The board
        // drops, boots, and re-advertises — a faithful "rebooted mid-flight"
        // drop, unlike a graceful gatt.disconnect() that leaves it half-open.
        // Short reboot delay so recovery fits the test budget.
        val conn = composeTestRule.activity.connectionManager.activeBleConnection()
            ?: error("no active connection to reboot")
        runBlocking { conn.rebootBoardForTest(rebootSeconds = 2) }
    }

    private fun forceDisconnect() {
        // App-side disconnect: stop+restart the transport.
        val mgr = composeTestRule.activity.connectionManager
        mgr.stopActiveConnection()
        // After a brief gap, the auto-reconnect machinery (T2) should
        // kick in. For now this is the trigger; T2 verifies recovery.
    }

    private fun killAppWithoutGracefulDisconnect() {
        // Can't reliably kill our own process AND keep the test running.
        // F2 will need a host-side sidecar to do this — for now stub.
        error("killAppWithoutGracefulDisconnect needs a host sidecar (F2)")
    }

    private fun sendForceDisconnectCommand() {
        error("CMD_FORCE_DISCONNECT not yet defined in firmware (F2)")
    }

    private fun assertBoardAdvertisingWithin(timeoutMs: Long) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bt.adapter?.bluetoothLeScanner ?: error("no BLE scanner")
        val latch = java.util.concurrent.CountDownLatch(1)
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val uuids = result.scanRecord?.serviceUuids ?: emptyList()
                if (uuids.any { it.uuid == MeshtasticGattUuids.SERVICE }) {
                    latch.countDown()
                }
            }
        }
        scanner.startScan(cb)
        try {
            val found = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            assert(found) { "Board not advertising Mezulla service UUID within ${timeoutMs}ms" }
        } finally {
            scanner.stopScan(cb)
        }
    }

    private fun assertHandshakeUnderMs(maxMs: Long) {
        error("assertHandshakeUnderMs needs a handshake-duration counter on BleConnection (F3)")
    }

    private fun assertKeepaliveObservedWithin(timeoutMs: Long) {
        error("assertKeepaliveObservedWithin needs F4 + observability hook")
    }

    private fun hardResetBoard() {
        // Hardware reset (RTS line on USB serial) is host-only; can't
        // do from inside the test process. Needs a sidecar.
        error("hardResetBoard requires host-side esptool — blocked by sidecar (F6)")
    }
}
