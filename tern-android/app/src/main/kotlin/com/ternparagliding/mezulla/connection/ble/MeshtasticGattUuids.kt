package com.ternparagliding.mezulla.connection.ble

import java.util.UUID

/**
 * GATT service + characteristic UUIDs that Meshtastic firmware exposes on
 * the BLE peripheral.
 *
 * Source of truth: GATT service discovery on real board (2026-05-26).
 * The firmware handoff doc had incorrect UUIDs for ToRadio and FromNum.
 * These were verified by connecting to board 007_6184 and dumping
 * services/characteristics via BlePairingService.
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
    val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /**
     * Notify-only. The board increments + notifies this value whenever a new
     * packet is enqueued in [FROM_RADIO]. The numeric value itself is a
     * monotonic counter; the only useful information is the change event.
     */
    val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    /**
     * Standard BLE Client Characteristic Configuration Descriptor. We write
     * `ENABLE_NOTIFICATION_VALUE` here to subscribe to [FROM_NUM] notifies.
     */
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
