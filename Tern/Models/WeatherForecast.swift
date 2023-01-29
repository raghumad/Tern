//
//  WeatherForecast.swift
//  Tern
//
//  Created by Raghu Madanala on 12/5/22.
//

import SwiftyJSON
import CoreLocation
struct WeatherForecastData: Identifiable {
    var id = UUID()
    var windspeed80m : Double
    var winddirection_80m : Double
    var windgusts_10m : Double
    var temperature_2m : Double
    var dewpoint_2m : Double
    var time : String
}

class WeatherForecast {
    var coordinate : CLLocationCoordinate2D
    private var weatherForecast : JSON = []
    private let units = MeasurementUnits.userDefaults
    init(coordinate: CLLocationCoordinate2D, weatherForecast: JSON = JSON("")) {
        self.coordinate = coordinate
        getForecast()
    }
    //MARK: windspeed80m[0].value will give 3.7 as Double whereas windspeed80m[0].description will give 3.7 mph
    var windspeed80m : [Measurement<UnitSpeed>] {
        get {
            return weatherForecast["hourly"]["windspeed_80m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: UnitSpeed.milesPerHour) }
        }
    }

    var winddirection_80m : [Measurement<UnitAngle>] {
        get {
            return weatherForecast["hourly"]["winddirection_80m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: .degrees) }
        }
    }

    //var [gust] = Measurement(value: weatherForecast["hourly"]["windgusts_10m"].arrayValue.map { $0.doubleValue }, unit: UnitSpeed.milesPerHour)
    var windgusts_10m : [Measurement<UnitSpeed>] {
        get {
            return weatherForecast["hourly"]["windgusts_10m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: .milesPerHour) }
        }
    }

    var temperature_2m : [Measurement<UnitTemperature>] {
        get {
            return weatherForecast["hourly"]["temperature_2m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: .fahrenheit) }
        }
    }

    var dewpoint_2m : [Measurement<UnitTemperature>] {
        get {
            return weatherForecast["hourly"]["dewpoint_2m"].arrayValue.map { Measurement(value: $0.doubleValue, unit: .fahrenheit) }
        }
    }

    var pressure_msl : [Measurement<UnitPressure>] {
        get {
            return weatherForecast["hourly"]["pressure_msl"].arrayValue.map { Measurement(value: $0.doubleValue, unit: .hectopascals) }
        }
    }

    var cloudcover : [UInt8] {
        get {
            return weatherForecast["hourly"]["cloudcover"].arrayValue.map { $0.uInt8Value }
        }
    }

    var relativehumidity_2m : [UInt8] {
        get {
            return weatherForecast["hourly"]["relativehumidity_2m"].arrayValue.map { $0.uInt8Value }
        }
    }

    var CAPE : [Double] {
        get {
            return weatherForecast["hourly"]["cloudcover"].arrayValue.map { $0.doubleValue   }
        }
    }

    var time : [NSDate] {
        get {
            return weatherForecast["hourly"]["time"].arrayValue.map { NSDate(timeIntervalSince1970: $0.doubleValue) }
        }
    }

    var weatherdata : [WeatherForecastData] {
        get {
            var weatherdata = [WeatherForecastData]()
            for i in time.indices {
                let hour = (Calendar.current.component(.hour, from: Date()) + Calendar.current.component(.hour, from: time[i] as Date)) % 24
                weatherdata.append(WeatherForecastData(windspeed80m: windspeed80m[i].converted(to: units.speed).value, winddirection_80m: winddirection_80m[i].value, windgusts_10m: windgusts_10m[i].converted(to: units.speed).value, temperature_2m: temperature_2m[i].converted(to: units.temperature).value, dewpoint_2m: dewpoint_2m[i].value, time: "\(hour)"))
            }
            return weatherdata
        }
    }

    func getForecast() {
        Task(priority: .high) {
            //https://api.open-meteo.com/v1/gfs?latitude=38.83&longitude=-104.82&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto
            //https://github.com/SwiftyJSON/SwiftyJSON to parse. got no time to create model structs.
            //https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Swift coordinate to xyz
            guard let url = URL(string: "https://api.open-meteo.com/v1/gfs?latitude=\(self.coordinate.latitude)&longitude=\(self.coordinate.longitude)&current_weather=true&hourly=temperature_2m,dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m,relativehumidity_2m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto&&timeformat=unixtime") else {
                print ("link error")
                return
            }
            
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                print(url)
                DispatchQueue.main.async {
                    self.weatherForecast = try! JSON(data: data)
                }
                print(self.weatherForecast)
            } catch {
                print("Open mateo fails.")
            }
        }
    }
}
