//
//  Copyright Google Inc. All Rights Reserved.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

import UIKit

class ViewController:  UIViewController, UINavigationControllerDelegate {

  /// An image picker for accessing the photo library or camera.
  private var imagePicker = UIImagePickerController()

  /// Image counter.
  private var currentImage = 0

  /// AutoML image classifier wrapper.
  private lazy var classifier = ImageClassifer()

  // MARK: - IBOutlets

  @IBOutlet fileprivate weak var imageView: UIImageView!
  @IBOutlet fileprivate weak var photoCameraButton: UIBarButtonItem!
  @IBOutlet fileprivate weak var videoCameraButton: UIBarButtonItem!
  @IBOutlet fileprivate weak var resultsLabelView: UILabel!
  
  // MARK: - UIViewController

  override func viewDidLoad() {
    super.viewDidLoad()

    imagePicker.delegate = self
    imagePicker.sourceType = .photoLibrary

    let isCameraAvailable = UIImagePickerController.isCameraDeviceAvailable(.front) ||
      UIImagePickerController.isCameraDeviceAvailable(.rear)
    if isCameraAvailable {
      // `CameraViewController` uses `AVCaptureDevice.DiscoverySession` which is only supported for
      // iOS 10 or newer.
      if #available(iOS 10.0, *) {
        videoCameraButton.isEnabled = true
      }
    } else {
      photoCameraButton.isEnabled = false
    }

    // Set up image view and classify the first image in the bundle.
    imageView.image = UIImage(named: Constant.images[currentImage])
    classifyImage()
  }

  override func viewWillAppear(_ animated: Bool) {
    super.viewWillAppear(animated)

    navigationController?.navigationBar.isHidden = true
  }

  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)

    navigationController?.navigationBar.isHidden = false
  }

  // MARK: - IBActions

  @IBAction func openPhotoLibrary(_ sender: Any) {
    imagePicker.sourceType = .photoLibrary
    present(imagePicker, animated: true)
  }

  @IBAction func openCamera(_ sender: Any) {
    guard UIImagePickerController.isCameraDeviceAvailable(.front) ||
      UIImagePickerController.isCameraDeviceAvailable(.rear)
    else {
      return
    }

    imagePicker.sourceType = .camera
    present(imagePicker, animated: true)
  }

  @IBAction func changeImage(_ sender: Any) {
    nextImageAndClassify()
  }

  // MARK: - Private

  /// Clears the results text view and removes any frames that are visible.
  private func clearResults() {
    resultsLabelView.text = ""
    imageView.image = nil
  }

  /// Update the results text view with classification result.
  private func showResult(_ resultText: String) {
    self.resultsLabelView.text = resultText
  }

  /// Change to the next image available in app's bundle, and run image classification.
  private func nextImageAndClassify() {
    clearResults()

    currentImage = (currentImage + 1) % Constant.images.count
    imageView.image = UIImage(named: Constant.images[currentImage])

    classifyImage()
  }

  /// Run image classification on the image currently display in imageView.
  private func classifyImage() {
    guard let image = imageView.image else {
      print("Error: Attempted to run classification on a nil object")
      showResult("Error: invalid image")
      return
    }

    classifier.classifyImage(image) { resultText, error in
      // Handle classification error
      guard error == nil else {
        self.showResult(error!.localizedDescription)
        return
      }

      // We don't expect resultText and error to be both nil, so this is just a safeguard.
      guard resultText != nil else {
        self.showResult("Error: Unknown error occured")
        return
      }

      self.showResult(resultText!)
    }
  }
}

// MARK: - UIImagePickerControllerDelegate

extension ViewController: UIImagePickerControllerDelegate {

  public func imagePickerController(
    _ picker: UIImagePickerController,
    didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
  ) {

    if let pickedImage = info[.originalImage] as? UIImage {
      clearResults()
      imageView.image = pickedImage
      classifyImage()
    }

    dismiss(animated: true)
  }
}

// MARK: - Constants

private enum Constant {
  static let images = ["sunflower_1627193_640.jpg", "sunflower_3292932_640.jpg",
                       "dandelion_4110356_640.jpg", "dandelion_2817950_640.jpg",
                       "rose_1463562_640.jpg", "rose_3063284_640.jpg"]
}
