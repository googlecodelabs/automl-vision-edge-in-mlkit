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

import CoreGraphics
import UIKit

// MARK: - UIImage

extension UIImage {

  /// Make a copy of the image and rotate it so that imageOrientation == .up.
  /// - Returns: The rotated image or `nil` if image could not be rotated.
  func imageOrientedUp() -> UIImage? {
    guard imageOrientation != .up else {
      // The image orientation is already is already up, so we don't need to do anything.
      return self.copy() as? UIImage
    }

    guard let cgImage = self.cgImage else {
      // CGImage is not available.
      return nil
    }

    guard let colorSpace = cgImage.colorSpace,
      let context = CGContext(
        data: nil,
        width: Int(size.width),
        height: Int(size.height),
        bitsPerComponent: cgImage.bitsPerComponent,
        bytesPerRow: 0,
        space: colorSpace,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    else {
      // Unable to create CGContext.
      return nil
    }

    var transform = CGAffineTransform.identity

    switch imageOrientation {
    case .down, .downMirrored:
      transform = transform.translatedBy(x: size.width, y: size.height)
      transform = transform.rotated(by: CGFloat.pi)
    case .left, .leftMirrored:
      transform = transform.translatedBy(x: size.width, y: 0)
      transform = transform.rotated(by: CGFloat.pi / 2.0)
    case .right, .rightMirrored:
      transform = transform.translatedBy(x: 0, y: size.height)
      transform = transform.rotated(by: CGFloat.pi / -2.0)
    case .up, .upMirrored:
      break
    @unknown default:
      break
    }

    // Flip image one more time if needed to, this is to prevent flipped image.
    switch imageOrientation {
    case .upMirrored, .downMirrored:
      transform = transform.translatedBy(x: size.width, y: 0)
      transform = transform.scaledBy(x: -1, y: 1)
    case .leftMirrored, .rightMirrored:
      transform = transform.translatedBy(x: size.height, y: 0)
      transform = transform.scaledBy(x: -1, y: 1)
    case .up, .down, .left, .right:
      break
    @unknown default:
      break
    }

    context.concatenate(transform)

    switch imageOrientation {
    case .left, .leftMirrored, .right, .rightMirrored:
      context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size.height, height: size.width))
    default:
      context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size.width, height: size.height))
    }

    guard let newCGImage = context.makeImage() else { return nil }

    return .init(cgImage: newCGImage, scale: 1.0, orientation: .up)
  }

  // MARK: - Private

  /// The PNG or JPEG data representation of the image or `nil` if the conversion failed.
  private var data: Data? {
    return self.pngData() ?? self.jpegData(compressionQuality: Constant.jpegCompressionQuality)
  }
}

// MARK: - Constants

private enum Constant {
  static let jpegCompressionQuality: CGFloat = 0.8
}
