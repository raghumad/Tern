//
//  PGSpotForecast.swift
//  Tern
//
//  Created by Raghu Madanala on 12/23/22.
//

import SwiftUI
import Charts
import CoreLocation
import SwiftyJSON

struct PGSpotForecast: View {
    let pgSpot : PGSpotAnnotation
    @State var isSheet = false
    var units = MeasurementUnits.userDefaults
    
    init(pgSpot: PGSpotAnnotation) {
        self.pgSpot = pgSpot
        pgSpot.forecast.getForecast()
    }
    var body: some View {
        VStack {
            if pgSpot.subtitle != "" {
                Text(pgSpot.subtitle ?? "").font(.system(size: 8, design: .monospaced))
                    .truncationMode(.tail)
                    .lineLimit(3)
            }
            if pgSpot.properties["comments"].stringValue != "" {
                Text(pgSpot.properties["comments"].stringValue).font(.system(size: 8, design: .monospaced))
                    .truncationMode(.tail)
                    .lineLimit(3)
            }
            if pgSpot.properties["going_there"].stringValue != "" {
                Text("Getting there-> \(pgSpot.properties["going_there"].stringValue)").font(.system(size: 8, design: .monospaced))
                    .truncationMode(.tail)
                    .lineLimit(3)
            }
            if let elevation = Measurement<UnitLength>(value: pgSpot.properties["takeoff_altitude"].doubleValue, unit: units.magnitude) {
                HStack{
                    //Image(systemName: "mountain.2")
                    Text("🏔️\(String(format:"%0.0f",elevation.converted(to: units.magnitude).value))\(units.magnitude.symbol)")
                }
                .foregroundColor(.white)
                .background(Color.blue)
                .cornerRadius(5, antialiased: true)
            }
            HStack {
                if pgSpot.forecast.winddirection_80m.count > 0 {
                    HStack {
                        Text("👆")
                            .rotationEffect(.degrees(pgSpot.forecast.winddirection_80m[0].converted(to: .degrees).value))
                        Text("\(String(format: "%0.1f", pgSpot.forecast.windspeed80m[0].converted(to: units.speed).value))\(units.speed.symbol)")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                    HStack {
                        //Image(systemName: "tornado.circle")
                        Text("💨\(String(format: "%0.1f", pgSpot.forecast.windgusts_10m[0].converted(to: units.speed).value))\(units.speed.symbol)")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                }
            }
            HStack {
                if pgSpot.forecast.relativehumidity_2m.count > 0 {
                    HStack{
                        Image(systemName: "humidity")
                        Text("\(pgSpot.forecast.relativehumidity_2m[0].description)%")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                    HStack {
                        //Image(systemName: "cloud.circle")
                        Text("⛅️\(pgSpot.forecast.cloudcover[0].description)%")
                    }
                    .foregroundColor(.white)
                    .background(Color.blue)
                    .cornerRadius(5, antialiased: true)
                }
            }
        }
        .onTapGesture {
            isSheet.toggle()
        }
        .sheet(isPresented: $isSheet) {
            VStack {
                Spacer()
                if pgSpot.properties["weather"].stringValue != "" {
                    Text(pgSpot.properties["weather"].stringValue).font(.system(size: 8, design: .monospaced))
                }
                ZStack{
                    VStack{
                        Text("Next 24hr forecast")
                        Spacer()
                    }
                    HStack{
                        Text("Windspeed").fontWeight(.ultraLight).foregroundColor(.cyan)
                        Text("Gustspeed").fontWeight(.ultraLight).foregroundColor(.red)
                    }
                    Chart (pgSpot.forecast.weatherdata) { item in
                        LineMark(x: .value("Time", item.time),
                                 y: .value("WindGust", item.windgusts_10m))
                    }
                    .foregroundStyle(.red)
                    Chart (pgSpot.forecast.weatherdata) { item in
                        LineMark(x: .value("Time", item.time),
                                 y: .value("WindSpeed", item.windspeed80m))
                    }
                    .foregroundStyle(.cyan)
                }
                ZStack{
                    Text("Wind Direction").fontWeight(.ultraLight).foregroundColor(.red)
                    Chart (pgSpot.forecast.weatherdata) { item in
                        RectangleMark(
                            x: .value("Time", item.time),
                            y: .value("WindDirection", item.winddirection_80m),
                            width:5, height: 2)
                    }
                    .foregroundStyle(.red)
                }
                ZStack {
                    HStack {
                        Text("Temperature").fontWeight(.ultraLight).foregroundColor(.orange)
                        Text("Due Point").fontWeight(.ultraLight).foregroundColor(.blue)
                    }
                    Chart (pgSpot.forecast.weatherdata) { item in
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
                        Link("View on Paragliding Earth", destination: URL(string: pgSpot.properties["pge_link"].stringValue)!).font(.system(size: 8, design: .monospaced))
                    } else {
                        Link("pg site info by paragliding earth", destination: URL(string: "https://www.paraglidingearth.com/")!).font(.system(size: 8, design: .monospaced))
                    }
                    Link("Weather data by Open-Meteo.com", destination: URL(string: "https://open-meteo.com/")!).font(.system(size: 8, design: .monospaced))
                }
            }
            .presentationDetents([.fraction(0.8)])
            .presentationDragIndicator(.visible)
            
        }
        .onDisappear{
            isSheet.toggle()
        }
    }
}

struct PGSpotForecast_Previews: PreviewProvider {
    static var previews: some View {
        let coordinate = CLLocationCoordinate2D(latitude: 40.2530073213, longitude: -105.609067564)
        PGSpotForecast(pgSpot: PGSpotAnnotation(coordinate: coordinate, title: "Hi", subtitle: "Big Hi", properties: JSON("")))
    }
}
