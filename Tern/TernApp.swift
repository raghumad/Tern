//
//  TernApp.swift
//  Tern
//
//  Created by Raghu Madanala on 11/5/22.
//

import SwiftUI

@main
struct TernApp: App {
    let persistenceController = PersistenceController.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.managedObjectContext, persistenceController.container.viewContext)
        }
    }
}
