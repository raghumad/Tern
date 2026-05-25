package com.madanala.tern.overlay.airspace

import com.madanala.tern.overlay.priority.OverlayCandidate
import com.madanala.tern.overlay.priority.OverlayKind
import com.madanala.tern.overlay.priority.Position
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature

/**
 * An airspace that wants a slot on the map. Wraps the cached
 * [OverlayFeature] and presents it as an [OverlayCandidate] so
 * the [OverlayPrioritizer] can rank it against every other
 * overlay type with a single sorted list.
 */
data class AirspaceCandidate(
    val feature: OverlayFeature,
    val airspaceClass: String,
) : OverlayCandidate {

    override val kind: OverlayKind = OverlayKind.AIRSPACE

    override val position: Position = Position(
        latitudeDeg = feature.centroid.latitude,
        longitudeDeg = feature.centroid.longitude,
    )

    val id: String get() = feature.id
}
