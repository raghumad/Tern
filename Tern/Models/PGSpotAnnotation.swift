//
//  PGSpotAnnotation.swift
//  Tern
//
//  Created by Raghu Madanala on 12/23/22.
//

import Foundation
import MapKit
import SwiftyJSON

class PGSpotAnnotation : NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    var forecast: OpenMeteoForecast?
    let properties : JSON
    init(coordinate: CLLocationCoordinate2D = CLLocationCoordinate2D(), title: String = "", subtitle: String = "", properties: JSON) {
        self.coordinate = coordinate
        self.title = title
        self.subtitle = subtitle
        self.properties = properties
        super.init()
        self.getForecast()
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
}
