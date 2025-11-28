package com.madanala.tern.model

data class SkewTPoint(
    val altitude: Double, // meters
    val temperature: Double, // Celsius
    val dewpoint: Double // Celsius
)

data class SkewTForecast(
    val points: List<SkewTPoint>
)

data class SkewTAnalysis(
    val hasInversion: Boolean,
    val inversionBaseAltitude: Double?,
    val estimatedBreakTimeHours: Double = 0.0
)

data class CapePoint(
    val time: String, // HH:mm
    val cape: Double // J/kg
)

enum class RiskLevel {
    LOW, MODERATE, HIGH, EXTREME
}

data class OverdevelopmentRisk(
    val peakTime: String,
    val maxCape: Double,
    val riskLevel: RiskLevel
)

object WeatherAnalyzer {

    private const val DRY_ADIABATIC_LAPSE_RATE = 9.8 // °C per 1000m

    fun analyzeSkewT(forecast: SkewTForecast, currentSurfaceTemp: Double = 0.0): SkewTAnalysis {
        var hasInversion = false
        var inversionBase: Double? = null
        
        // Simple inversion detection: Temp increases with altitude
        val sortedPoints = forecast.points.sortedBy { it.altitude }
        for (i in 0 until sortedPoints.size - 1) {
            val p1 = sortedPoints[i]
            val p2 = sortedPoints[i+1]
            
            if (p2.temperature > p1.temperature) {
                hasInversion = true
                inversionBase = p1.altitude
                break
            }
        }

        // Break time estimation
        // Find the warmest point in the inversion layer (or above the base)
        var breakTime = 0.0
        if (hasInversion && inversionBase != null) {
            // Find the peak temperature point within or above the inversion base
            // This is a simplification. Ideally we find the point where the dry adiabat intersects the ELR.
            // But taking the max temp in the inversion layer is a good proxy for the "lid".
            val inversionLayerPoints = sortedPoints.filter { it.altitude >= inversionBase!! }
            val peakInversionPoint = inversionLayerPoints.maxByOrNull { it.temperature }
            
            if (peakInversionPoint != null) {
                // T_surface = T_peak + (Altitude * LapseRate / 1000)
                val requiredSurfaceTemp = peakInversionPoint.temperature + (peakInversionPoint.altitude * DRY_ADIABATIC_LAPSE_RATE / 1000.0)
                
                if (requiredSurfaceTemp > currentSurfaceTemp) {
                    // Assume 2°C/hr heating rate
                    val heatingRate = 2.0 
                    breakTime = (requiredSurfaceTemp - currentSurfaceTemp) / heatingRate
                }
            }
        }

        return SkewTAnalysis(hasInversion, inversionBase, breakTime)
    }

    fun estimateCloudBase(forecast: SkewTForecast): Double {
        // Simple approximation: (Temp - Dewpoint) * 125 + Altitude
        // Using surface values
        val surface = forecast.points.minByOrNull { it.altitude } ?: return 0.0
        val spread = surface.temperature - surface.dewpoint
        return (spread * 125.0) + surface.altitude
    }

    fun analyzeOverdevelopmentRisk(forecast: List<CapePoint>): OverdevelopmentRisk {
        val maxPoint = forecast.maxByOrNull { it.cape } ?: return OverdevelopmentRisk("", 0.0, RiskLevel.LOW)
        
        val risk = when {
            maxPoint.cape < 500 -> RiskLevel.LOW
            maxPoint.cape < 1000 -> RiskLevel.MODERATE
            maxPoint.cape < 2500 -> RiskLevel.HIGH
            else -> RiskLevel.EXTREME
        }
        
        return OverdevelopmentRisk(maxPoint.time, maxPoint.cape, risk)
    }
}
