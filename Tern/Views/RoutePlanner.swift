//
//  RoutePlanner.swift
//  Tern
//
//  Created by Raghu Madanala on 11/21/22.
//

import SwiftUI
import MapKit
import CoreLocationUI

struct RoutePlanner: View {
    @StateObject var model = RoutePlannerModel()
    var body: some View {
        ZStack(alignment: .center){
            RoutePlannerMapViewHelper().environmentObject(model)
            .ignoresSafeArea()
            Image(systemName: "scope")
            VStack(alignment: .trailing) {
                
            }
            VStack{
                Spacer()
                HStack(alignment: .bottom){ //Everything in this stack will be white and title2 size.
                    Spacer()
                    Section{
                        Button{
                            model.addWaypoint()
                        } label: {
                            Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                        }
                        .padding(12)
                        .background(.blue.opacity(0.9))
                        .cornerRadius(8)
                        LocationButton(.currentLocation) {
                            model.region = MKCoordinateRegion(center: model.latestLocation.coordinate, latitudinalMeters: 10000, longitudinalMeters: 10000)
                            print(model.latestLocation.coordinate)
                        }
                        .foregroundColor(.white)
                        .cornerRadius(8)
                        .labelStyle(.iconOnly)
                    }
                }
                .foregroundColor(.white)
                .font(.title2) // This size looks better.
            }
            .padding(.trailing)
        }
    }
}

struct RoutePlanner_Previews: PreviewProvider {
    static var previews: some View {
        RoutePlanner(model: RoutePlannerModel())
    }
}
