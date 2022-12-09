//
//  qrCode.swift
//  Tern
//
//  Created by Raghu Madanala on 12/8/22.
//

import SwiftUI

struct qrCode: View {
    @State var qr : UIImage
    var body: some View {
        VStack{
            Image(uiImage: qr)
                .resizable()
                .interpolation(.none)
                .scaledToFit()
                .frame(width:300, height: 300)
        }
    }
}

struct qrCode_Previews: PreviewProvider {
    static var previews: some View {
        let img = UIImage(systemName: "scope")
        qrCode(qr: img!)
    }
}
