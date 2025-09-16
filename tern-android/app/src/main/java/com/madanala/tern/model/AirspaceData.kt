@file:Suppress("unused")

package com.madanala.tern.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Using Double for coordinates as is common in GeoJSON

@Serializable
data class GeoPoint( // You might have this already or a similar class, ensure it's serializable if used
    val latitude: Double,
    val longitude: Double
)

@Serializable
sealed class Geometry {
    @Serializable
    @SerialName("Point")
    data class Point(val coordinates: List<Double>) : Geometry() // GeoJSON standard: [longitude, latitude]

    @Serializable
    @SerialName("Polygon")
    // GeoJSON standard: Array of LinearRing coordinate arrays. First is exterior, others are interior.
    // LinearRing: Array of 4+ positions where first and last are equivalent.
    // Position: [longitude, latitude]
    data class Polygon(val coordinates: List<List<List<Double>>>) : Geometry()

    @Serializable
    @SerialName("MultiPolygon")
    // GeoJSON standard: Array of Polygon coordinate arrays.
    data class MultiPolygon(val coordinates: List<List<List<List<Double>>>>) : Geometry() // Fixed extra >

    // TODO: Add other geometry types if needed from your GeoJSON:
    // LineString, MultiPoint, MultiLineString, GeometryCollection
}

@Serializable
data class AirspaceProperties(
    // Define properties based on your GeoJSON. These are examples.
    // Make sure the @SerialName matches the exact key in the GeoJSON if different from Kotlin property name.
    val NAME: String? = null,         // Example: "LONDON CTR"
    val TYPE: String? = null,         // Example: "CTR"
    @SerialName("CLASS") // If GeoJSON key is "CLASS"
    val airspaceClass: String? = null,  // Example: "D"
    val MHZ: String? = null,          // Example: "118.500"
    val LOWER_LIMIT_VAL: Double? = null,
    val LOWER_LIMIT_UNIT: String? = null, // e.g., "FT", "FL"
    val UPPER_LIMIT_VAL: Double? = null,
    val UPPER_LIMIT_UNIT: String? = null, // e.g., "FT", "FL"
    // Add any other properties present in your GeoJSON files.
    // If some properties are very dynamic or you don't need to strongly type all of them,
    // you could use: val additionalProperties: Map<String, kotlinx.serialization.json.JsonElement>? = null
)

@Serializable
data class AirspaceFeature(
    val type: String = "Feature", // Should always be "Feature"
    val geometry: Geometry,
    val properties: AirspaceProperties
)

@Serializable
data class AirspaceFeatureCollection(
    val type: String = "FeatureCollection", // Should always be "FeatureCollection"
    val features: List<AirspaceFeature>,
    val name: String? = null, // Sometimes FeatureCollections have a name
    // You might also have a "crs" (Coordinate Reference System) object here in some GeoJSON files.
    // Example: val crs: CRSProperties? = null
)

// @Serializable
// data class CRSProperties(
//     val type: String?,
//     val properties: Map<String, String>?
// )
