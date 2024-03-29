//
//  OpenMeteoForecast.swift
//  Tern
//
//  Created by Raghu Madanala on 1/28/23.
//

import Foundation
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

struct OpenMeteoForecast : Codable {
    /*
     latitude    39.13498
     longitude    -104.818085
     generationtime_ms    1.6530752182006836
     utc_offset_seconds    -25200
     timezone    "America/Denver"
     timezone_abbreviation    "MST"
     elevation    2377
     current_weather    {…}
     hourly_units    {…}
     hourly    {…}
     */
    let latitude : Double
    let longitude : Double
    let generationtime_ms : Double
    let utc_offset_seconds : Double
    let timezone : String
    let timezone_abbreviation : String
    let elevation : UInt
    let current_weather : current_weather
    let hourly : hourly

    private enum CodingKeys : String, CodingKey {
        case latitude = "latitude"
        case longitude = "longitude"
        case generationtime_ms = "generationtime_ms"
        case utc_offset_seconds = "utc_offset_seconds"
        case timezone  = "timezone"
        case timezone_abbreviation = "timezone_abbreviation"
        case elevation = "elevation"
        case current_weather = "current_weather"
        //case hourly_units = "hourly_units"
        case hourly = "hourly"
    }
}

struct current_weather : Codable {
    /*
     current_weather
     temperature    18.5
     windspeed    6
     winddirection    333
     weathercode    71
     time    "2023-01-28T20:00"
     */
    let temperature : Measurement<UnitTemperature>
    let windspeed : Measurement<UnitSpeed>
    let winddirection : Measurement<UnitAngle>
    let weathercode : UInt
    let time : Date

    private enum CodingKeys : String, CodingKey {
        case temperature = "temperature"
        case windspeed = "windspeed"
        case winddirection = "winddirection"
        case weathercode = "weathercode"
        case time = "time"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.temperature = Measurement(value: try values.decode(Double.self, forKey: CodingKeys.temperature), unit: .fahrenheit)
        self.windspeed = Measurement(value: try values.decode(Double.self, forKey: CodingKeys.windspeed), unit: .milesPerHour)
        self.winddirection = Measurement(value: try values.decode(Double.self, forKey: CodingKeys.winddirection), unit: .degrees)
        self.weathercode = try values.decode(UInt.self, forKey: CodingKeys.weathercode)
        self.time = try values.decode(Date.self, forKey: CodingKeys.time)
    }
}

struct hourly : Codable {
    let time : [Date]
    let temperature_2m : [Measurement<UnitTemperature>]
    let relativehumidity_2m : [UInt8] //percentage
    let dewpoint_2m : [Measurement<UnitTemperature>]
    let weathercode : [UInt]
    let pressure_msl : [Measurement<UnitPressure>]
    let surface_pressure : [Measurement<UnitPressure>]
    let cloudcover : [UInt8] //percentage
    let windspeed_80m : [Measurement<UnitSpeed>]
    let winddirection_80m : [Measurement<UnitAngle>]
    let windgusts_10m : [Measurement<UnitSpeed>]
    private let units = MeasurementUnits.userDefaults

    /*
     hourly
     time    […]
     temperature_2m    […]
     relativehumidity_2m    […]
     dewpoint_2m    […]
     weathercode    […]
     pressure_msl    […]
     surface_pressure    […]
     cloudcover    […]
     windspeed_80m    […]
     winddirection_80m    […]
     windgusts_10m    […]
     */
    private enum CodingKeys : String, CodingKey {
        case time = "time"
        case temperature_2m = "temperature_2m"
        case relativehumidity_2m = "relativehumidity_2m"
        case dewpoint_2m = "dewpoint_2m"
        case weathercode = "weathercode"
        case pressure_msl = "pressure_msl"
        case surface_pressure = "surface_pressure"
        case cloudcover = "cloudcover"
        case windspeed_80m = "windspeed_80m"
        case winddirection_80m = "winddirection_80m"
        case windgusts_10m = "windgusts_10m"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.time = try values.decode([Date].self, forKey: CodingKeys.time)
        self.temperature_2m = try values.decode([Double].self, forKey: CodingKeys.temperature_2m).map{ Measurement(value: $0, unit: .fahrenheit) }
        self.relativehumidity_2m = try values.decode([UInt8].self, forKey: CodingKeys.relativehumidity_2m)
        self.dewpoint_2m = try values.decode([Double].self, forKey: CodingKeys.dewpoint_2m).map{ Measurement(value: $0, unit: .fahrenheit) }
        self.weathercode = try values.decode([UInt].self, forKey: CodingKeys.weathercode)
        self.pressure_msl = try values.decode([Double].self, forKey: CodingKeys.pressure_msl).map { Measurement(value: $0, unit: .hectopascals) }
        self.surface_pressure = try values.decode([Double].self, forKey: CodingKeys.surface_pressure).map { Measurement(value: $0, unit: .hectopascals)}
        self.cloudcover = try values.decode([UInt8].self, forKey: CodingKeys.cloudcover)
        self.windspeed_80m = try values.decode([Double].self, forKey: CodingKeys.windspeed_80m).map { Measurement(value: $0, unit: .milesPerHour) }
        self.winddirection_80m = try values.decode([Double].self, forKey: CodingKeys.winddirection_80m).map { Measurement(value: $0 , unit: .degrees) }
        self.windgusts_10m = try values.decode([Double].self, forKey: CodingKeys.windgusts_10m).map{ Measurement(value: $0, unit: .milesPerHour) }
    }

    var weatherdata : [WeatherForecastData] {
        get {
            var weatherdata = [WeatherForecastData]()
            for i in Calendar.current.component(.hour, from: Date())...(Calendar.current.component(.hour, from: Date())+23) {
                weatherdata.append(WeatherForecastData(windspeed80m: windspeed_80m[i].converted(to: units.speed).value, winddirection_80m: winddirection_80m[i].value, windgusts_10m: windgusts_10m[i].converted(to: units.speed).value, temperature_2m: temperature_2m[i].converted(to: units.temperature).value, dewpoint_2m: dewpoint_2m[i].value, time: "\(Calendar.current.component(.hour, from: time[i] as Date))"))
            }
            return weatherdata
        }
    }
}
