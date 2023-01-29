//
//  NWSWeatherForecast.swift
//  Tern
//
//  Created by Raghu Madanala on 1/25/23.
//

import Foundation
import CoreLocation

//NWS returns geojsons... but only for usa..

struct NWSPoint : Identifiable {
    let id : CLLocationCoordinate2D // CLLocationCoordinate2D is already Hashable, Codable.
    var url : URL? {
        return URL(string: "https://api.weather.gov/points/\(String(format: "%.3f", id.latitude)),\(String(format: "%.3f", id.longitude))")
    }
    var json : String {
        let config = URLSessionConfiguration.default
        config.httpAdditionalHeaders = ["User-Agent": "(https://tern.madanala.com/, tern@madanala.com) Tern Paragliding"]
        let session = URLSession(configuration: config)
        var request = URLRequest(url: self.url!)
        request.httpMethod = "GET"
        var jsonString = String("")
        let task = session.dataTask(with: request) { data, response, error in

            // ensure there is no error for this HTTP response
            guard error == nil else {
                print ("error: \(error!)")
                return
            }

            // ensure there is data returned from this HTTP response
            guard let content = data else {
                print("No data")
                return
            }

            // serialise the data / NSData object into Dictionary [String : Any]
            guard let json = (try? JSONSerialization.jsonObject(with: content, options: JSONSerialization.ReadingOptions.mutableContainers)) as? [String: Any] else {
                print("Not containing JSON")
                return
            }

            print("gotten json response dictionary is \n \(json)")
            jsonString =  json.description
            // update UI using the response here
        }

        // execute the HTTP request
        task.resume()
        return jsonString
    }
}

//TODO: NWSPointProperties
struct NWSPointProperties {
}
