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
        locationManager.requestWhenInUseAuthorization()
        //locationManager.startUpdatingLocation()
        //locationManager.startMonitoring(for: CLRegion())
    }
    
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways: manager.requestLocation()
        case .authorizedWhenInUse: manager.requestLocation()
        case .denied: handleLocationAuthError()
        case .notDetermined: manager.requestWhenInUseAuthorization()
        default: ()
        }
    }
    
    func handleLocationAuthError(){
        //Handle error
    }
    
    func addWaypoint(){
        let annotation = WayPoint(coordinate: mapView.region.center, cylinderRadius: 50)
        if waypoints.firstIndex(of: annotation) == nil { //fix duplicates
            waypoints.append(annotation)
            annotation.coordinate = mapView.region.center
            Task {
                await annotation.getMeteo()
            }
            annotation.title = "This is a very long waypoint title \(annotation.coordinate)"
            annotation.subtitle = String("This is a very long waypoint subtitle and a lot more information tooooooo\(annotation.coordinate)")
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

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else {return}
        Task {
            self.latestLocation = location
            self.region = MKCoordinateRegion(center: location.coordinate, latitudinalMeters: 6000, longitudinalMeters: 6000)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        //Set default location
        self.latestLocation = CLLocation()//latitude: 38.9121906016191, longitude: -104.72783900204881)
    }
    
    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        let marker = MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: "WaypointPin")
        marker.isDraggable = true
        marker.canShowCallout = true
        marker.glyphImage = UIImage(systemName: "arrowshape.turn.up.right.circle.fill")
        marker.markerTintColor = .green
        let callout = UIHostingController(rootView: WayPointCallout(waypointAnnotation: annotation))
//        marker.leftCalloutAccessoryView = callout.view
//        marker.rightCalloutAccessoryView = callout.view
        marker.detailCalloutAccessoryView = callout.view
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
}


struct RoutePlannerMapViewHelper : UIViewRepresentable {
    @EnvironmentObject var manager: RoutePlannerModel
    func makeUIView(context: Context) -> MKMapView {
        return manager.mapView
    }
    
    func updateUIView(_ uiView: MKMapView, context: Context) {
        
    }
}
