//
//  AnnotationsOverlays.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import Foundation
import MapKit
import SwiftyJSON

class WayPoint : NSObject, MKAnnotation {
    let id = ContinuousClock.now
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

    static func ==(left: WayPoint, right: WayPoint) -> Bool
    {
        return (left.coordinate.latitude == right.coordinate.latitude) && (left.coordinate.longitude == right.coordinate.longitude)
    }
    
    func notNear(newPt: WayPoint) -> Bool {
        return CLLocation(latitude: newPt.coordinate.latitude, longitude: newPt.coordinate.longitude).distance(from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)) >= Double(newPt.cylinderRadius + cylinderRadius + 100) //Cylinders atleast 100 apart
    }
    
    func isNear(newPt: WayPoint) -> Bool {
        return !notNear(newPt: newPt)
    }
}

extension WayPoint : Comparable { //Sort based on Id which is continuous clock so always incrementing. Example: waypoints.sort()
    static func < (lhs: WayPoint, rhs: WayPoint) -> Bool {
        return lhs.id < rhs.id
    }
}

