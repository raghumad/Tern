package com.madanala.tern.model

import org.osmdroid.util.GeoPoint
import java.time.Instant

/**
 * Core sensor data structures for aviation use
 * Real-time sensor fusion for flight computer functionality
 */

/**
 * GPS position data with aviation-grade accuracy requirements
 */
data class GPSData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double, // Meters above sea level
    val accuracy: Float, // Horizontal accuracy in meters
    val altitudeAccuracy: Float, // Vertical accuracy in meters
    val speed: Float, // Ground speed in m/s
    val bearing: Float, // Direction of movement in degrees
    val timestamp: Long = System.currentTimeMillis(),
    val satellites: Int = 0,
    val fixQuality: GPSFixQuality = GPSFixQuality.NONE
)

/**
 * GPS fix quality for aviation safety
 */
enum class GPSFixQuality {
    NONE,           // No fix
    LOW,           // Basic fix, not suitable for aviation
    MODERATE,      // Good for navigation but not precision approach
    HIGH,          // Aviation-grade fix suitable for all operations
    RTK           // RTK precision for competition use
}

/**
 * Barometric pressure sensor data
 */
data class BarometerData(
    val pressure: Float, // hPa
    val altitude: Float, // Meters above sea level
    val temperature: Float, // Celsius
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Accelerometer data for flight analysis
 */
data class AccelerometerData(
    val x: Float, // m/s²
    val y: Float, // m/s²
    val z: Float, // m/s²
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Gyroscope data for attitude estimation
 */
data class GyroscopeData(
    val x: Float, // rad/s
    val y: Float, // rad/s
    val z: Float, // rad/s
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Combined sensor data with aviation calculations
 */
data class FlightData(
    val gps: GPSData,
    val barometer: BarometerData? = null,
    val accelerometer: AccelerometerData? = null,
    val gyroscope: GyroscopeData? = null,
    val timestamp: Long = System.currentTimeMillis(),

    // Aviation-specific calculations
    val groundSpeed: Double = 0.0, // knots
    val track: Double = 0.0, // degrees
    val glideRatio: Double? = null,
    val verticalSpeed: Double? = null, // m/s
    val windSpeed: Double? = null, // knots
    val windDirection: Double? = null, // degrees
    val altitudeAGL: Double? = null // meters above ground level
)

/**
 * Variometer data for thermal and ridge soaring
 */
data class VariometerData(
    val verticalSpeed: Double, // m/s
    val altitude: Double, // meters MSL
    val timestamp: Long = System.currentTimeMillis(),
    val averagingPeriod: Int = 10, // seconds
    val trend: VarioTrend = VarioTrend.STEADY,
    val thermalStrength: ThermalStrength = ThermalStrength.NONE
)

/**
 * Thermal strength assessment for soaring
 */
enum class ThermalStrength {
    NONE,      // No thermal activity
    WEAK,      // Light lift, < 1 m/s
    MODERATE,  // Good soaring, 1-3 m/s
    STRONG,    // Excellent soaring, 3-6 m/s
    EXTREME    // Dangerous, > 6 m/s
}

/**
 * Variometer trend for thermal analysis
 */
enum class VarioTrend {
    SINKING,   // Consistent sink
    STEADY,    // No significant change
    LIFTING,   // Entering lift
    STRONG_LIFT // Strong thermal core
}

/**
 * Sensor fusion state for aviation use
 */
data class SensorState(
    val isActive: Boolean = false,
    val availableSensors: Set<SensorType> = emptySet(),
    val sensorAccuracy: SensorAccuracy = SensorAccuracy.LOW,
    val lastUpdate: Long = 0L,
    val batteryImpact: BatteryImpact = BatteryImpact.LOW,
    val flightMode: FlightMode = FlightMode.GROUND
)

/**
 * Available sensor types
 */
enum class SensorType {
    GPS,
    BAROMETER,
    ACCELEROMETER,
    GYROSCOPE,
    MAGNETOMETER,
    GPS_BARO_ALTITUDE
}

/**
 * Overall sensor accuracy assessment
 */
enum class SensorAccuracy {
    NONE,      // No sensors available
    LOW,       // Basic functionality, limited aviation use
    MODERATE,  // Good for cross-country flying
    HIGH,      // Competition-grade accuracy
    EXTREME    // Maximum precision for competition
}

/**
 * Battery impact of sensor usage
 */
enum class BatteryImpact {
    NONE,      // No sensors active
    LOW,       // GPS + basic sensors, minimal impact
    MODERATE,  // All sensors active, noticeable impact
    HIGH,      // Continuous high-frequency sampling
    EXTREME    // All sensors + recording, significant impact
}

/**
 * Current flight mode for sensor configuration
 */
enum class FlightMode {
    GROUND,    // On ground, normal sensor usage
    LAUNCH,    // Launch preparation, high-frequency sensors
    FLIGHT,    // In flight, optimized sensor mix
    LANDING,   // Landing approach, critical sensors only
    THERMAL,   // Thermal soaring, variometer priority
    RIDGE,     // Ridge soaring, wind sensors priority
    COMPETITION // Competition mode, maximum precision
}

/**
 * Sensor configuration for different flight phases
 */
data class SensorConfig(
    val flightMode: FlightMode,
    val enabledSensors: Set<SensorType>,
    val gpsUpdateRate: Long = 1000L, // milliseconds
    val barometerUpdateRate: Long = 1000L,
    val motionUpdateRate: Long = 100L, // High frequency for flight analysis
    val recordingEnabled: Boolean = false,
    val fusionEnabled: Boolean = true
)

/**
 * Flight performance metrics
 */
data class FlightMetrics(
    val startTime: Long,
    val duration: Long, // milliseconds
    val distance: Double, // meters
    val maxAltitude: Double, // meters
    val altitudeGain: Double, // meters
    val maxGroundSpeed: Double, // knots
    val averageGroundSpeed: Double, // knots
    val maxVerticalSpeed: Double, // m/s
    val maxSinkRate: Double, // m/s
    val thermalCount: Int,
    val averageGlideRatio: Double,
    val flightPath: List<GeoPoint> = emptyList()
)

/**
 * Real-time flight computer data
 */
data class FlightComputerData(
    val currentPosition: GeoPoint,
    val altitudeMSL: Double, // meters
    val altitudeAGL: Double, // meters
    val groundSpeed: Double, // knots
    val track: Double, // degrees
    val verticalSpeed: Double, // m/s
    val windSpeed: Double, // knots
    val windDirection: Double, // degrees
    val glideRatio: Double,
    val requiredGlideRatio: Double? = null, // For final glide calculations
    val distanceToGoal: Double? = null, // meters
    val estimatedArrivalAltitude: Double? = null, // meters
    val thermalStrength: ThermalStrength = ThermalStrength.NONE,
    val flightMode: FlightMode = FlightMode.FLIGHT,
    val timestamp: Long = System.currentTimeMillis()
)