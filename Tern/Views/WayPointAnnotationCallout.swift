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

    var body : some View {
        let renderer = ImageRenderer(content: WaypointCalloutImage(waypoint: $waypoint))
        Button {
            editWaypoint.toggle()
        } label: {
            Image(uiImage: renderer.uiImage!)
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
