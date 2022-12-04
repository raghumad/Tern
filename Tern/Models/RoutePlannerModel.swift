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

class RoutePlannerModel : NSObject, CLLocationManagerDelegate, ObservableObject, MKMapViewDelegate {
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

    func addWaypoint(){
        let annotation = WayPoint(coordinate: mapView.region.center, cylinderRadius: 50)
        if (!waypoints.contains(where: {$0 == annotation})) {//dont instert waypoint if its already there
            waypoints.append(annotation)
            annotation.coordinate = mapView.region.center
            Task {
                await annotation.getMeteo()
            }
            annotation.title = "WP\(waypoints.count)"
            annotation.subtitle = "\(String(format: "%.5f", annotation.coordinate.latitude)):\(String(format: "%.5f", annotation.coordinate.longitude))"
            //mapView.removeAnnotations(mapView.annotations)
            mapView.addAnnotation(annotation)
            let cyclinderOverlay = MKCircle(center: mapView.region.center, radius: CLLocationDistance(annotation.cylinderRadius))
            mapView.addOverlay(cyclinderOverlay)
            if waypoints.count >  1 {
                mapView.addOverlay(MKPolyline(coordinates: waypoints.map( {$0.coordinate} ), count: waypoints.count))
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
        self.region = MKCoordinateRegion(center: latestLocation.coordinate, latitudinalMeters: 10000, longitudinalMeters: 10000)
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
            self.region = MKCoordinateRegion(center: location.coordinate, latitudinalMeters: 6000, longitudinalMeters: 6000)
        }
    }
}

extension RoutePlannerModel {
    //MARK: MapViewDelegates
    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        let marker = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: "WaypointPin")
        marker.isDraggable = true
        marker.canShowCallout = true
        marker.glyphImage = UIImage(systemName: "arrowshape.turn.up.right.circle.fill")
        marker.markerTintColor = .systemBlue
        var waypoint = annotation as! WayPoint
        let wpc = WayPointCallout(waypoint: waypoint).environmentObject(self)
        let callout = UIHostingController(rootView: wpc)
//        marker.leftCalloutAccessoryView = callout.view //could be weather and wind direction
//        marker.rightCalloutAccessoryView = callout.view
        marker.detailCalloutAccessoryView = callout.view
//        for i in mapView.annotations.indices {
//            if mapView.annotations[i] as! WayPoint == wpt {
//                mapView.removeAnnotation(mapView.annotations[i])
//                print ("removing \(wpt.title)")
//            }
//        }
//        for i in waypoints.indices {
//            if waypoints[i] == wpt {
//                waypoints[i].coordinate = CLLocationCoordinate2D(latitude: wpc.latitude, longitude: wpc.longitude)
//                waypoints[i].cylinderRadius = wpc.cylinderRadius
//                waypoints[i].title = wpc.waypointName
//            }
//        }
//        mapView.addAnnotations(waypoints)
//        mapView.updateConstraints()
        return marker
    }

    func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, didChange newState: MKAnnotationView.DragState, fromOldState oldState: MKAnnotationView.DragState) {
        //print("\(view.annotation?.coordinate)")
        print(waypoints.count)
    }

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        if let circle = overlay as? MKCircle {
            let renderer = MKCircleRenderer(circle: circle)
            renderer.alpha = 0.6
            renderer.lineWidth = 4
            renderer.fillColor = .blue
            return renderer
        }
        if let route = overlay as? MKPolyline {
            let renderer = MKGradientPolylineRenderer(polyline: route)
            renderer.lineWidth = 6
            renderer.strokeColor = .red
            return renderer
        }
        return MKPolygonRenderer(overlay: overlay)
    }

    func mapView(_ mapView: MKMapView, didUpdate userLocation: MKUserLocation) {
        mapView.region = MKCoordinateRegion(center: userLocation.coordinate, latitudinalMeters: 10000, longitudinalMeters: 10000)
    }
}
