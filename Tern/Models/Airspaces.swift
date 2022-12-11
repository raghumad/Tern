//
//  Airspaces.swift
//  Tern
//
//  Created by Raghu Madanala on 12/10/22.
//

import Foundation
import CoreLocation
import MapKit

class Airspaces {
    //let logger = Logger(subsystem: "Tern", category: "Airspaces")

    // HTTP HEADERS:
    // Date: Wed, 04 Nov 2020 11:13:24 GMT
    // Server: Apache
    // Strict-Transport-Security: max-age=63072000; includeSubdomains; preload
    // X-Content-Type-Options: nosniff
    // X-Frame-Options: SAMEORIGIN
    // Last-Modified: Sun, 19 May 2002 14:49:00 GMT
    // Accept-Ranges: bytes
    // Content-Length: 20702285
    // Content-Type: application/pdf
    var countryCode : String
    var overlays = [MKOverlay]()

    // Custom URL cache with 1 GB disk storage
    lazy var cache: URLCache = {
        let cachesURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let diskCacheURL = cachesURL.appendingPathComponent("TernAirspaceCache")
        let cache = URLCache(memoryCapacity: 100_000_000, diskCapacity: 1_000_000_000, directory: diskCacheURL)
        //logger.info("Cache path: \(diskCacheURL.path)")
        //print("Cache path: \(diskCacheURL.path)")
        return cache
    }()

    // Custom URLSession that uses our cache
    lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = cache
        return URLSession(configuration: config)
    }()

    init(countryCode: String) {
        self.countryCode = countryCode
        let cacheURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("TernAirspaceCache")
        let airspaceURL = URL(string: "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/\(self.countryCode)_asp.geojson")!
        let targetURL = cacheURL.appendingPathComponent(airspaceURL.lastPathComponent)
        downloadFile(remoteURL: airspaceURL, targetURL: targetURL)
        print("Downloading airspaces to \(targetURL)")
    }

    func downloadFile(remoteURL: URL, targetURL: URL) {
        let request = URLRequest(url: remoteURL)
        let downloadTask = session.downloadTask(with: request) { url, response, error in
            //print("Download Task complete for \(remoteURL.absoluteString) to \(String(describing: url?.absoluteString))")

            // Store data in cache
            if let response = response, let url = url,
               self.cache.cachedResponse(for: request) == nil,
               let data = try? Data(contentsOf: url, options: [.mappedIfSafe]) {
                self.cache.storeCachedResponse(CachedURLResponse(response: response, data: data), for: request)
            }
                                                                
            // Move file to target location
            guard let tempURL = url else { return }
            _ = try? FileManager.default.replaceItemAt(targetURL, withItemAt: tempURL)
        }
        downloadTask.resume()
    }
}
