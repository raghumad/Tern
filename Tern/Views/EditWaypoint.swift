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
    let waypoint : WayPoint
    @Binding var editWaypoint : Bool
    
    @State var waypointName : String
    @State var latitude : Double
    @State var longitude : Double
    @State var cylinderRadius : Measurement<UnitLength>
    @State var waypointDescription : String
    
    func saveWaypoint() {
        waypoint.update(coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), name: waypointName, description: waypointDescription, radius: cylinderRadius)
        model.mapView.removeAnnotations(model.waypoints)
        model.mapView.addAnnotations(model.waypoints)
        model.mapView.removeOverlays(model.mapView.overlays) //remove before re adding all of them
        for wpt in model.waypoints {
            let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius.converted(to: .meters).value))
            model.mapView.addOverlay(cyclinderOverlay)
        }
        if model.waypoints.count >  1 {
            model.redrawRoutePath()
            model.mapView.addAnnotations(model.legLengthLabels)
        }
        //model.mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), latitudinalMeters: 50000, longitudinalMeters: 50000), animated: true)
        editWaypoint.toggle()
    }

    func deleteWaypoint() {
        if let id = model.waypoints.firstIndex(of: waypoint) {
            model.waypoints.remove(at: id)
        }
        model.mapView.removeAnnotation(waypoint)
        model.mapView.removeOverlays(model.mapView.overlays) //remove before re adding all of them
        for wpt in model.waypoints {
            model.mapView.removeAnnotation(wpt) //re-add all waypoints so that they are numbered correctly
            model.mapView.addAnnotation(wpt)
            let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius.converted(to: .meters).value))
            model.mapView.addOverlay(cyclinderOverlay)
        }
        model.redrawRoutePath()
        model.mapView.addAnnotations(model.legLengthLabels)
        editWaypoint.toggle()
    }

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
            HStack {
                Image(systemName: "cylinder")
                TextField("Cylinder Radius", value: $cylinderRadius.value, format: .number)
                    .keyboardType(.numberPad)
                    .frame(width: 50)
                Image(systemName: "figure.climbing")
                Text("\(String(format: "%0.0f", waypoint.elevation.converted(to: .feet).value))ft")
                Spacer()
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
                    saveWaypoint()
                } label: {
                    Image(systemName: "location")
                        .frame(width: 40,height: 40)
                        .foregroundColor(.white)
                        .background(.blue.opacity(0.9))
                        .cornerRadius(8)
                }
                Button {
                    deleteWaypoint()
                } label: {
                    Image(systemName: "location.slash.fill")
                        .frame(width: 40,height: 40)
                        .foregroundColor(.white)
                        .background(.red.opacity(0.7))
                        .cornerRadius(8)
                }
            }
        }
        .onDisappear {
            saveWaypoint()
        }
    }
}
