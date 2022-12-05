//
//  RoutePlannerModel.swift
//  Tern
//
//  Created by Raghu Madanala on 12/2/22.
//

import Foundation
import CoreLocation
import MapKit
import SwiftyJSON
import SwiftUI

class RoutePlannerModel : NSObject, CLLocationManagerDelegate, ObservableObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
    //38.9121906016191, -104.72783900204881
    @Published var latestLocation : CLLocation = .init()
    @Published var region : MKCoordinateRegion = .init()
    @Published var waypoints: [WayPoint] = .init()

    private let locationManager : CLLocationManager = .init()
    @Published var mapView: MKMapView = .init()

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

    func addWaypoint(newwpt: WayPoint){
        let newWaypoint = newwpt
        if (!waypoints.contains(where: {$0.isNear(newPt: newWaypoint)})) {//dont instert waypoints are kissing
            Task {
                await newWaypoint.getMeteo()
            }
            newWaypoint.title = "WP\(waypoints.count)"
            newWaypoint.subtitle = "Notes about this waypoint..."
            newWaypoint.cylinderRadius = 500
            //mapView.removeAnnotations(mapView.annotations)
            mapView.addAnnotation(newWaypoint)
            let cyclinderOverlay = MKCircle(center: mapView.region.center, radius: CLLocationDistance(newWaypoint.cylinderRadius))
            mapView.addOverlay(cyclinderOverlay)
            waypoints.append(newWaypoint)
            if waypoints.count >  1 {
                mapView.addOverlay(MKGeodesicPolyline(coordinates: waypoints.map( {$0.coordinate} ), count: waypoints.count))
            }
        }
    }
    
    func addWaypoint(){
        let newWaypoint = WayPoint(coordinate: mapView.region.center, cylinderRadius: 500)
        if (!waypoints.contains(where: {$0.isNear(newPt: newWaypoint)})) {//dont instert waypoints are kissing
            newWaypoint.coordinate = mapView.region.center
            Task {
                await newWaypoint.getMeteo()
            }
            newWaypoint.title = "WP\(waypoints.count)"
            newWaypoint.subtitle = "Notes about this waypoint..."
            //mapView.removeAnnotations(mapView.annotations)
            mapView.addAnnotation(newWaypoint)
            let cyclinderOverlay = MKCircle(center: mapView.region.center, radius: CLLocationDistance(newWaypoint.cylinderRadius))
            mapView.addOverlay(cyclinderOverlay)
            waypoints.append(newWaypoint)
            if waypoints.count >  1 {
                mapView.addOverlay(MKGeodesicPolyline(coordinates: waypoints.map( {$0.coordinate} ), count: waypoints.count))
            }
        }
    }

    deinit {
        locationManager.stopUpdatingLocation()
    }
}

extension RoutePlannerModel {
    //MARK: LocationManagerDelegates
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        //Set default location
        self.latestLocation = CLLocation(latitude: 38.9121906016191, longitude: -104.72783900204881)//latitude: 38.9121906016191, longitude: -104.72783900204881)
        self.region = MKCoordinateRegion(center: latestLocation.coordinate, latitudinalMeters: 50000, longitudinalMeters: 50000)
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
            print ("latest location in didUpdateLocations: \(location.coordinate)")
            self.latestLocation = location
            self.region = MKCoordinateRegion(center: location.coordinate, latitudinalMeters: 25000, longitudinalMeters: 25000)
            self.mapView.setRegion(self.region, animated: true)
        }
    }
}

extension RoutePlannerModel {
    //MARK: MapViewDelegates
    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        if annotation is WayPoint {
            let marker = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: "WaypointPin")
            marker.isDraggable = true
            marker.canShowCallout = true
            let wptIndex = waypoints.firstIndex(of: annotation as! WayPoint) ?? 9999
            if wptIndex != 9999 && wptIndex < 50 {
                marker.glyphImage = UIImage(systemName: "\(wptIndex).circle")
            } else {
                marker.glyphImage = UIImage(systemName: "1f595")
            }
            marker.markerTintColor = .systemBlue
            marker.animatesWhenAdded = true
            marker.selectedGlyphImage = UIImage(systemName: "mappin.and.ellipse")
            let wpt = annotation as! WayPoint
            let longitude = wpt.coordinate.longitude
            let latitude = wpt.coordinate.latitude
            let cyliderRadius = wpt.cylinderRadius
            let waypointDescription = wpt.subtitle ?? "Very long description..."
            
            let wpc = WayPointCallout(waypoint: annotation as! WayPoint, waypointName: wpt.title ?? "WPT___", latitude: latitude, longitude: longitude, cylinderRadius: cyliderRadius, waypointDescription: waypointDescription).environmentObject(self)
            let callout = UIHostingController(rootView: wpc)
            //        marker.leftCalloutAccessoryView = callout.view //could be weather and wind direction
            //        marker.rightCalloutAccessoryView = callout.view
            marker.detailCalloutAccessoryView = callout.view
            
            return marker
        } else {
            return MKUserLocationView()
        }
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
            let renderer = MKGradientPolylineRenderer(polyline: overlay as! MKPolyline)
            renderer.lineWidth = 2
            renderer.strokeColor = .red
            return renderer
        }
        if overlay is MKGeodesicPolyline {
            let renderer = MKGradientPolylineRenderer(polyline: overlay as! MKGeodesicPolyline)
            renderer.lineWidth = 2
            renderer.strokeColor = .red
            return renderer
        }
        return MKPolygonRenderer(overlay: overlay)
    }

    func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, didChange newState: MKAnnotationView.DragState, fromOldState oldState: MKAnnotationView.DragState) {
        if (newState == .ending) {
            //print ("Ending coordinate : \(view.annotation?.coordinate)")
            waypoints.removeAll()
            for i in mapView.annotations.indices {
                if mapView.annotations[i] is WayPoint {
                    waypoints.append(mapView.annotations[i] as! WayPoint)
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
                let cyclinderOverlay = MKCircle(center: wpt.coordinate, radius: CLLocationDistance(wpt.cylinderRadius))
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
            let wpt = WayPoint()
            wpt.title = "Fuck you!"
            wpt.subtitle = "You long pressed here"
            wpt.coordinate = touchMapCoordinate
            addWaypoint(newwpt: wpt)
        }
    }
}
