package com.madanala.tern.redux

/**
 * Marker interface for every action that flows through [MapStore].
 *
 * All action sealed types — [MapAction], [WeatherActions], [PeerAction] —
 * implement this so the dispatcher's `when` block is a closed type check
 * rather than an open `Any` with a silent catch-all. If a new action family
 * is added but not wired into the reducer, the `else` branch now logs a
 * warning (and throws in debug builds) instead of silently dropping the
 * action. That matters because a dropped [PeerAction.PeerAlertReceived]
 * means a pilot never sees an SOS.
 */
interface TernAction
