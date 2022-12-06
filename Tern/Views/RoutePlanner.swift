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
            RoutePlannerMapViewHelper(manager: model)
            .ignoresSafeArea()
//            Image(systemName: "mappin.and.ellipse")
//                .offset(y:-5)
//            VStack(alignment: .trailing) {
//                
//            }
            VStack{
                Spacer()
                HStack(alignment: .bottom){ //Everything in this stack will be white and title2 size.
                    Spacer()
                    Section{
                        Button{
                            model.addWaypoint()
                        } label: {
                            Image(systemName: "point.3.connected.trianglepath.dotted")
                                .rotationEffect(.degrees(30))
                        }
                        .padding(12)
                        .background(.blue.opacity(0.9))
                        .cornerRadius(8)
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
        RoutePlanner()
    }
}
