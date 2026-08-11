import CoreImage
import CoreImage.CIFilterBuiltins
import CoreTransferable
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ReceiptView: View {
    let document: ReceiptDocument

    private var pdfData: Data { ReceiptPDFRenderer.render(document) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Text(ReceiptFormatter.format(document))
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(
                            Color(.secondarySystemBackground),
                            in: RoundedRectangle(cornerRadius: 14)
                        )

                    QRCodeImage(
                        payload: document.explorerURL.absoluteString,
                        size: 220,
                        accessibilityLabel: "BaseScan transaction QR code",
                        failureDescription: "Open the BaseScan transaction link instead."
                    )

                    Link("Open transaction on BaseScan", destination: document.explorerURL)
                        .accessibilityIdentifier("receiptBaseScanLink")

                    HStack(spacing: 12) {
                        ShareLink(
                            item: ReceiptShareFile(data: pdfData),
                            preview: SharePreview("Receipt #\(document.receiptNumber)")
                        ) {
                            Label("Share PDF", systemImage: "square.and.arrow.up")
                        }
                        .buttonStyle(.bordered)

                        Button {
                            ReceiptPrintPresenter.present(
                                pdfData: pdfData,
                                jobName: "OPK Receipt #\(document.receiptNumber)"
                            )
                        } label: {
                            Label("AirPrint", systemImage: "printer")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding()
            }
            .navigationTitle("Payment receipt")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct ReceiptShareFile: Transferable, Sendable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .pdf) { receipt in
            receipt.data
        }
        .suggestedFileName("OPK-Payment-Receipt.pdf")
    }
}

enum ReceiptPDFRenderer {
    static func render(_ document: ReceiptDocument) -> Data {
        let page = CGRect(x: 0, y: 0, width: 612, height: 792)
        return UIGraphicsPDFRenderer(bounds: page).pdfData { context in
            context.beginPage()
            let margin: CGFloat = 42
            let contentWidth = page.width - margin * 2
            let paragraph = NSMutableParagraphStyle()
            paragraph.lineBreakMode = .byCharWrapping
            paragraph.lineSpacing = 1
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.monospacedSystemFont(ofSize: 10.5, weight: .regular),
                .foregroundColor: UIColor.black,
                .paragraphStyle: paragraph,
            ]
            let receiptText = ReceiptFormatter.format(document) as NSString
            let textBounds = receiptText.boundingRect(
                with: CGSize(width: contentWidth, height: 500),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: attributes,
                context: nil
            )
            receiptText.draw(
                in: CGRect(
                    x: margin,
                    y: margin,
                    width: contentWidth,
                    height: min(500, ceil(textBounds.height))
                ),
                withAttributes: attributes
            )

            guard let qrImage = qrImage(payload: document.explorerURL.absoluteString) else {
                return
            }
            let qrSide: CGFloat = 170
            let qrY = min(page.height - margin - qrSide, margin + ceil(textBounds.height) + 14)
            qrImage.draw(
                in: CGRect(
                    x: (page.width - qrSide) / 2,
                    y: qrY,
                    width: qrSide,
                    height: qrSide
                ),
                blendMode: .normal,
                alpha: 1
            )
        }
    }

    private static func qrImage(payload: String) -> UIImage? {
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
        let padded = translated.composited(
            over: CIImage(color: .white).cropped(to: paddedExtent)
        )
        let scale = max(1, floor(600 / paddedExtent.width))
        let scaled = padded.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let image = CIContext(options: [.useSoftwareRenderer: false])
            .createCGImage(scaled, from: scaled.extent)
        else { return nil }
        return UIImage(cgImage: image)
    }
}

@MainActor
private enum ReceiptPrintPresenter {
    static func present(pdfData: Data, jobName: String) {
        guard UIPrintInteractionController.isPrintingAvailable else { return }
        let controller = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = jobName
        info.outputType = .general
        controller.printInfo = info
        controller.printingItem = pdfData
        controller.present(animated: true)
    }
}
