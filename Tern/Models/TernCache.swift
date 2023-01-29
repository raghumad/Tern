//
//  TernCache.swift
//  Tern
//
//  Created by Raghu Madanala on 12/23/22.
//

import Foundation

class TernCache {
    let from, to : URL
    static let cacheDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("TernAirspaceCache")
    
    init(from: URL, to: URL) {
        self.from = from
        self.to = to
//        let airspaceURL = URL(string: "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/\(self.countryCode)_asp.geojson")!
//        let targetURL = cacheURL.appendingPathComponent(airspaceURL.lastPathComponent)
        if FileManager.default.fileExists(atPath: to.absoluteString) == false { //Dont download again.
            downloadFile(remoteURL: from, targetURL: to)
            //print("Downloading airspaces to \(targetURL)")
        }
        //For now piggy back on Airspaces for caching and downloading the launches.
//        let pgSpotsURL = URL(string: "https://www.paraglidingearth.com/api/geojson/getCountrySites.php?iso=\(countryCode.lowercased())&style=detailled")!
//        let pgSpotsTargetURL = cacheURL.appendingPathComponent("\(countryCode.lowercased())_pgspots.geojson")
//
//        if FileManager.default.fileExists(atPath: pgSpotsTargetURL.absoluteString) == false {
//            downloadFile(remoteURL: pgSpotsURL, targetURL: pgSpotsTargetURL)
//        }
    }
    // Custom URL cache with 1 GB disk storage
    lazy var cache: URLCache = {
        let cache = URLCache(memoryCapacity: 100_000_000, diskCapacity: 1_000_000_000, directory: TernCache.cacheDir)
        //logger.info("Cache path: \(diskCacheURL.path)")
        //print("Cache path: \(diskCacheURL.path)")
        return cache
    }()

    // Custom URLSession that uses our cache
    lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = cache
        config.httpAdditionalHeaders = ["User-Agent": "(https://tern.madanala.com/, tern@madanala.com) Tern Paragliding"]
        return URLSession(configuration: config)
    }()

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
