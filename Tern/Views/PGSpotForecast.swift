//
//  PGSpotForecast.swift
//  Tern
//
//  Created by Raghu Madanala on 12/23/22.
//

import SwiftUI
import Charts
import CoreLocation

struct PGSpotForecast: View {
    let pgSpot : PGSpotAnnotation
    @State var isSheet = false
    let units = MeasurementUnits.userDefaults
    
    init(pgSpot: PGSpotAnnotation) {
        self.pgSpot = pgSpot
        pgSpot.getForecast()
    }
    var body: some View {
        VStack {
            if pgSpot.subtitle != "" {
                Text(pgSpot.subtitle ?? "")
                    .font(.custom("Gruppo", size: 8).monospaced())
                    .truncationMode(.tail)
                    .lineLimit(3)
            }
            if pgSpot.properties["comments"].stringValue != "" {
                Text(pgSpot.properties["comments"].stringValue)
                    .font(.custom("Gruppo", size: 8).monospaced())
                    .truncationMode(.tail)
                    .lineLimit(3)
            }
            if pgSpot.properties["going_there"].stringValue != "" {
                Text("Getting there-> \(pgSpot.properties["going_there"].stringValue)")
                    .font(.custom("Gruppo", size: 8).monospaced())
                    .truncationMode(.tail)
                    .lineLimit(3)
            }
            if let elevation = Measurement<UnitLength>(value: pgSpot.properties["takeoff_altitude"].doubleValue, unit: .meters) {
                HStack{
                    Text("🏔️\(String(format:"%0.0f",elevation.converted(to: units.magnitude).value))\(units.magnitude.symbol)")
                        .font(.custom("Gruppo", size: 12))
                }
                .foregroundColor(.white)
                .background(Color.blue)
                .cornerRadius(3, antialiased: true)
            }
            if pgSpot.forecast?.current_weather != nil {
                HStack{
                    WindGauge(label: "Wind", windSpeed: (pgSpot.forecast?.current_weather.windspeed.converted(to: units.speed))!, windDirection: pgSpot.forecast?.current_weather.winddirection.converted(to: .degrees))
                    WindGauge(label: "Gust", windSpeed: (pgSpot.forecast?.hourly.windgusts_10m[0].converted(to: units.speed))!)
                }
                HStack{
                    Gauge(value: Double((pgSpot.forecast?.hourly.relativehumidity_2m[0].description)!) ?? 0, in: 0...100) {
                        Text("Humidity")
                            .foregroundColor(.primary)
                            .font(.custom("Gruppo", size: 8).monospaced())
                    }
                    .gaugeStyle(.accessoryLinearCapacity)
                    Gauge(value: Double((pgSpot.forecast?.hourly.cloudcover[0].description)!) ?? 0, in: 0...100) {
                        Text("Cloud Cover")
                            .font(.custom("Gruppo", size: 8).monospaced())
                            .foregroundColor(.primary)
                    }
                    .gaugeStyle(.accessoryLinearCapacity)
                }
                .padding(.bottom,5)
            }
        }
        .onTapGesture {
            isSheet.toggle()
        }
        .sheet(isPresented: $isSheet) {
            VStack {
                Spacer()
                if pgSpot.properties["weather"].stringValue != "" {
                    Text(pgSpot.properties["weather"].stringValue).font(.custom("Gruppo", size: 8).monospaced())
                }
                ZStack{
                    VStack{
                        Text("Next 24hr forecast")
                            .font(.custom("Gruppo", size: 12))
                        Spacer()
                    }
                    HStack{
                        Text("Windspeed \(units.speed.symbol)").font(.custom("Gruppo", size: 12)).foregroundColor(.cyan)
                        Text("Gustspeed \(units.speed.symbol)").font(.custom("Gruppo", size: 12)).foregroundColor(.red)
                    }
                    Chart ((pgSpot.forecast?.hourly.weatherdata)!) { item in
                        LineMark(x: .value("Time", item.time),
                                 y: .value("WindGust", item.windgusts_10m))
                    }
                    .foregroundStyle(.red)
                    Chart ((pgSpot.forecast?.hourly.weatherdata)!) { item in
                        LineMark(x: .value("Time", item.time),
                                 y: .value("WindSpeed", item.windspeed80m))
                    }
                    .chartXAxis(content: {
                    })
                    .foregroundStyle(.cyan)
                    VStack{
                        Spacer()
                        Text("Wind Direction")
                            .font(.custom("Gruppo", size: 12))
                            .foregroundColor(.green)
                        Chart ((pgSpot.forecast?.hourly.weatherdata)!) { item in
                            PointMark(
                                x: .value("Time", item.time)//,
                                //y: .value("WindDirection", item.winddirection_80m)
                            )
                            .symbol {
                                Image(systemName: "arrow.up")
                                    .resizable()
                                    .frame(width: 5.0, height: 10.0)
                                    .rotationEffect(.degrees(item.winddirection_80m))
                            }
                        }
                        .chartXAxis(content: {
                        })
                        .foregroundStyle(.green)
                        .frame(height: 35)
                    }
                }
                ZStack {
                    HStack {
                        Text("Temperature \(units.temperature.symbol)").font(.custom("Gruppo", size: 12)).foregroundColor(.orange)
                        Text("Due Point \(units.temperature.symbol)").font(.custom("Gruppo", size: 12)).foregroundColor(.blue)
                    }
                    Chart ((pgSpot.forecast?.hourly.weatherdata)!) { item in
                        LineMark(x: .value("Time", item.time),
                                 y: .value("Temp", item.temperature_2m))
                        .foregroundStyle(.orange)
                        RectangleMark(
                            x: .value("Time", item.time),
                            y: .value("DuePt", item.dewpoint_2m),
                            width:5, height: 1)
                        .foregroundStyle(.blue)
                    }
                    .chartLegend(position: .trailing)
                }
                HStack {
                    
                    if pgSpot.properties["pge_link"].stringValue != "" {
                        Link("View on Paragliding Earth", destination: URL(string: pgSpot.properties["pge_link"].stringValue)!).font(.custom("Gruppo", size: 12).monospaced())
                    } else {
                        Link("pg site info by paragliding earth", destination: URL(string: "https://www.paraglidingearth.com/")!).font(.custom("Gruppo", size: 12).monospaced())
                    }
                    Link("Weather data by Open-Meteo.com", destination: URL(string: "https://open-meteo.com/")!).font(.custom("Gruppo", size: 12).monospaced())
                }
            }
            .presentationDetents([.fraction(0.6)])
            .presentationDragIndicator(.visible)
            
        }
        .onDisappear{
            isSheet.toggle()
        }
    }
}
