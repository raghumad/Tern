# UX Validation Checklist

This checklist is designed to validate the user experience of all implemented features. Use this to manually verify that the app behaves as expected on a real device or emulator.

## 🗺️ Map & Navigation
- [ ] **Map Rendering**: Map tiles load correctly (OpenTopoMap/Mapbox).
- [ ] **Pan & Zoom**: Smooth panning and zooming interactions.
- [ ] **My Location**: "Locate Me" button centers map on user location.
- [ ] **Scale Bar**: Scale bar updates correctly while zooming.
- [ ] **Compass**: Compass icon appears when map is rotated and resets rotation on tap.

## 📍 Route Planning
- [ ] **Create Route (Long Press)**: Long press on map creates a new route with a "Launch" waypoint.
- [ ] **Add Waypoint (Tap)**: Tapping on the map (when route active) adds a new waypoint.
- [ ] **Select Waypoint**: Tapping an existing waypoint selects it (visual highlight).
- [ ] **Move Waypoint**: Dragging a selected waypoint updates the route line in real-time.
- [ ] **Delete Waypoint**: "Delete" button in waypoint detail panel removes the point.
- [ ] **Smart Suggestions**: Long-pressing near a known PG spot suggests snapping to it.
- [ ] **Undo/Redo**: Undo/Redo buttons work for route modifications.

## 📝 Route Management
- [ ] **Route List**: "Routes" button opens a list of saved routes.
- [ ] **Route Detail**: Tapping a route in the list opens the detail panel.
- [ ] **Route Statistics**: Distance, Duration, and Waypoint count are displayed.
- [ ] **FAI Analysis**: FAI Triangle status (Flat/FAI) and sector visualizations appear.
- [ ] **Share Route**: QR Code generation works; scanning imports the route.
- [ ] **Export Route**: Export to GPX/KML works via Android Sharesheet.

## 🌤️ Weather & Safety
- [ ] **Weather Overlays**: Weather data (Wind/Cloud) appears on the map.
- [ ] **Route Weather Panel**: "Weather" tab in route detail shows forecast.
- [ ] **Hourly Trend Chart**: Chart visualizes wind and cloud cover trends along the route.
- [ ] **Wind Gauges**: Dynamic markers show wind speed/direction at waypoints.
- [ ] **Stale Data Warning**: UI indicates if weather data is older than 4 hours.
- [ ] **Offline Caching**: Weather data persists after app restart (airplane mode test).

## ✈️ Airspaces & Overlays
- [ ] **Airspace Rendering**: Airspaces (Class B, C, D, etc.) are drawn on the map.
- [ ] **Airspace Info**: Tapping an airspace shows name, class, and altitude limits.
- [ ] **PG Spots**: Paragliding launches/landings are visible as icons.
- [ ] **Spot Details**: Tapping a PG spot shows details (wind, glide ratio).
- [ ] **Filter Overlays**: Layer control allows toggling Airspaces/Spots/Routes.

## ⚙️ Settings & System
- [ ] **Theme**: App respects system Dark/Light mode.
- [ ] **Permissions**: Location permission prompt appears and handles denial gracefully.
- [ ] **Offline Mode**: App functions (viewing cached data) without internet.
- [ ] **Performance**: App remains responsive during heavy map manipulation.
