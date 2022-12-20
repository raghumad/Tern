//
//  Hotspot.swift
//  Tern
//
//  Created by Raghu Madanala on 12/19/22.
//

import Foundation
import MapKit

class Hotspot : NSObject, MKAnnotation {
    var coordinate: CLLocationCoordinate2D
    var title: String?
    var subtitle: String?
    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D(), title: String = "", subtitle: String = "") {
        self.coordinate = coordinate
        self.title = title
        self.subtitle = subtitle
    }
}
