package com.madanala.tern.mezulla.connection

/**
 * Link state of the connection to the paired board.
 *
 * Three states is the minimum we need to drive both the WS3 "no LoRa"
 * indicator and the WS5 Phase 1 "board off" reminder without confusing the
 * two:
 *
 *  - [NEVER_PAIRED] — no board has ever been paired. UI shows nothing.
 *    The LoRa feature does not exist, from the pilot's point of view.
 *    This is the default for a fresh install and after a "forget board".
 *
 *  - [DOWN] — a board is paired but is not currently reachable. UI shows
 *    a discreet "board off" reminder; auto-clears when the board returns.
 *    This is the state during a battery swap, when the pilot walks out
 *    of BLE range, after the board crashes, etc.
 *
 *  - [UP] — paired and reachable; commands will be queued to the board
 *    and inbound events will flow.
 *
 * We do not split [DOWN] into "scanning" / "connecting" / "out of range" /
 * "crashed" because the pilot cannot do anything useful with that
 * distinction in flight, and the auto-connect background loop in WS5.1.3
 * tries everything anyway.
 */
enum class LinkState {
    NEVER_PAIRED,
    DOWN,
    UP,
}
