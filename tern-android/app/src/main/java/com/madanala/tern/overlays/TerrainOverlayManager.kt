package com.madanala.tern.overlays

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import kotlinx.coroutines.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*

/**
 * Terrain overlay manager for 3D terrain visualization and analysis
 * Aviation-grade terrain awareness for paragliding safety and competition
 */
class TerrainOverlayManager(
    private val context: Context,
    store: MapStore? = null
) : BaseOverlayManager(OverlayType.TERRAIN, store) {

    companion object {
        private const val TAG = "TerrainOverlayManager"
        private const val ELEVATION_CACHE_SIZE = 10000 // Maximum elevation points to cache
        private const val TERRAIN_UPDATE_DISTANCE = 1000.0 // meters
        private const val ELEVATION_API_TIMEOUT = 10000L // milliseconds
    }

    // Terrain data structures
    data class ElevationPoint(
        val point: GeoPoint,
        val elevation: Double, // meters above sea level
        val slope: Double, // degrees
        val aspect: Double, // degrees (direction of slope)
        val terrainType: TerrainType
    )

    private data class TerrainAnalysis(
        val area: BoundingBox,
        val elevationRange: Pair<Double, Double>, // min, max elevation
        val averageSlope: Double,
        val ridgeLines: List<Polyline>,
        val landingZones: List<Polygon>,
        val hazardAreas: List<Polygon>
    )

    enum class TerrainType {
        FLAT,       // < 5° slope
        MODERATE,   // 5-15° slope
        STEEP,      // 15-30° slope
        EXTREME,    // > 30° slope
        RIDGE,      // Potential ridge soaring
        VALLEY,     // Valley formations
        WATER,      // Water bodies
        URBAN       // Urban/building areas
    }

    // Elevation data cache for performance
    private val elevationCache = mutableMapOf<Pair<Double, Double>, ElevationPoint>()
    private var currentTerrainAnalysis: TerrainAnalysis? = null
    private var lastTerrainUpdate: GeoPoint? = null

    // Terrain visualization overlays
    private val elevationContours = mutableListOf<Polyline>()
    private val ridgeLineOverlays = mutableListOf<Polyline>()
    private val landingZoneOverlays = mutableListOf<Polygon>()
    private val hazardAreaOverlays = mutableListOf<Polygon>()
    private val slopeVisualizationOverlays = mutableListOf<Polygon>()

    override fun onOverlayAttached() {
        Log.d(TAG, "Initializing terrain overlay manager")
        initializeTerrainData()
        setupTerrainVisualization()
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Detaching terrain overlay manager")
        clearTerrainOverlays()
        elevationCache.clear()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Update terrain data when map moves significantly
        lastTerrainUpdate?.let { lastUpdate ->
            val distance = calculateDistance(
                lastUpdate.latitude, lastUpdate.longitude,
                center.latitude, center.longitude
            )

            if (distance > TERRAIN_UPDATE_DISTANCE || zoomChangedSignificantly(zoom)) {
                updateTerrainData(center, zoom)
            }
        } ?: run {
            updateTerrainData(center, zoom)
        }
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        updateTerrainVisibility(viewport)
    }

    override fun onReduxStateChanged(state: MapState) {
        // React to terrain overlay configuration changes
        if (isEnabled() != getCurrentConfig()?.enabled) {
            if (isEnabled()) {
                showTerrainOverlays()
            } else {
                hideTerrainOverlays()
            }
        }
    }

    override fun clearOverlays() {
        coroutineScope.launch(Dispatchers.Main) {
            clearTerrainOverlays()
        }
    }

    /**
     * Initialize terrain data structures and load cached elevation data
     */
    private fun initializeTerrainData() {
        // Load cached elevation data if available
        loadCachedElevationData()

        // Initialize with sample terrain data for development
        // In production, this would load from elevation service
        initializeSampleTerrainData()
    }

    /**
     * Setup terrain visualization layers
     */
    private fun setupTerrainVisualization() {
        if (!isEnabled()) return

        // Create elevation contour lines
        createElevationContours()

        // Create ridge line visualizations
        createRidgeLineVisualizations()

        // Create landing zone overlays
        createLandingZoneOverlays()

        // Create hazard area overlays
        createHazardAreaOverlays()

        // Create slope visualization
        createSlopeVisualization()
    }

    /**
     * Update terrain data for new viewport
     */
    private fun updateTerrainData(center: GeoPoint, zoom: Double) {
        if (!isEnabled()) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Updating terrain data for ${center.latitude}, ${center.longitude}")

                // Create bounding box for terrain analysis
                val viewport = createTerrainBoundingBox(center, zoom)

                // Fetch elevation data for the area
                val elevationData = fetchElevationData(viewport)

                // Analyze terrain features
                val analysis = analyzeTerrain(elevationData, viewport)

                // Update terrain analysis
                withContext(Dispatchers.Main) {
                    currentTerrainAnalysis = analysis
                    updateTerrainVisualizations(analysis)
                    lastTerrainUpdate = center
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error updating terrain data", e)
            }
        }
    }

    /**
     * Create bounding box for terrain analysis based on map viewport
     */
    private fun createTerrainBoundingBox(center: GeoPoint, zoom: Double): BoundingBox {
        // Calculate appropriate size based on zoom level
        val sizeKm = when {
            zoom > 15 -> 2.0  // Close zoom - small area
            zoom > 12 -> 5.0  // Medium zoom - medium area
            zoom > 10 -> 10.0 // Far zoom - larger area
            else -> 20.0      // Very far - large area
        }

        val latKmPerDegree = 111.0 // Approximate km per degree latitude
        val lonKmPerDegree = 111.0 * cos(Math.toRadians(center.latitude))

        val latOffset = (sizeKm / latKmPerDegree) / 2
        val lonOffset = (sizeKm / lonKmPerDegree) / 2

        return BoundingBox(
            center.latitude + latOffset,
            center.longitude + lonOffset,
            center.latitude - latOffset,
            center.longitude - lonOffset
        )
    }

    /**
     * Fetch elevation data for a bounding box
     * In production, this would call an elevation API like Google Elevation API
     */
    private suspend fun fetchElevationData(viewport: BoundingBox): List<ElevationPoint> {
        return withContext(Dispatchers.IO) {
            // For now, generate sample elevation data
            // In production, this would make API calls to elevation services
            generateSampleElevationData(viewport)
        }
    }

    /**
     * Generate sample elevation data for development
     */
    private fun generateSampleElevationData(viewport: BoundingBox): List<ElevationPoint> {
        val points = mutableListOf<ElevationPoint>()
        val steps = 20 // Grid resolution

        val latStep = (viewport.latNorth - viewport.latSouth) / steps
        val lonStep = (viewport.lonEast - viewport.lonWest) / steps

        for (i in 0..steps) {
            for (j in 0..steps) {
                val lat = viewport.latSouth + (latStep * i)
                val lon = viewport.lonWest + (lonStep * j)

                // Generate realistic elevation data based on latitude (mountains vs plains)
                val baseElevation = when {
                    lat > 45 -> 800.0 + (Math.random() * 1200) // Mountain region
                    lat > 40 -> 400.0 + (Math.random() * 800)  // Hilly region
                    else -> 100.0 + (Math.random() * 300)      // Plains
                }

                // Add some realistic terrain features
                val terrainElevation = addTerrainFeatures(lat, lon, baseElevation)

                val point = GeoPoint(lat, lon)
                val slope = calculateSlope(lat, lon, steps, latStep, lonStep)
                val aspect = calculateAspect(lat, lon, steps, latStep, lonStep)
                val terrainType = classifyTerrain(slope, terrainElevation)

                points.add(ElevationPoint(
                    point = point,
                    elevation = terrainElevation,
                    slope = slope,
                    aspect = aspect,
                    terrainType = terrainType
                ))
            }
        }

        return points
    }

    /**
     * Add realistic terrain features to elevation data
     */
    private fun addTerrainFeatures(lat: Double, lon: Double, baseElevation: Double): Double {
        var elevation = baseElevation

        // Simulate mountain ridges
        if (lat > 44 && lat < 46 && lon > -110 && lon < -108) {
            elevation += 800 * cos((lat - 45) * 10) * cos((lon + 109) * 10)
        }

        // Simulate valleys
        if (lat > 42 && lat < 44) {
            elevation -= 200 * sin((lat - 43) * 15)
        }

        // Add some random variation for realism
        elevation += (Math.random() - 0.5) * 100

        return elevation
    }

    /**
     * Calculate slope at a point (simplified)
     */
    private fun calculateSlope(lat: Double, lon: Double, steps: Int, latStep: Double, lonStep: Double): Double {
        // Simplified slope calculation - in practice would use neighboring elevation points
        return Math.random() * 30 // 0-30 degrees for sample data
    }

    /**
     * Calculate aspect (direction of slope)
     */
    private fun calculateAspect(lat: Double, lon: Double, steps: Int, latStep: Double, lonStep: Double): Double {
        // Simplified aspect calculation
        return Math.random() * 360 // 0-360 degrees for sample data
    }

    /**
     * Classify terrain type based on slope and elevation
     */
    private fun classifyTerrain(slope: Double, elevation: Double): TerrainType {
        return when {
            slope < 5 -> TerrainType.FLAT
            slope < 15 -> TerrainType.MODERATE
            slope < 30 -> TerrainType.STEEP
            elevation < 200 -> TerrainType.WATER
            slope > 30 -> TerrainType.EXTREME
            else -> TerrainType.MODERATE
        }
    }

    /**
     * Analyze terrain features for aviation use
     */
    private fun analyzeTerrain(elevationData: List<ElevationPoint>, area: BoundingBox): TerrainAnalysis {
        val elevations = elevationData.map { it.elevation }
        val minElevation = elevations.minOrNull() ?: 0.0
        val maxElevation = elevations.maxOrNull() ?: 0.0

        // Calculate average slope
        val averageSlope = elevationData.map { it.slope }.average()

        // Identify ridge lines (areas of high elevation with steep slopes)
        val ridgeLines = identifyRidgeLines(elevationData)

        // Identify potential landing zones (flat areas at lower elevation)
        val landingZones = identifyLandingZones(elevationData)

        // Identify hazard areas (very steep slopes, water bodies)
        val hazardAreas = identifyHazardAreas(elevationData)

        return TerrainAnalysis(
            area = area,
            elevationRange = Pair(minElevation, maxElevation),
            averageSlope = averageSlope,
            ridgeLines = ridgeLines,
            landingZones = landingZones,
            hazardAreas = hazardAreas
        )
    }

    /**
     * Identify ridge lines for potential ridge soaring
     */
    private fun identifyRidgeLines(elevationData: List<ElevationPoint>): List<Polyline> {
        val ridges = mutableListOf<Polyline>()

        // Find points that could be ridge lines (high elevation + steep slope)
        val ridgePoints = elevationData.filter { point ->
            point.elevation > 500 && point.slope > 15
        }

        // Group ridge points into lines (simplified)
        if (ridgePoints.size > 10) {
            val ridgeLine = Polyline().apply {
                setPoints(ridgePoints.map { it.point })
                color = Color.BLUE
                width = 3.0f
                isGeodesic = true
            }
            ridges.add(ridgeLine)
        }

        return ridges
    }

    /**
     * Identify potential landing zones (flat, open areas)
     */
    private fun identifyLandingZones(elevationData: List<ElevationPoint>): List<Polygon> {
        val zones = mutableListOf<Polygon>()

        // Find flat areas (low slope, moderate elevation)
        val landingPoints = elevationData.filter { point ->
            point.slope < 8 && point.elevation > 100 && point.elevation < 1000
        }

        // Group into potential landing zones (simplified)
        if (landingPoints.size > 20) {
            val zone = createPolygonFromPoints(landingPoints.map { it.point })
            zone?.let { polygon ->
                polygon.fillColor = Color.argb(100, 0, 255, 0) // Semi-transparent green
                polygon.strokeColor = Color.GREEN
                polygon.strokeWidth = 2.0f
                zones.add(polygon)
            }
        }

        return zones
    }

    /**
     * Identify hazard areas (steep slopes, water, urban areas)
     */
    private fun identifyHazardAreas(elevationData: List<ElevationPoint>): List<Polygon> {
        val hazards = mutableListOf<Polygon>()

        // Find steep or water areas
        val hazardPoints = elevationData.filter { point ->
            point.terrainType == TerrainType.EXTREME ||
            point.terrainType == TerrainType.WATER ||
            point.slope > 35
        }

        // Group into hazard zones (simplified)
        if (hazardPoints.isNotEmpty()) {
            val zone = createPolygonFromPoints(hazardPoints.map { it.point })
            zone?.let { polygon ->
                polygon.fillColor = Color.argb(120, 255, 0, 0) // Semi-transparent red
                polygon.strokeColor = Color.RED
                polygon.strokeWidth = 2.0f
                hazards.add(polygon)
            }
        }

        return hazards
    }

    /**
     * Create polygon from list of points (simplified convex hull)
     */
    private fun createPolygonFromPoints(points: List<GeoPoint>): Polygon? {
        if (points.size < 3) return null

        return try {
            Polygon().apply {
                this.points = points
                isGeodesic = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error creating polygon", e)
            null
        }
    }

    /**
     * Create elevation contour lines
     */
    private fun createElevationContours() {
        // Create contour lines at 200m intervals
        val contourLevels = listOf(200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0)

        contourLevels.forEach { level ->
            val contourPoints = elevationCache.values.filter { point ->
                abs(point.elevation - level) < 100 // Points near this contour level
            }.map { it.point }

            if (contourPoints.size > 5) {
                val contour = Polyline().apply {
                    setPoints(contourPoints)
                    color = Color.argb(150, 139, 69, 19) // Brown color for contours
                    width = 2.0f
                    isGeodesic = true
                }
                elevationContours.add(contour)
            }
        }
    }

    /**
     * Create ridge line visualizations
     */
    private fun createRidgeLineVisualizations() {
        currentTerrainAnalysis?.ridgeLines?.forEach { ridgeLine ->
            ridgeLineOverlays.add(ridgeLine)
        }
    }

    /**
     * Create landing zone overlays
     */
    private fun createLandingZoneOverlays() {
        currentTerrainAnalysis?.landingZones?.forEach { zone ->
            landingZoneOverlays.add(zone)
        }
    }

    /**
     * Create hazard area overlays
     */
    private fun createHazardAreaOverlays() {
        currentTerrainAnalysis?.hazardAreas?.forEach { hazard ->
            hazardAreaOverlays.add(hazard)
        }
    }

    /**
     * Create slope visualization overlay
     */
    private fun createSlopeVisualization() {
        elevationCache.values.groupBy { it.terrainType }.forEach { (terrainType, points) ->
            if (points.size > 10) {
                val color = when (terrainType) {
                    TerrainType.FLAT -> Color.argb(100, 255, 255, 0) // Yellow
                    TerrainType.MODERATE -> Color.argb(100, 255, 165, 0) // Orange
                    TerrainType.STEEP -> Color.argb(100, 255, 0, 0) // Red
                    TerrainType.EXTREME -> Color.argb(150, 128, 0, 128) // Purple
                    else -> Color.argb(100, 128, 128, 128) // Gray
                }

                val polygon = createPolygonFromPoints(points.map { it.point })
                polygon?.let { poly ->
                    poly.fillColor = color
                    poly.strokeColor = Color.BLACK
                    poly.strokeWidth = 1.0f
                    slopeVisualizationOverlays.add(poly)
                }
            }
        }
    }

    /**
     * Update terrain visualizations based on analysis
     */
    private fun updateTerrainVisualizations(analysis: TerrainAnalysis) {
        mapView?.let { map ->
            coroutineScope.launch(Dispatchers.Main) {
                // Clear existing overlays
                clearTerrainOverlays()

                // Add new overlays based on analysis
                elevationContours.forEach { map.overlays.add(it) }
                ridgeLineOverlays.forEach { map.overlays.add(it) }
                landingZoneOverlays.forEach { map.overlays.add(it) }
                hazardAreaOverlays.forEach { map.overlays.add(it) }
                slopeVisualizationOverlays.forEach { map.overlays.add(it) }

                map.invalidate()
            }
        }
    }

    /**
     * Update terrain visibility based on viewport
     */
    private fun updateTerrainVisibility(viewport: BoundingBox) {
        // Show/hide terrain features based on zoom level and viewport
        val shouldShowContours = mapView?.zoomLevelDouble ?: 0.0 > 12.0

        if (!shouldShowContours) {
            elevationContours.forEach { contour ->
                mapView?.overlays?.remove(contour)
            }
        }
    }

    /**
     * Show all terrain overlays
     */
    private fun showTerrainOverlays() {
        mapView?.let { map ->
            coroutineScope.launch(Dispatchers.Main) {
                elevationContours.forEach { map.overlays.add(it) }
                ridgeLineOverlays.forEach { map.overlays.add(it) }
                landingZoneOverlays.forEach { map.overlays.add(it) }
                hazardAreaOverlays.forEach { map.overlays.add(it) }
                slopeVisualizationOverlays.forEach { map.overlays.add(it) }
                map.invalidate()
            }
        }
    }

    /**
     * Hide all terrain overlays
     */
    private fun hideTerrainOverlays() {
        mapView?.let { map ->
            coroutineScope.launch(Dispatchers.Main) {
                elevationContours.forEach { map.overlays.remove(it) }
                ridgeLineOverlays.forEach { map.overlays.remove(it) }
                landingZoneOverlays.forEach { map.overlays.remove(it) }
                hazardAreaOverlays.forEach { map.overlays.remove(it) }
                slopeVisualizationOverlays.forEach { map.overlays.remove(it) }
                map.invalidate()
            }
        }
    }

    /**
     * Clear all terrain overlays from map
     */
    private fun clearTerrainOverlays() {
        mapView?.let { map ->
            elevationContours.forEach { map.overlays.remove(it) }
            ridgeLineOverlays.forEach { map.overlays.remove(it) }
            landingZoneOverlays.forEach { map.overlays.remove(it) }
            hazardAreaOverlays.forEach { map.overlays.remove(it) }
            slopeVisualizationOverlays.forEach { map.overlays.remove(it) }
        }

        // Clear lists
        elevationContours.clear()
        ridgeLineOverlays.clear()
        landingZoneOverlays.clear()
        hazardAreaOverlays.clear()
        slopeVisualizationOverlays.clear()
    }

    /**
     * Load cached elevation data
     */
    private fun loadCachedElevationData() {
        // In production, load from persistent cache
        // For now, this is a placeholder
    }

    /**
     * Initialize with sample terrain data for development
     */
    private fun initializeSampleTerrainData() {
        // Create initial terrain analysis for the default area
        val defaultCenter = GeoPoint(45.0, -110.0) // Sample mountain location
        updateTerrainData(defaultCenter, 12.0)
    }

    /**
     * Check if zoom level changed significantly
     */
    private fun zoomChangedSignificantly(newZoom: Double): Boolean {
        val currentZoom = mapView?.zoomLevelDouble ?: return false
        return abs(newZoom - currentZoom) > 2.0
    }

    /**
     * Calculate distance between two points
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Get terrain information for a specific location
     */
    fun getTerrainInfo(location: GeoPoint): ElevationPoint? {
        // Find closest elevation point in cache
        val cacheKey = Pair(location.latitude, location.longitude)

        // Check exact match first
        elevationCache[cacheKey]?.let { return it }

        // Find nearest point within reasonable distance
        return elevationCache.minByOrNull { entry ->
            val (key, _) = entry
            calculateDistance(location.latitude, location.longitude, key.first, key.second)
        }?.value
    }

    /**
     * Get ridge lines for soaring analysis
     */
    fun getRidgeLines(): List<Polyline> {
        return currentTerrainAnalysis?.ridgeLines ?: emptyList()
    }

    /**
     * Get potential landing zones
     */
    fun getLandingZones(): List<Polygon> {
        return currentTerrainAnalysis?.landingZones ?: emptyList()
    }

    /**
     * Get hazard areas to avoid
     */
    fun getHazardAreas(): List<Polygon> {
        return currentTerrainAnalysis?.hazardAreas ?: emptyList()
    }

    /**
     * Check if a location is in a hazard area
     */
    fun isLocationHazardous(location: GeoPoint): Boolean {
        return getHazardAreas().any { polygon ->
            isPointInPolygon(location, polygon.points)
        }
    }

    /**
     * Check if a point is inside a polygon (simplified ray casting)
     */
    private fun isPointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        // Simplified point-in-polygon test
        // In production, use a proper geometric library
        var inside = false

        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            if (((polygon[i].latitude > point.latitude) != (polygon[j].latitude > point.latitude)) &&
                (point.longitude < (polygon[j].longitude - polygon[i].longitude) *
                (point.latitude - polygon[i].latitude) / (polygon[j].latitude - polygon[i].latitude) + polygon[i].longitude)) {
                inside = !inside
            }
        }

        return inside
    }

    /**
     * Get elevation profile along a path
     */
    fun getElevationProfile(path: List<GeoPoint>): List<Pair<GeoPoint, Double>> {
        return path.mapNotNull { point ->
            getTerrainInfo(point)?.let { terrainInfo ->
                Pair(point, terrainInfo.elevation)
            }
        }
    }
}