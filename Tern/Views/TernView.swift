//
//  RoutePlanner.swift
//  Tern
//
//  Created by Raghu Madanala on 11/21/22.
//

import SwiftUI
import MapKit
import CoreImage
import CoreImage.CIFilterBuiltins

struct TernView: View {
    @StateObject var model = TernModel()
    var body: some View {
        ZStack(alignment: .center){
            TernMapViewHelper(manager: model)
            VStack{
                HStack{
                    SettingsMenu().environmentObject(model)
                        .padding([.top, .leading], 20)
                        .padding(.top, 30)
                    Spacer()
                }
                Spacer()
                if model.screen == .planning {
                    HStack{ //Everything in this stack will be white and title2 size.
                        Spacer()
                        HStack{
                            Button{
                                model.addWaypoint(coordinate: model.mapView.centerCoordinate)
                            } label: {
                                Image(systemName: "point.filled.topleft.down.curvedto.point.bottomright.up")
                            }.padding([.vertical, .leading], 7)
                            Divider().overlay(.white).padding(0)
                            Button{
                                //We open a menu here so nothing doing.
                            } label: {
                                Menu {
                                    Button {
                                        model.save()
                                    } label: {
                                        Label("Save", systemImage: "square.and.arrow.down")
                                    }
                                    Button {
                                        let urlPath = URL(filePath: model.saveXCTSK())
                                        model.shareItems.removeAll()
                                        model.shareItems.append(urlPath)
                                        model.shareRoute.toggle()
                                    } label: {
                                        HStack {
                                            Text (".xctsk File")
                                            Image(systemName: "paperplane")
                                        }
                                    }
                                    Button {
                                        let imageContext = CIContext()
                                        let qrFilter = CIFilter.qrCodeGenerator()
                                        qrFilter.message = Data(model.saveXCTSKWqr().utf8)
                                        if let outputImage = qrFilter.outputImage {
                                            if let cgImg = imageContext.createCGImage(outputImage, from: outputImage.extent) {
                                                let qr = qrCode(qr: UIImage(cgImage: cgImg))
                                                let renderer = ImageRenderer(content: qr)
                                                if let qrImage = renderer.uiImage {
                                                    model.shareItems.removeAll()
                                                    model.shareItems.append(qrImage)
                                                    model.shareRoute.toggle()
                                                }
                                            }
                                        }
                                    } label: {
                                        HStack{
                                            Spacer()
                                            Text ("QrCode")
                                            Image(systemName: "qrcode")
                                        }
                                    }
                                    Button {
                                        let urlPath = URL(filePath: model.saveCUP())
                                        model.shareItems.removeAll()
                                        model.shareItems.append(urlPath)
                                        model.shareRoute.toggle()
                                    } label: {
                                        HStack {
                                            Text (".cup File")
                                            Image(systemName: "cup.and.saucer")
                                        }
                                    }
                                    Button {
                                        let urlPath = URL(filePath: model.saveCompegpsWpt())
                                        model.shareItems.removeAll()
                                        model.shareItems.append(urlPath)
                                        model.shareRoute.toggle()
                                    } label: {
                                        HStack {
                                            Label(".wpt File(CompeGPS)", systemImage: "point.filled.topleft.down.curvedto.point.bottomright.up")
                                        }
                                    }
                                } label: {
                                    Image(systemName: "square.and.arrow.up.on.square")
                                }
                            }.padding([.vertical, .trailing], 5)
                        }
                        .foregroundColor(.white)
                        .font(.title2) // This size looks better.
                        .padding(0)
                        .background(.gray)
                        .cornerRadius(5)
                        .fixedSize()
                    }
                    .padding([.bottom, .trailing], 10)
                    .padding(.bottom, 20)
                }
            }
            .sheet(isPresented: $model.shareRoute) {
                ShareSheet(items: $model.shareItems)
             .presentationDetents([.fraction(0.8)])
             .presentationDragIndicator(.visible)
             .onDisappear { model.shareItems.removeAll() }
             }
            .padding(.trailing)
            VStack {
                Spacer()
                Picker(selection: $model.screen) {
                    Text("Planner").tag(TernScreen.planning)
                    Text("FlightDeck").tag(TernScreen.flightDeck)
                } label: {
                    Text("Screen")
                }
                .font(.custom("Gruppo", size: 12))
                .foregroundColor(.primary)
                .cornerRadius(5)
                .pickerStyle(.segmented)
                .frame(width: 200)
                .padding(.bottom, 50)
                .onChange(of: model.screen) { _ in
                    model.onScreenChange()
                }
            }
        }
        .ignoresSafeArea()
    }
}

struct ShareSheet : UIViewControllerRepresentable {
    @Binding var items : [Any]
    func makeUIViewController(context: Context) ->UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
    }
}
