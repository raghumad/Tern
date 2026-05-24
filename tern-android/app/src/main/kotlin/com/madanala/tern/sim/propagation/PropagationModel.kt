package com.madanala.tern.sim.propagation

/**
 * Question the swarm simulator (WS1.4) asks once per emitted packet:
 * "pilot A just transmitted; for each other pilot B, was the packet
 * delivered?"
 *
 * A [PropagationModel] answers that yes/no with a reason, given the
 * sender and receiver positions plus the sender's nominal transmit
 * power category.
 *
 * Design notes:
 *
 *  - The return type is a sealed [PropagationOutcome], not a Boolean,
 *    so richer models can give a specific [LossReason] without
 *    breaking callers. The simulator can pass that reason to the BDD
 *    log so a failing scenario explains *why* a peer dropped.
 *  - [LossReason] is itself a sealed hierarchy. A future model — for
 *    example a `LineOfSightPropagation` that consults a digital
 *    elevation model — can introduce new reasons (terrain blocking,
 *    co-channel interference, fade) without forcing every existing
 *    caller to handle them: the existing `Lost` arm still matches.
 *  - The interface is single-method on purpose. Adding a richer model
 *    is purely additive: implement [propagate], maybe surface extra
 *    configuration in your constructor, done. No abstract hooks to
 *    refactor.
 *  - Pure logic: no Android, no third-party deps. Only Kotlin stdlib
 *    and java.lang.Math. The simulator runs in a unit test on a JVM.
 *
 * Implementations must be pure functions of their inputs and may be
 * called from any thread.
 */
interface PropagationModel {

    /**
     * Decide whether a packet sent by [sender] is received by [receiver].
     *
     * @param sender  position and altitude of the transmitting pilot.
     * @param receiver position and altitude of the receiving pilot.
     * @param txPower transmit power category of the sender. Today the
     *   simulator always passes [TxPower.TX_DEFAULT]; the enum exists
     *   so a future model can read sender power without an interface
     *   change.
     */
    fun propagate(
        sender: PilotEndpoint,
        receiver: PilotEndpoint,
        txPower: TxPower = TxPower.TX_DEFAULT,
    ): PropagationOutcome
}

/**
 * One end of a propagation calculation: a pilot's location in three
 * dimensions.
 *
 * @property latitudeDeg  decimal degrees, positive north, negative south.
 * @property longitudeDeg decimal degrees, positive east, negative west.
 * @property altitudeMeters metres above mean sea level. The simulator
 *   feeds GPS altitude from the IGC fix here.
 */
data class PilotEndpoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double,
)

/**
 * Coarse transmit-power buckets. The first cut is single-category
 * (`TX_DEFAULT`); LOW and HIGH are reserved so a future model can
 * distinguish a low-duty-cycle beacon from a long-range burst without
 * a breaking interface change.
 */
enum class TxPower {
    TX_DEFAULT,
}

/** Result of a single [PropagationModel.propagate] call. */
sealed interface PropagationOutcome {

    /** Packet reaches the receiver. */
    data object Delivered : PropagationOutcome

    /**
     * Packet does not reach the receiver. [reason] explains why, so a
     * failing BDD scenario can point at the cause.
     */
    data class Lost(val reason: LossReason) : PropagationOutcome
}

/**
 * Why a packet was lost. Sealed so richer models can add their own
 * reasons (e.g. `BlockedByTerrain`, `Interference`, `Faded`) without
 * forcing existing callers to handle new variants — the `Lost` arm in
 * a `when` keeps working.
 */
sealed interface LossReason {

    /**
     * The 3-D separation between sender and receiver exceeds the
     * configured maximum range.
     */
    data object OutOfRange : LossReason
}
