# Tern release (R8) keep rules.
#
# This app maps a LOT of DTOs with Jackson + jackson-module-kotlin (geocoder, weather,
# overlay/PG-spot/task/spatial caches, remembered devices), which resolves fields and
# constructors reflectively BY NAME. R8 renaming therefore silently breaks deserialization
# (and runCatching swallows it → degraded features, no crash). So we shrink + optimize but
# do NOT obfuscate — that keeps almost all the size win (it comes from dead-code / resource
# shrinking, not renaming) while leaving reflection-by-name intact and robust.
-dontobfuscate

# Generic type info for Jackson TypeReference / reified readValue<T>(); without this the
# offline geocoder and the typed caches throw "TypeReference constructed without actual type
# information".
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# ---- Jackson + Kotlin module (reflection + generic TypeReference) ----
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class * extends com.fasterxml.jackson.core.type.TypeReference { *; }
# jackson-module-kotlin reflects on Kotlin metadata.
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ---- Tern DTOs (de)serialized by Jackson — keep classes + members ----
-keep class com.ternparagliding.model.** { *; }
-keep class com.ternparagliding.device.RememberedDevice { *; }
# Flight recordings (the black box): FlightRecording + nested DTOs + the live-log envelope are
# (de)serialized by Jackson-by-name. Crash recovery reads these back, so keep names + members.
-keep class com.ternparagliding.flight.recording.** { *; }
-keep class com.ternparagliding.utils.cache.MapOverlayCacheUtils { *; }
-keep class com.ternparagliding.utils.cache.MapOverlayCacheUtils$* { *; }

# ---- kotlinx.serialization (spatialk GeoJSON etc.) ----
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- MapLibre (native / JNI-backed) + osmdroid ----
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
-dontwarn org.osmdroid.**
