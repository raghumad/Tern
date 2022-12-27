//
//  json_models.swift
//  Tern
//
//  Created by Raghu Madanala on 12/27/22.
//

import Foundation
/* Will do the comp json later. not needed now.
 {
   "T": "W",   taskType: Waypoints
   "V": 2,     version: 2
   "t": [      list of turnpoints
     {
        "z":   string, required - polyline encoded coordinates with altitude
        "n":   string, required - name of the turnpoint
     },
     ...
   ]
 }
 */

struct xctsk_wp_turnpoint : Codable {
    let polylineCoordinate : String
    let name : String
    init(polylineCoordinate: String, name: String) {
        self.polylineCoordinate = polylineCoordinate
        self.name = name
    }
    private enum CodingKeys : String, CodingKey {
        case polylineCoordinate = "z"
        case name = "n"
    }
}

struct xctsk_wp : Codable {
    let taskType = String("W")
    let version = Int(2)
    var turnpoints = [xctsk_wp_turnpoint]()
    
    private enum CodingKeys : String, CodingKey {
        case taskType = "T"
        case version = "V"
        case turnpoints = "t"
    }
}

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
