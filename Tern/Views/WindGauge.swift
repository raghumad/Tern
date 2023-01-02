//
//  WeatherGuages.swift
//  Tern
//
//  Created by Raghu Madanala on 12/30/22.
//

import SwiftUI

struct WindGauge: View {
    let windSpeed : Measurement<UnitSpeed>
    let windDirection : Measurement<UnitAngle>?
    let label : String
    init(label: String, windSpeed: Measurement<UnitSpeed>, windDirection: Measurement<UnitAngle>? = nil) {
        self.label = label
        self.windSpeed = windSpeed
        self.windDirection = windDirection
    }
    let units = MeasurementUnits.userDefaults
    let gradient : Gradient = .init(colors: [Color.purple,
                                             Color(UIColor(red: 0, green: 0, blue: 1, alpha: 1)),
                                             Color.cyan,
                                             Color(UIColor(red: 0, green: 1, blue: 0, alpha: 1)),
                                             Color.yellow,
                                             Color(UIColor(red: 1, green: 0, blue: 0, alpha: 1))])
    let windMin = Measurement<UnitSpeed>(value: 0.0, unit: .milesPerHour)
    let windMax = Measurement<UnitSpeed>(value: 45, unit: .milesPerHour)
    var body: some View {
        ZStack{
            if windDirection != nil {
                Image(systemName: "triangle.fill")
                    .resizable()
                    .frame(width: 10,height: 5)
                    .foregroundColor(.mint)
                //.resizable()
                    .offset(y:-31)
                    .rotationEffect(.degrees(windDirection?.converted(to: .degrees).value ?? 0),anchor: .center)
                Rectangle().frame(width: 3, height: 57)
                    .foregroundColor(.mint)
                    .rotationEffect(.degrees(windDirection?.converted(to: .degrees).value ?? 0),anchor: .center)
            }
            Gauge(value: windSpeed.converted(to: units.speed).value, in: windMin.converted(to: units.speed).value...windMax.converted(to: units.speed).value) {
                Text("\(label) \(units.speed.symbol)")
                    .font(.custom("Gruppo", size: 8).monospaced())
                .foregroundColor(.accentColor)
            } currentValueLabel: {
                Text("\(String(format: "%0.0f", windSpeed.converted(to: units.speed).value))")
                    .font(.custom("Gruppo", size: 22).monospaced())
                    .foregroundColor(.accentColor)
            }
            .gaugeStyle(.accessoryCircular)
            .tint(gradient)
        }
    }
}

struct WeatherGuages_Previews: PreviewProvider {
    static var previews: some View {
        WindGauge(label: "Test", windSpeed: Measurement<UnitSpeed>(value: 15, unit: .milesPerHour), windDirection: Measurement<UnitAngle>(value: 10, unit: .degrees))
    }
}
