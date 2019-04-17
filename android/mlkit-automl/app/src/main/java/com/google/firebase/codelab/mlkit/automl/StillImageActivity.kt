/**
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.codelab.mlkit.automl

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import com.google.firebase.ml.common.FirebaseMLException
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date


class StillImageActivity : AppCompatActivity() {

  private var currentPhotoFile: File? = null
  private var imageSpinner: Spinner? = null
  private var imagePreview: ImageView? = null
  private var textView: TextView? = null

  private var classifier: ImageClassifier? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_still_image)

    imageSpinner = findViewById(R.id.image_spinner)
    imagePreview = findViewById(R.id.image_preview)
    textView = findViewById(R.id.result_text)

    val imageList = resources.getStringArray(R.array.image_name_array)
    val captureImageItemIndex = imageList.size
    val spinnerItemList = imageList.clone().plus(getString(R.string.capture_with_camera))

    // Setup image selector
    ArrayAdapter<CharSequence>(this, android.R.layout.simple_spinner_item, spinnerItemList).also {
      it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
      imageSpinner?.adapter = it
    }
    imageSpinner?.onItemSelectedListener = object : OnItemSelectedListener {
      override fun onNothingSelected(parent: AdapterView<*>?) = Unit

      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (position == captureImageItemIndex) {
          dispatchTakePictureIntent()
        } else {
          // Load image from resource
          val imageName = imageList[position]
          val drawableId = resources.getIdentifier(imageName, "drawable", packageName)
          val bitmap = BitmapFactory.decodeResource(resources, drawableId)

          classifyImage(bitmap)
        }
      }
    }

    // Setup image classifier
    try {
      classifier = ImageClassifier(this)
    } catch (e: FirebaseMLException) {
      textView?.text = getString(R.string.fail_to_initialize_img_classifier)
    }
  }

  @Throws(IOException::class)
  private fun createImageFile(): File {
    // Create an image file name
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    val storageDir = cacheDir
    return createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
    ).apply {
      // Save a file: path for use with ACTION_VIEW intents
      currentPhotoFile = this
    }
  }

  private fun dispatchTakePictureIntent() {
    Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
      // Ensure that there's a camera activity to handle the intent
      takePictureIntent.resolveActivity(packageManager)?.also {
        // Create the File where the photo should go
        val photoFile: File? = try {
          createImageFile()
        } catch (e: IOException) {
          // Error occurred while creating the File
          Log.e(TAG, "Unable to save image to run classification.", e)
          null
        }
        // Continue only if the File was successfully created
        photoFile?.also {
          val photoURI: Uri = FileProvider.getUriForFile(
                  this,
                  "com.google.firebase.codelab.mlkit.automl.fileprovider",
                  it
          )
          takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
          startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
        }
      }
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
      // Make use of FirebaseVisionImage.fromFilePath to take into account Exif Orientation of
      // the image captured
      FirebaseVisionImage.fromFilePath(this, Uri.fromFile(currentPhotoFile)).also {
        classifyImage(it.bitmap)
      }
    }
  }

  private fun classifyImage(bitmap: Bitmap) {
    if (classifier == null) {
      textView?.text = getString(R.string.uninitialized_img_classifier_or_invalid_context)
      return
    }

    // Show image on screen
    imagePreview?.setImageBitmap(bitmap)

    // Classify image
    classifier?.classifyFrame(bitmap)?.
            addOnCompleteListener { task ->
              if (task.isSuccessful) {
                textView?.text = task.result
              } else {
                val e = task.exception
                Log.e(TAG, "Error classifying frame", e)
                textView?.text = e?.message
              }
            }
  }

  companion object {

    /** Tag for the [Log].  */
    private const val TAG = "StillImageActivity"

    /** Request code for starting photo capture activity  */
    private const val REQUEST_IMAGE_CAPTURE = 1

  }
}
