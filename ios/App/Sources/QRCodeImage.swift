import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

struct QRCodeImage: View {
    let payload: String
    var size: CGFloat = 280
    var accessibilityLabel = "ERC-681 payment QR code"
    var failureDescription = "Copy the payment URI instead."

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Group {
            if let image = render() {
                Image(decorative: image, scale: displayScale)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                ContentUnavailableView(
                    "QR unavailable",
                    systemImage: "qrcode",
                    description: Text(failureDescription)
                )
            }
        }
        .frame(width: size, height: size)
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityLabel(accessibilityLabel)
    }

    private func render() -> CGImage? {
        let generator = CIFilter.qrCodeGenerator()
        generator.message = Data(payload.utf8)
        generator.correctionLevel = "M"
        guard let raw = generator.outputImage else { return nil }

        let monochrome = raw.applyingFilter(
            "CIFalseColor",
            parameters: [
                "inputColor0": CIColor.black,
                "inputColor1": CIColor.white,
            ]
        )
        let quietZone: CGFloat = 4
        let paddedExtent = CGRect(
            x: 0,
            y: 0,
            width: monochrome.extent.width + quietZone * 2,
            height: monochrome.extent.height + quietZone * 2
        )
        let translated = monochrome.transformed(
            by: CGAffineTransform(translationX: quietZone, y: quietZone)
        )
        let white = CIImage(color: .white).cropped(to: paddedExtent)
        let padded = translated.composited(over: white)
        let integerScale = max(1, floor((size * displayScale) / paddedExtent.width))
        let scaled = padded.transformed(by: CGAffineTransform(scaleX: integerScale, y: integerScale))
        return CIContext(options: [.useSoftwareRenderer: false]).createCGImage(scaled, from: scaled.extent)
    }
}
