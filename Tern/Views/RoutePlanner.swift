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
                    HStack (spacing: 10){
                        Button{
                            model.addWaypoint(coordinate: model.mapView.centerCoordinate)
                        } label: {
                            Image(systemName: "point.3.connected.trianglepath.dotted")
                                .rotationEffect(.degrees(30))
                        }.padding(.trailing, 10)
                        Button{
                            //We open a menu here so nothing doing.
                        } label: {
                            Menu {
                                Button {
                                    let urlPath = URL(filePath: model.saveXCTSK())
                                    model.shareItems.removeAll()
                                    model.shareItems.append(urlPath)
                                    model.shareRoute.toggle()
                                } label: {
                                    HStack {
                                        Text (".xctsk File")
                                        Image(systemName: "square.and.arrow.up")
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
                            } label: {
                                Image(systemName: "doc.badge.arrow.up")
                            }
                        }
                    }
                    .padding(5)
                    .background(.blue.opacity(0.9))
                    .cornerRadius(8)
                }
                .foregroundColor(.white)
                .font(.title2) // This size looks better.
            }
            .sheet(isPresented: $model.shareRoute) {
                ShareSheet(items: $model.shareItems)
             .presentationDetents([.fraction(0.8)])
             .presentationDragIndicator(.visible)
             .onDisappear { model.shareItems.removeAll() }
             }
            .padding(.trailing)
        }
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
