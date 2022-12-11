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
    let waypointIndex : Int

    var body : some View {
        VStack{
            HStack{
                Image(systemName: "mappin.and.ellipse")
                Text("\(String(format: "%.5f", model.waypoints[waypointIndex].coordinate.latitude)),\(String(format: "%.5f", model.waypoints[waypointIndex].coordinate.longitude))")
                Spacer()
            }
            HStack {
                Image(systemName: "cylinder")
                Text("\(String(model.waypoints[waypointIndex].cylinderRadius.converted(to: .meters).value))m")
                Image(systemName: "figure.climbing")
                Text("\(String(format: "%.1f ft", model.waypoints[waypointIndex].elevation.converted(to: .feet).value))")
                Spacer()
            }
            HStack {
                if model.waypoints[waypointIndex].weatherForecast.winddirection_80m.count > 0 {
                    Image(systemName: "arrow.up.circle")
                        .rotationEffect(.degrees(model.waypoints[waypointIndex].weatherForecast.winddirection_80m[0].converted(to: .degrees).value))
                }
                if model.waypoints[waypointIndex].weatherForecast.windspeed80m.count > 0 {
                    Text("\(model.waypoints[waypointIndex].weatherForecast.windspeed80m[0].description)")
                }
                if model.waypoints[waypointIndex].weatherForecast.windgusts_10m.count > 0 {
                    Image(systemName: "wind.circle")
                    Text("\(model.waypoints[waypointIndex].weatherForecast.windgusts_10m[0].description)")
                }
                if model.waypoints[waypointIndex].weatherForecast.relativehumidity_2m.count > 0 {
                    Image(systemName: "humidity")
                    Text("\(model.waypoints[waypointIndex].weatherForecast.relativehumidity_2m[0].description)")
                }
                if model.waypoints[waypointIndex].weatherForecast.cloudcover.count > 0 {
                    Image(systemName: "cloud.circle")
                    Text("\(model.waypoints[waypointIndex].weatherForecast.cloudcover[0].description)")
                }
                Spacer()
            }
        }
        .onTapGesture {
            editWaypoint.toggle()
        }
        .sheet(isPresented: $editWaypoint) {
            EditWaypoint(waypoint: model.waypoints[waypointIndex], editWaypoint: $editWaypoint, waypointName: model.waypoints[waypointIndex].title ?? "", latitude: model.waypoints[waypointIndex].coordinate.latitude, longitude: model.waypoints[waypointIndex].coordinate.longitude, cylinderRadius: model.waypoints[waypointIndex].cylinderRadius, waypointDescription: model.waypoints[waypointIndex].subtitle!).environmentObject(model)
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
