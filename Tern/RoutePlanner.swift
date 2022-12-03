//
//  RoutePlanner.swift
//  Tern
//
//  Created by Raghu Madanala on 11/21/22.
//

import SwiftUI
import MapKit

struct RoutePlanner: View {
    @StateObject var model = RoutePlannerModel()
    var body: some View {
        ZStack(alignment: .center){
            RoutePlannerMapViewHelper().environmentObject(model)
            .ignoresSafeArea()
            Image(systemName: "scope")
            VStack{
                Spacer()
                HStack(alignment: .bottom){ //Everything in this stack will be white and title2 size.
                    Spacer()
                    Button{
                        model.addWaypoint()
                    } label: {
                        Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                    }
                    .padding()
                    .background(.black.opacity(0.75))
                    .clipShape(Circle())
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
