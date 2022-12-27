//
//  Airspaces.swift
//  Tern
//
//  Created by Raghu Madanala on 12/10/22.
//

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
    let countryCode : String
    lazy var airspace = [CLLocationCoordinate2D: MKGeoJSONFeature]()

    init(countryCode: String) {
        self.countryCode = countryCode
        _ = TernCache(from: URL(string: "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/\(self.countryCode)_asp.geojson")!, to: TernCache.cacheDir.appendingPathComponent("\(self.countryCode)_asp.geojson"))
//        let airspaceURL = URL(string: "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/\(self.countryCode)_asp.geojson")!
//        let targetURL = airspaceCache.cacheURL.appendingPathComponent(airspaceURL.lastPathComponent)
//        if FileManager.default.fileExists(atPath: targetURL.absoluteString) == false { //Dont download again.
//            downloadFile(remoteURL: airspaceURL, targetURL: targetURL)
//            //print("Downloading airspaces to \(targetURL)")
//        }
//        //For now piggy back on Airspaces for caching and downloading the launches.
//        let pgSpotsURL = URL(string: "https://www.paraglidingearth.com/api/geojson/getCountrySites.php?iso=\(countryCode.lowercased())&style=detailled")!
//        let pgSpotsTargetURL = cacheURL.appendingPathComponent("\(countryCode.lowercased())_pgspots.geojson")
//        if FileManager.default.fileExists(atPath: pgSpotsTargetURL.absoluteString) == false {
//            downloadFile(remoteURL: pgSpotsURL, targetURL: pgSpotsTargetURL)
//        }
    }
}

extension CLLocationCoordinate2D : Hashable, Codable {
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(latitude, forKey: CodingKeys.latitude)
        try container.encode(longitude, forKey: CodingKeys.longitude)
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let longitude = try container.decode(CLLocationDegrees.self, forKey: CLLocationCoordinate2D.CodingKeys.longitude)
        let latitude = try container.decode(CLLocationDegrees.self, forKey: CLLocationCoordinate2D.CodingKeys.latitude)
        self.init(latitude: latitude as Double, longitude: longitude)
    }
    
    private enum CodingKeys : String, CodingKey {
        case latitude = "latitude"
        case longitude = "longitude"
    }
    
    public static func == (lhs: CLLocationCoordinate2D, rhs: CLLocationCoordinate2D) -> Bool {
        if lhs.longitude == rhs.longitude && lhs.latitude == rhs.latitude {
            return true
        } else {
            return false
        }
    }
    
    public func hash(into hasher: inout Hasher) {
        hasher.combine(longitude)
        hasher.combine(latitude)
    }
}
