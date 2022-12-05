//
//  AnnotationsOverlays.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import Foundation
import MapKit

class WayPoint : NSObject, MKAnnotation {
    let id = ContinuousClock.now
    var coordinate: CLLocationCoordinate2D
    var cylinderRadius: Int = 0
    var title: String?
    var subtitle: String?
    var weatherForecast : WeatherForecast

    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D(), cylinderRadius: Int = 0) {
        self.coordinate = coordinate
        self.cylinderRadius = cylinderRadius
        self.weatherForecast = WeatherForecast(coordinate: coordinate)
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

