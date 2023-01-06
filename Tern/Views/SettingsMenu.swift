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
    @AppStorage("showHotspots") var showHotspots = true
    @State var units = MeasurementUnits.userDefaults
    
    var body: some View {
        HStack{
            Image(systemName: "gearshape")
                .resizable()
                .scaledToFit()
                .frame(height: 20)
                .foregroundColor(.white)
                .onTapGesture {
                    self.menu.toggle()
                    //Also restore the other states into appstate.
                }
                .sheet(isPresented: $menu) {
                    HStack {
                        Button{
                            self.menu.toggle()
                        } label: {
                            Text("𐦂").font(.custom("Gruppo", size: 12))
                                .foregroundColor(.red)
                        }
                        .padding([.leading, .top], 10)
                        Spacer()
                    }
                    NavigationView {
                        List {
                            Toggle(isOn: $showAirspaces) {
                                Label("Airspaces", systemImage: "airplane.circle")
                            }
                            Toggle(isOn: $showHotspots) {
                                Label("Hotspots", systemImage: "tornado")
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
                            HStack{
                                Text("Temperature")
                                Spacer()
                                Picker(selection: $units.temperature) {
                                    Text(UnitTemperature.fahrenheit.symbol).tag(UnitTemperature.fahrenheit)
                                    Text(UnitTemperature.celsius.symbol).tag(UnitTemperature.celsius)
                                    Text(UnitTemperature.kelvin.symbol).tag(UnitTemperature.kelvin)
                                } label: {
                                    Text("Temperature")
                                }
                                .cornerRadius(5)
                                .pickerStyle(.segmented)
                                .frame(width: 200)
                            }
                            HStack{
                                Text("Distance")
                                Spacer()
                                Picker(selection: $units.xcDistance) {
                                    Text(UnitLength.kilometers.symbol).tag(UnitLength.kilometers)
                                    Text(UnitLength.miles.symbol).tag(UnitLength.miles)
                                    Text(UnitLength.furlongs.symbol).tag(UnitLength.furlongs)
                                } label: {
                                    Text("Altitude")
                                }
                                .cornerRadius(5)
                                .pickerStyle(.segmented)
                                .frame(width: 200)
                            }
                            HStack{
                                Text("Speed")
                                Spacer()
                                Picker(selection: $units.speed) {
                                    Text(UnitSpeed.knots.symbol).tag(UnitSpeed.knots)
                                    Text(UnitSpeed.milesPerHour.symbol).tag(UnitSpeed.milesPerHour)
                                    Text(UnitSpeed.kilometersPerHour.symbol).tag(UnitSpeed.kilometersPerHour)
                                    Text(UnitSpeed.metersPerSecond.symbol).tag(UnitSpeed.metersPerSecond)
                                } label: {
                                    Text("Speed")
                                }
                                .cornerRadius(5)
                                .pickerStyle(.segmented)
                                .frame(width: 200)
                            }
                            HStack{
                                Text("Altitude")
                                Spacer()
                                Picker(selection: $units.magnitude) {
                                    Text(UnitLength.feet.symbol).tag(UnitLength.feet)
                                    Text(UnitLength.meters.symbol).tag(UnitLength.meters)
                                    Text(UnitLength.inches.symbol).tag(UnitLength.inches)
                                } label: {
                                    Text("Altitude")
                                }
                                .cornerRadius(5)
                                .pickerStyle(.segmented)
                                .frame(width: 200)
                            }
                        }
                    }
                    .font(.custom("Gruppo", size: 12))
                    .foregroundColor(.primary)
                    .presentationDetents([.fraction(0.5)])
                    .presentationDragIndicator(.visible)
                    .onDisappear {
                        //Save the values to state.
                        units.save()
                    }
                }
                .foregroundColor(.black)
        }
        .foregroundColor(.white)
        .font(.title2) // This size looks better.
        .padding(5)
        .background(.gray)
        .cornerRadius(5)
        .fixedSize()
    }
}

struct SettingsMenu_Previews: PreviewProvider {
    static var previews: some View {
        SettingsMenu()
    }
}
