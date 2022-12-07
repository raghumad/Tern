//
//  EditWaypoint.swift
//  Tern
//
//  Created by Raghu Madanala on 12/6/22.
//

import SwiftUI
import CoreLocation
import MapKit
import Charts

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
                Image(systemName: "cylinder")
                TextField("Cylinder Radius", value: $cylinderRadius, format: .number)
                    .keyboardType(.numberPad)
            }
            ZStack{
                VStack{
                    Text("Next 24hr forecast")
                    Spacer()
                }
                HStack{
                    Text("Windspeed").fontWeight(.ultraLight).foregroundColor(.cyan)
                    Text("Gustspeed").fontWeight(.ultraLight).foregroundColor(.red)
                }
                Chart (waypoint.weatherForecast.weatherdata) { item in
                    LineMark(x: .value("Time", item.time),
                             y: .value("WindGust", item.windgusts_10m))
                }
                .foregroundStyle(.red)
                Chart (waypoint.weatherForecast.weatherdata) { item in
                    LineMark(x: .value("Time", item.time),
                             y: .value("WindSpeed", item.windspeed80m))
                }
                .foregroundStyle(.cyan)
            }
            ZStack{
                Text("Wind Direction").fontWeight(.ultraLight).foregroundColor(.red)
                Chart (waypoint.weatherForecast.weatherdata) { item in
                    RectangleMark(
                        x: .value("Time", item.time),
                        y: .value("WindDirection", item.winddirection_80m),
                        width:5, height: 2)
                }
                .foregroundStyle(.red)
            }
            ZStack {
                HStack {
                    Text("Temperature").fontWeight(.ultraLight).foregroundColor(.orange)
                    Text("Due Point").fontWeight(.ultraLight).foregroundColor(.blue)
                }
                Chart (waypoint.weatherForecast.weatherdata) { item in
                    LineMark(x: .value("Time", item.time),
                             y: .value("Temp", item.temperature_2m))
                    .foregroundStyle(.orange)
                    RectangleMark(
                        x: .value("Time", item.time),
                        y: .value("DuePt", item.dewpoint_2m),
                        width:5, height: 1)
                    .foregroundStyle(.blue)
                }
                .chartLegend(position: .trailing)
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
                    Image(systemName: "location")
                        .frame(width: 40,height: 40)
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
//                    Text("Kill It")
//                        .fontWeight(.heavy)
//                        .frame(width: 80,height: 40)
//                        .foregroundColor(.white)
//                        .background(.red.opacity(0.7))
//                        .cornerRadius(8)
                    Image(systemName: "location.slash.fill")
                        .frame(width: 40,height: 40)
                        .foregroundColor(.white)
                        .background(.red.opacity(0.7))
                        .cornerRadius(8)
                }
            }
        }
    }
}
