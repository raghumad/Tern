//
//  RoutePlannerModel.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import CoreLocation
import MapKit
import SwiftUI
import SwiftyJSON
import Polyline
import GPX

enum TernScreen {
    case planning
    case flightDeck
}

class TernModel : NSObject, CLLocationManagerDelegate, ObservableObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
    //38.9121906016191, -104.72783900204881
    @Published var showAirspaces = UserDefaults.standard.bool(forKey: "showAirspaces")
    @Published var showPGSpots = UserDefaults.standard.bool(forKey: "showPGSpots")
    @Published var showHotspots = UserDefaults.standard.bool(forKey: "showHotspots")
    var waypoints: [WayPoint] = .init()
    @Published var shareRoute : Bool = false
    var shareItems : [Any] = .init()
    let locationManager : CLLocationManager = .init()
    var mapView: MKMapView = .init()
    var airspaces : [String:Airspaces] = .init()
    var pgspots : [String:PGSpots] = .init()
    let units = MeasurementUnits.userDefaults
    @Published var screen : TernScreen = .planning

    override init() {
        super.init()
        locationManager.delegate = self
        mapView.delegate = self
        mapView.showsUserLocation = true
        mapView.showsCompass = true
        mapView.showsScale = true
        mapView.preferredConfiguration.elevationStyle = .realistic
        mapView.showsBuildings = false
        mapView.pointOfInterestFilter = .excludingAll
        mapView.showsTraffic = false
        mapView.mapType = .hybrid
        //locationManager.startUpdatingLocation()
        //locationManager.startMonitoring(for: CLRegion())
    }

    deinit {
        locationManager.stopUpdatingLocation()
    }
}

extension TernModel {
    //MARK: LocationManagerDelegates
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        //Set default location
        let region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 38.9121906016191, longitude: -104.72783900204881), latitudinalMeters: 50000, longitudinalMeters: 50000)
        self.mapView.setRegion(region, animated: false)
    }

    func handleLocationAuthError(){
        //Handle error
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways: manager.requestLocation()
        case .authorizedWhenInUse: manager.requestLocation()
        case .denied: handleLocationAuthError()
        case .notDetermined: manager.requestWhenInUseAuthorization()
        default: manager.requestWhenInUseAuthorization()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else {return}
        let region = MKCoordinateRegion(center: location.coordinate, latitudinalMeters: 50000, longitudinalMeters: 50000)
        self.mapView.setRegion(region, animated: true)
    }
}

extension TernModel {
    //MARK: MapViewDelegates

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        if showAirspaces == false { mapView.removeOverlays(mapView.overlays) }
        if showPGSpots == false || showHotspots == false {
            for pgspot in mapView.annotations {
                if pgspot is PGSpotAnnotation && showPGSpots == false {
                    mapView.removeAnnotation(pgspot)
                }
                if pgspot is TextAnnotation && showHotspots == false {
                    mapView.removeAnnotation(pgspot)
                }
            }
        }
        if mapView.region.span.latitudeDelta < 20 { //download only when zoomed in
            CLGeocoder().reverseGeocodeLocation(
                CLLocation(
                    latitude: mapView.region.center.latitude,
                    longitude: mapView.region.center.longitude),
                completionHandler: {(placemarks, error) in
                    if (error != nil) {print("reverse geodcode fail: \(error!.localizedDescription)")}
                    if let pm = placemarks as [CLPlacemark]? {
                        if pm.count > 0 {
                            if pm[0].isoCountryCode != nil && !self.airspaces.keys.contains((pm[0].isoCountryCode?.lowercased())!){
                                self.airspaces[pm[0].isoCountryCode!.lowercased()] = Airspaces(countryCode: pm[0].isoCountryCode!.lowercased())//Creating airspaces obj will download file.
                                //Also download PGSpots
                                self.pgspots[pm[0].isoCountryCode!.lowercased()] = PGSpots(countryCode: pm[0].isoCountryCode!.lowercased())
                                //print("Downloading airspaces: \(pm[0].isoCountryCode!.lowercased())")
                            }
                        }
                    }
                })
            if mapView.annotations.count > 2000 {mapView.removeAnnotations(mapView.annotations)}
            if mapView.region.span.latitudeDelta > 5 || mapView.overlays.count > 500 {
                mapView.removeOverlays(mapView.overlays)
            } else {
                addAirspaceOverlays()
                addPGSpots()
            }
            redrawRoutePath()
        } else {
            //remove overlays when zoomed out
            mapView.removeOverlays(mapView.overlays)
            mapView.removeAnnotations(mapView.annotations)
        }
    }

    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        guard !annotation.isKind(of: MKUserLocation.self) else {
                // Make a fast exit if the annotation is the `MKUserLocation`, as it's not an annotation view we wish to customize.
                return nil
            }
        /*https://stackoverflow.com/questions/30793315/customize-mkannotation-callout-view
        */
        if annotation is WayPoint {
            let wptIndex = waypoints.firstIndex(of: annotation as! WayPoint) ?? 9999
            let marker = MKMarkerAnnotationView(annotation: annotation as? WayPoint, reuseIdentifier: MKMapViewDefaultAnnotationViewReuseIdentifier)
            marker.isDraggable = true
            marker.canShowCallout = true
            marker.clusteringIdentifier = "Waypoint"
            if wptIndex != 9999 && wptIndex < 51 {
                marker.glyphImage = UIImage(systemName: "\(wptIndex + 1).circle")
            } else {
                marker.glyphImage = UIImage(systemName: "🖕")
            }
            marker.markerTintColor = .systemBlue
            marker.animatesWhenAdded = true
            marker.annotation = annotation
            marker.titleVisibility = .visible
            marker.subtitleVisibility = .visible
            return marker
        }
        if annotation is TextAnnotation {
            let marker = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: MKMapViewDefaultAnnotationViewReuseIdentifier)
            marker.glyphImage = UIImage(systemName: "")
            marker.clusteringIdentifier = "TextAnnotation"
            marker.annotation = annotation
            marker.glyphTintColor = .clear
            marker.tintColor = .clear
            marker.markerTintColor = .clear
            marker.canShowCallout = false
            marker.subtitleVisibility = .visible
            return marker
        }
        if annotation is Hotspot {
            let marker = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: MKMapViewDefaultAnnotationViewReuseIdentifier)
            marker.animatesWhenAdded = false
            marker.glyphImage = UIImage(systemName: "tornado")
            marker.clusteringIdentifier = "HotSpot"
            marker.annotation = annotation
            let prob = Double(annotation.title!!.replacingOccurrences(of: "%", with: "", options: .regularExpression))
            marker.glyphTintColor = UIColor(red: (prob ?? 0)/100, green: 0.3, blue: 0.2, alpha: 1)
            marker.markerTintColor = .clear
            marker.canShowCallout = false
            marker.titleVisibility = .visible
            marker.subtitleVisibility = .hidden
            return marker
        }
        if annotation is PGSpotAnnotation {
            let marker = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: MKMapViewDefaultAnnotationViewReuseIdentifier)
            marker.animatesWhenAdded = false
            marker.glyphImage = renderWindGauge(forecast: (annotation as! PGSpotAnnotation).forecast)
            marker.clusteringIdentifier = "PGSpot"
            marker.annotation = annotation
            marker.glyphTintColor = .black
            marker.markerTintColor = .systemCyan
            marker.canShowCallout = true
            marker.titleVisibility = .visible
            marker.subtitleVisibility = .adaptive
            return marker
        }
        return MKAnnotationView()
    }

    @MainActor func renderWindGauge(forecast: WeatherForecast) -> UIImage? {
        let renderer : ImageRenderer<WindGauge>
        if forecast.windspeed80m.count > 0 {
            renderer = .init(content: WindGauge(label: "Wind", windSpeed: forecast.windspeed80m[0], windDirection: forecast.winddirection_80m[0]))
            return renderer.uiImage
        }
        return UIImage(named: "Kjartan Birgisson")
    }

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        if overlay is MKCircle {
            let renderer = MKCircleRenderer(circle: overlay as! MKCircle)
            renderer.alpha = 0.3
            renderer.lineWidth = 2
            renderer.fillColor = .blue
            return renderer
        }
        if overlay is MKPolyline {
            let renderer = MKPolylineRenderer(polyline: overlay as! MKPolyline)
            renderer.lineWidth = 2
            renderer.strokeColor = .red
            return renderer
        }
        if overlay is MKGeodesicPolyline {
            let renderer = MKPolylineRenderer(polyline: overlay as! MKGeodesicPolyline)
            renderer.lineWidth = 2
            renderer.strokeColor = .red
            return renderer
        }
        if overlay is MKPolygon {
            for country in self.airspaces.keys {
                if self.airspaces[country]?.airspace[overlay.coordinate] != nil {
                    let json = try! JSON(data: (self.airspaces[country]?.airspace[overlay.coordinate]?.properties)!)
                    switch json["icaoClass"].intValue {
                    case 1,2,3,4:
                        let renderer = MKPolygonRenderer(polygon: overlay as! MKPolygon)
                        renderer.fillColor = .black
                        renderer.alpha = 1 / (2 * CGFloat(json["icaoClass"].intValue))
                        renderer.strokeColor = .systemRed
                        return renderer
                    case 8:
                        switch json["type"].intValue {
                        case 1:
                            mapView.addAnnotation(TextAnnotation(coordinate: overlay.coordinate,title: "Restricted", subtitle: json["remarks"].stringValue))
                            let renderer = MKPolygonRenderer(polygon: overlay as! MKPolygon)
                            //renderer.polygon.title = json["name"].stringValue
                            //renderer.polygon.subtitle = "Restricted"
                            renderer.fillColor = .systemRed
                            renderer.alpha = 0.2
                            renderer.strokeColor = .black
                            renderer.lineWidth = 2
                            return renderer
                        case 2:
                            mapView.addAnnotation(TextAnnotation(coordinate: overlay.coordinate,title: "Danger", subtitle: json["remarks"].stringValue))
                            let renderer = MKPolygonRenderer(polygon: overlay as! MKPolygon)
                            renderer.fillColor = .systemOrange
                            renderer.alpha = 0.2
                            renderer.lineWidth = 1
                            renderer.strokeColor = .black
                            return renderer
                        /*case 28,21:
                            renderer.fillColor = .systemGreen
                            renderer.alpha = 0.2
                            renderer.strokeColor = .systemGreen
                            renderer.lineWidth = 2
                            break */
                        default:
//                            renderer.polygon.title = "Other"
//                            renderer.strokeColor = .lightGray
//                            renderer.fillColor = .clear
//                            renderer.lineWidth = 1
                            break
                        }
                        break
                    default:
                        break
                    }
                }
            }
        }
        return MKOverlayRenderer(overlay: overlay)
    }

    func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, didChange newState: MKAnnotationView.DragState, fromOldState oldState: MKAnnotationView.DragState) {
        if (newState == .ending) && view is MKMarkerAnnotationView {
            //print ("Ending coordinate : \(view.annotation?.coordinate)")
            //mapView.removeAnnotations(waypoints) // Remove only waypoints.
            for label in mapView.annotations { //Remove all corresponding label.
                if label is TextAnnotation { mapView.removeAnnotation(label) }
            }
            waypoints.removeAll()
            for i in mapView.annotations.indices {
                if mapView.annotations[i] is WayPoint {
                    waypoints.append(mapView.annotations[i] as! WayPoint)
                    //update waypoint icon and get new weather
                    (mapView.annotations[i] as! WayPoint).update()
                }
            }
            waypoints.sort() // Always ordered
            mapView.removeAnnotations(waypoints) // Remove all waypoints.
            for overlay in mapView.overlays {
                if overlay is MKCircle || overlay is MKPolyline {
                    mapView.removeOverlay(overlay)
                }
            }
            mapView.addAnnotations(waypoints)
            for wpt in waypoints {
                let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius.converted(to: .meters).value))
                mapView.addOverlay(cyclinderOverlay)
            }
            redrawRoutePath()
            mapView.addAnnotations(legLengthLabels)
            view.dragState = .none //May be this is hanging the phone RTFM
        }
    }

    func mapViewDidFinishLoadingMap(_ mapView: MKMapView) {
        let lpgr = UILongPressGestureRecognizer(target: self,
                             action:#selector(self.handleLongPress))
        lpgr.minimumPressDuration = 1
        lpgr.delaysTouchesBegan = true
        lpgr.delegate = self
        self.mapView.addGestureRecognizer(lpgr)
    }

    @objc func handleLongPress(gestureRecognizer: UILongPressGestureRecognizer) {
        if gestureRecognizer.state != UIGestureRecognizer.State.ended {
            return
        }
        else if gestureRecognizer.state != UIGestureRecognizer.State.began {
            
            let touchPoint = gestureRecognizer.location(in: self.mapView)
            
            let touchMapCoordinate =  self.mapView.convert(touchPoint, toCoordinateFrom: mapView)
            addWaypoint(coordinate: touchMapCoordinate)
        }
    }

    func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
        if view.annotation is PGSpotAnnotation {
            (view.annotation as! PGSpotAnnotation).forecast.getForecast() //Also update forecast
            (view as! MKMarkerAnnotationView).glyphImage = renderWindGauge(forecast: (view.annotation as! PGSpotAnnotation).forecast)
            let callout = UIHostingController(rootView: PGSpotForecast(pgSpot: view.annotation as! PGSpotAnnotation))
            callout.loadView()
            view.detailCalloutAccessoryView = callout.viewIfLoaded
        }
        if view.annotation is WayPoint {
            (view.annotation as! WayPoint).weatherForecast.getForecast() // update forecast
            let callout = UIHostingController(rootView: WayPointAnnotationCallout(waypoint: view.annotation as! WayPoint).environmentObject(self))
            callout.loadView()
            view.detailCalloutAccessoryView = callout.viewIfLoaded
        }
    }

}

extension TernModel {
    //MARK: helper functions
    func encodeSingleInteger(_ value: Int) -> String {
        
        var intValue = value
        
        if intValue < 0 {
            intValue = intValue << 1
            intValue = ~intValue
        } else {
            intValue = intValue << 1
        }
        
        return encodeFiveBitComponents(intValue)
    }

    func encodeFiveBitComponents(_ value: Int) -> String {
        var remainingComponents = value
        
        var fiveBitComponent = 0
        var returnString = String()
        
        repeat {
            fiveBitComponent = remainingComponents & 0x1F
            
            if remainingComponents >= 0x20 {
                fiveBitComponent |= 0x20
            }
            
            fiveBitComponent += 63

            let char = UnicodeScalar(fiveBitComponent)!
            returnString.append(String(char))
            remainingComponents = remainingComponents >> 5
        } while (remainingComponents != 0)
        
        return returnString
    }

    func addWaypoint(coordinate: CLLocationCoordinate2D){
        let newWaypoint = WayPoint(coordinate: coordinate)
        if (!waypoints.contains(where: {$0.isNear(newPt: newWaypoint)})) {//dont instert waypoints are kissing
            //newWaypoint.weatherForecast.getForecast() //fire off weather while other stuff is done.
            newWaypoint.getElevation()

            newWaypoint.title = "WP\(waypoints.count + 1)"
            newWaypoint.subtitle = "Waypoint description"
            //mapView.removeAnnotations(mapView.annotations)
            mapView.addAnnotation(newWaypoint)
            let cyclinderOverlay = MKCircle(center: newWaypoint.coordinate, radius: CLLocationDistance(newWaypoint.cylinderRadius.converted(to: .meters).value))
            mapView.addOverlay(cyclinderOverlay)
            waypoints.append(newWaypoint)
            redrawRoutePath()
            mapView.addAnnotations(legLengthLabels)
            if waypoints.count >= 2 {
                addHotspots()
            }
        }
    }

    func addPGSpots(){
        if showPGSpots == false { return }
        for cC in self.pgspots.keys {
            var pgSpots = [MKGeoJSONObject]()
            do {
                let pgSpotsPath = TernCache.cacheDir.appending(path: "\(cC.lowercased())_pgspots.geojson")
                let data = try Data(contentsOf: pgSpotsPath)
                pgSpots = try MKGeoJSONDecoder().decode(data)
                if mapView.region.span.latitudeDelta < 5 { // adjusting to 300nm about 690miles or 600nm for 10 delta.
                    for item in pgSpots {
                        if let feature =  item as? MKGeoJSONFeature {
                            let feaureProperties = try! JSON(data: feature.properties!)
                            for pgspotPoint in feature.geometry {
                                if let pgspotPoint = pgspotPoint as? MKPointAnnotation {
                                    if  pgspotPoint.coordinate.longitude > mapView.region.center.longitude - mapView.region.span.longitudeDelta &&
                                            pgspotPoint.coordinate.longitude < mapView.region.center.longitude + mapView.region.span.longitudeDelta &&
                                            pgspotPoint.coordinate.latitude > mapView.region.center.latitude - mapView.region.span.latitudeDelta &&
                                            pgspotPoint.coordinate.latitude < mapView.region.center.latitude + mapView.region.span.latitudeDelta {
                                        //add only if inside the radius.
                                        self.pgspots[cC]?.pgspot[pgspotPoint.coordinate] = feature // Add only abcd and other.
                                        if self.mapView.annotations.firstIndex(where: { $0.coordinate == pgspotPoint.coordinate }) == nil { //only add if not added before
                                            self.mapView.addAnnotation(PGSpotAnnotation(
                                                coordinate: pgspotPoint.coordinate,
                                                title: feaureProperties["name"].stringValue,
                                                subtitle: feaureProperties["takeoff_description"].stringValue,
                                                properties: feaureProperties))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch {
                print(error.localizedDescription)
            }
        }
    }

    func addAirspaceOverlays(){
        if showAirspaces == false { return }
        //Update overlays for airspaces because we have a route.
        for cC in self.airspaces.keys {
            var airspaces = [MKGeoJSONObject]()
            do {
                let airspacePath = TernCache.cacheDir.appending(path: "\(cC)_asp.geojson",directoryHint: .notDirectory)
                let data = try Data(contentsOf: airspacePath)
                airspaces = try MKGeoJSONDecoder().decode(data)
                //print ("got airspaces: \(airspaces.count)")
                if mapView.region.span.latitudeDelta < 5 { // adjusting to 300nm about 690miles or 600nm for 10 delta.
                    for item in airspaces {
                        if let feature =  item as? MKGeoJSONFeature {
                            let feaureProperties = try! JSON(data: feature.properties!)
                            if feaureProperties["icaoClass"].intValue < 4 || feaureProperties["icaoClass"].intValue == 8{ //only EFGandUncliassified
                                for polygon in feature.geometry {
                                    if let airspacePolygon = polygon as? MKPolygon {
                                        if  airspacePolygon.coordinate.longitude > mapView.region.center.longitude - mapView.region.span.longitudeDelta &&
                                                airspacePolygon.coordinate.longitude < mapView.region.center.longitude + mapView.region.span.longitudeDelta &&
                                                airspacePolygon.coordinate.latitude > mapView.region.center.latitude - mapView.region.span.latitudeDelta &&
                                                airspacePolygon.coordinate.latitude < mapView.region.center.latitude + mapView.region.span.latitudeDelta {
                                            //add only if inside the radius.
                                            self.airspaces[cC]?.airspace[airspacePolygon.coordinate] = feature // Add only abcd and other.
                                            if self.mapView.overlays.firstIndex(where: { $0.coordinate == airspacePolygon.coordinate }) == nil { //only add if not added before
                                                self.mapView.addOverlay(airspacePolygon)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch {
                print (error.localizedDescription)
            }
        }
    }

    func addHotspots(){
        if showHotspots == false { return }
        //https://thermal.kk7.ch/api/hotspots/cup/all_all/36.862,-112.819,41.295,-96.668
        //https://thermal.kk7.ch/api/hotspots/csv/all_all/25.681,-133.375,53.305,-68.774
        //https://thermal.kk7.ch/api/hotspots/gpx/all_all/36.862,-112.819,41.295,-96.668
        //https://github.com/FlineDev/CSVImporter
        //https://www.paraglidingforum.com/leonardo/download.php?type=sites&sites=34736,58036,
        var url = "https://thermal.kk7.ch/api/hotspots/gpx/all_all/" //Add 600 mile bounding box.
        url.append(String(format: "%.3f", waypoints[0].coordinate.latitude - 1.25))
        url.append(String(format: ",%.3f", waypoints[0].coordinate.longitude - 1.25))
        url.append(String(format: ",%.3f", waypoints[0].coordinate.latitude + 1.25))
        url.append(String(format: ",%.3f", waypoints[0].coordinate.longitude + 1.25))
        let task = URLSession.shared.dataTask(with: URLRequest(url: URL(string: url)!), completionHandler: { data, response, error -> Void in
            //print(response!)
            do {
                if let data = data {
                    let gpx = try GPX(data: data)
                    self.mapView.addAnnotations(gpx.waypoints.map{
                        return Hotspot(
                            coordinate: CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude),
                            title: $0.comment?.replacingOccurrences(of: "probability:|,.*", with: "", options: .regularExpression) ?? "",
                            subtitle: ""
                        )
                    })
                }
            } catch {
                print("error")
            }
        })

        task.resume()
    }

    func saveCUP() -> String{
        //+ve latitude is N and longitude is E
        //-ve latitude is S and longitude is W
        //latitude: 38.9121906016191, longitude: -104.72783900204881
        //Degrees is 38 N
        // Decimal minutes is 0.9121906016191 * 60 = 54.731436097146 => 54'
        // Seconds is 0.731436097146 * 60 = 43.88616582876 => 43"
        
        guard let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return ""
        }
        let urlPath = paths.appendingPathComponent("waypoints.cup")
        
        //let url = URL(fileURLWithPath: urlPath.absoluteString, isDirectory: false)
        var cupFile = "name,code,country,lat,lon,elev,style,rwdir,rwlen,freq,desc"
        for wpt in waypoints {
            //print (wpt.CUPdata)
            cupFile = "\(cupFile)\n\(wpt.CUPdata)"
        }
        cupFile = "\(cupFile)\n"
        do {
            //try savedata.write(toFile: url.absoluteString, atomically: true, encoding: .utf8)
            try cupFile.write(to: urlPath, atomically: true, encoding: .utf8)
            let input = try String(contentsOf: urlPath)
            print(input)
        } catch {
            print(error.localizedDescription)
        }
        //print (cupFile)
        return urlPath.absoluteString
    }

    func saveCompegpsWpt() -> String {
        // gpsbabel/deprecated/compegps.cc
        /*

            the meaning of leading characters in CompeGPS data lines (enhanced PCX):

            header lines:

            "G": WGS 84            - Datum of the map
            "N": Anybody            - Name of the user
            "L": -02:00:00            - Difference to UTC
            "M": ...            - Any comments
            "R": 16711680 , xxxx , 1     - Route header
            "U": 1                - System of coordinates (0=UTM 1=Latitude/Longitude)

            "C":  0 0 255 2 -1.000000    - ???
            "V":  0.0 0.0 0 0 0 0 0.0    - ???
            "E": 0|1|00-NUL-00 00:00:00|00:00:00|0 - ???

            data lines:

            "W": if(route) routepoint; else waypoint
            "T": trackpoint
                "t": if(track) additionally track info
                 if(!track) additionally trackpoint info
            "a": link to ...
            "w": waypoint additional info

         G  WGS 84
         U  1
         W  START A 46.0116190∫N 11.3010020∫E 08-AUG-22 07:58:51 500.000000 Levico start
         w Waypoint,,,,,,,,,CyliderRadius
         W  RIALTO A 45.6452480∫N 11.2424500∫E 08-AUG-22 07:58:51 764.000000 Malga Rialto
         w Waypoint,,,,,,,,,
         W  GOAL A 46.0116190∫N 11.3010020∫E 08-AUG-22 07:58:51 500.000000 Levico Goal
         w Waypoint,,,,,,,,,
        */
        var compegpsWptData = "G WGS 84\n"
        compegpsWptData.append("M Generated by Tern Paragliding\n")
        compegpsWptData.append("U 1\n")//we dont Name and U is always 1 as we dont use UTM also no L as its only for waypoints.
        for waypoint in waypoints {
            compegpsWptData.append(waypoint.CompeGPSdata)
        }
        guard let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return ""
        }
        let urlPath = paths.appendingPathComponent("waypoints.wpt")
        do {
            //try savedata.write(toFile: url.absoluteString, atomically: true, encoding: .utf8)
            try compegpsWptData.write(to: urlPath, atomically: true, encoding: .utf8)
            let input = try String(contentsOf: urlPath)
            print(input)
        } catch {
            print(error.localizedDescription)
        }
        //print (cupFile)
        return urlPath.absoluteString
    }

    func saveOziWpt() -> String {
        /*OziExplorer Waypoint File Version 1.1
         WGS 84
         Reserved 2
         Reserved 3
         1,GCEBB,35.972033,-87.134700,,0,1,3,0,65535,Mountain Bike Heaven by susy1313,0,0,0,0,6,0,17
         2,GC1A37,36.090683,-86.679550,,0,1,3,0,65535,The Troll by a182pilot & Family,0,0,0,0,6,0,17
         3,GC1C2B,35.996267,-86.620117,,0,1,3,0,65535,Dive Bomber by JoGPS & family,0,0,0,0,6,0,17
         4,GC25A9,36.038483,-86.648617,,0,1,3,0,65535,FOSTER by JoGPS & Family,0,0,0,0,6,0,17
         5,GC2723,36.112183,-86.741767,,0,1,3,0,65535,Logan Lighthouse by JoGps & Family,0,0,0,0,6,0,17
         6,GC2B71,36.064083,-86.790517,,0,1,3,0,65535,Ganier Cache by Susy1313,0,0,0,0,6,0,17
         7,GC309F,36.087767,-86.809733,,0,1,3,0,65535,Shy's Hill by FireFighterEng33,0,0,0,0,6,0,17
         8,GC317A,36.057500,-86.892000,,0,1,3,0,65535,GittyUp by JoGPS / Warner Parks,0,0,0,0,6,0,17
         9,GC317D,36.082800,-86.867283,,0,1,3,0,65535,Inlighting by JoGPS / Warner Parks,0,0,0,0,6,0,17
         
         Waypoint File (.wpt)
         Line 1 : File type and version information
         Line 2 : Geodetic Datum used for the Lat/Lon positions for each waypoint Line 3 : Reserved for future use
         Line 4 : GPS Symbol set - not used yet
         Waypoint data
         • One line per waypoint
         • each field separated by a comma
         • comma's not allowed in text fields, character 209 can be used instead and a comma will be substituted.
         • non essential fields need not be entered but comma separators must still be used (example ,,)
         defaults will be used for empty fields
         • Any number of the last fields in a data line need not be included at all not even the commas.
         Field 1 : Number - for Lowrance/Eagles and Silva GPS receivers this is the storage location (slot) of the waypoint in the gps, must be unique. For other GPS receivers set this number to -1 (minus 1). For Lowrance/Eagles and Silva if the slot number is not known (new waypoints) set the number to -1.
         Field 2 : Name - the waypoint name, use the correct length name to suit the GPS type.
         Field 3 : Latitude - decimal degrees.
         Field 4 : Longitude - decimal degrees.
         Field 5 : Date - see Date Format below, if blank a preset date will be used Field 6 : Symbol - 0 to number of symbols in GPS
         Field 7 : Status - always set to 1
         Field 8 : Map Display Format
         Field 9 : Foreground Color (RGB value)
         Field 10 : Background Color (RGB value)
         Field 11 : Description (max 40), no commas
         Field 12 : Pointer Direction
         Field 13 : Garmin Display Format
         Field 14 : Proximity Distance - 0 is off any other number is valid
         Field 15 : Altitude - in feet (-777 if not valid)
         Field 16 : Font Size - in points
         Field 17 : Font Style - 0 is normal, 1 is bold.
         Field 18 : Symbol Size - 17 is normal size
         Field 19 : Proximity Symbol Position
         Field 20 : Proximity Time
         Field 21 : Proximity or Route or Both
         Field 22 : File Attachment Name
         Field 23 : Proximity File Attachment Name
         Field 24 : Proximity Symbol Name*/
        return "You're fucked!"
    }

    func saveXCTSKqr() -> String{
        //https://github.com/twpayne/go-xctrack
        /*
         {
           "taskType": "CLASSIC",
           "version": 2,
           "t": [             List of turnpoints
             {
                 "z":         string, required - polyline encoded coordinates with altitude and radius
                 "n":         string, required - name of the turnpoint
                 "d":         string, optional - turnpoint description
                 "t":         number, optional, one of 2 (SSS), 3 (ESS)
             },
             ...
           ],
           "s": {             Start type, optional (since 0.9.1)
             "g": [..]        array of times, required - Time gates, start open time
             "d":             number, OBSOLETE, one of 1 (ENTRY), 2 (EXIT) (ignored when reading task, should be part of exported task for backwards compatibility)
             "t":             number, required, one of 1 (RACE), 2 (ELAPSED-TIME)
           },
           "g": {            Goal type, optional (since 0.9.1)
             "d":            time, optional - Deadline (default 23:00 local time UTC equivalent
             "t":            number, optional on of 1 (LINE), 2 (CYLINDER) (default 2)
           }
           "e":    number, optional, 0 (wgs84, default), 1(fai sphere)
         }
         */
        var xctskData = "{"
        xctskData.append("\"taskType\":\"CLASSIC\",")
        xctskData.append("\"version\":\"2\",")
        xctskData.append("\"t\":")
        if waypoints.count > 0 {
            xctskData.append("[")
            for i in waypoints.indices {
                xctskData.append("{")
                var encpoly = ""
                let polyline = Polyline(coordinates: [waypoints[i].coordinate])
                encpoly.append(polyline.encodedPolyline)
                print(polyline.encodedPolyline)
                
                encpoly.append(encodeSingleInteger(Int(waypoints[i].elevation.converted(to: .meters).value)))
                print(encodeSingleInteger(Int(waypoints[i].elevation.converted(to: .meters).value)))
                encpoly.append(encodeSingleInteger(Int(waypoints[i].cylinderRadius.converted(to: .meters).value)))
                print(encodeSingleInteger(Int(waypoints[i].cylinderRadius.converted(to: .meters).value)))
                
                xctskData.append("\"z\":\"\(encpoly)\",")
                xctskData.append("\"n\":\"\(waypoints[i].title!)\",")
                xctskData.append("\"d\":\"\(waypoints[i].subtitle!)\"")
                if i == 0 {
                    xctskData.append(",\"t\":\"2\"")
                }
                if i == waypoints.count-1 {
                    xctskData.append(",\"t\":\"3\"")
                }
                if i == waypoints.count-1 {
                    xctskData.append("}")
                } else {
                    xctskData.append("},")
                }
            }
            xctskData.append("],")
        }
        xctskData.append("\"e\":\"0\"")
        xctskData.append("}")
        print(xctskData)
        return xctskData
    }

    func saveXCTSKWqr() -> String{
        /*{
         "T": "W",   taskType: Waypoints
         "V": 2,     version: 2
         "t": [      list of turnpoints
           {
              "z":   string, required - polyline encoded coordinates with altitude
              "n":   string, required - name of the turnpoint
           },
           ...
         ]
       }*/
//        var xctskData = "XCTSK:{"
//        xctskData.append("\"T\":\"W\",")
//        xctskData.append("\"V\":2,")
//        xctskData.append("\"t\":")
//        if waypoints.count > 0 {
//            xctskData.append("[")
//            for i in waypoints.indices {
//                xctskData.append("{")
//                var encpoly = ""
//                let polyline = Polyline(coordinates: [waypoints[i].coordinate])
//                encpoly.append(polyline.encodedPolyline)
//                print(polyline.encodedPolyline)
//
//                encpoly.append(encodeSingleInteger(Int(waypoints[i].elevation.converted(to: .meters).value)))
//                print(encodeSingleInteger(Int(waypoints[i].elevation.converted(to: .meters).value)))
//                xctskData.append("\"z\":\"\(encpoly)\",")
//                xctskData.append("\"n\":\"\(waypoints[i].title!)\"")
//                if i == waypoints.count-1 {
//                    xctskData.append("}]}")
//                } else {
//                    xctskData.append("},")
//                }
//            }
//        }
//        print(xctskData)
        var xctskData = "XCTSK:"
        do {
            var xctsk_wp_qr = xctsk_wp()
            xctsk_wp_qr.turnpoints = waypoints.map{ $0.xctsk_wp_tp }
            let data = try JSONEncoder().encode(xctsk_wp_qr)
            xctskData.append(String(data: data, encoding: .utf8) ?? "")
        } catch {
            print(error.localizedDescription)
        }
        return xctskData
    }

    func saveXCTSK() -> String{
        var xctskData = "{\"taskType\":\"CLASSIC\",\"version\": 1,\"earthModel\":\"WGS84\",\"turnpoints\":"
        if waypoints.count > 0 {
            xctskData.append("[")
            for i in waypoints.indices {
                xctskData.append("{\"type\":\"TAKEOFF\",")
                xctskData.append("\"radius\":\(waypoints[i].cylinderRadius.converted(to: .meters).value),")
                xctskData.append("\"waypoint\": {\"name\":\"\(waypoints[i].title ?? "")\",")
                xctskData.append("\"description\":\"\(waypoints[i].subtitle ?? "")\",")
                xctskData.append("\"lat\":\(waypoints[i].coordinate.latitude),")
                xctskData.append("\"lon\":\(waypoints[i].coordinate.longitude),")
                xctskData.append("\"altSmoothed\":\(waypoints[i].elevation.converted(to: .meters).value)}")
                if i == waypoints.count-1 {
                    xctskData.append("}]")
                } else {
                    xctskData.append("},")
                }
            }
            xctskData.append(",\"goal\":{\"type\":\"CYLINDER\",\"deadline\":\"07:14:32Z\"}")
            xctskData.append("}")
        }
        print(xctskData)
        guard let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return ""
        }
        let urlPath = paths.appendingPathComponent("waypoints.xctsk")
        do {
            try xctskData.write(to: urlPath, atomically: true, encoding: .utf8)
        }
        catch {
            print(error.localizedDescription)
        }
        return urlPath.absoluteString
    }

    func save() {
        if waypoints.count == 0 { return }
        guard let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        let urlPath = paths.appendingPathComponent("route.tern")
        do {
            //figure out a way to encode to geojson.
            let data = try JSONEncoder().encode(waypoints)
            try data.write(to: urlPath)
            //try "HelloTern".write(to: urlPath, atomically: true, encoding: .utf8)
            print(urlPath.absoluteString)
        } catch {
            print(error.localizedDescription)
        }
    }

    var totalXC : Measurement<UnitLength> {
        get {
            var xc : Measurement<UnitLength> = Measurement(value: 0, unit: .meters)
            if waypoints.count > 1 {
                for i in 0...waypoints.count-1{
                    if i == 0 { continue }
                    xc.value += CLLocation(latitude: waypoints[i].coordinate.latitude, longitude: waypoints[i].coordinate.longitude).distance(from: CLLocation(latitude: waypoints[i-1].coordinate.latitude, longitude: waypoints[i-1].coordinate.longitude))
                }
            }
            return xc
        }
    }

    var legLengthLabels : [TextAnnotation] {
        var labels = [TextAnnotation]()
        for label in mapView.annotations {
            if label is TextAnnotation {
                mapView.removeAnnotation(label)
            }
        }
        if waypoints.count > 2 { //if more than two legs
            for routePath in mapView.overlays {
                if routePath is MKGeodesicPolyline {
                    labels.append(TextAnnotation(
                        coordinate: routePath.coordinate,
                        title: "\(String(format: "%0.1f", totalXC.converted(to: units.xcDistance).value))\(units.xcDistance.symbol)",
                        subtitle: "Total XC Distance"))
                }
            }
        }
        if waypoints.count > 1 { //Add individual leg lengths
            for i in 1...waypoints.count-1 {
                let legDist = Measurement<UnitLength>(value: CLLocation(latitude: waypoints[i].coordinate.latitude, longitude: waypoints[i].coordinate.longitude)
                    .distance(from: CLLocation(latitude: waypoints[i-1].coordinate.latitude, longitude: waypoints[i-1].coordinate.longitude)), unit: .meters)
                let legLine = MKGeodesicPolyline(coordinates: [waypoints[i-1].coordinate, waypoints[i].coordinate], count: 2)
                labels.append(TextAnnotation(
                    coordinate: legLine.coordinate,
                    title: "\(String(format: "%0.1f", legDist.converted(to: units.xcDistance).value))\(units.xcDistance.symbol)"
                    ))
            }
        }
        return labels
    }

    func redrawRoutePath() {
        for line in mapView.overlays {
            if line is MKGeodesicPolyline {
                mapView.removeOverlay(line)
            }
        }
        if waypoints.count > 1 {
            mapView.addOverlay(MKGeodesicPolyline(coordinates: waypoints.map{ $0.coordinate }, count: waypoints.count))
        }
    }

    /*var fiveKmileWaypointBoudingRect : [CLLocationCoordinate2D] {
        var lx, ly, hx, hy : Double
        lx = waypoints.map{ $0.coordinate.latitude }.sorted().first ?? region.center.latitude
        ly = waypoints.map{ $0.coordinate.longitude }.sorted().first ?? region.center.longitude
        hx = waypoints.map{ $0.coordinate.latitude }.sorted().last ?? region.center.latitude
        hy = waypoints.map{ $0.coordinate.longitude }.sorted().last ?? region.center.longitude
        // 450nm = 0.125 arc degrees or 450 arc sec or 500mi radius
        lx = lx - 0.125
        ly = ly - 0.125
        hx = hx + 0.125
        hy = hy + 0.125
        let coLow = CLLocationCoordinate2D(latitude: lx, longitude: ly)
        let coHi = CLLocationCoordinate2D(latitude: hx, longitude: hy)
        return [coLow, coHi]
    }*/

    func onScreenChange() {
        switch screen {
            case TernScreen.planning:
                self.showAirspaces = UserDefaults.standard.bool(forKey: "showAirspaces")
                self.showPGSpots = UserDefaults.standard.bool(forKey: "showPGSpots")
                self.showHotspots = UserDefaults.standard.bool(forKey: "showHotspots")
                mapView.mapType = .hybrid
                mapView.camera.pitch = 0
                mapView.updateConstraints()
                break
            case TernScreen.flightDeck:
                self.showAirspaces = true
                self.showPGSpots = false
                self.showHotspots = true //override the values so that we dont show pg spots anymore.
                mapView.mapType = .hybridFlyover
                mapView.camera.pitch = 70
                mapView.setUserTrackingMode(.followWithHeading, animated: true)
                mapView.camera.centerCoordinateDistance = 35000
                mapView.updateConstraints()
                break
        }
    }
}
