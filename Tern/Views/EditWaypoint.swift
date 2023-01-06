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
    let units = MeasurementUnits.userDefaults
    
    @State var waypointName : String
    @State var latitude : Double
    @State var longitude : Double
    @State var cylinderRadius : Measurement<UnitLength>
    @State var waypointDescription : String
    
    func saveWaypoint() {
        waypoint.update(coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), name: waypointName, description: waypointDescription, radius: cylinderRadius)
        model.mapView.removeAnnotations(model.waypoints)
        model.mapView.addAnnotations(model.waypoints)
        for wpt in model.waypoints {
            let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius.converted(to: .meters).value))
            model.mapView.addOverlay(cyclinderOverlay)
        }
        if model.waypoints.count >  1 {
            model.redrawRoutePath()
            model.mapView.addAnnotations(model.legLengthLabels)
        }
        editWaypoint.toggle()
    }

    func deleteWaypoint() {
        if let id = model.waypoints.firstIndex(of: waypoint) {
            model.waypoints.remove(at: id)
        }
        model.mapView.removeAnnotation(waypoint)
        for wpt in model.waypoints {
            model.mapView.removeAnnotation(wpt) //re-add all waypoints so that they are numbered correctly
            model.mapView.addAnnotation(wpt)
            let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius.converted(to: .meters).value))
            model.mapView.addOverlay(cyclinderOverlay)
        }
        model.redrawRoutePath() // redraw path will also remove previous.
        model.mapView.addAnnotations(model.legLengthLabels)
        editWaypoint.toggle()
    }

    var body: some View {
        VStack {
            TextField("Waypoint Name", text: $waypointName).font(.custom("Gruppo", size: 16))
                .keyboardType(.twitter)
                .padding([.top,.leading], 5)
            HStack{
                //Image(systemName: "mappin.and.ellipse")
                Text("📍")
                TextField("Latitude", value: $latitude, format: .number)
                    .font(.custom("Gruppo", size: 16))
                    .keyboardType(.decimalPad)
                    .frame(width: 80)
                Text(",")
                    .font(.custom("Gruppo", size: 16))
                TextField("Longitude", value: $longitude, format: .number)
                    .font(.custom("Gruppo", size: 16))
                    .keyboardType(.decimalPad)
                Spacer()
            }
            HStack {
                Text("⌭")
                    .font(.custom("Gruppo", size: 16))
                TextField("Cylinder Radius", value: $cylinderRadius.value, format: .number)
                    .font(.custom("Gruppo", size: 16))
                    .keyboardType(.numberPad)
                    .frame(width: 80)
                //Image(systemName: "mountain.2")
                Text("🏔️\(String(format: "%0.0f", waypoint.elevation.converted(to: units.magnitude).value))\(units.magnitude.symbol)")
                    .font(.custom("Gruppo", size: 16))
                Spacer()
            }
            ZStack{
                VStack{
                    Text("Next 24hr forecast")
                        .font(.custom("Gruppo", size: 12))
                    Spacer()
                }
                HStack{
                    Text("Windspeed").fontWeight(.ultraLight).foregroundColor(.cyan)
                        .font(.custom("Gruppo", size: 12))
                    Text("Gustspeed").fontWeight(.ultraLight).foregroundColor(.red)
                        .font(.custom("Gruppo", size: 12))
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
                    .font(.custom("Gruppo", size: 12))
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
                        .font(.custom("Gruppo", size: 12))
                    Text("Due Point").fontWeight(.ultraLight).foregroundColor(.blue)
                        .font(.custom("Gruppo", size: 12))
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
                TextEditor(text: $waypointDescription).font(.custom("Gruppo", size: 12))
            }
            HStack{
                Button {
                    saveWaypoint()
                } label: {
                    Image(systemName: "location")
                        .frame(width: 40,height: 40)
                        .foregroundColor(.white)
                        .background(.green.opacity(0.9))
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
            Link("Weather data by Open-Meteo.com", destination: URL(string: "https://www.open-meteo.com/")!).font(.custom("Gruppo", size: 8).monospaced())
        }
        .onDisappear {
            saveWaypoint()
        }
    }
}
