//
//  WayPointAnnotationCallout.swift
//  Tern
//
//  Created by Raghu Madanala on 12/3/22.
//

import SwiftUI
import CoreLocation
import MapKit

struct WayPointAnnotationCallout : View {
    @EnvironmentObject var model : RoutePlannerModel
    @State var editWaypoint : Bool = false
    @State var waypoint : WayPoint // This has to be copy or delete will crash.
    let units = MeasurementUnits.userDefaults

    var body : some View {
        VStack{
            if ((waypoint.forecast?.current_weather.winddirection) != nil) {
                HStack {
                    WindGauge(label: "Wind", windSpeed: (waypoint.forecast?.current_weather.windspeed.converted(to: units.speed))!, windDirection: waypoint.forecast?.current_weather.winddirection.converted(to: .degrees))
                    WindGauge(label: "Gust", windSpeed: (waypoint.forecast?.hourly.windgusts_10m[0].converted(to: units.speed))!)
                }
                HStack {
                    if (waypoint.forecast?.hourly.relativehumidity_2m.count)! > 0 {
                        Gauge(value: Double((waypoint.forecast?.hourly.relativehumidity_2m[0].description)!) ?? 0, in: 0...100) {
                            Text("Humidity")
                                .foregroundColor(.primary)
                                .font(.custom("Gruppo", size: 8).monospaced())
                        }
                        .gaugeStyle(.accessoryLinearCapacity)
                        Gauge(value: Double((waypoint.forecast?.hourly.cloudcover[0].description)!) ?? 0, in: 0...100) {
                            Text("Cloud Cover")
                                .font(.custom("Gruppo", size: 8).monospaced())
                                .foregroundColor(.primary)
                        }
                        .gaugeStyle(.accessoryLinearCapacity)
                    }
                }
            }
        }
        .onTapGesture {
            editWaypoint.toggle()
        }
        .sheet(isPresented: $editWaypoint) {
            EditWaypoint(
                waypoint: waypoint,
                editWaypoint: $editWaypoint,
                waypointName: waypoint.title ?? "",
                latitude: waypoint.coordinate.latitude,
                longitude: waypoint.coordinate.longitude,
                cylinderRadius: waypoint.cylinderRadius.converted(to: units.magnitude),
                waypointDescription: waypoint.subtitle!
            ).environmentObject(model)
         .presentationDetents([.fraction(0.8)])
         .presentationDragIndicator(.visible)
         }
    }
}

//struct WayPointCallout_Previews: PreviewProvider {
//    //40.2530073213 -105.609067564
//    static var previews: some View {
//        //model.addWaypoint(coordinate: CLLocationCoordinate2D(latitude: 40.2530073213, longitude: -105.609067564))
//        //WayPointCallout(index: 0).environmentObject(model)
//    }
//}
