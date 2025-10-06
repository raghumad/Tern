package com.madanala.tern.overlays

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.model.*
import com.madanala.tern.model.FlightComputer
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import kotlinx.coroutines.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

/**
 * Sensor overlay manager for real-time flight data integration
 * Aviation-grade sensor fusion for paragliding and competition use
 */
class SensorOverlayManager(
    private val context: Context,
    store: MapStore? = null
) : BaseOverlayManager(OverlayType.SENSORS, store), SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "SensorOverlayManager"
        private const val SENSOR_UPDATE_INTERVAL = 100L // 10Hz updates for flight data
        private const val ALTITUDE_FILTER_ALPHA = 0.1f // Low-pass filter for altitude stability
        private const val GPS_MIN_UPDATE_DISTANCE = 5.0f // meters
        private const val GPS_MIN_UPDATE_TIME = 1000L // milliseconds
    }

    // Sensor managers and hardware
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var gpsLocationListener: LocationListener? = null
    private var networkLocationListener: LocationListener? = null

    // Sensor objects
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var barometer: Sensor? = null
    private var magnetometer: Sensor? = null

    // Current sensor readings
    private var currentGPSData: GPSData? = null
    private var currentBarometerData: BarometerData? = null
    private var currentAccelerometerData: AccelerometerData? = null
    private var currentGyroscopeData: GyroscopeData? = null

    // Aviation calculations
    private var filteredAltitude: Float = 0.0f
    private var groundAltitudeAGL: Double = 0.0
    private var flightStartAltitude: Double = 0.0
    private var flightPathPoints: MutableList<GeoPoint> = mutableListOf()
    private var altitudeHistory: ConcurrentLinkedQueue<Double> = ConcurrentLinkedQueue()

    // Flight computer integration
    private val flightComputer = FlightComputer()
    private var altitudeTimeHistory: MutableList<Long> = mutableListOf()
    private var windCalculationHistory: MutableList<Pair<Double, Double>> = mutableListOf()
    private var varioHistory: MovingAverageCalculator = MovingAverageCalculator(10)

    // Flight session tracking
    private var flightSessionStart: Long = 0
    private var totalDistance: Double = 0.0
    private var maxAltitude: Double = 0.0
    private var altitudeGain: Double = 0.0
    private var thermalCount: Int = 0
    private var previousAltitude: Double = 0.0

    // Map overlays for visualization
    private var flightPathOverlay: Polyline? = null
    private var currentPositionMarker: Marker? = null
    private var thermalMarkers: MutableList<Marker> = mutableListOf()

    // Aviation-specific calculations
    private var currentWind: Pair<Double, Double>? = null // speed, direction
    private var glideRatio: Double = 0.0
    private var verticalSpeedAveraging: MovingAverage? = null

    // Sensor fusion system
    private var altitudeKalmanFilter: KalmanFilter = KalmanFilter()
    private var positionKalmanFilter: KalmanFilter = KalmanFilter()
    private var velocityKalmanFilter: KalmanFilter = KalmanFilter()
    private var attitudeKalmanFilter: AttitudeKalmanFilter = AttitudeKalmanFilter()
    private var sensorFusionHistory: MutableList<SensorFusionState> = mutableListOf()

    // Public accessors for sensor fusion system
    fun getSensorFusionHistory(): List<SensorFusionState> = sensorFusionHistory.toList()


    override fun onOverlayAttached() {
        Log.d(TAG, "Initializing sensor overlay manager")
        initializeSensors()
        setupLocationServices()
        initializeAviationCalculations()
        startSensorUpdates()
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Detaching sensor overlay manager")
        stopSensorUpdates()
        cleanupLocationServices()
        clearMapOverlays()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Sensor overlay doesn't need map movement handling
        // Flight path is updated based on GPS position, not map interactions
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        // Update thermal markers visibility based on viewport
        updateThermalMarkersVisibility(viewport)
    }

    override fun onReduxStateChanged(state: MapState) {
        // React to sensor state changes from Redux
        if (state.sensorState.isActive != isSensorsActive()) {
            if (state.sensorState.isActive) {
                startFlightSession(state.sensorState.flightMode)
            } else {
                endFlightSession()
            }
        }

        // Update sensor configuration based on Redux state
        if (state.sensorState.flightMode != getCurrentFlightMode()) {
            updateSensorConfiguration(state.sensorState.flightMode)
        }
    }

    override fun clearOverlays() {
        coroutineScope.launch(Dispatchers.Main) {
            clearMapOverlays()
        }
    }

    /**
     * Initialize all available sensors
     */
    private fun initializeSensors() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Get sensor references
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Determine available sensors
        val availableSensors = mutableSetOf<SensorType>()
        if (accelerometer != null) availableSensors.add(SensorType.ACCELEROMETER)
        if (gyroscope != null) availableSensors.add(SensorType.GYROSCOPE)
        if (barometer != null) availableSensors.add(SensorType.BAROMETER)
        if (magnetometer != null) availableSensors.add(SensorType.MAGNETOMETER)

        // Update Redux state with available sensors
        mapStore?.dispatch(MapAction.UpdateSensorState(
            SensorState(
                availableSensors = availableSensors,
                sensorAccuracy = determineSensorAccuracy(availableSensors)
            )
        ))
    }

    /**
     * Setup location services for GPS data
     */
    private fun setupLocationServices() {
        try {
            // Request location updates from both GPS and network providers
            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {

                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_MIN_UPDATE_TIME,
                    GPS_MIN_UPDATE_DISTANCE,
                    this,
                    Looper.getMainLooper()
                )

                // Network provider for backup when GPS is weak
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        GPS_MIN_UPDATE_TIME * 2,
                        GPS_MIN_UPDATE_DISTANCE * 2,
                        this
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
        }
    }

    /**
     * Initialize aviation-specific calculation systems
     */
    private fun initializeAviationCalculations() {
        verticalSpeedAveraging = MovingAverage(10) // 10-second averaging for vario
        flightPathPoints = mutableListOf()
        altitudeHistory = ConcurrentLinkedQueue()
    }

    /**
     * Start sensor updates and flight data processing
     */
    private fun startSensorUpdates() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isAttached) {
                processSensorData()
                delay(SENSOR_UPDATE_INTERVAL)
            }
        }
    }

    /**
     * Stop all sensor updates
     */
    private fun stopSensorUpdates() {
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensor listener", e)
        }
    }

    /**
     * Cleanup location services
     */
    private fun cleanupLocationServices() {
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }
    }

    /**
     * Process all current sensor data and update Redux state with sensor fusion
     */
    private suspend fun processSensorData() {
        withContext(Dispatchers.Default) {
            try {
                val currentTime = System.currentTimeMillis()

                // Create combined flight data if we have GPS
                currentGPSData?.let { gps ->
                    val flightData = createFlightData(gps, currentTime)

                    // Perform sensor fusion for enhanced accuracy
                    val sensorFusionState = performSensorFusion(
                        gpsData = gps,
                        barometerData = currentBarometerData,
                        accelerometerData = currentAccelerometerData,
                        gyroscopeData = currentGyroscopeData
                    )

                    // Store fusion state for trend analysis
                    sensorFusionHistory.add(sensorFusionState)
                    if (sensorFusionHistory.size > 50) {
                        sensorFusionHistory = sensorFusionHistory.takeLast(25).toMutableList()
                    }

                    val flightComputerData = calculateFlightComputerData(flightData)

                    // Update Redux state on main thread
                    withContext(Dispatchers.Main) {
                        mapStore?.dispatch(MapAction.UpdateFlightData(flightData))
                        flightComputerData?.let {
                            mapStore?.dispatch(MapAction.UpdateFlightComputerData(it))
                        }

                        // Update sensor fusion state in Redux
                        mapStore?.dispatch(MapAction.UpdateSensorState(
                            mapStore?.state?.value?.sensorState?.copy(
                                sensorAccuracy = when (sensorFusionState.fusionQuality) {
                                    FusionQuality.NONE -> SensorAccuracy.NONE
                                    FusionQuality.POOR -> SensorAccuracy.LOW
                                    FusionQuality.FAIR -> SensorAccuracy.MODERATE
                                    FusionQuality.GOOD -> SensorAccuracy.HIGH
                                    FusionQuality.EXCELLENT -> SensorAccuracy.EXTREME
                                }
                            ) ?: SensorState()
                        ))
                    }

                    // Update map overlays if attached
                    if (isAttached) {
                        updateMapOverlays(flightData, flightComputerData)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing sensor data", e)
            }
        }
    }

    /**
     * Create comprehensive flight data from sensor readings
     */
    private fun createFlightData(gpsData: GPSData, timestamp: Long): FlightData {
        // Apply altitude filtering for stability
        filteredAltitude = if (filteredAltitude == 0.0f) {
            gpsData.altitude.toFloat()
        } else {
            filteredAltitude * (1 - ALTITUDE_FILTER_ALPHA) + gpsData.altitude.toFloat() * ALTITUDE_FILTER_ALPHA
        }

        // Calculate aviation parameters
        val groundSpeed = calculateGroundSpeed(gpsData)
        val track = gpsData.bearing.toDouble()

        // Estimate altitude above ground level (simplified)
        val altitudeAGL = max(0.0, gpsData.altitude - groundAltitudeAGL)

        // Calculate vertical speed using altitude history
        val verticalSpeed = calculateVerticalSpeed(gpsData.altitude)

        // Update altitude history for trend analysis
        altitudeHistory.offer(gpsData.altitude)
        if (altitudeHistory.size > 100) { // Keep last 100 readings (~10 seconds)
            altitudeHistory.poll()
        }

        return FlightData(
            gps = gpsData,
            barometer = currentBarometerData,
            accelerometer = currentAccelerometerData,
            gyroscope = currentGyroscopeData,
            timestamp = timestamp,
            groundSpeed = groundSpeed,
            track = track,
            altitudeAGL = altitudeAGL,
            verticalSpeed = verticalSpeed
        )
    }

    /**
     * Calculate aviation flight computer parameters using advanced algorithms
     */
    private fun calculateFlightComputerData(flightData: FlightData): FlightComputerData? {
        val gps = flightData.gps

        // Use FlightComputer for advanced variometer calculations
        val altitudeList = altitudeHistory.toList()
        val timeList = altitudeTimeHistory.toList()

        val varioData = if (altitudeList.size >= 3 && timeList.size >= 3) {
            flightComputer.calculateVarioData(
                currentAltitude = gps.altitude,
                altitudeHistory = altitudeList,
                timeHistory = timeList
            )
        } else null

        // Use FlightComputer for wind calculations
        val windData = if (flightData.groundSpeed > 2.0 && windCalculationHistory.size >= 3) {
            flightComputer.calculateWind(
                groundSpeed = flightData.groundSpeed,
                groundTrack = flightData.track,
                airSpeed = flightData.groundSpeed, // Simplified - would need pitot tube
                heading = flightData.track, // Simplified - would need compass
                gpsBearing = flightData.track,
                windHistory = windCalculationHistory
            )
        } else null

        // Calculate glide ratio using FlightComputer
        val glideRatio = if (flightData.groundSpeed > 5.0 && varioData != null) {
            flightComputer.calculateGlideRatio(flightData.groundSpeed, varioData.verticalSpeed)
        } else {
            this.glideRatio
        }

        this.glideRatio = glideRatio

        // Update altitude time history
        altitudeTimeHistory.add(System.currentTimeMillis())
        if (altitudeTimeHistory.size > 100) {
            altitudeTimeHistory = altitudeTimeHistory.takeLast(50).toMutableList()
        }

        // Update wind history for better calculations
        windData?.let {
            windCalculationHistory.add(Pair(it.windSpeed, it.windDirection))
            if (windCalculationHistory.size > 20) {
                windCalculationHistory = windCalculationHistory.takeLast(10).toMutableList()
            }
        }

        // Update vario history for smoothing
        varioData?.verticalSpeed?.let { varioHistory.add(it) }

        // Update flight metrics if in flight session
        if (flightSessionStart > 0) {
            updateFlightMetrics(flightData)
        }

        return FlightComputerData(
            currentPosition = GeoPoint(gps.latitude, gps.longitude),
            altitudeMSL = gps.altitude,
            altitudeAGL = flightData.altitudeAGL ?: 0.0,
            groundSpeed = flightData.groundSpeed,
            track = flightData.track,
            verticalSpeed = varioData?.verticalSpeed ?: 0.0,
            windSpeed = windData?.windSpeed ?: 0.0,
            windDirection = windData?.windDirection ?: 0.0,
            glideRatio = glideRatio,
            flightMode = mapStore?.state?.value?.sensorState?.flightMode ?: FlightMode.GROUND,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Calculate ground speed in knots from GPS data
     */
    private fun calculateGroundSpeed(gpsData: GPSData): Double {
        return (gpsData.speed * 3.6 * 0.539957).coerceAtLeast(0.0) // m/s to knots
    }

    /**
     * Calculate vertical speed using altitude history
     */
    private fun calculateVerticalSpeed(currentAltitude: Double): Double? {
        if (altitudeHistory.size < 5) return null

        val history = altitudeHistory.toList()
        val oldest = history.first()
        val newest = history.last()
        val timeSpan = (altitudeHistory.size - 1) * 0.1 // Approximate time span in seconds

        return if (timeSpan > 0) {
            (newest - oldest) / timeSpan // m/s
        } else {
            null
        }
    }

    /**
     * Calculate wind speed and direction (simplified)
     */
    private fun calculateWind(flightData: FlightData): Pair<Double, Double>? {
        // Simplified wind calculation - in practice this would use more sophisticated algorithms
        // For now, return null as we need more data for accurate wind calculation
        return currentWind
    }

    /**
     * Update flight metrics during active session
     */
    private fun updateFlightMetrics(flightData: FlightData) {
        val gps = flightData.gps
        val currentAltitude = gps.altitude

        // Track maximum altitude
        if (currentAltitude > maxAltitude) {
            maxAltitude = currentAltitude
        }

        // Track altitude gain from start
        if (flightStartAltitude == 0.0) {
            flightStartAltitude = currentAltitude
        }
        altitudeGain = max(altitudeGain, currentAltitude - flightStartAltitude)

        // Track thermal detection (simplified - significant lift events)
        flightData.verticalSpeed?.let { vario ->
            if (vario > 1.0 && currentAltitude > previousAltitude + 5) {
                // Potential thermal entry
                if (thermalCount == 0 || currentAltitude > previousAltitude + 50) {
                    thermalCount++
                }
            }
        }

        previousAltitude = currentAltitude

        // Update total distance (simplified calculation)
        currentGPSData?.let { previousGPS ->
            if (previousGPS.latitude != 0.0 && previousGPS.longitude != 0.0) {
                val distance = calculateDistance(
                    previousGPS.latitude, previousGPS.longitude,
                    gps.latitude, gps.longitude
                )
                totalDistance += distance
            }
        }
    }

    /**
     * Update map overlays with current flight data
     */
    private fun updateMapOverlays(flightData: FlightData, flightComputerData: FlightComputerData?) {
        mapView?.let { map ->
            coroutineScope.launch(Dispatchers.Main) {
                // Update flight path
                updateFlightPathOverlay(flightData.gps)

                // Update current position marker
                updateCurrentPositionMarker(flightComputerData)

                // Update thermal markers
                updateThermalMarkers(flightData)
            }
        }
    }

    /**
     * Update flight path overlay
     */
    private fun updateFlightPathOverlay(gpsData: GPSData) {
        val currentPoint = GeoPoint(gpsData.latitude, gpsData.longitude)

        if (flightPathPoints.isEmpty() ||
            calculateDistance(flightPathPoints.last().latitude, flightPathPoints.last().longitude,
                           currentPoint.latitude, currentPoint.longitude) > 10) {

            flightPathPoints.add(currentPoint)

            // Keep only recent points to avoid memory issues
            if (flightPathPoints.size > 1000) {
                flightPathPoints = flightPathPoints.takeLast(500).toMutableList()
            }

            flightPathOverlay?.let { mapView?.overlays?.remove(it) }

            flightPathOverlay = Polyline().apply {
                setPoints(flightPathPoints)
                color = android.graphics.Color.BLUE
                width = 4.0f
                isGeodesic = true
            }

            flightPathOverlay?.let { mapView?.overlays?.add(it) }
            mapView?.invalidate()
        }
    }

    /**
     * Update current position marker
     */
    private fun updateCurrentPositionMarker(flightComputerData: FlightComputerData?) {
        flightComputerData?.let { data ->
            currentPositionMarker?.let { mapView?.overlays?.remove(it) }

            currentPositionMarker = Marker(mapView).apply {
                position = data.currentPosition
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Current Position\n" +
                       "Alt: ${data.altitudeMSL.toInt()}m\n" +
                       "Speed: ${data.groundSpeed.toInt()}kt\n" +
                       "Vario: ${data.verticalSpeed.toInt()}m/s"
                // Use a simple icon for now - could be enhanced with aviation symbols
            }

            currentPositionMarker?.let { mapView?.overlays?.add(it) }
            mapView?.invalidate()
        }
    }

    /**
     * Update thermal markers (placeholder for future implementation)
     */
    private fun updateThermalMarkers(flightData: FlightData) {
        // Future implementation for thermal visualization
    }

    /**
     * Update thermal markers visibility based on viewport
     */
    private fun updateThermalMarkersVisibility(viewport: BoundingBox) {
        // Future implementation for thermal marker management
    }

    /**
     * Clear all map overlays
     */
    private fun clearMapOverlays() {
        mapView?.let { map ->
            coroutineScope.launch(Dispatchers.Main) {
                flightPathOverlay?.let { map.overlays.remove(it) }
                currentPositionMarker?.let { map.overlays.remove(it) }
                thermalMarkers.forEach { map.overlays.remove(it) }
                thermalMarkers.clear()
                map.invalidate()
            }
        }
    }

    /**
     * Start a new flight session
     */
    private fun startFlightSession(flightMode: FlightMode) {
        Log.d(TAG, "Starting flight session in $flightMode mode")
        flightSessionStart = System.currentTimeMillis()
        flightStartAltitude = currentGPSData?.altitude ?: 0.0
        totalDistance = 0.0
        maxAltitude = 0.0
        altitudeGain = 0.0
        thermalCount = 0

        mapStore?.dispatch(MapAction.StartFlightSession)

        // Register sensor listeners
        registerSensorListeners()
    }

    /**
     * End current flight session
     */
    private fun endFlightSession() {
        Log.d(TAG, "Ending flight session")

        // Unregister sensor listeners to save battery
        unregisterSensorListeners()

        // Create final flight metrics
        val metrics = createFlightMetrics()
        mapStore?.dispatch(MapAction.UpdateFlightMetrics(metrics))
        mapStore?.dispatch(MapAction.EndFlightSession)

        // Reset session variables
        flightSessionStart = 0
        flightPathPoints.clear()
    }

    /**
     * Create final flight metrics summary
     */
    private fun createFlightMetrics(): FlightMetrics {
        val duration = if (flightSessionStart > 0) {
            System.currentTimeMillis() - flightSessionStart
        } else 0L

        return FlightMetrics(
            startTime = flightSessionStart,
            duration = duration,
            distance = totalDistance,
            maxAltitude = maxAltitude,
            altitudeGain = altitudeGain,
            maxGroundSpeed = 0.0, // Would need speed history
            averageGroundSpeed = if (duration > 0) totalDistance / (duration / 1000.0) else 0.0,
            maxVerticalSpeed = 0.0, // Would need vario history
            maxSinkRate = 0.0,
            thermalCount = thermalCount,
            averageGlideRatio = glideRatio,
            flightPath = flightPathPoints.toList()
        )
    }

    /**
     * Register sensor event listeners
     */
    private fun registerSensorListeners() {
        try {
            accelerometer?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroscope?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            barometer?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering sensor listeners", e)
        }
    }

    /**
     * Unregister sensor event listeners
     */
    private fun unregisterSensorListeners() {
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensor listeners", e)
        }
    }

    /**
     * Update sensor configuration for different flight modes
     */
    private fun updateSensorConfiguration(flightMode: FlightMode) {
        when (flightMode) {
            FlightMode.LAUNCH -> {
                // High frequency for launch detection
                registerSensorListeners()
            }
            FlightMode.FLIGHT -> {
                // Normal flight frequency
                registerSensorListeners()
            }
            FlightMode.LANDING -> {
                // Critical sensors only for landing
                registerSensorListeners()
            }
            FlightMode.THERMAL -> {
                // Focus on variometer sensors
                registerSensorListeners()
            }
            FlightMode.RIDGE -> {
                // Focus on wind sensors
                registerSensorListeners()
            }
            FlightMode.COMPETITION -> {
                // Maximum frequency for competition accuracy
                registerSensorListeners()
            }
            FlightMode.GROUND -> {
                // Minimal sensors when on ground
                unregisterSensorListeners()
            }
        }
    }

    /**
     * Determine overall sensor accuracy for aviation use
     */
    private fun determineSensorAccuracy(availableSensors: Set<SensorType>): SensorAccuracy {
        return when {
            availableSensors.contains(SensorType.GPS) &&
            availableSensors.contains(SensorType.BAROMETER) &&
            availableSensors.size >= 4 -> SensorAccuracy.EXTREME

            availableSensors.contains(SensorType.GPS) &&
            availableSensors.contains(SensorType.BAROMETER) -> SensorAccuracy.HIGH

            availableSensors.contains(SensorType.GPS) -> SensorAccuracy.MODERATE

            availableSensors.isNotEmpty() -> SensorAccuracy.LOW

            else -> SensorAccuracy.NONE
        }
    }

    /**
     * Check if sensors are currently active
     */
    private fun isSensorsActive(): Boolean {
        return flightSessionStart > 0
    }

    /**
     * Get current flight mode from Redux state
     */
    private fun getCurrentFlightMode(): FlightMode {
        return mapStore?.state?.value?.sensorState?.flightMode ?: FlightMode.GROUND
    }

    // SensorEventListener implementation
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAccelerometerData = AccelerometerData(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestamp = System.currentTimeMillis()
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyroscopeData = GyroscopeData(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestamp = System.currentTimeMillis()
                )
            }
            Sensor.TYPE_PRESSURE -> {
                val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0])
                currentBarometerData = BarometerData(
                    pressure = event.values[0],
                    altitude = altitude,
                    temperature = 15.0f, // Default temperature - would need temperature sensor
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracy")
    }

    // LocationListener implementation
    override fun onLocationChanged(location: Location) {
        val fixQuality = when (location.accuracy) {
            in 0.0..3.0 -> GPSFixQuality.HIGH
            in 3.0..10.0 -> GPSFixQuality.MODERATE
            in 10.0..50.0 -> GPSFixQuality.LOW
            else -> GPSFixQuality.NONE
        }

        currentGPSData = GPSData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            accuracy = location.accuracy,
            altitudeAccuracy = (location.altitude * 0.1).toFloat(), // Estimate vertical accuracy
            speed = location.speed,
            bearing = location.bearing,
            timestamp = location.time,
            satellites = if (location.extras?.containsKey("satellites") == true) {
                location.extras?.getInt("satellites") ?: 0
            } else 0,
            fixQuality = fixQuality
        )

        // Update GPS fix status in base class
        updateGPSFixStatus(fixQuality != GPSFixQuality.NONE)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.d(TAG, "Location provider $provider status changed to $status")
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Location provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Location provider disabled: $provider")
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula
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
     * Calculate final glide to a specific goal using advanced flight computer algorithms
     */
    fun calculateFinalGlideToGoal(
        goalPosition: GeoPoint,
        goalAltitude: Double = 0.0, // meters MSL, defaults to ground level
        safetyMargin: Double = 50.0 // meters
    ): FlightComputer.FinalGlideCalculation? {
        currentGPSData?.let { gps ->
            val currentPosition = GeoPoint(gps.latitude, gps.longitude)
            val currentAltitude = gps.altitude

            // Get current wind data for more accurate calculations
            val windData = if (windCalculationHistory.isNotEmpty()) {
                val avgWindSpeed = windCalculationHistory.map { it.first }.average()
                val avgWindDirection = windCalculationHistory.map { it.second }.average()

                FlightComputer.WindCalculation(
                    windSpeed = avgWindSpeed,
                    windDirection = avgWindDirection,
                    headwindComponent = 0.0, // Would need proper calculation
                    crosswindComponent = 0.0, // Would need proper calculation
                    windQuality = FlightComputer.WindQuality.FAIR
                )
            } else null

            return flightComputer.calculateFinalGlide(
                currentPosition = currentPosition,
                currentAltitude = currentAltitude,
                goalPosition = goalPosition,
                goalAltitude = goalAltitude,
                currentGlideRatio = glideRatio,
                wind = windData,
                safetyMargin = safetyMargin
            )
        }
        return null
    }

    /**
     * Get current thermal strength for soaring guidance
     */
    fun getCurrentThermalStrength(): com.madanala.tern.model.ThermalStrength {
        val recentVario = varioHistory.getAverage()
        return when {
            recentVario < -2.0 -> com.madanala.tern.model.ThermalStrength.NONE
            recentVario < -0.5 -> com.madanala.tern.model.ThermalStrength.WEAK
            recentVario < 1.0 -> com.madanala.tern.model.ThermalStrength.MODERATE
            recentVario < 3.0 -> com.madanala.tern.model.ThermalStrength.STRONG
            else -> com.madanala.tern.model.ThermalStrength.EXTREME
        }
    }

    /**
     * Get current wind calculation for navigation
     */
    fun getCurrentWind(): FlightComputer.WindCalculation? {
        return if (windCalculationHistory.isNotEmpty()) {
            val avgWindSpeed = windCalculationHistory.map { it.first }.average()
            val avgWindDirection = windCalculationHistory.map { it.second }.average()

            FlightComputer.WindCalculation(
                windSpeed = avgWindSpeed,
                windDirection = avgWindDirection,
                headwindComponent = 0.0, // Simplified
                crosswindComponent = 0.0, // Simplified
                windQuality = FlightComputer.WindQuality.FAIR
            )
        } else null
    }

    /**
     * Get current sensor fusion state for situational awareness
     */
    fun getSensorFusionState(): SensorFusionState? {
        return sensorFusionHistory.lastOrNull()
    }

}

/**
 * Sensor fusion state for tracking data quality and fusion results
 */
data class SensorFusionState(
    val timestamp: Long,
    val positionAccuracy: Double, // meters
    val altitudeAccuracy: Double, // meters
    val velocityAccuracy: Double, // m/s
    val attitudeAccuracy: Double, // degrees
    val fusionQuality: FusionQuality,
    val sensorRedundancy: Int, // Number of sensors contributing
    val kalmanFilterConvergence: Boolean
)

/**
 * Quality of sensor fusion results
 */
enum class FusionQuality {
    NONE,       // No fusion possible
    POOR,       // Limited sensor data, low confidence
    FAIR,       // Some redundancy, moderate confidence
    GOOD,       // Good sensor mix, high confidence
    EXCELLENT   // Full sensor suite, very high confidence
}

/**
 * Attitude estimation using Kalman filter for aviation use
 */
class AttitudeKalmanFilter {
    private var phi = 0.0 // Roll angle
    private var theta = 0.0 // Pitch angle
    private var psi = 0.0 // Yaw angle

    private var p11 = 1.0; private var p12 = 0.0; private var p13 = 0.0
    private var p21 = 0.0; private var p22 = 1.0; private var p23 = 0.0
    private var p31 = 0.0; private var p32 = 0.0; private var p33 = 1.0

    private val qAngle = 0.001 // Process noise for angle
    private val qGyro = 0.003 // Process noise for gyro bias
    private val rAngle = 0.03 // Measurement noise

    fun update(
        gyroX: Double, gyroY: Double, gyroZ: Double, // rad/s
        accelX: Double, accelY: Double, accelZ: Double, // m/s²
        magX: Double, magY: Double, magZ: Double, // μT
        dt: Double // time step in seconds
    ): Triple<Double, Double, Double> {

        // Prediction step using gyro data
        phi += dt * (gyroX + phi * qGyro)
        theta += dt * (gyroY + theta * qGyro)
        psi += dt * (gyroZ + psi * qGyro)

        // Update covariance matrix
        p11 += dt * (dt * p22 - p12 - p21 + qAngle)
        p12 -= dt * p22
        p21 -= dt * p22
        p22 += qGyro * dt

        // Calculate accelerometer angles
        val accelMagnitude = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
        val accelPhi = atan2(accelY, accelZ)
        val accelTheta = asin(accelX / accelMagnitude)

        // Calculate magnetometer yaw
        val magPhi = atan2(magY, magZ)
        val magMagnitude = sqrt(magX * magX + magY * magY + magZ * magZ)
        val magTheta = asin(magX / magMagnitude)
        val magYaw = atan2(-magY * cos(accelPhi) + magZ * sin(accelPhi),
                          magX * cos(accelTheta) + magY * sin(accelTheta) * sin(accelPhi) + magZ * sin(accelTheta) * cos(accelPhi))

        // Kalman gain
        val s11 = p11 + rAngle
        val s21 = p21
        val s31 = p31

        val k11 = p11 / s11
        val k21 = p21 / s11
        val k31 = p31 / s11

        // Update angles with measurements
        phi += k11 * (accelPhi - phi)
        theta += k21 * (accelTheta - theta)
        psi += k31 * (magYaw - psi)

        // Update covariance matrix
        p11 -= k11 * p11
        p12 -= k11 * p12
        p13 -= k11 * p13
        p21 -= k21 * p11
        p22 -= k21 * p12
        p23 -= k21 * p13
        p31 -= k31 * p11
        p32 -= k31 * p12
        p33 -= k31 * p13

        return Triple(phi, theta, psi)
    }

    fun getAttitude(): Triple<Double, Double, Double> = Triple(phi, theta, psi)
}

    /**
     * Enhanced sensor fusion for aviation situational awareness
     */
    private fun performSensorFusion(
        gpsData: GPSData?,
        barometerData: BarometerData?,
        accelerometerData: AccelerometerData?,
        gyroscopeData: GyroscopeData?
    ): SensorFusionState {
        val timestamp = System.currentTimeMillis()
        var positionAccuracy = Double.MAX_VALUE
        var altitudeAccuracy = Double.MAX_VALUE
        var velocityAccuracy = Double.MAX_VALUE
        var attitudeAccuracy = Double.MAX_VALUE
        var sensorRedundancy = 0
        var kalmanConvergence = false

        // GPS data fusion
        gpsData?.let { gps ->
            sensorRedundancy++
            positionAccuracy = min(positionAccuracy, gps.accuracy.toDouble())
            velocityAccuracy = min(velocityAccuracy, gps.accuracy * 0.1) // Estimate velocity accuracy
        }

        // Barometer data fusion for altitude
        barometerData?.let { baro ->
            sensorRedundancy++
            altitudeAccuracy = min(altitudeAccuracy, 5.0) // Barometer typically accurate to 5m
        }

        // Accelerometer data for attitude estimation
        accelerometerData?.let { accel ->
            sensorRedundancy++

            // Estimate attitude accuracy from accelerometer stability
            val accelMagnitude = sqrt(accel.x * accel.x + accel.y * accel.y + accel.z * accel.z)
            attitudeAccuracy = min(attitudeAccuracy, abs(accelMagnitude - 9.81) * 10) // degrees
        }

        // Gyroscope data for attitude rate integration
        gyroscopeData?.let { gyro ->
            sensorRedundancy++
        }

        // Determine fusion quality based on sensor availability and accuracy
        val fusionQuality = when {
            sensorRedundancy >= 4 && positionAccuracy < 10 && altitudeAccuracy < 10 -> FusionQuality.EXCELLENT
            sensorRedundancy >= 3 && positionAccuracy < 20 -> FusionQuality.GOOD
            sensorRedundancy >= 2 && positionAccuracy < 50 -> FusionQuality.FAIR
            sensorRedundancy >= 1 -> FusionQuality.POOR
            else -> FusionQuality.NONE
        }

        // Check sensor convergence (simplified)
        kalmanConvergence = sensorRedundancy >= 2 && positionAccuracy < 20.0

        return SensorFusionState(
            timestamp = timestamp,
            positionAccuracy = positionAccuracy,
            altitudeAccuracy = altitudeAccuracy,
            velocityAccuracy = velocityAccuracy,
            attitudeAccuracy = attitudeAccuracy,
            fusionQuality = fusionQuality,
            sensorRedundancy = sensorRedundancy,
            kalmanFilterConvergence = kalmanConvergence
        )
    }

/**
 * Simple moving average calculator for sensor smoothing
 */
class MovingAverage(private val size: Int) {
    private val values = mutableListOf<Double>()
    private var sum = 0.0

    fun add(value: Double): Double {
        values.add(value)
        sum += value

        if (values.size > size) {
            sum -= values.removeAt(0)
        }

        return sum / values.size
    }

    fun getAverage(): Double {
        return if (values.isEmpty()) 0.0 else sum / values.size
    }

    fun reset() {
        values.clear()
        sum = 0.0
    }
}