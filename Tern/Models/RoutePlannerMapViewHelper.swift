//
//  RoutePlannerMapViewHelper.swift
//  Tern
//
//  Created by Raghu Madanala on 12/3/22.
//

import Foundation
import SwiftUI
import MapKit

struct RoutePlannerMapViewHelper : UIViewRepresentable {
    @ObservedObject var manager: RoutePlannerModel
    func makeUIView(context: Context) -> MKMapView {
        return manager.mapView
    }

    func updateUIView(_ uiView: MKMapView, context: Context) {
    }
}
