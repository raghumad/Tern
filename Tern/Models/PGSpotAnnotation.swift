//
//  PGSpotAnnotation.swift
//  Tern
//
//  Created by Raghu Madanala on 12/23/22.
//

import Foundation
import MapKit

class PGSpotAnnotation : NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    var forecast: WeatherForecast
    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D(), title: String = "", subtitle: String = "") {
        self.coordinate = coordinate
        self.title = title
        self.subtitle = subtitle
        self.forecast = WeatherForecast(coordinate: coordinate)
    }
}
