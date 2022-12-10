//
//  Airspaces.swift
//  Tern
//
//  Created by Raghu Madanala on 12/10/22.
//

import Foundation
import CoreLocation
import MapKit

class Airspaces : ObservableObject{
    var countryCode : String = "US"
    var overlays = [MKOverlay]()

    init(countryCode: String) {
        self.countryCode = countryCode
    }

    func getAirspaces () async {
        // Look up the location and pass it to the completion handler
        // 38.9121906016191, -104.72783900204881
        Task {
            let cachesURL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            let diskCacheURL = cachesURL.appendingPathComponent("TernAirspaceCache")
            let cache = URLCache(memoryCapacity: 1_000_000, diskCapacity: 1_000_000_000, directory: diskCacheURL)
            var remotePath = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f"
            remotePath.append(self.countryCode.lowercased())
            remotePath.append("_asp.geojson")
            let remoteURL = URL(string: remotePath)
            let req = URLRequest(url: remoteURL!)
            _ = URLSession.shared.downloadTask(with: req) { url, response, error in
                if let response = response, let url = url,
                   cache.cachedResponse(for: req) == nil,
                   let data = try? Data(contentsOf: url) {
                    print("Download Task complete. downloaded to \(url.absoluteString)")
                    cache.storeCachedResponse(CachedURLResponse(response: response, data: data), for: req)
                    var airspaces = [MKGeoJSONObject]()
                    do {
                        airspaces = try MKGeoJSONDecoder().decode(data)
                        //mapView.addOverlays(airspaces)
                    } catch {
                        print ("cant get airspaces.")
                    }

                    for item in airspaces {
                        if let feature =  item as? MKGeoJSONFeature {
                            for polygon in feature.geometry {
                                if let airspacePolygon = polygon as? MKPolygon {
                                    self.overlays.append(airspacePolygon)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
