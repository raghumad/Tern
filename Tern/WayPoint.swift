//
//  AnnotationsOverlays.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import Foundation
import MapKit
import SwiftyJSON
import SwiftUI

class WayPoint : NSObject, MKAnnotation {
    var coordinate: CLLocationCoordinate2D
    var cylinderRadius: Int = 0
    var title: String?
    var subtitle: String?
    var weather : JSON
    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D(), cylinderRadius: Int = 0, weather: JSON = JSON("")) {
        self.coordinate = coordinate
        self.cylinderRadius = cylinderRadius
        self.weather = weather
    }
    func getMeteo() async {
        //https://api.open-meteo.com/v1/gfs?latitude=38.83&longitude=-104.82&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto
        //https://github.com/SwiftyJSON/SwiftyJSON to parse. got no time to create model structs.
        //https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Swift coordinate to xyz
        guard let url = URL(string: "https://api.open-meteo.com/v1/gfs?latitude=\(self.coordinate.latitude)&longitude=\(self.coordinate.longitude)&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto") else {
            print ("link error")
            return
        }
        
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            //print(url)
            DispatchQueue.main.async {
                self.weather = try! JSON(data: data)
            }
            print(self.weather)
        } catch {
            print("Open mateo fails.")
        }
    }
}

struct WayPointCallout : View {
    let waypointAnnotation: MKAnnotation
    var body : some View {
        Text("This is a very long waypoint subtitle and a lot more information tooooooo asfldkjasd;lfja;slkj qow;ij a;fsodfjaslkjd fawoiej f;asdkgfnj ;aksjghoaw;ei jfsdl;fkj as;glijewoajsldfjl")
    }
}
