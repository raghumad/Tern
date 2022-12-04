//
//  WayPointCallout.swift
//  Tern
//
//  Created by Raghu Madanala on 12/3/22.
//

import SwiftUI

struct WayPointCallout : View {
    @EnvironmentObject var manager: RoutePlannerModel
    @State var editWaypoint : Bool = false
    @State var waypoint : WayPoint

    var body : some View {
        Button{
            editWaypoint.toggle()
        } label: {
            Section{
                HStack{
                    Image(systemName: "scope")
                    Text("\(String(format: "%.5f", waypoint.coordinate.latitude)):\(String(format: "%.5f", waypoint.coordinate.longitude))")
                }
                HStack{
                    Image(systemName: "circle")
                    Text("\(String(waypoint.cylinderRadius))")
                }
            }
        }
//        .sheet(item: $editWaypoint, onDismiss: {
//        }, content: { $waypoint in
//            Section{
//                TextField("Waypoint Name", text: $waypoint.title)
//                    .keyboardType(.twitter)
//                HStack{
//                    Image(systemName: "scope")
//                    TextField("Latitude", value: $waypoint.coordinate.latitude, format: .number)
//                        .keyboardType(.decimalPad)
//                    Text(":")
//                    TextField("Longitude", value: $waypoint.coordinate.longitude, format: .number)
//                        .keyboardType(.decimalPad)
//                }
//                HStack{
//                    Image(systemName: "circle")
//                    TextField("Cylinder Radius", value: $waypoint.cylinderRadius, format: .number)
//                        .keyboardType(.numberPad)
//                }
//            }
//        })
    }
}

