//
//  WaypointCalloutImage.swift
//  Tern
//
//  Created by Raghu Madanala on 12/13/22.
//

import SwiftUI
import CoreLocation

struct WaypointCalloutImage: View {
    @Binding var waypoint: WayPoint
    var body: some View {
        VStack{
            HStack {
                HStack {Image(systemName: "mappin.and.ellipse")
                    Text("\(String(format: "%.5f", waypoint.coordinate.latitude)),\(String(format: "%.5f", waypoint.coordinate.longitude))")
                }
                .foregroundColor(.white)
                .background(Color.blue)
                .cornerRadius(5, antialiased: true)
                Spacer()
            }
            HStack {
                HStack {
                    Image(systemName: "cylinder")
                    Text("\(String(waypoint.cylinderRadius.converted(to: .meters).value))m")
                }
                .foregroundColor(.white)
                .background(Color.blue)
                .cornerRadius(5, antialiased: true)
                HStack{
                    Image(systemName: "figure.climbing")
                    Text("\(String(format:"%0.0f",waypoint.elevation.converted(to: .feet).value))ft")
                }
                .foregroundColor(.white)
                .background(Color.blue)
                .cornerRadius(5, antialiased: true)
                Spacer()
            }
            HStack {
                if waypoint.weatherForecast.winddirection_80m.count > 0 {
                    HStack {
                        Image(systemName: "arrow.up.circle")
                            .rotationEffect(.degrees(waypoint.weatherForecast.winddirection_80m[0].converted(to: .degrees).value))
                        Text("\(String(format: "%0.1f", waypoint.weatherForecast.windspeed80m[0].value))mph")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                    HStack {
                        Image(systemName: "tornado.circle")
                        Text("\(String(format: "%0.1f", waypoint.weatherForecast.windgusts_10m[0].value))mph")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                }
                Spacer()
            }
            HStack {
                if waypoint.weatherForecast.relativehumidity_2m.count > 0 {
                    HStack{
                        Image(systemName: "humidity")
                        Text("\(waypoint.weatherForecast.relativehumidity_2m[0].description)%")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                    HStack {
                        Image(systemName: "cloud.circle")
                        Text("\(waypoint.weatherForecast.cloudcover[0].description)%")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                }
                Spacer()
            }
        }
    }
}

/*
 struct WaypointCalloutImage_Previews: PreviewProvider {
    static var previews: some View {
        @State var wpt = WayPoint(coordinate: CLLocationCoordinate2D(latitude: 40.2530073213, longitude: -105.609067564))
        WaypointCalloutImage(waypoint: $wpt))
    }
}
*/
