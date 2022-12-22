//
//  WelcomeScreen.swift
//  Tern
//
//  Created by Raghu Madanala on 12/21/22.
//

import SwiftUI
import CoreLocation

struct WelcomeScreen: View {
    @State var splash : Bool = true
    let routePlanner = RoutePlanner()
    var body: some View {
        if (splash) {
            VStack{
                Spacer()
                ZStack{
                    HStack{
                        //Spacer()
                        ZStack{
                            Image("Kjartan Birgisson")
                                .resizable()
                                .scaledToFit()
                                .padding(.all, 20)
                                .padding(.top, -20)
                                .padding(.leading,90)
                                .frame(width: 250)
                            //                        Circle()
                            //                            .padding(.leading, 300)
                            //                            .padding(.bottom, 50)
                            //                            .foregroundColor(.yellow)
                        }
                    }
                    Text("Tern Paragliding")
                        .font(.custom("Gruppo", size: 40))
                        .kerning(1.5)
                        .fontWeight(.heavy)
                        .font(.largeTitle)
                        .foregroundColor(.white)
                        .shadow(color: .black, radius: 5)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.cyan)
            .ignoresSafeArea(.all)
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                    splash.toggle()
                }
            }
        } else {
            routePlanner
        }
    }
}

struct WelcomeScreen_Previews: PreviewProvider {
    static var previews: some View {
        WelcomeScreen()
    }
}
