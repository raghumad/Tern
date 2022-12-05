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
    @State var waypoint : WayPoint
    
    @State var waypointName : String
    @State var latitude : Double
    @State var longitude : Double
    @State var cylinderRadius : Int

    var body : some View {
        Button{
            editWaypoint.toggle()
        } label: {
            Section{
                VStack{
                    HStack{
                        Image(systemName: "scope")
                        Text("\(String(format: "%.5f", waypoint.coordinate.latitude)),\(String(format: "%.5f", waypoint.coordinate.longitude))")
                    }
                    HStack{
                        Image(systemName: "circle")
                        Text("\(String(waypoint.cylinderRadius))m")
                    }
                    HStack {
                        Text("Weather is \(waypoint.weather.stringValue)")
                        //Text("\(waypoint.weather["hourly"]["inddirection_80m"][0].stringValue)\(waypoint.weather["hourly_units"]["winddirection_80m"].stringValue)")
                    }
                }
            }
        }
        .sheet(isPresented: $editWaypoint) {
            Section {
                TextField("Waypoint Name", text: $waypointName)
                    .keyboardType(.twitter)
                HStack{
                    Image(systemName: "scope")
                    TextField("Latitude", value: $latitude, format: .number)
                        .keyboardType(.decimalPad)
                    Text(",")
                    TextField("Longitude", value: $longitude, format: .number)
                        .keyboardType(.decimalPad)
                }
                HStack{
                    Image(systemName: "circle")
                    TextField("Cylinder Radius", value: $cylinderRadius, format: .number)
                        .keyboardType(.numberPad)
                }
                Button {
                    for i in model.waypoints.indices {
                        if model.waypoints[i] == waypoint {
                            model.waypoints[i].coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                            model.waypoints[i].title = waypointName
                            model.waypoints[i].cylinderRadius = cylinderRadius
                            Task {
                                await model.waypoints[i].getMeteo()
                            }
                            model.mapView.removeAnnotations(model.waypoints)
                            model.mapView.addAnnotations(model.waypoints)
                            model.mapView.removeOverlays(model.mapView.overlays) //remove before re adding all of them
                            for wpt in model.waypoints {
                                let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius))
                                model.mapView.addOverlay(cyclinderOverlay)
                            }
                            if model.waypoints.count >  1 {
                                model.mapView.addOverlay(MKPolyline(coordinates: model.waypoints.map( {$0.coordinate} ), count: model.waypoints.count))
                            }
                        }
                    }
                    model.mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), latitudinalMeters: 50000, longitudinalMeters: 50000), animated: true)
                    editWaypoint.toggle()
                } label: {
                    Text("Send It")
                        .fontWeight(.heavy)
                        .frame(width: 100,height: 20)
                        .foregroundColor(.white)
                        .background(.blue.opacity(0.9))
                        .cornerRadius(8)
                }
            }
        }
    }
}

