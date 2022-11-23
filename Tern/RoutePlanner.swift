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
    //@State private var weatherData: String
    //@State private var region = MKCoordinateRegion(center: CLLocation(latitude: 38.9121906016191, longitude: -104.72783900204881).coordinate, span: MKCoordinateSpan(latitudeDelta: 50, longitudeDelta: 50))
    var body: some View {
        ZStack(alignment: .bottom){
            Map(coordinateRegion: $model.region, showsUserLocation: true)
                .ignoresSafeArea()
            HStack{
                LocationButton(.currentLocation) {
                    model.requestAllowOnceLocationPermission()
                    Task{
                        await model.getWeather()
                    }
                }
                .foregroundColor(.white)
                .cornerRadius(8)
                .labelStyle(.iconOnly)
                //Text(model.weather)
            }
        }
    }
}

class RoutePlannerModel : NSObject, CLLocationManagerDelegate, ObservableObject {
    @Published var weather = String("No Weather Data")
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

    func getWeather() async {
        //https://api.open-meteo.com/v1/gfs?latitude=38.83&longitude=-104.82&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto
        //https://github.com/SwiftyJSON/SwiftyJSON to parse. got no time to create model structs. 
        guard let url = URL(string: "https://api.open-meteo.com/v1/gfs?latitude=\(self.region.center.latitude)&longitude=\(self.region.center.longitude)&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto") else {
            print ("link error")
            return
        }
        
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            print(url)
            DispatchQueue.main.async {
                self.weather = String(decoding: data, as: UTF8.self)
            }
            print(self.weather)
        } catch {
            print("Open mateo fails.")
        }
    }
}

struct RoutePlanner_Previews: PreviewProvider {
    static var previews: some View {
        RoutePlanner()
    }
}
