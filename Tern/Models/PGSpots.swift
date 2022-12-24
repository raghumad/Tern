//
//  PGSpots.swift
//  Tern
//
//  Created by Raghu Madanala on 12/23/22.
//

import Foundation
import MapKit

class PGSpots {
    let countryCode : String
    lazy var pgspot = [CLLocationCoordinate2D: MKGeoJSONFeature]()

    init(countryCode: String) {
        self.countryCode = countryCode
        let _ = TernCache(
            from: URL(string: "https://www.paraglidingearth.com/api/geojson/getCountrySites.php?iso=\(countryCode.lowercased())&style=detailled")!,
            to: TernCache.cacheDir.appendingPathComponent("\(countryCode.lowercased())_pgspots.geojson"))
    }
}
