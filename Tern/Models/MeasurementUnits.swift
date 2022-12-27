//
//  MeasurementUnits.swift
//  Tern
//
//  Created by Raghu Madanala on 12/26/22.
//

import Foundation

class MeasurementUnits : ObservableObject {
    @Published var speed : UnitSpeed
    @Published var magnitude : UnitLength
    @Published var xcDistance : UnitLength
    @Published var temperature : UnitTemperature

    static let userDefaults = MeasurementUnits()

    private init() {
        switch UserDefaults.standard.string(forKey: "speedUnit") {
        case UnitSpeed.kilometersPerHour.symbol:
            self.speed = UnitSpeed.kilometersPerHour
            break
        case UnitSpeed.milesPerHour.symbol:
            self.speed = UnitSpeed.milesPerHour
            break
        case UnitSpeed.metersPerSecond.symbol:
            self.speed = UnitSpeed.metersPerSecond
            break
        case UnitSpeed.knots.symbol:
            self.speed = UnitSpeed.knots
            break
        default:
            self.speed = UnitSpeed.kilometersPerHour
        }
        switch UserDefaults.standard.string(forKey: "magnitudeUnit") {
        case UnitLength.meters.symbol:
            self.magnitude = UnitLength.meters
            break
        case UnitLength.feet.symbol:
            self.magnitude = UnitLength.feet
            break
        case UnitLength.inches.symbol:
            self.magnitude = UnitLength.inches
            break
        default:
            self.magnitude = UnitLength.meters
        }
        switch UserDefaults.standard.string(forKey: "xcDistanceUnit") {
        case UnitLength.kilometers.symbol:
            self.xcDistance = UnitLength.kilometers
            break
        case UnitLength.miles.symbol:
            self.xcDistance = UnitLength.miles
            break
        case UnitLength.furlongs.symbol:
            self.xcDistance = UnitLength.furlongs
            break
        default:
            self.xcDistance = UnitLength.kilometers
        }
        switch UserDefaults.standard.string(forKey: "temperaturUnit") {
        case UnitTemperature.fahrenheit.symbol:
            self.temperature = UnitTemperature.fahrenheit
            break
        case UnitTemperature.kelvin.symbol:
            self.temperature = UnitTemperature.kelvin
            break
        case UnitTemperature.celsius.symbol:
            self.temperature = UnitTemperature.celsius
            break
        default:
            self.temperature = UnitTemperature.celsius
            break
        }
    }
    func save() {
        UserDefaults.standard.set(speed.symbol, forKey: "speedUnit")
        UserDefaults.standard.set(magnitude.symbol, forKey: "magnitudeUnit")
        UserDefaults.standard.set(xcDistance.symbol, forKey: "xcDistanceUnit")
        UserDefaults.standard.set(temperature.symbol, forKey: "temperaturUnit")
    }
}
