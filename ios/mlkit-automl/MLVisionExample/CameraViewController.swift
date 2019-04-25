//
//  Copyright 2019 Google LLC
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

import AVFoundation
import CoreVideo
import UIKit

class CameraViewController: UIViewController {

  private var isUsingFrontCamera = false
  private var previewLayer: AVCaptureVideoPreviewLayer!
  private lazy var captureSession = AVCaptureSession()
  private lazy var sessionQueue = DispatchQueue(label: Constant.sessionQueueLabel)
  private lazy var classifer = ImageClassifer()
  private var lastFrame: CMSampleBuffer?

  private lazy var previewOverlayView: UIImageView = {
    precondition(isViewLoaded)
    let previewOverlayView = UIImageView(frame: .zero)
    previewOverlayView.translatesAutoresizingMaskIntoConstraints = false
    return previewOverlayView
  }()

  // MARK: - IBOutlets

  @IBOutlet fileprivate weak var cameraView: UIView!
  @IBOutlet fileprivate weak var detectionResultText: UILabel!

  // MARK: - UIViewController

  override func viewDidLoad() {
    super.viewDidLoad()

    previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
    setUpPreviewOverlayView()
    setUpCaptureSessionOutput()
    setUpCaptureSessionInput()
  }

  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)

    startSession()
  }

  override func viewDidDisappear(_ animated: Bool) {
    super.viewDidDisappear(animated)

    stopSession()
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()

    previewLayer.frame = cameraView.frame
  }

  // MARK: - IBActions

  @IBAction func switchCamera(_ sender: Any) {
    isUsingFrontCamera = !isUsingFrontCamera
    setUpCaptureSessionInput()
  }

  // MARK: - On-Device AutoML Detections

  private func detectImageLabelsAutoMLOnDevice(in sampleBuffer: CMSampleBuffer) {

    let group = DispatchGroup()
    group.enter()
    classifer.classifySampleBuffer(sampleBuffer, isUsingFrontCamera: isUsingFrontCamera) {
      resultText, error in
        defer { group.leave() }

        self.updatePreviewOverlayView()

        if let error = error {
          self.detectionResultText.text = error.localizedDescription
          return
        }

        self.detectionResultText.text = resultText
      }

    group.wait()
  }

  // MARK: - Private

  private func setUpCaptureSessionOutput() {
    sessionQueue.async {
      self.captureSession.beginConfiguration()
      // When performing latency tests to determine ideal capture settings,
      // run the app in 'release' mode to get accurate performance metrics.
      self.captureSession.sessionPreset = AVCaptureSession.Preset.medium

      let output = AVCaptureVideoDataOutput()
      output.videoSettings =
        [(kCVPixelBufferPixelFormatTypeKey as String): kCVPixelFormatType_32BGRA]
      let outputQueue = DispatchQueue(label: Constant.videoDataOutputQueueLabel)
      output.setSampleBufferDelegate(self, queue: outputQueue)
      guard self.captureSession.canAddOutput(output) else {
        print("Failed to add capture session output.")
        return
      }
      self.captureSession.addOutput(output)
      self.captureSession.commitConfiguration()
    }
  }

  private func setUpCaptureSessionInput() {
    sessionQueue.async {
      let cameraPosition: AVCaptureDevice.Position = self.isUsingFrontCamera ? .front : .back
      guard let device = self.captureDevice(forPosition: cameraPosition) else {
        print("Failed to get capture device for camera position: \(cameraPosition)")
        return
      }
      do {
        self.captureSession.beginConfiguration()
        let currentInputs = self.captureSession.inputs
        for input in currentInputs {
          self.captureSession.removeInput(input)
        }

        let input = try AVCaptureDeviceInput(device: device)
        guard self.captureSession.canAddInput(input) else {
          print("Failed to add capture session input.")
          return
        }
        self.captureSession.addInput(input)
        self.captureSession.commitConfiguration()
      } catch {
        print("Failed to create capture device input: \(error.localizedDescription)")
      }
    }
  }

  private func startSession() {
    sessionQueue.async {
      self.captureSession.startRunning()
    }
  }

  private func stopSession() {
    sessionQueue.async {
      self.captureSession.stopRunning()
    }
  }

  private func setUpPreviewOverlayView() {
    cameraView.addSubview(previewOverlayView)
    NSLayoutConstraint.activate([
      previewOverlayView.topAnchor.constraint(greaterThanOrEqualTo: cameraView.topAnchor),
      previewOverlayView.centerYAnchor.constraint(equalTo: cameraView.centerYAnchor),
      previewOverlayView.leadingAnchor.constraint(equalTo: cameraView.leadingAnchor),
      previewOverlayView.trailingAnchor.constraint(equalTo: cameraView.trailingAnchor),
      previewOverlayView.bottomAnchor.constraint(lessThanOrEqualTo: cameraView.bottomAnchor),
    ])
  }

  private func captureDevice(forPosition position: AVCaptureDevice.Position) -> AVCaptureDevice? {
    if #available(iOS 10.0, *) {
      let discoverySession = AVCaptureDevice.DiscoverySession(
        deviceTypes: [.builtInWideAngleCamera],
        mediaType: .video,
        position: .unspecified
      )
      return discoverySession.devices.first { $0.position == position }
    }
    return nil
  }

  private func updatePreviewOverlayView() {
    guard let lastFrame = lastFrame,
      let imageBuffer = CMSampleBufferGetImageBuffer(lastFrame)
    else {
      return
    }

    let ciImage = CIImage(cvPixelBuffer: imageBuffer)
    let context = CIContext(options: nil)

    guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else {
      return
    }

    // Rotates the camera feed to match with device's orientation
    let rotatedImage = UIImage(
      cgImage: cgImage,
      scale: Constant.originalScale,
      orientation: .right
    )

    // Mirrors the camera feed if using front camera to make it looks natural
    if isUsingFrontCamera {
      guard let rotatedCGImage = rotatedImage.cgImage else {
        return
      }
      let mirroredImage = UIImage(
        cgImage: rotatedCGImage,
        scale: Constant.originalScale,
        orientation: .leftMirrored
      )
      previewOverlayView.image = mirroredImage
    } else {
      previewOverlayView.image = rotatedImage
    }
  }
}

// MARK: AVCaptureVideoDataOutputSampleBufferDelegate

extension CameraViewController: AVCaptureVideoDataOutputSampleBufferDelegate {

  func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    lastFrame = sampleBuffer
    detectImageLabelsAutoMLOnDevice(in: sampleBuffer)
  }
}

// MARK: - Constants

private enum Constant {
  static let videoDataOutputQueueLabel = "com.google.firebaseml.visiondetector.VideoDataOutputQueue"
  static let sessionQueueLabel = "com.google.firebaseml.visiondetector.SessionQueue"
  static let originalScale: CGFloat = 1.0
}
