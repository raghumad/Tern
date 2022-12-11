//
//  RoutePlannerModel.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import Foundation
import CoreLocation
import MapKit
import SwiftUI
import UniformTypeIdentifiers
import SwiftyJSON
import Polyline

class RoutePlannerModel : NSObject, CLLocationManagerDelegate, ObservableObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
    //38.9121906016191, -104.72783900204881
    @Published var region : MKCoordinateRegion = .init()
    @Published var waypoints: [WayPoint] = .init()
    
    @Published var shareRoute : Bool = false
    @Published var shareItems : [Any] = .init()

    private let locationManager : CLLocationManager = .init()
    @Published var mapView: MKMapView = .init()

    @Published var airspaces : [String:Airspaces] = .init()

    override init() {
        super.init()
        locationManager.delegate = self
        mapView.delegate = self
        mapView.showsUserLocation = true
        //locationManager.startUpdatingLocation()
        //locationManager.startMonitoring(for: CLRegion())
    }

    func handleLocationAuthError(){
        //Handle error
    }

    deinit {
        locationManager.stopUpdatingLocation()
    }
}

extension RoutePlannerModel {
    //MARK: LocationManagerDelegates
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        //Set default location
        self.region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 38.9121906016191, longitude: -104.72783900204881), latitudinalMeters: 50000, longitudinalMeters: 50000)
        self.mapView.setRegion(self.region, animated: false)
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
        DispatchQueue.main.async {
            //print ("latest location in didUpdateLocations: \(location.coordinate)")
            self.region = MKCoordinateRegion(center: location.coordinate, latitudinalMeters: 25000, longitudinalMeters: 25000)
            self.mapView.setRegion(self.region, animated: true)
        }
    }
}

extension RoutePlannerModel {
    //MARK: MapViewDelegates

//    optional func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, calloutAccessoryControlTapped control: UIControl) {
//
//    }

    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        guard !annotation.isKind(of: MKUserLocation.self) else {
                // Make a fast exit if the annotation is the `MKUserLocation`, as it's not an annotation view we wish to customize.
                return nil
            }
        /*var annotationView: MKAnnotationView?
            
            if let annotation = annotation as? BridgeAnnotation {
                annotationView = setupBridgeAnnotationView(for: annotation, on: mapView)
            } else if let annotation = annotation as? CustomAnnotation {
                annotationView = setupCustomAnnotationView(for: annotation, on: mapView)
            } else if let annotation = annotation as? SanFranciscoAnnotation {
                annotationView = setupSanFranciscoAnnotationView(for: annotation, on: mapView)
            } else if let annotation = annotation as? FerryBuildingAnnotation {
                annotationView = setupFerryBuildingAnnotationView(for: annotation, on: mapView)
            }
            
            return annotationView
         private func setupSanFranciscoAnnotationView(for annotation: SanFranciscoAnnotation, on mapView: MKMapView) -> MKAnnotationView {
             let reuseIdentifier = NSStringFromClass(SanFranciscoAnnotation.self)
             let flagAnnotationView = mapView.dequeueReusableAnnotationView(withIdentifier: reuseIdentifier, for: annotation)
             
             flagAnnotationView.canShowCallout = true
             
             // Provide the annotation view's image.
             let image = #imageLiteral(resourceName: "flag")
             flagAnnotationView.image = image
             
             // Provide the left image icon for the annotation.
             flagAnnotationView.leftCalloutAccessoryView = UIImageView(image: #imageLiteral(resourceName: "sf_icon"))
             
             // Offset the flag annotation so that the flag pole rests on the map coordinate.
             let offset = CGPoint(x: image.size.width / 2, y: -(image.size.height / 2) )
             flagAnnotationView.centerOffset = offset
             
             return flagAnnotationView
         }
         let rightButton = UIButton(type: .detailDisclosure)
         markerAnnotationView.rightCalloutAccessoryView = rightButton
         https://stackoverflow.com/questions/30793315/customize-mkannotation-callout-view
        }*/
        if annotation is WayPoint {
            let wptIndex = waypoints.firstIndex(of: annotation as! WayPoint) ?? 9999
            //let mkAnnotation = mapView.dequeueReusableAnnotationView(withIdentifier: "WaypointPin") ?? MKMarkerAnnotationView()
            let marker = MKMarkerAnnotationView(annotation: waypoints[wptIndex], reuseIdentifier: "WaypointPin")
            //let marker = mkAnnotation as! MKMarkerAnnotationView
            marker.isDraggable = true
            marker.canShowCallout = true
            if wptIndex != 9999 && wptIndex < 51 {
                marker.glyphImage = UIImage(systemName: "\(wptIndex + 1).circle")
            } else {
                marker.glyphImage = UIImage(systemName: "1f595")
            }
            marker.markerTintColor = .systemBlue
            marker.animatesWhenAdded = true
            marker.annotation = waypoints[wptIndex]
            
            //marker.selectedGlyphImage = UIImage(systemName: "mappin.and.ellipse")
            let wpc = WayPointAnnotationCallout(waypointIndex: wptIndex).environmentObject(self)
            let callout = UIHostingController(rootView: wpc)
            
            //detailCalloutAccessoryView is hanging so we create an image and pass it instead.
            //let renderer = ImageRenderer(content: wpc)
            //let callout = UIImageView(image: renderer.uiImage)
            callout.loadView()
            marker.detailCalloutAccessoryView = callout.viewIfLoaded
            //callout.isUserInteractionEnabled = true
            //marker.largeContentImage = renderer.uiImage
            //marker.leftCalloutAccessoryView = callout.view //could be weather and wind direction
            //marker.rightCalloutAccessoryView = callout.view
            return marker
        }
        return MKAnnotationView()
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
            let renderer = MKPolygonRenderer(polygon: overlay as! MKPolygon)
            renderer.fillColor = .red
            renderer.alpha = 0.2
            renderer.strokeColor = .red
            return renderer
        }
        return MKOverlayRenderer(overlay: overlay)
    }

    func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, didChange newState: MKAnnotationView.DragState, fromOldState oldState: MKAnnotationView.DragState) {
        if (newState == .ending) {
            //print ("Ending coordinate : \(view.annotation?.coordinate)")
            waypoints.removeAll()
            for i in mapView.annotations.indices {
                if mapView.annotations[i] is WayPoint {
                    waypoints.append(mapView.annotations[i] as! WayPoint)
                    //update waypoint icon and get new weather
                    (mapView.annotations[i] as! WayPoint).update()
                }
            }
            waypoints.sort() // Always ordered
            mapView.removeAnnotations(waypoints)
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
            if waypoints.count >  1 {
                mapView.addOverlay(MKPolyline(coordinates: waypoints.map( {$0.coordinate} ), count: waypoints.count))
            }
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
}

extension RoutePlannerModel {
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
            Task {
                await newWaypoint.weatherForecast.getForecast() //fire off weather while other stuff is done.
            }
            Task {
                await newWaypoint.getElevation()
            }
            CLGeocoder().reverseGeocodeLocation(
                CLLocation(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude),
                    completionHandler: {(placemarks, error) in
                if (error != nil) {print("reverse geodcode fail: \(error!.localizedDescription)")}
                let pm = placemarks! as [CLPlacemark]
                        if pm.count > 0 {
                            self.airspaces[pm[0].isoCountryCode!.lowercased()] = Airspaces(countryCode: pm[0].isoCountryCode!.lowercased())//Creating airspaces obj will download file.
                            print("Downloading airspaces: \(pm[0].isoCountryCode!.lowercased())")
                        }
            })

            newWaypoint.title = "WP\(waypoints.count + 1)"
            newWaypoint.subtitle = "Waypoint description"
            //mapView.removeAnnotations(mapView.annotations)
            mapView.addAnnotation(newWaypoint)
            let cyclinderOverlay = MKCircle(center: newWaypoint.coordinate, radius: CLLocationDistance(newWaypoint.cylinderRadius.converted(to: .meters).value))
            mapView.addOverlay(cyclinderOverlay)
            waypoints.append(newWaypoint)
            if waypoints.count >  1 {
                mapView.addOverlay(MKGeodesicPolyline(coordinates: waypoints.map( {$0.coordinate} ), count: waypoints.count))
            }
            addAirspaceOverlays()
        }
    }

    func addAirspaceOverlays(){
        //Update overlays for airspaces because we have a route.
        let cachesURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("TernAirspaceCache")
        for cC in self.airspaces.keys {
            var airspaces = [MKGeoJSONObject]()
            do {
                let airspacePath = cachesURL.appending(path: "\(cC)_asp.geojson",directoryHint: .notDirectory)
                let data = try Data(contentsOf: airspacePath)
                airspaces = try MKGeoJSONDecoder().decode(data)
                print ("got airspaces: \(airspaces.count)")
                let waypointsBoundingRect = self.fiveKmileWaypointBoudingRect
                for item in airspaces {
                    if let feature =  item as? MKGeoJSONFeature {
                        for polygon in feature.geometry {
                            if let airspacePolygon = polygon as? MKPolygon {
                                if  airspacePolygon.coordinate.longitude > waypointsBoundingRect[0].longitude &&
                                    airspacePolygon.coordinate.longitude < waypointsBoundingRect[1].longitude &&
                                    airspacePolygon.coordinate.latitude > waypointsBoundingRect[0].latitude &&
                                    airspacePolygon.coordinate.latitude < waypointsBoundingRect[1].latitude {
                                    //add only if inside the radius
                                    self.airspaces[cC]?.overlays.append(airspacePolygon)
                                    //self.mapView.addOverlay(airspacePolygon)
                                }
                            }
                        }
                    }
                }
                if let overl = self.airspaces[cC]?.overlays {
                    print("adding \(self.airspaces[cC]?.overlays.count ?? 0) airspaces to the map.")
                    self.mapView.addOverlays(overl)
                }
            } catch {
                print (error.localizedDescription)
            }
        }
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

    func saveWpt() -> String {
        // NUMBER, NAME,  LAT,  LON, XXX, WAYPOINT_SYMBOL, XXX, DISPLAY_FORMAT, FONT_COLOR, BACKGROUND_COLOR, DESCRIPTION, POINTER_DIRECTION, GARMIN_DISPLAY, PROXIMITY_DISTANCE, ALTITUDE, FONT_SIZE, FONT_BOLD, SYMBOL_SIZE, XXX, XXX, XXX,,,
        //waypoint = "%d,%s,  %s,  %s,,0,0,3,%s,%s,%s,0,0,0,-777,6,0,17,0,10.0,2,,,\n" % (x+1, name, lat, lon, textCol, backCol, desc)
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
        var xctsk = JSON()
        var xctskData = "{"
        xctsk["taskType"] = "CLASSIC" //taskType - the type of task used, right now only type CLASSIC is used - for other task types different set of the following attributes can be defined
        xctskData.append("\"taskType\":\"CLASSIC\",")
        xctsk["version"] = 2 //version natural number corresponding to the version of the format for specific taskType - now fixed to 1
        xctskData.append("\"version\":\"2\",")
        xctsk["t"] = JSON()
        xctskData.append("\"t\":")
        if waypoints.count > 0 {
            var wptJ = JSON()
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
                
                /*var intcoord = Int(waypoints[i].coordinate.longitude*100000)
                encpoly.append(encodeSingleInteger(intcoord))
                print(encodeSingleInteger(intcoord))
                intcoord = Int(waypoints[i].coordinate.latitude*100000)
                encpoly.append(encodeSingleInteger(intcoord))
                print(encodeSingleInteger(intcoord))*/
                
                wptJ["z"].stringValue = encpoly
                xctskData.append("\"z\":\"\(encpoly)\",")
                wptJ["n"].stringValue = waypoints[i].title! //waypoints[i].title? "WPT\(i)"
                xctskData.append("\"n\":\"\(waypoints[i].title!)\",")
                wptJ["d"].stringValue = waypoints[i].subtitle!
                xctskData.append("\"d\":\"\(waypoints[i].subtitle!)\"")
                if i == 0 {
                    wptJ["t"] = 2 //"SSS"
                    xctskData.append(",\"t\":\"2\"")
                }
                if i == waypoints.count-1 {
                    wptJ = 3 // "ESS"
                    xctskData.append(",\"t\":\"3\"")
                }
                xctsk["t"][i] = wptJ
                if i == waypoints.count-1 {
                    xctskData.append("}")
                } else {
                    xctskData.append("},")
                }
            }
            xctskData.append("],")
        }
        xctsk["e"] = 0 //Always WGS84
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
        var xctskData = "XCTSK:{"
        xctskData.append("\"T\":\"W\",")
        xctskData.append("\"V\":2,")
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
                xctskData.append("\"z\":\"\(encpoly)\",")
                xctskData.append("\"n\":\"\(waypoints[i].title!)\"")
                if i == waypoints.count-1 {
                    xctskData.append("}]}")
                } else {
                    xctskData.append("},")
                }
            }
        }
        print(xctskData)
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
        let urlPath = paths.appendingPathComponent("\(waypoints[0].title ?? "wpts").xctsk")
        do {
            try xctskData.write(to: urlPath, atomically: true, encoding: .utf8)
        }
        catch {
            print(error.localizedDescription)
        }
        return urlPath.absoluteString
    }

    var fiveKmileWaypointBoudingRect : [CLLocationCoordinate2D] {
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
    }
}
