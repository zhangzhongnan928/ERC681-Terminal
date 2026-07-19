import AVFoundation
import Dispatch
import SwiftUI
import UIKit

/// `AVCaptureSession` is not `Sendable`, so this small audited wrapper owns the
/// cross-thread boundary. All blocking running-state transitions are confined
/// to one serial queue; configuration completes before the first start request.
private final class ConfigurationAddressSessionRunner: @unchecked Sendable {
    let session = AVCaptureSession()

    private let queue = DispatchQueue(
        label: "com.openpasskey.terminal.configuration-address-scanner"
    )

    func start() {
        queue.async { [self] in
            guard !session.isRunning else { return }
            session.startRunning()
        }
    }

    func stop() {
        queue.async { [self] in
            guard session.isRunning else { return }
            session.stopRunning()
        }
    }
}

struct ConfigurationAddressScanner: UIViewControllerRepresentable {
    let onPayload: @MainActor (String) -> Bool

    func makeUIViewController(context: Context) -> ConfigurationAddressScannerViewController {
        ConfigurationAddressScannerViewController(onPayload: onPayload)
    }

    func updateUIViewController(
        _ uiViewController: ConfigurationAddressScannerViewController,
        context: Context
    ) {}
}

final class ConfigurationAddressScannerViewController: UIViewController,
    @MainActor AVCaptureMetadataOutputObjectsDelegate
{
    private let sessionRunner = ConfigurationAddressSessionRunner()
    private let metadataOutput = AVCaptureMetadataOutput()
    private let onPayload: @MainActor (String) -> Bool
    private let statusLabel = UILabel()
    private var isConfigured = false
    private var lastRejectedPayload: String?
    private var lastRejectedAt = Date.distantPast

    private var session: AVCaptureSession { sessionRunner.session }

    private lazy var previewLayer: AVCaptureVideoPreviewLayer = {
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        return layer
    }()

    init(onPayload: @escaping @MainActor (String) -> Bool) {
        self.onPayload = onPayload
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.layer.insertSublayer(previewLayer, at: 0)
        configureStatusLabel()
        prepareCamera()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if isConfigured {
            startSession()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stopSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
    }

    private func prepareCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            Task { [weak self] in
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard let self else { return }
                if granted {
                    self.configureSession()
                } else {
                    self.showStatus("Camera access is disabled. Enable it in Settings to scan QR codes.")
                }
            }
        case .denied, .restricted:
            showStatus("Camera access is disabled. Enable it in Settings to scan QR codes.")
        @unknown default:
            showStatus("Camera access is unavailable.")
        }
    }

    private func configureSession() {
        guard !isConfigured else { return }
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input),
              session.canAddOutput(metadataOutput)
        else {
            showStatus("A camera capable of scanning QR codes is unavailable on this device.")
            return
        }

        session.beginConfiguration()
        session.addInput(input)
        session.addOutput(metadataOutput)
        metadataOutput.setMetadataObjectsDelegate(self, queue: .main)
        metadataOutput.metadataObjectTypes = [.qr]
        session.commitConfiguration()

        isConfigured = true
        statusLabel.isHidden = true
        startSession()
    }

    private func startSession() {
        sessionRunner.start()
    }

    private func stopSession() {
        sessionRunner.stop()
    }

    private func configureStatusLabel() {
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.textColor = .white
        statusLabel.font = .preferredFont(forTextStyle: .body)
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.72)
        statusLabel.layer.cornerRadius = 12
        statusLabel.layer.masksToBounds = true
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            statusLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            statusLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 32),
            statusLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -32),
            statusLabel.widthAnchor.constraint(lessThanOrEqualToConstant: 320),
            statusLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 72),
        ])
    }

    private func showStatus(_ message: String) {
        statusLabel.text = "  \(message)  "
        statusLabel.isHidden = false
        UIAccessibility.post(notification: .announcement, argument: message)
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let payload = object.stringValue
        else {
            return
        }

        let now = Date()
        if payload == lastRejectedPayload, now.timeIntervalSince(lastRejectedAt) < 1.5 {
            return
        }

        if onPayload(payload) {
            stopSession()
        } else {
            lastRejectedPayload = payload
            lastRejectedAt = now
        }
    }
}
