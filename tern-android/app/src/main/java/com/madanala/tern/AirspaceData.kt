package com.madanala.tern

import org.osmdroid.util.GeoPoint

data class Airspace(
    val id: String,
    val name: String,
    val type: String,
    val coordinates: List<GeoPoint>,
    val properties: Map<String, Any>
)

data class AirspaceFeature(
    val type: String,
    val geometry: AirspaceGeometry,
    val properties: Map<String, Any>
)

data class AirspaceGeometry(
    val type: String,
    val coordinates: List<List<List<Double>>>
)
