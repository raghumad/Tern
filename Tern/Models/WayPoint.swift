//
//  AnnotationsOverlays.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import Foundation
import MapKit
import SwiftyJSON
import Polyline

class WayPoint : NSObject, MKAnnotation, Codable {
    var id = ContinuousClock.now
    var coordinate: CLLocationCoordinate2D
    var elevation : Measurement<UnitLength> = .init(value: 0, unit: .meters)
    var cylinderRadius: Measurement<UnitLength> = .init(value: 100, unit: .meters)
    var title: String?
    var subtitle: String?
    var forecast : OpenMeteoForecast?

    private enum CodingKeys : String, CodingKey {
        //case id = "id"
        case coordinate = "coordinate"
        case elevation = "elevation"
        case cylinderRadius = "cylinder_radius"
        case title = "name"
        case subtitle = "description"
    }

    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D()) {
        self.coordinate = coordinate
        super.init()
        self.getForecast()
        self.getElevation()
    }

    private init(coordinate: CLLocationCoordinate2D, elevation: Measurement<UnitLength>, cylinderRadius: Measurement<UnitLength>, title: String, subtitle: String)
    {
        self.id = ContinuousClock.now
        self.coordinate = coordinate
        self.elevation = elevation
        self.cylinderRadius = cylinderRadius
        self.title = title
        self.subtitle = subtitle
        super.init()
        self.getForecast()
        self.getElevation()
    }

    required convenience init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let coordinate = try container.decode(CLLocationCoordinate2D.self, forKey: WayPoint.CodingKeys.coordinate)
        let elevattion = try container.decode(Double.self, forKey: WayPoint.CodingKeys.elevation)
        let cylinderRadius = try container.decode(Double.self, forKey: WayPoint.CodingKeys.cylinderRadius)
        let title = try container.decode(String.self, forKey: WayPoint.CodingKeys.title)
        let subtitle = try container.decode(String.self, forKey: WayPoint.CodingKeys.subtitle)
        //let id  = try container.decode(Double.self, forKey: WayPoint.CodingKeys.id)
        self.init(coordinate: coordinate, elevation: Measurement<UnitLength>(value: elevattion, unit: .meters), cylinderRadius: Measurement<UnitLength>(value: cylinderRadius, unit: .meters), title: title, subtitle: subtitle)
        self.getForecast()
        self.getElevation()
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(self.coordinate, forKey: .coordinate)
        try container.encode(self.elevation.converted(to: .meters).value, forKey: .elevation)
        try container.encode(self.cylinderRadius.converted(to: .meters).value, forKey: .cylinderRadius)
        try container.encodeIfPresent(self.title, forKey: .title)
        try container.encodeIfPresent(self.subtitle, forKey: .subtitle)
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
        //weatherForecast.coordinate = self.coordinate
        //weatherForecast.getForecast()
        getForecast()
        getElevation()
    }

    func getElevation() {
        Task(priority: .background) {
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
    }

    func getForecast() {
        Task(priority: .high) {
            do {
                guard let url = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(self.coordinate.latitude)&longitude=\(self.coordinate.longitude)&hourly=temperature_2m,relativehumidity_2m,dewpoint_2m,weathercode,pressure_msl,surface_pressure,cloudcover,windspeed_80m,winddirection_80m,windgusts_10m&current_weather=true&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&timezone=auto&past_days=0&timeformat=unixtime&models=best_match") else {
                    print ("Invalid openmeteo url.\n")
                    return
                }
                let config = URLSessionConfiguration.default
                config.httpAdditionalHeaders = ["User-Agent": "(https://tern.madanala.com/, tern@madanala.com) Tern Paragliding"]
                let session = URLSession(configuration: config)
                let (data, _) = try await session.data(for: URLRequest(url: url))
                self.forecast = try JSONDecoder().decode(OpenMeteoForecast.self, from: data)
                //print("\(self.title):\(self.forecast?.latitude),\(self.forecast?.longitude)")
            } catch {
                print(error)
            }
        }
    }

    func update(coordinate: CLLocationCoordinate2D, name: String, description: String, radius: Measurement<UnitLength>)
    {
        self.coordinate = coordinate
        title = name
        cylinderRadius = radius
        subtitle = description
        //weatherForecast.getForecast()
        getElevation()
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

    var CompeGPSdata : String {
        var strLatitude = "\(abs(coordinate.longitude))"
        if coordinate.latitude > 0 {
            strLatitude = "\(strLatitude)ºN"
        } else {
            strLatitude = "\(strLatitude)ºS"
        }
        var strLongitude = "\(abs(coordinate.longitude))"
        if coordinate.longitude > 0 {
            strLongitude = "\(strLongitude)ºE"
        } else {
            strLongitude = "\(strLongitude)ºW"
        }
        /*
        G  WGS 84
        U  1
        W  START A 46.0116190∫N 11.3010020∫E 08-AUG-22 07:58:51 500.000000 Levico start
        w Waypoint,,,,,,,,,
        W  RIALTO A 45.6452480∫N 11.2424500∫E 08-AUG-22 07:58:51 764.000000 Malga Rialto
        w Waypoint,,,,,,,,,
        W  GOAL A 46.0116190∫N 11.3010020∫E 08-AUG-22 07:58:51 500.000000 Levico Goal
        w Waypoint,,,,,,,,,*/
        let fmt = DateFormatter()
        fmt.dateFormat = "DD-MMM-YY HH:mm:ss"
        fmt.timeZone = TimeZone.current
        return "W \(title!) \(strLatitude) \(strLongitude) \(fmt.string(from: Date())) \(elevation.converted(to: .meters).value) \"\(subtitle!)\"\nw Waypoint,,,,,,,,\(cylinderRadius.converted(to: .meters).value)\n"
    }
    var OziWPTdata : String {
        //https://www.oziexplorer4.com/eng/help/fileformats.html
        return "You're Fucked!"
    }
    var xctsk_wp_tp : xctsk_wp_turnpoint {
        let polyline = Polyline(coordinates: [coordinate])
        var coord = polyline.encodedPolyline
        coord.append(encodeSingleInteger(Int(elevation.converted(to: .meters).value)))
        return xctsk_wp_turnpoint(polylineCoordinate: coord, name: self.title ?? "wp")
    }
}

extension WayPoint : Comparable { //Sort based on Id which is continuous clock so always incrementing. Example: waypoints.sort()
    static func < (lhs: WayPoint, rhs: WayPoint) -> Bool {
        return lhs.id < rhs.id
    }
}

