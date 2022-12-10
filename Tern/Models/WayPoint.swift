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
    var elevation : Measurement<UnitLength> = .init(value: 0, unit: .meters)
    var cylinderRadius: Measurement<UnitLength> = .init(value: 100, unit: .meters)
    var title: String?
    var subtitle: String?
    var weatherForecast : WeatherForecast
    
    var countryCode : String {
        var countryCode = "us"
        
        CLGeocoder().reverseGeocodeLocation(
            CLLocation(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude),
                completionHandler: {(placemarks, error) in
            if (error != nil) {print("reverse geodcode fail: \(error!.localizedDescription)")}
            let pm = placemarks! as [CLPlacemark]
                    if pm.count > 0 { countryCode = pm[0].isoCountryCode?.lowercased() ?? "us" }
        })
        return countryCode
    }

    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D()) {
        self.coordinate = coordinate
        self.weatherForecast = WeatherForecast(coordinate: coordinate)
    }

    static func ==(left: WayPoint, right: WayPoint) -> Bool
    {
        return (left.coordinate.latitude == right.coordinate.latitude) && (left.coordinate.longitude == right.coordinate.longitude)
    }
    
    func notNear(newPt: WayPoint) -> Bool {
        return CLLocation(latitude: newPt.coordinate.latitude, longitude: newPt.coordinate.longitude).distance(from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)) >= (newPt.cylinderRadius.converted(to: .feet).value + cylinderRadius.converted(to: .feet).value + 100) //Cylinders atleast 100 apart
    }

    func isNear(newPt: WayPoint) -> Bool {
        return !notNear(newPt: newPt)
    }

    func update()
    {
        weatherForecast.coordinate = self.coordinate
        Task {
            await weatherForecast.getForecast()
        }
        Task {
            await getElevation()
        }
    }

    func getElevation() async {
        //https://api.open-meteo.com/v1/elevation?latitude=52.52&longitude=13.41
        guard let url = URL(string: "https://api.open-meteo.com/v1/elevation?latitude=\(coordinate.latitude)&longitude=\(coordinate.longitude)") else {
            print ("link error")
            return
        }
        
        do {
            //print(url.absoluteString)
            let (data, _) = try await URLSession.shared.data(from: url)
            let eleJSON = try! JSON(data: data)
            //print(url)
            DispatchQueue.main.async {
                self.elevation = Measurement<UnitLength>(value: eleJSON["elevation"][0].doubleValue, unit: .meters)
            }
            //print(self.weather)
        } catch {
            print("Open mateo fails.")
        }
    }

    func update(coordinate: CLLocationCoordinate2D, name: String, description: String, radius: Measurement<UnitLength>)
    {
        self.coordinate = coordinate
        title = name
        cylinderRadius = radius
        subtitle = description
        Task(priority: .background) {
            await weatherForecast.getForecast()
        }
        Task {
            await getElevation()
        }
    }

    var CUPdata : String {
        //https://downloads.naviter.com/docs/SeeYou_CUP_file_format.pdf
        var strLatitude = "\(String(format: "%.0f", abs(coordinate.latitude.rounded(.towardZero))))\(String(format: "%.3f",  coordinate.latitude.truncatingRemainder(dividingBy: 1)*60))"
        if coordinate.latitude > 0 {
            strLatitude = "\(strLatitude)N"
        } else {
            strLatitude = "\(strLatitude)S"
        }
        var strLongitude = "\(String(format: "%.0f", abs(coordinate.longitude.rounded(.towardZero))))\(String(format: "%.3f", coordinate.latitude.truncatingRemainder(dividingBy: 1)*60))"
        if coordinate.longitude > 0 {
            strLongitude = "\(strLongitude)E"
        } else {
            strLongitude = "\(strLongitude)W"
        }
        return "\"\(subtitle!)\",\"\(title!)\",US,\(strLatitude),\(strLongitude),0m,1,,,,"
    }

    var OziWPTdata : String {
        //https://www.oziexplorer4.com/eng/help/fileformats.html
        return "You're Fucked!"
    }
}

extension WayPoint : Comparable { //Sort based on Id which is continuous clock so always incrementing. Example: waypoints.sort()
    static func < (lhs: WayPoint, rhs: WayPoint) -> Bool {
        return lhs.id < rhs.id
    }
}

