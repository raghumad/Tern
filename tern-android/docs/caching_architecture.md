# Caching System Architecture

This document provides a high-level overview of the caching system used in the Tern application, specifically for map overlays like PG Spots and Airspaces.

## Class Diagram

```mermaid
classDiagram
    class CacheManager {
        <<Singleton>>
        +AirspaceCache airspaceCache
        +PGSpotCache pgSpotCache
        +RouteCache routeCache
        +WeatherCache weatherCache
        +initialize(Context)
        +getCacheStats()
        +clearAllCaches()
    }

    class UniversalCountryCacheManager {
        -AirspaceCache airspaceCache
        -PGSpotCache pgSpotCache
        -Set~String~ cachedCountries
        -GeoPoint lastLocation
        +onCountryLoaded: ((String) -> Unit)?
        +onLocationChanged(GeoPoint)
        +preloadCountry(String)
        +queryMultiCountryArea(GeoPoint, Double)
        +getCacheStats()
    }

    class PGSpotCache {
        -File cacheDir
        -ConcurrentHashMap~String, Long~ cacheIndex
        -ConcurrentHashMap~String, SpatialIndex~ spatialIndexCache
        -ConcurrentHashMap~String, MappedByteBuffer~ memoryMappedBuffers
        +isCached(String): Boolean
        +downloadAndCache(String): List~OverlayFeature~?
        +queryNearbyPGSpots(String, GeoPoint, Double): List~OverlayFeature~
        +clearCache()
        +getCacheStats()
    }

    class AirspaceCache {
        -File cacheDir
        -ConcurrentHashMap~String, Long~ cacheIndex
        -ConcurrentHashMap~String, SpatialIndex~ spatialIndexCache
        -ConcurrentHashMap~String, MappedByteBuffer~ memoryMappedBuffers
        +isCached(String): Boolean
        +downloadAndCache(String): Boolean
        +queryNearbyFeatures(String, GeoPoint, Double): List~OverlayFeature~
        +clearCache()
        +getCacheStats()
    }

    class RouteCache {
        -File cacheDir
        -ConcurrentHashMap~String, Long~ cacheIndex
        -ConcurrentHashMap~String, SpatialIndex~ spatialIndexCache
        -ConcurrentHashMap~String, MappedByteBuffer~ memoryMappedBuffers
        +isCached(String): Boolean
        +cacheRoute(Route)
        +getCachedRoute(String): Route?
        +queryNearbyRoutes(GeoPoint, Double): List~Route~
        +clearCache()
    }

    class WeatherCache {
        -File cacheDir
        -ConcurrentHashMap~String, Long~ cacheIndex
        -ConcurrentHashMap~String, SpatialIndex~ spatialIndexCache
        -ConcurrentHashMap~String, MappedByteBuffer~ memoryMappedBuffers
        +cacheWeather(String, GeoPoint, WeatherForecast)
        +queryNearbyWeather(String, GeoPoint, Double): List~WeatherForecast~
        +clearCache()
    }

    class PGSpotWeatherCache {
        -LruCache~String, CachedWeatherData~ memoryCache
        +queryNearbyWeather(Double, Double, Double): WeatherForecast?
        +cacheWeatherData(Double, Double, WeatherForecast)
        +removeExpiredData()
        +clearCache()
    }

    class MapOverlayCacheUtils {
        <<Utility>>
        +createSpatialIndexAndSerialize(List~OverlayFeature~)
        +deserializeFlexBuffersToFeatures(ByteArray)
        +computeHilbertIndex(GeoPoint, Int)
        +parseGeoJsonToFeatures(String, String)
    }

    class OverlayFeature {
        +Map~String, Any~ feature
        +GeoPoint centroid
        +Long hilbertIndex
        +String overlayType
    }

    class SpatialIndex {
        +List~HilbertIndexEntry~ entries
        +Int bits
        +findNearbyIndices(Long, Long)
    }

    class GeoJsonUtils {
        <<Utility>>
        +downloadGeoJson(String): String?
        +validateGeoJsonContent(String, String): Boolean
    }

    CacheManager --> AirspaceCache : manages
    CacheManager --> PGSpotCache : manages
    CacheManager --> RouteCache : manages
    CacheManager --> WeatherCache : manages
    
    UniversalCountryCacheManager --> AirspaceCache : uses
    UniversalCountryCacheManager --> PGSpotCache : uses
    
    PGSpotCache ..> MapOverlayCacheUtils : uses
    AirspaceCache ..> MapOverlayCacheUtils : uses
    RouteCache ..> MapOverlayCacheUtils : uses
    WeatherCache ..> MapOverlayCacheUtils : uses
    
    PGSpotCache ..> GeoJsonUtils : uses
    AirspaceCache ..> GeoJsonUtils : uses
    
    MapOverlayCacheUtils ..> OverlayFeature : creates
    MapOverlayCacheUtils ..> SpatialIndex : creates
```

## Key Components

### 1. CacheManager
The central singleton registry that holds references to all specific cache implementations. It ensures lazy initialization and provides a single point of access for clearing caches or gathering statistics.

### 2. UniversalCountryCacheManager
The orchestrator for location-based data loading. It monitors the user's location, determines the current country, and triggers `downloadAndCache` on the specific caches (`PGSpotCache`, `AirspaceCache`) for that country. It also manages preloading of adjacent countries.

### 3. PGSpotCache, AirspaceCache, RouteCache, & WeatherCache
Specific implementations for different data types. They share a common architecture (except `PGSpotWeatherCache`):
*   **Storage**: Data is stored in **Real FlexBuffers** (binary format) for high-performance serialization and deserialization.
*   **Indexing**: A Hilbert Curve spatial index is used to map 2D geospatial coordinates to a 1D index.
*   **Querying**: Currently uses an **in-memory filtering** approach for correctness. Features are loaded from the binary cache and filtered by distance in memory, bypassing complex Hilbert range queries for now.
*   **Memory Mapping**: Files are memory-mapped (`MappedByteBuffer`) for zero-copy read access during queries.
*   **Downloading**: `PGSpotCache` and `AirspaceCache` handle fetching data from their respective APIs via `GeoJsonUtils`. `RouteCache` and `WeatherCache` are primarily populated by the app's logic.

### 4. PGSpotWeatherCache
A specialized in-memory cache for weather data associated with specific PG spots.
*   **Storage**: Uses an `LruCache` to store `CachedWeatherData` objects in memory.
*   **Indexing**: Uses a simplified on-the-fly Hilbert index approximation for spatial lookups.
*   **Expiration**: Implements strict expiration policies (2 hours for current weather, 12 hours for forecasts) to ensure safety.

### 5. MapOverlayCacheUtils
A utility class that handles the heavy lifting of:
*   Parsing GeoJSON.
*   Computing Hilbert indices.
*   Serializing features to the binary format.
*   Creating the `SpatialIndex`.

### 6. GeoJsonUtils
Handles network requests to download GeoJSON data, with built-in validation and timeouts.

## Data Flow & Control Logic

The following diagram illustrates the lifecycle of data within the `SpatialDiskCache`, from ingestion (download & write) to retrieval (query).

```mermaid
sequenceDiagram
    participant App as Application
    participant Cache as SpatialDiskCache
    participant Utils as MapOverlayCacheUtils
    participant Disk as File System

    %% Ingestion Flow
    note over App, Disk: Phase 1: Ingestion (Write)
    App->>Cache: cacheFeatures(regionId, features)
    activate Cache
    
    Cache->>Utils: createSpatialIndexAndSerialize(features)
    activate Utils
    
    loop For Each Feature
        Utils->>Utils: Compute Centroid (Lat/Lon)
        Utils->>Utils: Compute Hilbert Index (Lat/Lon -> Long)
    end
    
    Utils->>Utils: Sort Features by Hilbert Index (Spatial Sorting)
    
    loop For Each Sorted Feature
        Utils->>Utils: Serialize to FlexBuffer (Binary)
        Utils->>Utils: Record Offset & Length
    end
    
    Utils-->>Cache: Returns (SpatialIndex, ByteArray)
    deactivate Utils
    
    Cache->>Disk: Write .flex file (Binary Data)
    Cache->>Disk: Write .idx file (Spatial Index)
    Cache->>Disk: Map .flex file to Memory (MappedByteBuffer)
    
    deactivate Cache

    %% Query Flow
    note over App, Disk: Phase 2: Query (Read)
    App->>Cache: queryNearby(regionId, center, radius)
    activate Cache
    
    Cache->>Disk: Read .idx file (Load SpatialIndex)
    
    note right of Cache: Current Implementation: In-Memory Filter
    
    Cache->>Disk: Access MappedByteBuffer (.flex file)
    
    loop For Each Entry in SpatialIndex
        Cache->>Cache: Read Length + Data from Buffer
        Cache->>Utils: FlexBuffers.getRoot(data) -> Map
        Cache->>Cache: Calculate Distance(center, feature.centroid)
        alt Distance <= Radius
            Cache->>Cache: Add to Result List
        end
    end
    
    Cache-->>App: Return List<OverlayFeature>
    deactivate Cache
```
