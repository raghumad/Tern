//
//  RoutePlanner.swift
//  Tern
//
//  Created by Raghu Madanala on 11/21/22.
//

import SwiftUI
import MapKit
import CoreLocationUI

struct RoutePlanner: View {
    @StateObject private var model = RoutePlannerModel()
    //@State private var region = MKCoordinateRegion(center: CLLocation(latitude: 38.9121906016191, longitude: -104.72783900204881).coordinate, span: MKCoordinateSpan(latitudeDelta: 50, longitudeDelta: 50))
    var body: some View {
        ZStack(alignment: .bottom){
            Map(coordinateRegion: $model.region, showsUserLocation: true)
                .ignoresSafeArea()
            HStack{
                LocationButton(.currentLocation) {
                    model.requestAllowOnceLocationPermission()
                }
                .foregroundColor(.white)
                .cornerRadius(8)
                .labelStyle(.iconOnly)
            }
        }
    }
}

class RoutePlannerModel : NSObject, CLLocationManagerDelegate, ObservableObject {
    //38.9121906016191, -104.72783900204881
    @Published var region = MKCoordinateRegion(center: CLLocation(latitude: 38.9121906016191, longitude: -104.72783900204881).coordinate, latitudinalMeters: 25*1.61*1000, longitudinalMeters: 25*1.61*1000)
    private let locationManager = CLLocationManager()

    func requestAllowOnceLocationPermission () {
        locationManager.requestLocation()
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else {return}
        DispatchQueue.main.async {
            self.region = MKCoordinateRegion(
                center: location.coordinate,
                latitudinalMeters: 25*1.61*1000,
                longitudinalMeters: 25*1.61*1000) //25 miles
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        //Set default location
        self.region = MKCoordinateRegion(center: CLLocation(latitude: 38.9121906016191, longitude: -104.72783900204881).coordinate, span: MKCoordinateSpan(latitudeDelta: 25, longitudeDelta: 25))
    }

    override init() {
        super.init()
        locationManager.delegate = self
    }
}

struct RoutePlanner_Previews: PreviewProvider {
    static var previews: some View {
        RoutePlanner()
    }
}
