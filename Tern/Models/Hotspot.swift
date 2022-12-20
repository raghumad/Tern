//
//  Hotspot.swift
//  Tern
//
//  Created by Raghu Madanala on 12/19/22.
//

import Foundation
import MapKit

class Hotspot : MKCircle{
    let elevation, probability : Double
    init(coordinate: CLLocationCoordinate2D, elevation: Double, probability: Double) {
        self.elevation = elevation
        self.probability = probability
    }
}
