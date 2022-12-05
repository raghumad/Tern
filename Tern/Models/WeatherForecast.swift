//
//  WeatherForecast.swift
//  Tern
//
//  Created by Raghu Madanala on 12/5/22.
//

import Foundation
import SwiftyJSON
import CoreLocation

class WeatherForecast {
    let coordinate : CLLocationCoordinate2D
    private var weatherForecast : JSON = []
    init(coordinate: CLLocationCoordinate2D, weatherForecast: JSON = JSON("")) {
        self.coordinate = coordinate
    }
    //MARK: windspeed80m[0].value will give 3.7 as Double whereas windspeed80m[0].description will give 3.7 mph
    var windspeed80m : [Measurement<UnitSpeed>] {
        get {
            return weatherForecast["hourly"]["windspeed_80m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitSpeed.milesPerHour) }
        }
    }

    var winddirection_80m : [Measurement<UnitAngle>] {
        get {
            return weatherForecast["hourly"]["winddirection_80m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitAngle.degrees) }
        }
    }

    //var [gust] = Measurement(value: weatherForecast["hourly"]["windgusts_10m"].arrayValue.map { $0.doubleValue }, unit: UnitSpeed.milesPerHour)
    var windgusts_10m : [Measurement<UnitSpeed>] {
        get {
            return weatherForecast["hourly"]["windgusts_10m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitSpeed.milesPerHour) }
        }
    }

    var temperature_2m : [Measurement<UnitTemperature>] {
        get {
            return weatherForecast["hourly"]["temperature_2m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitTemperature.fahrenheit) }
        }
    }

    var dewpoint_2m : [Measurement<UnitTemperature>] {
        get {
            return weatherForecast["hourly"]["dewpoint_2m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitTemperature.fahrenheit) }
        }
    }

    var pressure_msl : [Measurement<UnitPressure>] {
        get {
            return weatherForecast["hourly"]["pressure_msl"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitPressure.hectopascals) }
        }
    }

    var cloudcover : [UInt8] {
        get {
            return weatherForecast["hourly"]["cloudcover"].arrayValue.map { $0.uInt8Value }
        }
    }

    var CAPE : [Double] {
        get {
            return weatherForecast["hourly"]["cloudcover"].arrayValue.map { $0.doubleValue   }
        }
    }

    var weather_time : [NSDate] {
        get {
            return weatherForecast["hourly"]["time"].arrayValue.map { NSDate(timeIntervalSince1970: $0.doubleValue) }
        }
    }

    func getMeteoForecast() async {
        //https://api.open-meteo.com/v1/gfs?latitude=38.83&longitude=-104.82&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto
        //https://github.com/SwiftyJSON/SwiftyJSON to parse. got no time to create model structs.
        //https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Swift coordinate to xyz
        guard let url = URL(string: "https://api.open-meteo.com/v1/gfs?latitude=\(self.coordinate.latitude)&longitude=\(self.coordinate.longitude)&current_weather=true&hourly=temperature_2m,dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto&&timeformat=unixtime") else {
            print ("link error")
            return
        }
        
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            //print(url)
            DispatchQueue.main.async {
                self.weatherForecast = try! JSON(data: data)
            }
            //print(self.weather)
        } catch {
            print("Open mateo fails.")
        }
    }
}