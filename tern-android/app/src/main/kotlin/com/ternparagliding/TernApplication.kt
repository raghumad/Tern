package com.ternparagliding

import android.app.Application
import com.ternparagliding.mezulla.MezullaConnectionManager
import com.ternparagliding.mezulla.pairing.PairingOrchestrator

/**
 * Process-lifetime owner of the Mezulla connection stack.
 *
 * The persistent BLE link MUST survive Activity recreation — rotation,
 * config change, process-restore, returning from background. A pilot in
 * flight cannot lose the Mezulla link just because Android recreated the
 * UI. So the [MezullaConnectionManager] and [PairingOrchestrator] live
 * here at Application scope, NOT on the Activity.
 *
 * Previously these were `by lazy` on [TernParaglidingActivity], so every
 * Activity recreation built a fresh manager (activeConnection = null) while
 * the previous connection leaked — holding the board so the new manager
 * could never reconnect. Hoisting them here is what the connection scope's
 * own KDoc ("Application-scoped coroutine scope") always intended.
 *
 * The Activity binds its (activity-scoped) Redux MapStore to the manager in
 * onCreate via [MezullaConnectionManager.initialize]; the manager re-points
 * its peer-event stream at the new store on each recreation while keeping
 * the live connection.
 */
class TernApplication : Application() {

    val pairingOrchestrator: PairingOrchestrator by lazy {
        PairingOrchestrator(applicationContext)
    }

    val connectionManager: MezullaConnectionManager by lazy {
        MezullaConnectionManager(applicationContext, pairingOrchestrator).also { mgr ->
            // Wire the pairing-flow hooks once, at app scope:
            //  - on a new pair, tear down any stale connection so it can't
            //    race the new pair's SMP handshake with a stale PIN;
            //  - ...unless we're already linked to that exact board, in which
            //    case re-scanning the QR is an idempotent no-op success.
            pairingOrchestrator.onPairStart = { mgr.stopActiveConnection() }
            pairingOrchestrator.isAlreadyLinked = { nodeId -> mgr.isLinkedTo(nodeId) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize offline geocoder with resident country boundaries
        com.ternparagliding.utils.geo.OfflineGeocoder.initialize(this)
    }
}
