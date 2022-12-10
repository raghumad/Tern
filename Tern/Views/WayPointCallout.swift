//
//  WayPointCallout.swift
//  Tern
//
//  Created by Raghu Madanala on 12/3/22.
//

import SwiftUI
import CoreLocation
import MapKit

struct WayPointCallout : View {
    @EnvironmentObject var model : RoutePlannerModel
    @State var editWaypoint : Bool = false
    @State var index : Int

    var body : some View {
        VStack{
            HStack{
                Image(systemName: "mappin.and.ellipse")
                Text("\(String(format: "%.5f", model.waypoints[index].coordinate.latitude)),\(String(format: "%.5f", model.waypoints[index].coordinate.longitude))")
                Spacer()
            }
            HStack {
                Image(systemName: "cylinder")
                Text("\(String(model.waypoints[index].cylinderRadius.converted(to: .meters).value))m")
                Image(systemName: "figure.climbing")
                Text("\(String(format: "%.1f ft", model.waypoints[index].elevation.converted(to: .feet).value))")
                Spacer()
            }
            HStack {
                Image(systemName: "arrow.up.circle")
                    .rotationEffect(.degrees((model.waypoints[index].weatherForecast.winddirection_80m[0].value)))
                Text("\(model.waypoints[index].weatherForecast.windspeed80m.first!.description)")
                Image(systemName: "wind.circle")
                Text("\(model.waypoints[index].weatherForecast.windgusts_10m.first!.description)")
                Image(systemName: "humidity")
                Text("\(model.waypoints[index].weatherForecast.relativehumidity_2m.first!.description)")
                Image(systemName: "cloud.circle")
                Text("\(model.waypoints[index].weatherForecast.cloudcover.first!.description)")
                Spacer()
            }
        }
        .onTapGesture {
            editWaypoint.toggle()
        }
        .sheet(isPresented: $editWaypoint) {
            EditWaypoint(index: index, editWaypoint: $editWaypoint, waypointName: model.waypoints[index].title ?? "", latitude: model.waypoints[index].coordinate.latitude, longitude: model.waypoints[index].coordinate.longitude, cylinderRadius: model.waypoints[index].cylinderRadius, waypointDescription: model.waypoints[index].subtitle!).environmentObject(model)
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
