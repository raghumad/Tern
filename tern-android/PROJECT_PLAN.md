# Tern Paragliding App - Project Plan

## Context
Android paragliding app with osmdroid maps, Jetpack Compose UI, Redux architecture, and advanced caching. Focus on aviation safety and performance for competition use (Red Bull X-Alps).

## ✅ COMPLETED (Phases 1-3, Priorities 1-4)
- **Redux Architecture & Performance**: 100% complete with 95%+ reduction in state updates
- **Overlay System**: Smart country management, smooth border transitions, universal caching
- **Critical Bugs**: All resolved (UI blocking, cache persistence, ANR crashes)
- **Aviation Safety**: Border cache issues resolved, continuous visual display during flight

## 🎯 NEXT PRIORITIES

### PRIORITY 5: Enhanced Features & Polish

#### Phase 1: Foundation Layer (Enables All Other Features)
**🔧 Advanced Aviation Features** - *Build First*
- [ ] **Sensor Integration**: Barometer, GPS, accelerometer data fusion for real-time flight data
- [ ] **3D Terrain Visualization**: Elevation profiles, ridge analysis, landing field identification
- [ ] **Flight Computer**: Variometer integration, wind drift calculation, final glide computation
- [ ] **Sensor Fusion**: Combine multiple data sources for enhanced situational awareness

**📊 Strategy Tools** - *Weather routing, tactical decision support, risk assessment*
- [ ] **Weather-Based Routing**: Multi-hour weather forecasting with flight path optimization
- [ ] **Risk Assessment Engine**: Airspace, terrain, and weather risk analysis
- [ ] **Tactical Decision Support**: Real-time recommendations for course corrections

#### Phase 2: Enhanced Weather Intelligence
**Current Status**: Basic weather screen exists with wind/temp/humidity/pressure/visibility
**Missing Enhancements**:
- [ ] **Aviation-Specific Calculations**: Thermal index, cloud base height, lift/sink potential
- [ ] **Flight Conditions Assessment**: Flyable rating (Poor/Fair/Good/Excellent) with color coding
- [ ] **Wind Analysis**: Crosswind/component wind calculations for launch/landing directions
- [ ] **Soaring Forecast**: Ridge/thermal soaring conditions, convective potential
- [ ] **Safety Alerts**: Weather warnings for dangerous conditions (gusts, thunderstorms)
- [ ] **Historical Trends**: 24-hour trend analysis for better flight planning

#### Phase 3: Competition Features (Enabled by Foundation Layer)
**Target**: Competition-grade features for X-Alps style events
**Core Components**:
- [ ] **Route Optimization**: Turnpoint management, optimal route calculation, waypoint navigation
- [ ] **Race Tracking**: Live position tracking, speed/distance calculations, race progress
- [ ] **Competition Mode**: Race timer, checkpoint validation, penalty tracking
- [ ] **Performance Analytics**: Flight efficiency, glide ratio optimization, thermal utilization
- [ ] **Live Leaderboard**: Real-time position comparison, gap analysis, overtake predictions

#### Phase 4: Polish & Production
- [ ] **Testing & Documentation**: Device testing, performance profiling, user documentation
- [ ] **Performance Optimization**: Battery optimization, memory usage validation, network efficiency

### PRIORITY 6: Memory & Network Optimization
- [ ] **Memory Leak Prevention**: Enhanced cleanup and lifecycle management
- [ ] **Request Deduplication**: Prevent duplicate network/spatial requests
- [ ] **Cache Hit Ratio Improvement**: Optimize spatial queries for >80% hit rate
- [ ] **API Rate Limiting**: Background throttling for aviation safety

## Architecture Principles & Enforcement

### Core Architecture (Non-Negotiable)
- **Redux-Only Pattern**: All new features MUST use Redux overlay managers extending `BaseOverlayManager`
- **Aviation Safety First**: Smooth transitions, predictable performance, offline resilience
- **Spatial Optimization**: Hilbert indexing, intelligent country caching (max 4 countries)
- **Performance Monitoring**: Debug-only tracking with zero release impact

### Technical Stack
- **Maps**: osmdroid (offline aviation use)
- **State Management**: Redux exclusively
- **UI**: Jetpack Compose
- **Caching**: FlexBuffers + Hilbert spatial indexing

### Redux Architecture Enforcement (MANDATORY)
**✅ DO (Redux Pattern Only):**
- Create specialized overlay managers extending `BaseOverlayManager`
- Use Redux actions for all state changes (`store.dispatch(Action)`)
- Coordinate through `OverlayCoordinator` for overlay lifecycle
- Follow single responsibility principle per overlay type
- Use Redux state observation (`store.state.collect { }`)

**❌ FORBIDDEN (Legacy Patterns):**
- ❌ Add overlay methods to `MapViewModel`
- ❌ Direct overlay manipulation outside overlay managers
- ❌ Manual overlay lifecycle management in ViewModels
- ❌ Duplicate overlay logic between ViewModel and overlay managers
- ❌ Call overlay manager methods directly from UI components

### Critical Bugfixes (Do Not Repeat)
- **Performance**: Move ALL processing to `Dispatchers.IO` (prevents ANR crashes)
- **Architecture**: Delay map operations until GPS fix (prevents invalid API calls)
- **Cache Management**: Never auto-clear caches on startup (preserves offline capability)
- **Spatial Queries**: Always use `queryNearbyFeatures()` only (never entire countries)

## Success Metrics
- **Performance**: <10 Redux dispatches/sec, <75% memory usage
- **Safety**: Zero visual discontinuity during border crossings
- **Competition Ready**: Handle X-Alps routes with <3 country downloads total

## Risk Assessment
Low risk - building on solid, validated foundation with clear architectural guardrails.

---

# 📱 Sensor Hardware Integration Guide

## Device Sensor Availability Matrix

### Google Pixel Series Sensor Support
| Device Series | Barometer | Gyroscope | Accelerometer | Magnetometer |
|---------------|-----------|-----------|---------------|--------------|
| **Pixel 1-3** | ✅ | ✅ | ✅ | ✅ |
| **Pixel 4-5** | ✅ | ✅ | ✅ | ✅ |
| **Pixel 6-9** | ❌ | ❌ | ✅ | ⚠️ Partial |
| **Pixel 10+** | ❓ | ❓ | ✅ | ❓ |

### Sensor Detection Strategy
```kotlin
// Runtime sensor detection with graceful fallback
val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

// Automatic capability assessment
val sensorAccuracy = when {
    barometer != null && gyroscope != null -> SensorAccuracy.EXTREME
    barometer != null || gyroscope != null -> SensorAccuracy.HIGH
    accelerometer != null -> SensorAccuracy.MODERATE
    else -> SensorAccuracy.LOW
}
```

## Sensor Graceful Degradation Architecture

### 🏔️ Altitude Data Flow
```
With Barometer (Best Case):
GPS Altitude → Barometer → Kalman Filter → Aviation Calculations → Redux State
     ↓              ↓            ↓              ↓                   ↓
±50m raw      ±5m pressure   ±3m filtered   Flight Computer     UI Display
```

```
Without Barometer (GPS Only):
GPS Altitude → Kalman Filter → Aviation Calculations → Redux State
     ↓              ↓              ↓                   ↓
±50m raw       ±20m filtered  Flight Computer     UI Display
```

### 🧭 Attitude Estimation Data Flow
```
With Gyroscope (High Precision):
Accelerometer + Gyroscope → Kalman Filter → Attitude → Redux State
     ↓              ↓            ↓            ↓          ↓
±10° raw       ±2° filtered  Roll/Pitch/Yaw   Aviation   UI Display
```

```
Without Gyroscope (Accel Only):
Accelerometer → Gravity Attitude → Redux State
     ↓               ↓              ↓
±10° raw        Roll/Pitch      Aviation UI
```

### 📊 Performance Comparison Matrix

| Feature | With All Sensors | GPS + Accel Only | GPS Only |
|---------|------------------|------------------|----------|
| **Altitude Accuracy** | ±3-5m | ±10-20m | ±20-50m |
| **Attitude Accuracy** | ±2-5° | ±5-10° | ±15-30° |
| **Update Rate** | 10-20Hz | 5-10Hz | 1Hz |
| **Battery Impact** | Medium | Low | Very Low |
| **Aviation Grade** | 🎯 Professional | ✈️ Advanced | 📱 Standard |

## Critical Path Dependencies

### ✅ Features Independent of Motion Sensors
- **Flight Path Tracking** - GPS-only sufficient
- **Altitude Monitoring** - GPS/barometer enhance but not required
- **Weather Integration** - Network/API based
- **Airspace Awareness** - Database driven
- **Terrain Analysis** - Elevation data from external sources

### ⚠️ Features Enhanced by Motion Sensors
- **Precise Attitude Display** - Gyroscope enables smooth yaw estimation
- **Advanced Flight Dynamics** - Accelerometer enables G-force analysis
- **Coordinated Turn Detection** - Gyroscope enables slip/skid indication
- **Thermal Entry Analysis** - Motion sensors improve circling detection

### 🎯 Competition Features (All Functional Without Motion Sensors)
- **Route Optimization** - Weather and terrain-based algorithms
- **Risk Assessment** - Airspace, terrain, weather analysis
- **Performance Analytics** - Speed, distance, efficiency calculations
- **Live Tracking** - GPS-based position and movement

## Hardware Detection & User Feedback

### Runtime Sensor Assessment
```kotlin
// Automatic sensor capability detection
val sensorState = SensorState(
    availableSensors = detectAvailableSensors(),
    sensorAccuracy = calculateOverallAccuracy(),
    isActive = true,
    flightMode = determineOptimalFlightMode()
)

// Update UI indicators based on actual hardware
when (sensorAccuracy) {
    SensorAccuracy.EXTREME -> showIndicator("🎯 Aviation Grade")
    SensorAccuracy.HIGH -> showIndicator("✈️ Flight Grade")
    SensorAccuracy.MODERATE -> showIndicator("📱 Mobile Grade")
}
```

### User Notification Strategy
- **Startup Detection** - Inform user of sensor availability
- **Feature Adaptation** - Disable/enable features based on hardware
- **Precision Indicators** - Show data accuracy levels in UI
- **Graceful Degradation** - All features work, some with reduced precision

## Future Hardware Considerations

### Sensor Addition Wishlist
1. **Barometer** - Pressure altitude for aviation precision
2. **Gyroscope** - Smooth attitude estimation and turn analysis
3. **Magnetometer** - Compass integration for yaw accuracy
4. **Temperature** - Environmental conditions for density altitude

### Software Compensation Strategies
1. **Enhanced GPS Algorithms** - Multi-constellation improvements
2. **Network Altitude Fallback** - Weather station pressure data
3. **Camera-Based Motion** - Optical flow for attitude estimation
4. **Machine Learning Enhancement** - Pattern recognition for missing sensors

## Development Guidelines

### Mandatory Graceful Degradation
- **✅ DO**: Design all features to work without optional sensors
- **✅ DO**: Implement fallback algorithms for missing hardware
- **✅ DO**: Provide appropriate user feedback about precision levels
- **❌ DON'T**: Create features that require specific sensors
- **❌ DON'T**: Assume sensor availability without runtime checking

### Sensor Priority System
1. **GPS** (Universal) - Core positioning and altitude
2. **Accelerometer** (Nearly Universal) - Motion and attitude
3. **Barometer** (Optional) - Enhanced altitude precision
4. **Gyroscope** (Optional) - Smooth attitude transitions
5. **Magnetometer** (Optional) - Compass and yaw accuracy

This sensor integration strategy ensures the app works excellently on all devices while taking advantage of enhanced hardware when available.
