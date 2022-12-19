//
//  TextAnnotation.swift
//  Tern
//
//  Created by Raghu Madanala on 12/18/22.
//

import Foundation
import MapKit

class TextAnnotation : NSObject, MKAnnotation {
    var coordinate: CLLocationCoordinate2D
    var title: String?
    var subtitle: String?
    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D()) {
        self.coordinate = coordinate
    }
}
