# 🗺️ Route Planner Feature Specification

## Overview
Complete specification for the iOS route planning feature integration, including UX flows, safety considerations, technical requirements, and implementation details.

## 🎯 User Experience Design

### Primary Entry Points
- **Launch Site Selection** - Browse established PG sites as starting points
- **Map-Based Creation** - Long press to add waypoints, drag to edit
- **Competition Import** - Import official FAI tasks
- **Popular Routes** - Browse community XC routes

### Route Creation Workflow
1. **Select Launch Site** → Creates WP1 with launch characteristics
2. **Add Turnpoints** → Tap map or select from PG spots database
3. **Configure Waypoints** → Set types, elevations, cylinder radii
4. **Safety Checklists** → Complete pre-flight safety validation
5. **Offline Caching** → Download route-line tiles and safety data
6. **Export/Share** → Generate QR codes or files for sharing

### Waypoint Type System
- **🏔️ Launch** - Takeoff sites with elevation and conditions
- **⭕ Turnpoint** - FAI competition cylinders (400m default)
- **🏁 Landing** - Safe landing zones with terrain analysis
- **📍 Intermediate** - Route waypoints for navigation
- **🌪️ Thermal** - Known thermal areas for soaring

## 🛡️ Safety-First Architecture

### Pre-Flight Safety Checklists
```
Aviation Safety:
□ Wing inspection and airworthiness
□ Harness and carabiner checks
□ Reserve parachute verification
□ Weather condition assessment

Personal Resources:
□ Water (2L minimum for XC flights)
□ Snacks and energy food
□ Electrolyte supplements
□ Emergency cash (€50-100)

Electronics & Communication:
□ Phone fully charged + backup battery
□ Radio charged and tested
□ GPS device functional
□ Headlamp/flashlight

Emergency Preparedness:
□ Emergency contacts list
□ Flight plan shared with someone
□ Retrieve driver arranged
□ Local radio frequencies
□ Hospital locations cached
```

### Offline-First Caching Strategy
- **Route-Line Downloads** - Map tiles downloaded along flight path lines (Launch→WP1→Landing) with 2km buffer
- **Airspace Data** - All relevant airspace for route corridor
- **Terrain Data** - Elevation profiles for entire route
- **Weather Data** - Current conditions + 6-hour forecast
- **Emergency Services** - Hospitals, emergency contacts, phone numbers
- **PG Spots** - All takeoff/landing sites in route area

### Cache Status Monitoring
- **Visual Indicators** - Show cache progress during planning
- **Validation** - Verify all data cached before flight
- **Graceful Degradation** - Multiple fallback levels if cache incomplete

## ⚙️ Technical Implementation Requirements

### Redux State Management
```kotlin
data class RouteState(
    val waypoints: List<Waypoint> = emptyList(),
    val selectedRoute: Route? = null,
    val isEditMode: Boolean = false,
    val cacheStatus: CacheStatus = CacheStatus.EMPTY,
    val safetyChecklist: SafetyChecklist = SafetyChecklist()
)

sealed class RouteAction {
    data class AddWaypoint(val waypoint: Waypoint) : RouteAction()
    data class UpdateWaypoint(val index: Int, val waypoint: Waypoint) : RouteAction()
    data class CacheRouteData(val route: Route) : RouteAction()
    data class UpdateSafetyChecklist(val checklist: SafetyChecklist) : RouteAction()
}
```

### FAI Competition Compliance
- **Cylinder Validation** - 400m radius standard for turnpoints
- **Task Types** - Race to Goal, Elapsed Time, Open Distance
- **Scoring System** - Distance/speed/leading points calculation
- **Track Validation** - IGC format for official validation

### QR Code System (iOS-Tested)
- **XCTSK Format** - XCTrack competition standard
- **5-Bit Encoding** - Compact integer encoding for altitude/radius
- **Polyline Encoding** - Google polyline algorithm for coordinates
- **Multi-format Support** - XCTSK, CUP, CompeGPS WPT, GPX, KML

## 🗺️ Map Integration Features

### Interactive Waypoint Management
- **Tap to Create** - Long press map to add waypoints
- **Drag to Edit** - Move waypoints to exact positions
- **Visual Feedback** - Numbered markers (WP1, WP2, WP3...)
- **Cylinder Overlays** - FAI-standard 400m circles
- **Route Lines** - Geodesic polylines with distance labels

### Real-Time Map Features
- **Live Route Updates** - Route adjusts as waypoints are modified
- **Distance Calculations** - Real-time leg and total distance
- **Elevation Profiles** - Terrain elevation along route
- **Safety Validation** - Real-time airspace and terrain conflict detection

## 📱 User Interface Components

### Route Planning Screen
- **Map View** - Interactive map with route overlays
- **Waypoint List** - Scrollable list of route waypoints
- **Route Statistics** - Distance, duration, elevation gain
- **Action Buttons** - Export, share, duplicate, delete

### Waypoint Editor
- **Basic Info** - Name, description, elevation
- **FAI Settings** - Cylinder radius, turnpoint type
- **Safety Notes** - Custom notes and warnings
- **Weather Integration** - Current conditions display

### Safety Checklist Interface
- **Category Tabs** - Aviation, Resources, Electronics, Emergency
- **Progress Tracking** - Visual completion indicators
- **Validation** - Prevents flight without completed checks
- **Custom Notes** - User-defined safety considerations

## 🌐 Offline Execution Capabilities

### Flight Mode Features
- **Cached Map Display** - Route visible without connectivity
- **Navigation Assistance** - Bearing and distance to waypoints
- **Safety Information** - Emergency contacts and procedures
- **Weather Reference** - Cached forecast for decision making
- **Alternative Routes** - Pre-calculated escape options

### Performance Requirements
- **<10 Redux dispatches/sec** during route editing
- **<75% memory usage** with complex route caching
- **Smooth map interactions** during waypoint editing
- **No UI blocking** during cache operations

## 📤 Sharing and Export Features

### QR Code Generation
- **iOS-Compatible Format** - XCTSK with 5-bit encoding
- **Instant Sharing** - Scan QR to import complete routes
- **Competition Ready** - FAI task sharing and validation
- **Social Integration** - WhatsApp, email, Airtribune

### Multi-Format Export
- **XCTSK** - Competition and XCTrack compatibility
- **GPX** - Garmin and navigation app compatibility
- **CUP** - GPS device compatibility
- **KML** - Google Earth and mapping software
- **IGC** - FAI official track validation format

## 🔗 Integration Points

### Existing System Integration
- **Redux Architecture** - Route state management through existing store
- **Overlay System** - Route visualization using BaseOverlayManager pattern
- **Weather System** - Route optimization using existing WeatherRouter
- **Cache System** - Route data caching using existing FlexBuffers system
- **Location Services** - Launch site discovery using existing location system

### New System Components
- **RouteOverlayManager** - Extends BaseOverlayManager for route visualization
- **CacheManager** - Route-specific caching for offline execution
- **ExportManager** - Multi-format export with QR code generation
- **SafetyManager** - Pre-flight checklist and validation system

## ✅ Success Metrics

### Functional Requirements
- ✅ Interactive waypoint management (add, edit, delete, drag)
- ✅ Route visualization on map with overlays
- ✅ FAI compliance (400m cylinders, standard formats)
- ✅ Multi-format export (GPX, KML, XCTSK, CUP)
- ✅ QR code sharing (iOS-tested 5-bit encoding)
- ✅ Comprehensive safety checklists
- ✅ Offline route execution capability

### Performance Requirements
- ✅ <10 Redux dispatches/sec during route editing
- ✅ Smooth map interactions with overlay updates
- ✅ <75% memory usage with complex routes
- ✅ No UI blocking during cache operations
- ✅ Fast QR code generation/scanning

### Safety Requirements
- ✅ Complete offline functionality
- ✅ Pre-flight checklist validation
- ✅ Emergency information always accessible
- ✅ Graceful degradation if cache incomplete
- ✅ Clear safety status indicators

## 📋 Implementation Checklist

### Phase 1: Redux Foundation
- [ ] RouteState data class and Redux actions
- [ ] RouteOverlayManager extending BaseOverlayManager
- [ ] Basic waypoint storage in Redux state

### Phase 2: Map Integration
- [ ] Interactive waypoint creation (tap to add)
- [ ] Route visualization (polylines and markers)
- [ ] Waypoint editing (drag to move, tap to edit)

### Phase 3: Route Calculation
- [ ] Distance/bearing calculations between waypoints
- [ ] Route validation and safety checks
- [ ] Route statistics (total distance, leg distances)

### Phase 4: FAI Competition Rules
- [ ] FAI turnpoint cylinder validation (400m radius)
- [ ] Competition task management
- [ ] FAI scoring system integration

### Phase 5: Export System
- [ ] Multi-format export (GPX, KML, XCTSK, CUP)
- [ ] File sharing capabilities
- [ ] QR code foundation setup

### Phase 6: Safety Integration
- [ ] Pre-flight checklist system
- [ ] Route-line tile caching
- [ ] Offline validation and execution

### Phase 7: Weather Integration
- [ ] Weather-optimized route display
- [ ] Thermal opportunity visualization
- [ ] Risk assessment integration

---

*This document provides detailed technical specifications for the route planner implementation. Refer to PROJECT_PLAN.md for high-level roadmap and priorities.*