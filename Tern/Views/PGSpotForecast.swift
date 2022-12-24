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
    @State var isSheet = true
    
    init(pgSpot: PGSpotAnnotation) {
        self.pgSpot = pgSpot
        Task { await pgSpot.forecast.getForecast() }
    }
    var body: some View {
        VStack {
            Text(pgSpot.title ?? "")
            Text(pgSpot.subtitle ?? "")
        }
        .sheet(isPresented: $isSheet) {
            VStack {
                HStack{
                    Text(pgSpot.subtitle ?? "")
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
            }
        }
        .onDisappear{
            isSheet.toggle()
        }
    }
}

struct PGSpotForecast_Previews: PreviewProvider {
    static var previews: some View {
        let coordinate = CLLocationCoordinate2D(latitude: 40.2530073213, longitude: -105.609067564)
        PGSpotForecast(pgSpot: PGSpotAnnotation(coordinate: coordinate, title: "Hi", subtitle: "Big Hi"))
    }
}
