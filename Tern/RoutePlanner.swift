//
//  RoutePlanner.swift
//  Tern
//
//  Created by Raghu Madanala on 11/21/22.
//

import SwiftUI
import MapKit
import SwiftyJSON

struct RoutePlanner: View {
    var routerPlannerMap = RoutePlannerMap()
    var body: some View {
        ZStack(alignment: .center){
//            Map(coordinateRegion: $model.region, showsUserLocation: true)
//                .ignoresSafeArea()
//                .onAppear(){
//                    model.requestAllowOnceLocationPermission()
//                    Task{
//                        await model.getWeather()
//                    }
//                }
            routerPlannerMap
                .ignoresSafeArea()
            Image(systemName: "dot.circle.and.hand.point.up.left.fill")
                .foregroundColor(.red)
            VStack{
                Spacer()
                HStack(alignment: .bottom){ //Everything in this stack will be white and title2 size.
                    Spacer()
                    Button{
                        routerPlannerMap.addWayPoint()
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

class RoutePlannerModel : NSObject, CLLocationManagerDelegate, ObservableObject {
    //@Published var weather = String("No Weather Data")
    @Published var weather : JSON
    //38.9121906016191, -104.72783900204881
    @Published var latestLocation : CLLocation
    
    private let locationManager : CLLocationManager
    
    override init() {
        self.locationManager = CLLocationManager()
        self.weather = JSON(stringLiteral: "") //Just have a empty json please.
        self.latestLocation = CLLocation()//latitude: 38.9121906016191, longitude: -104.72783900204881)
        super.init()
        locationManager.delegate = self
        locationManager.requestLocation()
        //locationManager.startUpdatingLocation()
        //locationManager.startMonitoring(for: CLRegion())
    }
    
    deinit {
        //locationManager.stopUpdatingLocation()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else {return}
        DispatchQueue.main.async {
            self.latestLocation = location
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        //Set default location
        self.latestLocation = CLLocation()//latitude: 38.9121906016191, longitude: -104.72783900204881)
    }

    func getWeather() async {
        //https://api.open-meteo.com/v1/gfs?latitude=38.83&longitude=-104.82&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto
        //https://github.com/SwiftyJSON/SwiftyJSON to parse. got no time to create model structs.
        //https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Swift coordinate to xyz
        guard let url = URL(string: "https://api.open-meteo.com/v1/gfs?latitude=\(self.latestLocation.coordinate.latitude)&longitude=\(self.latestLocation.coordinate.longitude)&current_weather=true&hourly=dewpoint_2m,pressure_msl,cloudcover,cape,windspeed_80m,winddirection_80m,windgusts_10m&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch&forecast_days=1&timezone=auto") else {
            print ("link error")
            return
        }
        
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            //print(url)
            DispatchQueue.main.async {
                self.weather = try! JSON(data: data)
            }
            print(self.weather)
        } catch {
            print("Open mateo fails.")
        }
    }
}

struct RoutePlannerMap: UIViewRepresentable {
    @StateObject private var model = RoutePlannerModel()
    
    class Coordinator: NSObject, MKMapViewDelegate {
        var parent: RoutePlannerMap
        
        init(parent: RoutePlannerMap){
            self.parent = parent
        }
    }
    
    func addWayPoint() {
        return
    }
    
    func makeCoordinator() -> Coordinator {
        return Coordinator(parent: self)
    }
    
    func makeUIView(context: Context) -> MKMapView {
        let mapScreen = MKMapView(frame: .zero)
        mapScreen.delegate = context.coordinator
        mapScreen.showsUserLocation = true
        mapScreen.showsCompass = false
        mapScreen.mapType = MKMapType.hybridFlyover
        mapScreen.isRotateEnabled = false
        mapScreen.showsScale = true
        mapScreen.showsBuildings = false
        mapScreen.setUserTrackingMode(MKUserTrackingMode.none, animated: false)
        if #available(iOS 16.0, *) {
            mapScreen.preferredConfiguration = MKStandardMapConfiguration(elevationStyle: .realistic)
        }
        return mapScreen
    }
    
    func updateUIView(_ uiView: MKMapView, context: Context) {
        uiView.setRegion(MKCoordinateRegion(center: model.latestLocation.coordinate, span: MKCoordinateSpan(latitudeDelta: 0.5, longitudeDelta: 0.5)), animated: false)
        //uiView.camera = MKMapCamera(lookingAtCenter: self.model.latestLocation.coordinate, fromEyeCoordinate: self.model.latestLocation.coordinate, eyeAltitude: self.model.latestLocation.altitude+5000)
    }
}

struct RoutePlanner_Previews: PreviewProvider {
    static var previews: some View {
        RoutePlanner()
    }
}
