package com.madanala.tern.mezulla.connection.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a [BleConnection] against a real Android Bluetooth stack.
 *
 * For the WS4.3 skeleton this is a free function rather than a DI graph
 * entry. Pairing UX (WS5) will replace direct callers of this with a
 * persisted-board lookup.
 *
 * @param context any context (we keep a reference to the application
 *   context internally, not the activity)
 * @param targetMacAddress the LilyGo board's MAC, e.g. `f0:24:f9:92:61:84`
 * @param ourNodeNumber the node number Tern should claim when sending
 *   packets. For the dev board this can be any value — a real WS5 wiring
 *   will read it from the board's `MyNodeInfo` after first connect.
 * @param scope coroutine scope that owns the BLE lifecycle (typically the
 *   application or feature scope so the connection survives screen
 *   rotations).
 */
fun buildBleConnection(
    context: Context,
    targetMacAddress: String,
    ourNodeNumber: Long,
    scope: CoroutineScope,
    pairedBoardId: String? = inferPairedBoardId(targetMacAddress),
): BleConnection {
    val transport = AndroidBleTransport(
        context = context.applicationContext,
        targetMacAddress = targetMacAddress,
        scope = scope,
    )
    return BleConnection(
        pairedBoardId = pairedBoardId,
        ourNodeNumber = ourNodeNumber,
        transport = transport,
        scope = scope,
    )
}

/**
 * Best-effort placeholder until WS5 stores the actual board identifier
 * the pilot paired with. Returning the MAC means `pairedBoardId` is
 * non-null whenever a target MAC exists, which gates [BleConnection.start]
 * to actually scan. The real WS5 pairing flow will hand us the `!hex`
 * NodeInfo id from the board itself.
 */
private fun inferPairedBoardId(targetMacAddress: String): String? =
    if (targetMacAddress.isBlank()) null else targetMacAddress
