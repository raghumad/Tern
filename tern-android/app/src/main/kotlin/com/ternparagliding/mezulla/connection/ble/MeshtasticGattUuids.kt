package com.ternparagliding.mezulla.connection.ble

import java.util.UUID

/**
 * GATT service + characteristic UUIDs that Meshtastic firmware exposes on
 * the BLE peripheral. Source:
 * https://meshtastic.org/docs/development/device/client-api/
 *
 * These are fixed by the Meshtastic firmware and have been stable across
 * 2.x releases. Bumping a Meshtastic major version is the only realistic
 * way these change.
 */
internal object MeshtasticGattUuids {

    /** Meshtastic primary service. */
    val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    /**
     * Polled (read) by the phone to drain the board's outbound FIFO. Returns
     * an empty value when the FIFO is empty. After draining once, the phone
     * subscribes to [FROM_NUM] and reads again whenever a notification arrives.
     */
    val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")

    /** Written by the phone to push `ToRadio` protobufs into the board. */
    val TO_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120003")

    /**
     * Notify-only. The board increments + notifies this value whenever a new
     * packet is enqueued in [FROM_RADIO]. The numeric value itself is a
     * monotonic counter; the only useful information is the change event.
     */
    val FROM_NUM: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120004")

    /**
     * Standard BLE Client Characteristic Configuration Descriptor. We write
     * `ENABLE_NOTIFICATION_VALUE` here to subscribe to [FROM_NUM] notifies.
     */
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
