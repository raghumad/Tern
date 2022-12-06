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

    var body : some View {
        VStack{
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
                VStack {
                    //Text("Forecast Coordinate is: \(waypoint.weatherForecast.coordinate.latitude):\(waypoint.weatherForecast.coordinate.longitude)")
                    //                        HStack {
                    //                            Image(systemName: "watchface.applewatch.case")
                    //                            Text("\(waypoint.weatherForecast.weather_time[0].description)")
                    //                            Spacer()
                    //                        }
                    HStack {
                        Image(systemName: "arrow.up.circle")
                            .rotationEffect(.degrees((waypoint.weatherForecast.winddirection_80m[0].value)))
                        Text("\(waypoint.weatherForecast.windspeed80m[0].description)")
                        Image(systemName: "wind.circle")
                        Text("\(waypoint.weatherForecast.windgusts_10m[0].description)")
                        Spacer()
                    }
                    //Text("\(waypoint.weather["hourly"]["inddirection_80m"][0].stringValue)\(waypoint.weather["hourly_units"]["winddirection_80m"].stringValue)")
                    Spacer()
                }
            }
        }
        .onTapGesture {
            editWaypoint.toggle()
        }
        .sheet(isPresented: $editWaypoint) {
            EditWaypoint(waypoint: waypoint, editWaypoint: $editWaypoint, waypointName: waypoint.title ?? "", latitude: waypoint.coordinate.latitude, longitude: waypoint.coordinate.longitude, cylinderRadius: waypoint.cylinderRadius, waypointDescription: waypoint.description).environmentObject(model)
         .presentationDetents([.fraction(0.6)])
         .presentationDragIndicator(.visible)
         }
    }
}
//
//struct WayPointCallout_Previews: PreviewProvider {
//    static var previews: some View {
//        WayPointCallout(waypoint: WayPoint(coordinate: CLLocationCoordinate2D(latitude: 38.839149999999997, longitude: -104.78780999999999),cylinderRadius: 500)).environmentObject(RoutePlannerModel())
//    }
//}
