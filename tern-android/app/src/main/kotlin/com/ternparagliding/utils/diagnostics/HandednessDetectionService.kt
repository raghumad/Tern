package com.ternparagliding.utils.diagnostics

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.ternparagliding.redux.Handedness
import com.ternparagliding.redux.HandednessSource

/**
 * Privacy-safe handedness detection service
 * Only runs during onboarding or settings changes - no persistent monitoring
 */
class HandednessDetectionService(private val context: Context) {

    private val TAG = "HandednessDetection"

    /**
     * Main detection method - ONLY runs when explicitly requested
     * Privacy-safe: only reads current system settings, no behavioral tracking
     */
    fun detectHandednessForOnboarding(): HandednessInfo {
        Log.d(TAG, "Running privacy-safe handedness detection for onboarding")

        // Step 1: Try system-level detection (privacy-safe)
        val systemDetection = detectFromSystemSettings()

        return if (systemDetection.confidence >= 0.7f) {
            Log.d(TAG, "High confidence system detection: ${systemDetection.handedness}")
            systemDetection.copy(source = HandednessSource.SYSTEM_DETECTED)
        } else {
            // Step 2: Smart default (no user data involved)
            val smartDefault = getSmartDefault()
            Log.d(TAG, "Using smart default: ${smartDefault.handedness}")
            HandednessInfo(
                handedness = smartDefault.handedness,
                confidence = smartDefault.confidence,
                source = HandednessSource.SMART_DEFAULT,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    /**
     * Privacy-safe system detection - only reads current settings
     */
    private fun detectFromSystemSettings(): HandednessInfo {
        var confidence = 0.0f
        var detectedHandedness = Handedness.RIGHT_HANDED

        // Check accessibility services (some indicate handedness)
        if (hasHandednessAccessibilityService()) {
            detectedHandedness = getAccessibilityHandedness()
            confidence += 0.4f
            Log.d(TAG, "Found handedness in accessibility services: $detectedHandedness")
        }

        // Check input method preferences
        if (hasHandednessInputMethod()) {
            detectedHandedness = getInputMethodHandedness()
            confidence += 0.3f
            Log.d(TAG, "Found handedness in input methods: $detectedHandedness")
        }

        // Check device configuration
        if (hasHandednessDeviceConfig()) {
            detectedHandedness = getDeviceConfigHandedness()
            confidence += 0.2f
            Log.d(TAG, "Found handedness in device config: $detectedHandedness")
        }

        return HandednessInfo(
            handedness = detectedHandedness,
            confidence = confidence.coerceIn(0f, 1f),
            source = HandednessSource.SYSTEM_DETECTED,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun hasHandednessAccessibilityService(): Boolean {
        return try {
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            services?.contains("left") == true ||
            services?.contains("right") == true ||
            services?.contains("onehand") == true ||
            services?.contains("handedness") == true
        } catch (e: Exception) {
            Log.w(TAG, "Error checking accessibility services", e)
            false
        }
    }

    private fun getAccessibilityHandedness(): Handedness {
        return try {
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            when {
                services?.contains("left") == true -> Handedness.LEFT_HANDED
                services?.contains("onehand") == true -> Handedness.AMBIDEXTROUS
                else -> Handedness.RIGHT_HANDED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading accessibility handedness", e)
            Handedness.RIGHT_HANDED
        }
    }

    private fun hasHandednessInputMethod(): Boolean {
        return try {
            val inputMethods = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )
            inputMethods?.contains("left") == true ||
            inputMethods?.contains("right") == true ||
            inputMethods?.contains("swipe") == true
        } catch (e: Exception) {
            Log.w(TAG, "Error checking input methods", e)
            false
        }
    }

    private fun getInputMethodHandedness(): Handedness {
        return try {
            val inputMethods = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )
            when {
                inputMethods?.contains("left") == true -> Handedness.LEFT_HANDED
                inputMethods?.contains("swipe") == true -> Handedness.AMBIDEXTROUS
                else -> Handedness.RIGHT_HANDED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading input method handedness", e)
            Handedness.RIGHT_HANDED
        }
    }

    private fun hasHandednessDeviceConfig(): Boolean {
        // Check device characteristics that might indicate handedness preferences
        val deviceModel = android.os.Build.MODEL.lowercase()
        val deviceBrand = android.os.Build.BRAND.lowercase()

        return deviceModel.contains("fold") ||
               deviceModel.contains("flip") ||
               deviceModel.contains("duo") ||
               deviceBrand.contains("samsung") // Samsung devices often have one-handed modes
    }

    private fun getDeviceConfigHandedness(): Handedness {
        val deviceModel = android.os.Build.MODEL.lowercase()

        return when {
            deviceModel.contains("fold") || deviceModel.contains("flip") -> Handedness.AMBIDEXTROUS
            else -> Handedness.RIGHT_HANDED
        }
    }

    /**
     * Smart default based on device and regional characteristics
     * No user data involved - only device configuration
     */
    private fun getSmartDefault(): HandednessInfo {
        val smartDefault = calculateSmartDefault()
        return HandednessInfo(
            handedness = smartDefault.handedness,
            confidence = smartDefault.confidence,
            source = HandednessSource.SMART_DEFAULT,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun calculateSmartDefault(): HandednessInfo {
        var confidence = 0.5f // Base confidence for smart defaults
        var handedness = Handedness.RIGHT_HANDED

        // Device-based hints
        val deviceModel = android.os.Build.MODEL.lowercase()
        val screenSize = getScreenSizeInches()

        when {
            // Foldable devices often used in multiple orientations
            deviceModel.contains("fold") || deviceModel.contains("flip") -> {
                handedness = Handedness.AMBIDEXTROUS
                confidence += 0.1f
            }

            // Large screens might indicate desk use (less handedness concern)
            screenSize > 7.0 -> {
                handedness = Handedness.AMBIDEXTROUS
                confidence += 0.1f
            }

            // Small screens often used one-handed
            screenSize < 6.0 -> {
                confidence += 0.1f // Slightly higher confidence for small screens
            }
        }

        return HandednessInfo(
            handedness = handedness,
            confidence = confidence.coerceIn(0f, 1f),
            source = HandednessSource.SMART_DEFAULT,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun getScreenSizeInches(): Float {
        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.density

            val widthInches = width / density
            val heightInches = height / density

            // Use diagonal screen size
            kotlin.math.sqrt((widthInches * widthInches) + (heightInches * heightInches))
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating screen size", e)
            6.0f // Default assumption
        }
    }
}

/**
 * Data class for handedness detection results
 */
data class HandednessInfo(
    val handedness: Handedness,
    val confidence: Float,
    val source: HandednessSource,
    val lastUpdated: Long
)