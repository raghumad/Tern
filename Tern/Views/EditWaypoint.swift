//
//  EditWaypoint.swift
//  Tern
//
//  Created by Raghu Madanala on 12/6/22.
//

import SwiftUI
import CoreLocation
import MapKit

struct EditWaypoint: View {
    @EnvironmentObject var model : RoutePlannerModel
    @State var waypoint : WayPoint
    @Binding var editWaypoint : Bool
    
    @State var waypointName : String
    @State var latitude : Double
    @State var longitude : Double
    @State var cylinderRadius : Int
    @State var waypointDescription : String
    var body: some View {
        VStack {
            TextField("Waypoint Name", text: $waypointName)
                .keyboardType(.twitter)
            HStack{
                Image(systemName: "mappin.and.ellipse")
                TextField("Latitude", value: $latitude, format: .number)
                    .keyboardType(.decimalPad)
                    .frame(width: 80)
                Text(",")
                TextField("Longitude", value: $longitude, format: .number)
                    .keyboardType(.decimalPad)
                Spacer()
            }
            HStack{
                Image(systemName: "cylinder")
                TextField("Cylinder Radius", value: $cylinderRadius, format: .number)
                    .keyboardType(.numberPad)
            }
            HStack{
                TextEditor(text: $waypointDescription)
            }
            HStack{
                Button {
                    for i in model.waypoints.indices {
                        if model.waypoints[i] == waypoint {
                            model.waypoints[i].update(coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), name: waypointName, description: waypointDescription, radius: cylinderRadius)
                            model.mapView.removeAnnotations(model.waypoints)
                            model.mapView.addAnnotations(model.waypoints)
                            model.mapView.removeOverlays(model.mapView.overlays) //remove before re adding all of them
                            for wpt in model.waypoints {
                                let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius))
                                model.mapView.addOverlay(cyclinderOverlay)
                            }
                            if model.waypoints.count >  1 {
                                model.mapView.addOverlay(MKGeodesicPolyline(coordinates: model.waypoints.map( {$0.coordinate} ), count: model.waypoints.count))
                            }
                        }
                    }
                    model.mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), latitudinalMeters: 50000, longitudinalMeters: 50000), animated: true)
                    editWaypoint.toggle()
                } label: {
                    Text("Send It")
                        .fontWeight(.heavy)
                        .frame(width: 80,height: 40)
                        .foregroundColor(.white)
                        .background(.blue.opacity(0.9))
                        .cornerRadius(8)
                }
                Button {
                    model.waypoints.remove(at: model.waypoints.firstIndex(of: waypoint) ?? 9999)
                    model.mapView.removeAnnotation(waypoint)
                    //model.mapView.addAnnotations(model.waypoints)
                    model.mapView.removeOverlays(model.mapView.overlays) //remove before re adding all of them
                    for wpt in model.waypoints {
                        model.mapView.removeAnnotation(wpt) //re-add all waypoints so that they are numbered correctly
                        model.mapView.addAnnotation(wpt)
                        let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius))
                        model.mapView.addOverlay(cyclinderOverlay)
                    }
                    if model.waypoints.count >  1 {
                        model.mapView.addOverlay(MKGeodesicPolyline(coordinates: model.waypoints.map( {$0.coordinate} ), count: model.waypoints.count))
                    }
                    editWaypoint.toggle()
                } label: {
                    Text("Kill It")
                        .fontWeight(.heavy)
                        .frame(width: 80,height: 40)
                        .foregroundColor(.white)
                        .background(.red.opacity(0.7))
                        .cornerRadius(8)
                }
            }
        }
    }
}
