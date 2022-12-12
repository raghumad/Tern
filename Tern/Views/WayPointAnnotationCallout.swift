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
    let waypoint : WayPoint // This has to be copy or delete will crash.

    var body : some View {
        VStack{
            HStack{
                Image(systemName: "mappin.and.ellipse")
                Text("\(String(format: "%.5f", waypoint.coordinate.latitude)),\(String(format: "%.5f", waypoint.coordinate.longitude))")
                Spacer()
            }
            HStack {
                Image(systemName: "cylinder")
                Text("\(String(waypoint.cylinderRadius.converted(to: .meters).value))m")
                Image(systemName: "figure.climbing")
                Text("\(String(format: "%.1f ft", waypoint.elevation.converted(to: .feet).value))")
                Spacer()
            }
            HStack {
                if waypoint.weatherForecast.winddirection_80m.count > 0 {
                    Image(systemName: "arrow.up.circle")
                        .rotationEffect(.degrees(waypoint.weatherForecast.winddirection_80m[0].converted(to: .degrees).value))
                }
                if waypoint.weatherForecast.windspeed80m.count > 0 {
                    Text("\(waypoint.weatherForecast.windspeed80m[0].description)")
                }
                if waypoint.weatherForecast.windgusts_10m.count > 0 {
                    Image(systemName: "wind.circle")
                    Text("\(waypoint.weatherForecast.windgusts_10m[0].description)")
                }
                if waypoint.weatherForecast.relativehumidity_2m.count > 0 {
                    Image(systemName: "humidity")
                    Text("\(waypoint.weatherForecast.relativehumidity_2m[0].description)")
                }
                if waypoint.weatherForecast.cloudcover.count > 0 {
                    Image(systemName: "cloud.circle")
                    Text("\(waypoint.weatherForecast.cloudcover[0].description)")
                }
                Spacer()
            }
        }
        .onTapGesture {
            editWaypoint.toggle()
        }
        .sheet(isPresented: $editWaypoint) {
            EditWaypoint(waypoint: waypoint, editWaypoint: $editWaypoint, waypointName: waypoint.title ?? "", latitude: waypoint.coordinate.latitude, longitude: waypoint.coordinate.longitude, cylinderRadius: waypoint.cylinderRadius, waypointDescription: waypoint.subtitle!).environmentObject(model)
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
