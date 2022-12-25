//
//  SettingsMenu.swift
//  Tern
//
//  Created by Raghu Madanala on 12/25/22.
//

import SwiftUI

struct SettingsMenu: View {
    @State var menu = false
    @AppStorage("showAirspaces") var showAirspaces = true
    @AppStorage("showPGSpots") var showPGSpots = true
    @State var windUnit : UnitSpeed = .milesPerHour
    @State var altitudeUnit : UnitLength = .feet
    @State var distanceUnit : UnitLength = .miles
    
    var body: some View {
        HStack{
            VStack{
                Image(systemName: "gearshape")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 25,alignment: .topLeading)
                    .foregroundColor(.white)
                    .onTapGesture {
                        self.menu.toggle()
                    }
                if menu {
                    Toggle(isOn: $showAirspaces) {
                        Label("Airspaces", systemImage: "airplane.circle")
                    }
                    Toggle(isOn: $showPGSpots) {
                        HStack{
                            Image("Kjartan Birgisson")
                                .resizable()
                                .scaledToFit()
                                .frame(height: 15)
                            Text("PGSpots")
                        }
                    }
                    Divider()
                    HStack{
                        Text("Distance")
                        Spacer()
                        Picker(selection: $distanceUnit) {
                            Text("km").tag(UnitLength.kilometers)
                            Text("miles").tag(UnitLength.miles)
                            Text("fathoms").tag(UnitLength.fathoms)
                        } label: {
                            Text("Altitude")
                        }
                        .cornerRadius(5)
                        .pickerStyle(.segmented)
                    }
                    HStack{
                        Text("Wind")
                        Spacer()
                        Picker(selection: $windUnit) {
                            Text("kt").tag(UnitSpeed.knots)
                            Text("mph").tag(UnitSpeed.milesPerHour)
                            Text("kmph").tag(UnitSpeed.kilometersPerHour)
                            Text("m/s").tag(UnitSpeed.metersPerSecond)
                        } label: {
                            Text("Wind")
                        }
                        .cornerRadius(5)
                        .pickerStyle(.segmented)
                    }
                    HStack{
                        Text("Altitude")
                        Spacer()
                        Picker(selection: $altitudeUnit) {
                            Text("ft").tag(UnitLength.feet)
                            Text("m").tag(UnitLength.meters)
                            Text("lightyears").tag(UnitLength.lightyears)
                        } label: {
                            Text("Altitude")
                        }
                        .cornerRadius(5)
                        .pickerStyle(.segmented)
                    }
                }
            }
            .frame(alignment: .leading)
            //.animation(.spring())
            Spacer()
        }
    }
}

struct SettingsMenu_Previews: PreviewProvider {
    static var previews: some View {
        SettingsMenu()
    }
}
