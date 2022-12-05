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
    @State var waypointDescription : String

    var body : some View {
        Button{
            editWaypoint.toggle()
        } label: {
            Section{
                VStack{
                    HStack{
                        Image(systemName: "mappin.and.ellipse")
                        Text("\(String(format: "%.5f", waypoint.coordinate.latitude)),\(String(format: "%.5f", waypoint.coordinate.longitude))")
                        Spacer()
                    }
                    HStack{
                        Image(systemName: "cylinder")
                        Text("\(String(waypoint.cylinderRadius))m")
                        Spacer()
                    }
                    HStack {
                        Text("Weather is \(waypoint.weather.stringValue)")
                        //Text("\(waypoint.weather["hourly"]["inddirection_80m"][0].stringValue)\(waypoint.weather["hourly_units"]["winddirection_80m"].stringValue)")
                        Spacer()
                    }
                }
            }
        }
        .sheet(isPresented: $editWaypoint) {
            Section {
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
                                model.waypoints[i].coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                                model.waypoints[i].title = waypointName
                                model.waypoints[i].cylinderRadius = cylinderRadius
                                model.waypoints[i].subtitle = waypointDescription
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
                            let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius))
                            model.mapView.addOverlay(cyclinderOverlay)
                        }
                        if model.waypoints.count >  1 {
                            model.mapView.addOverlay(MKGeodesicPolyline(coordinates: model.waypoints.map( {$0.coordinate} ), count: model.waypoints.count))
                        }
                        model.mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), latitudinalMeters: 50000, longitudinalMeters: 50000), animated: true)
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
            .presentationDetents([.fraction(0.6)])
            .presentationDragIndicator(.visible)
        }
    }
}

struct WayPointCallout_Previews: PreviewProvider {
    static var previews: some View {
        WayPointCallout(waypoint: WayPoint(coordinate: CLLocationCoordinate2D(latitude: 38.839149999999997, longitude: -104.78780999999999),cylinderRadius: 500), waypointName: "Die Motherfucker", latitude: 38.839149999999997, longitude: -104.78780999999999, cylinderRadius: 500, waypointDescription: "A very long descrption and personal feelings about this waypoint. Don't hold anything back!!!").environmentObject(RoutePlannerModel())
    }
}
