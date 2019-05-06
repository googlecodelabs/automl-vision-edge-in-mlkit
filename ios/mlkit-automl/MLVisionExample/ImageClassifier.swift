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

import Foundation
import FirebaseMLCommon
import FirebaseMLVisionAutoML

class ImageClassifer {

  private let vision = Vision.vision()
  private let modelManager = ModelManager.modelManager()
  private let autoMLOnDeviceLabeler: VisionImageLabeler

  private let localModel: LocalModel? = {
    guard let localModelFilePath = Bundle.main.path(
      forResource: Constant.autoMLManifestFileName,
      ofType: Constant.autoMLManifestFileType,
      inDirectory: Constant.autoMLManifestFolder
      ) else {
        print("Failed to find AutoML local model manifest file.")
        return nil
    }

    return LocalModel(name: Constant.localAutoMLModelName, path: localModelFilePath)
  }()

  private let remoteModel: RemoteModel = {
    let initialConditions = ModelDownloadConditions()
    let updateConditions = ModelDownloadConditions(
      allowsCellularAccess: false,
      allowsBackgroundDownloading: true
    )
    return RemoteModel(
      name: Constant.remoteAutoMLModelName,
      allowsModelUpdates: true,
      initialConditions: initialConditions,
      updateConditions: updateConditions
    )
  }()

  init() {
    // Load the remote AutoML model.
    modelManager.register(remoteModel)
    modelManager.download(remoteModel)

    // Load the local AutoML model.
    var localModelName: String?
    if let model = localModel {
      modelManager.register(model)
      localModelName = Constant.localAutoMLModelName
    }

    // Create AutoML image labeler.
    let options = VisionOnDeviceAutoMLImageLabelerOptions(
      remoteModelName: Constant.remoteAutoMLModelName,
      localModelName: localModelName
    )
    options.confidenceThreshold = Constant.labelConfidenceThreshold
    autoMLOnDeviceLabeler = vision.onDeviceAutoMLImageLabeler(options: options)

    // Set up to get notified when remote model download succeeded.
    setupRemoteModelDownloadNotification()
  }
}

// MARK: - Private

extension ImageClassifer {

  /// Set up receiver to get notified about model download progress.
  private func setupRemoteModelDownloadNotification() {
    NotificationCenter.default.addObserver(
      forName: .firebaseMLModelDownloadDidSucceed,
      object: nil,
      queue: OperationQueue.main
    ) { _ in
      print("Sucessfully downloaded AutoML remote model")
    }

    NotificationCenter.default.addObserver(
      forName: .firebaseMLModelDownloadDidFail,
      object: nil,
      queue: OperationQueue.main
    ) { _ in
      print("Error: AutoML remote model download failed. Check if Constant.remoteAutoMLModelName",
            "matches with the model name you published in the Firebase Console.")
    }
  }

}

// MARK: - Classify image

extension ImageClassifer {

  /// Classify the given UIImage instance. This method is useful to classify still images.
  func classifyImage(_ image: UIImage, completionHandler: @escaping ImageClassificationCompletion) {
    // Rotate the image so that its imageOrientation is always "up"
    guard let rotatedImage = image.imageOrientedUp() else {
      completionHandler(nil, ClassificationError.invalidInput)
      return
    }

    // Initialize a VisionImage object with the rotated image.
    let visionImage = VisionImage(image: rotatedImage)

    // Feed the image to ML Kit AutoML SDK.
    classifyVisionImage(visionImage, completionHandler: completionHandler)
  }

  /// Classify the given CMSampleBuffer instance.
  /// This method is useful to classify frames of video streams.
  func classifySampleBuffer(
    _ sampleBuffer: CMSampleBuffer,
    isUsingFrontCamera: Bool,
    completionHandler: @escaping ImageClassificationCompletion
  ) {

    let visionImage = VisionImage(buffer: sampleBuffer)
    let metadata = VisionImageMetadata()
    let orientation = UIUtilities.imageOrientation(
      fromDevicePosition: isUsingFrontCamera ? .front : .back
    )
    metadata.orientation = UIUtilities.visionImageOrientation(from: orientation)
    visionImage.metadata = metadata

    // Feed the image to ML Kit AutoML SDK.
    classifyVisionImage(visionImage, completionHandler: completionHandler)
  }

  /// Classify a VisionImage instance. This private method provides actual implementation of
  /// the image classification logic and is called by the public "classify" methods.
  private func classifyVisionImage(
    _ visionImage: VisionImage,
    completionHandler: @escaping ImageClassificationCompletion
  ) {

    // Return error if AutoML local model is not available.
    guard localModel != nil else {
      completionHandler(nil, ClassificationError.localModelNotAvailable)
      return
    }

    // Indicate whether the remote or local model is used.
    // Note: in most common cases, once a remote model is downloaded it will be used. However, in
    // very rare cases, the model itself might not be valid, and thus the local model is used. In
    // addition, since model download failures can be transient, and model download can also be
    // triggered in the background during inference, it is possible that a remote model is used
    // even if the first download fails.
    let isRemoteModelDownloaded = modelManager.isRemoteModelDownloaded(remoteModel)
    var result = "Source: " + (isRemoteModelDownloaded ? "Remote" : "Local") + " model\n"

    let startTime = DispatchTime.now()

    autoMLOnDeviceLabeler.process(visionImage) { detectedLabels, error in
      guard error == nil else {
        completionHandler(nil, error)
        return
      }

      // Measure inference latency and format it to show to user.
      let endTime = DispatchTime.now()
      let nanoTime = endTime.uptimeNanoseconds - startTime.uptimeNanoseconds
      let latencyInMs = round(Double(nanoTime) / 1_000_000)
      result += "Latency: \(latencyInMs)ms\n"

      // Format detection result to show to user.
      result += detectedLabels?.map { label -> String in
            return "Label: \(label.text), Confidence: \(label.confidence ?? 0)"
      }.joined(separator: "\n") ?? "No Result"

      completionHandler(result, nil)
    }
  }
}

// MARK: - Constants and types

private enum Constant {
  /// Definition of AutoML local model
  static let localAutoMLModelName = "automl_image_labeling_model"
  static let autoMLManifestFileName = "manifest"
  static let autoMLManifestFileType = "json"
  static let autoMLManifestFolder = "automl"

  /// Definition of AutoML remote model.
  static let remoteAutoMLModelName = "mlkit_flowers"

  /// Config for AutoML Image Labeler classification task.
  static let labelConfidenceThreshold: Float = 0.6
}

private enum ClassificationError: Error {
  case invalidInput
  case localModelNotAvailable
}

extension ClassificationError: LocalizedError {
  public var errorDescription: String? {
    switch self {
      case .invalidInput:
        return "Invalid input image."
      case .localModelNotAvailable:
        return "AutoML local model is not available. Please check if you have " +
          "downloaded and added the model files to this project."
    }
  }
}

typealias ImageClassificationCompletion = (String?, Error?) -> Void
