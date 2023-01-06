//
//  WaypointCalloutImage.swift
//  Tern
//
//  Created by Raghu Madanala on 12/13/22.
//

import SwiftUI
import CoreLocation

struct WaypointCalloutImage: View {
    @Binding var waypoint: WayPoint
    let units = MeasurementUnits.userDefaults
    var body: some View {
        VStack{
            if waypoint.weatherForecast.winddirection_80m.count > 0 {
                HStack {
                    WindGauge(label: "Wind", windSpeed: waypoint.weatherForecast.windspeed80m[0], windDirection: waypoint.weatherForecast.winddirection_80m[0])
                    WindGauge(label: "Gust", windSpeed: waypoint.weatherForecast.windgusts_10m[0])
                }
                HStack {
                    if waypoint.weatherForecast.relativehumidity_2m.count > 0 {
                        Gauge(value: Double(waypoint.weatherForecast.relativehumidity_2m[0].description) ?? 0, in: 0...100) {
                            Text("Humidity")
                                .foregroundColor(.accentColor)
                                .font(.custom("Gruppo", size: 8).monospaced())
                        }
                        .gaugeStyle(.accessoryLinearCapacity)
                        Gauge(value: Double(waypoint.weatherForecast.cloudcover[0].description) ?? 0, in: 0...100) {
                            Text("Cloud Cover")
                                .font(.custom("Gruppo", size: 8).monospaced())
                                .foregroundColor(.accentColor)
                        }
                        .gaugeStyle(.accessoryLinearCapacity)
                    }
                }
            }
        }
    }
}

//struct WaypointCalloutImage_Previews: PreviewProvider {
//    //var wpt = WayPoint(coordinate: CLLocationCoordinate2D(latitude: 40.2530073213, longitude: -105.609067564))
//    static var previews: some View {
//        WaypointCalloutImage(waypoint: WayPoint(coordinate: CLLocationCoordinate2D(latitude: 40.2530073213, longitude: -105.609067564)))
//    }
//}
