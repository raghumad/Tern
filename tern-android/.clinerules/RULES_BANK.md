# 📋 RULES BANK: Tern Development Guidelines

## 🚨 MANDATORY RULES (Non-Negotiable)

### 1. Redux Architecture Compliance
- ✅ **MUST** use Redux for ALL state changes
- ✅ **MUST** extend `BaseOverlayManager` for overlay features
- ✅ **MUST** dispatch actions, never direct state manipulation
- ✅ **MUST** observe state with `.collectAsState()`
- ❌ **NEVER** manage state in UI components
- ❌ **NEVER** call overlay methods directly from UI

### 2. Aviation Safety Standards
- ✅ **MUST** validate GPS before all aviation operations
- ✅ **MUST** maintain visual continuity during flight
- ✅ **MUST** implement progressive enhancement
- ✅ **MUST** preserve safety-critical overlays under memory pressure
- ❌ **NEVER** block UI during flight operations
- ❌ **NEVER** compromise safety for performance

### 3. Performance Requirements
- ✅ **MUST** maintain <10 Redux dispatches/sec
- ✅ **MUST** keep memory usage <75%
- ✅ **MUST** ensure 60fps UI updates
- ✅ **MUST** use background processing for heavy operations
- ❌ **NEVER** load entire countries for spatial queries
- ❌ **NEVER** auto-clear caches on startup

### 4. Code Quality Standards
- ✅ **MUST** compile without errors or warnings
- ✅ **MUST** follow existing architectural patterns
- ✅ **MUST** test on device before GitHub push
- ✅ **MUST** maintain aviation safety standards
- ❌ **NEVER** introduce compilation warnings
- ❌ **NEVER** break existing functionality

## ⚠️ STRONG RECOMMENDATIONS

### 5. Development Workflow
- ✅ **SHOULD** make minimal changes (1-3 files max per feature)
- ✅ **SHOULD** test immediately after each change
- ✅ **SHOULD** push working checkpoints to GitHub
- ✅ **SHOULD** follow incremental approach: implement → test → push → repeat

### 6. Memory Management
- ✅ **SHOULD** use distance-based zoning for overlays
- ✅ **SHOULD** implement Hilbert curve animations
- ✅ **SHOULD** respect device memory class limitations
- ✅ **SHOULD** monitor Android memory state continuously

### 7. Paraglider-Specific Priorities
- ✅ **SHOULD** prioritize danger areas and parachute zones
- ✅ **SHOULD** show landing options below 1000ft AGL
- ✅ **SHOULD** correlate thermal overlays with thermal activity
- ✅ **SHOULD** adapt to flight phase requirements

## 📋 IMPLEMENTATION PATTERNS

### Redux Action Pattern
```kotlin
// ✅ CORRECT: Action → Dispatch → Reducer → State
sealed class MapAction {
    data class UpdateLocation(val location: GeoPoint?) : MapAction()
}

fun mapReducer(state: MapState, action: MapAction): MapState {
    return when (action) {
        is MapAction.UpdateLocation -> state.copy(userLocation = action.location)
    }
}

// In component:
val state by store.state.collectAsState()
Button(onClick = { store.dispatch(MapAction.UpdateLocation(location)) })
```

### Overlay Manager Pattern
```kotlin
// ✅ CORRECT: Extend BaseOverlayManager
class AirspaceOverlayManager(
    context: Context,
    store: MapStore
) : BaseOverlayManager(OverlayType.AIRSPACE, store) {

    override fun onReduxStateChanged(state: MapState) {
        // React to state changes
        if (state.overlayState.airspaces.enabled) {
            loadAirspacesForLocation(state.userLocation)
        }
    }
}
```

### GPS Safety Pattern
```kotlin
// ✅ CORRECT: Validate before operations
fun performAviationOperation() {
    if (!hasValidGPSFix) {
        Log.d(TAG, "GPS not ready, postponing operation")
        return
    }
    // Proceed with validated GPS data
}
```

### Memory-Aware Overlay Management
```kotlin
// ✅ CORRECT: Adaptive allocation
private fun calculateOverlayBudget(memoryState: ApplicationMemoryState): Int {
    return when (memoryState.calculatedPressure) {
        MemoryPressureLevel.CRITICAL -> 50  // Minimum safe
        MemoryPressureLevel.LOW -> 400     // High-end device
        else -> 200                        // Standard allocation
    }
}
```

## 🚦 SUCCESS METRICS CHECKLIST

### Technical Success (Mandatory)
- [ ] Zero compilation errors or warnings
- [ ] <10 Redux dispatches per second
- [ ] <75% memory usage maintained
- [ ] 100% Redux architecture compliance
- [ ] Zero visual discontinuity during flight
- [ ] Progressive enhancement for all devices

### Aviation Safety Success
- [ ] GPS validation before all operations
- [ ] Smooth overlay transitions
- [ ] Safety-critical overlays preserved
- [ ] Visual continuity during border crossings
- [ ] Progressive degradation without safety compromise

### User Experience Success
- [ ] Problems systematically identified and resolved
- [ ] Clear user benefit for all improvements
- [ ] No regression in existing functionality
- [ ] Intuitive experience for all user types

## 🚫 ANTI-PATTERNS TO AVOID

### Redux Anti-Patterns
- ❌ Direct state manipulation in components
- ❌ Multiple sources of truth
- ❌ Complex business logic in UI components
- ❌ Redux-first development before working features

### Performance Anti-Patterns
- ❌ Loading entire countries for spatial queries
- ❌ Auto-clearing caches on startup
- ❌ Blocking operations on main thread
- ❌ Individual Redux dispatches for batch operations

### Safety Anti-Patterns
- ❌ Operations without GPS validation
- ❌ Abrupt overlay clearing during flight
- ❌ Compromising safety for performance
- ❌ No progressive enhancement fallback

## 🛠️ DEVELOPMENT TOOLS & PATTERNS

### File Modification Standards
- Use `diff` and `patch` commands for precise editing
- Maintain existing code formatting and patterns
- Test compilation after each change
- Push working checkpoints to GitHub

### Debug Monitoring
- PerformanceDebugger tracks 7 priority levels
- Memory monitoring with Android ActivityManager
- Redux dispatch frequency monitoring
- Spatial query performance tracking

### Quality Gates
- **Pre-Push**: Zero warnings, device tested, no regressions
- **Post-Push**: Performance verified, safety maintained
- **User Validation**: Problems resolved, benefits clear

## 🎯 SUCCESS METRICS (Mandatory for ALL Features)

### Core Technical Standards
- **Performance**: <10 Redux dispatches/sec, <75% memory usage
- **Safety**: Zero visual discontinuity during border crossings
- **Competition Ready**: Handle X-Alps routes with <3 country downloads total
- **Code Quality**: Zero compilation errors, zero warnings
- **Architecture**: 100% Redux compliance, no legacy patterns

### User Experience Standards
- **Problems Resolved**: Original issue completely solved
- **No Regression**: Existing features unaffected
- **Progressive Enhancement**: Works for all user types
- **Clear Benefit**: Improvement obvious to users
- **Aviation Safety**: Standards maintained (smooth transitions, no UI blocking)

## 📊 Implementation Verification Process

**✅ CONSIDERED COMPLETE ONLY WHEN:**

1. **Technical Verification**:
   - [ ] Code compiles without errors or warnings
   - [ ] Redux architecture compliance verified
   - [ ] Performance targets met (<10 dispatches/sec, <75% memory)
   - [ ] No regression in existing functionality
   - [ ] Aviation safety standards maintained

2. **User Experience Verification**:
   - [ ] Problem described by users is resolved
   - [ ] Solution provides clear user benefit
   - [ ] No negative impact on expert users
   - [ ] Progressive enhancement maintained

3. **Quality Assurance**:
   - [ ] Git commit with clear description
   - [ ] Technical documentation updated
   - [ ] Success metrics validated through testing

**🚫 NOT COMPLETE IF:**
- Code has compilation errors
- Performance targets exceeded
- Redux architecture violated
- Aviation safety compromised
- User experience degraded

This Rules Bank ensures all development maintains the high standards of aviation safety, performance, and code quality that define the Tern paragliding app.
