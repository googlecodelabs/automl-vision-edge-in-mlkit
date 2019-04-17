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

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.google.firebase.ml.common.FirebaseMLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.Arrays
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Basic fragments for the Camera.  */
class Camera2BasicFragment : Fragment() {

  private var checkedPermissions = false
  private var textView: TextView? = null

  /** An [ImageClassifier] that classifies frame received from camera feed.  */
  private var classifier: ImageClassifier? = null

  /** A [Job] to ensure that uiScope only exists when the fragment is visible.  */
  private val classificationJob = SupervisorJob()

  /** An [CoroutineScope] that run jobs on the main thread.  */
  private val uiScope = MainScope() + classificationJob

  /**
   * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
   */
  private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
      openCamera(width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
      configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
  }

  /** ID of the current [CameraDevice].  */
  private var cameraId: String? = null

  /** An [AutoFitTextureView] for camera preview.  */
  private var textureView: AutoFitTextureView? = null

  /** A [CameraCaptureSession] for camera preview.  */
  private var captureSession: CameraCaptureSession? = null

  /** A reference to the opened [CameraDevice].  */
  private var cameraDevice: CameraDevice? = null

  /** The [android.util.Size] of camera preview.  */
  private var previewSize: Size? = null

  /** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.  */
  private val stateCallback = object : CameraDevice.StateCallback() {

    override fun onOpened(currentCameraDevice: CameraDevice) {
      // This method is called when the camera is opened.  We start camera preview here.
      cameraOpenCloseLock.release()
      cameraDevice = currentCameraDevice
      createCameraPreviewSession()
    }

    override fun onDisconnected(currentCameraDevice: CameraDevice) {
      cameraOpenCloseLock.release()
      currentCameraDevice.close()
      cameraDevice = null
    }

    override fun onError(currentCameraDevice: CameraDevice, error: Int) {
      cameraOpenCloseLock.release()
      currentCameraDevice.close()
      cameraDevice = null
      activity?.finish()
    }
  }

  /** An [ImageReader] that handles image capture.  */
  private var imageReader: ImageReader? = null

  /** A [Semaphore] to prevent the app from exiting before closing the camera.  */
  private val cameraOpenCloseLock = Semaphore(1)

  /** A [CameraCaptureSession.CaptureCallback] that handles events related to capture.  */
  private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

    override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult) {
    }

    override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult) {
    }
  }

  private val requiredPermissions: Array<String> by lazy {
    try {
      activity?.packageManager?.getPackageInfo(
        activity!!.packageName,
        PackageManager.GET_PERMISSIONS
      )?.requestedPermissions
              ?: arrayOf()
    } catch (e: PackageManager.NameNotFoundException) {
      arrayOf<String>()
    }

  }

  /**
   * Update UI with classification results.
   *
   * @param text The message to show
   */
  private fun showText(text: String?) {
    textView?.text = text
  }

  /** Layout the preview and buttons.  */
  override fun onCreateView(
          inflater: LayoutInflater,
          container: ViewGroup?,
          savedInstanceState: Bundle?
  ): View? = inflater.inflate(R.layout.fragment_camera2_basic, container, false)

  /** Connect the buttons to their event handler.  */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    textureView = view.findViewById(R.id.image_preview)
    textView = view.findViewById(R.id.text)
  }

  /** Load the model and labels.  */
  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    try {
      classifier = activity?.let { ImageClassifier(it) }
    } catch (e: FirebaseMLException) {
      Log.e(TAG, "Failed to initialize an image classifier.")
    }
  }

  override fun onResume() {
    super.onResume()

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    val textureView = textureView ?: return
    if (textureView.isAvailable) {
      openCamera(textureView.width, textureView.height)
    } else {
      textureView.surfaceTextureListener = surfaceTextureListener
    }

    // Repeatedly classifying frame after frame as long as the fragment is visible
    uiScope.launch {
      while (true) { classifyFrame() }
    }
  }

  override fun onPause() {
    closeCamera()
    classificationJob.cancelChildren()
    super.onPause()
  }

  override fun onDestroy() {
    classifier?.close()
    uiScope.cancel()
    super.onDestroy()
  }

  /**
   * Sets up member variables related to camera.
   *
   * @param width The width of available size for camera preview
   * @param height The height of available size for camera preview
   */
  private fun setUpCameraOutputs(width: Int, height: Int) {
    val activity = activity ?: return
    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)

        // We don't use a front facing camera in this sample.
        val facing = characteristics[CameraCharacteristics.LENS_FACING]
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue
        }

        val map = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!

        // For still image captures, we use the largest available size.
        val largest = map.getOutputSizes(ImageFormat.JPEG).maxWith(compareSizesByArea)!!
        imageReader = ImageReader.newInstance(
                largest.width, largest.height, ImageFormat.JPEG, /*maxImages*/ 2)

        // Find out if we need to swap dimension to get the preview size relative to sensor
        // coordinate.
        val displayRotation = activity.windowManager.defaultDisplay.rotation

        /* Orientation of the camera sensor */
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        val swappedDimensions = when (displayRotation) {
          Surface.ROTATION_0, Surface.ROTATION_180 ->
            (sensorOrientation == 90 || sensorOrientation == 270)
          Surface.ROTATION_90, Surface.ROTATION_270 ->
            (sensorOrientation == 0 || sensorOrientation == 180)
          else -> {
            Log.e(TAG, "Display rotation is invalid: $displayRotation")
            false
          }
        }

        val displaySize = Point()
        activity.windowManager.defaultDisplay.getSize(displaySize)
        var rotatedPreviewWidth = width
        var rotatedPreviewHeight = height
        var maxPreviewWidth = displaySize.x
        var maxPreviewHeight = displaySize.y

        if (swappedDimensions) {
          rotatedPreviewWidth = height
          rotatedPreviewHeight = width
          maxPreviewWidth = displaySize.y
          maxPreviewHeight = displaySize.x
        }

        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
          maxPreviewWidth = MAX_PREVIEW_WIDTH
        }

        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
          maxPreviewHeight = MAX_PREVIEW_HEIGHT
        }

        val optimalSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                rotatedPreviewWidth,
                rotatedPreviewHeight,
                maxPreviewWidth,
                maxPreviewHeight,
                largest)

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          textureView?.setAspectRatio(optimalSize.width, optimalSize.height)
        } else {
          textureView?.setAspectRatio(optimalSize.height, optimalSize.width)
        }

        this.previewSize = optimalSize
        this.cameraId = cameraId
        return
      }
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    } catch (e: NullPointerException) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the
      // device this code runs.
      ErrorDialog.newInstance(getString(R.string.camera_error))
              .show(childFragmentManager, FRAGMENT_DIALOG)
    }

  }

  /** Opens the camera specified by [Camera2BasicFragment.cameraId].  */
  private fun openCamera(width: Int, height: Int) {
    if (!checkedPermissions && !allPermissionsGranted()) {
      requestPermissions(requiredPermissions, PERMISSIONS_REQUEST_CODE)
      return
    } else {
      checkedPermissions = true
    }
    setUpCameraOutputs(width, height)
    configureTransform(width, height)
    val activity = activity ?: return
    val cameraId = cameraId ?: return
    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw RuntimeException("Time out waiting to lock camera opening.")
      }
      manager.openCamera(cameraId, stateCallback, null)
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    } catch (e: SecurityException) {
      e.printStackTrace()
    } catch (e: InterruptedException) {
      throw RuntimeException("Interrupted while trying to lock camera opening.", e)
    }

  }

  private fun allPermissionsGranted() = requiredPermissions.all {
    val activity = activity ?: return false
    ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
  }

  /** Closes the current [CameraDevice].  */
  private fun closeCamera() {
    try {
      cameraOpenCloseLock.acquire()
      captureSession?.close().also { captureSession = null }
      cameraDevice?.close().also { cameraDevice = null }
      imageReader?.close().also { imageReader = null }
    } catch (e: InterruptedException) {
      throw RuntimeException("Interrupted while trying to lock camera closing.", e)
    } finally {
      cameraOpenCloseLock.release()
    }
  }

  /** Creates a new [CameraCaptureSession] for camera preview.  */
  private fun createCameraPreviewSession() {
    val textureView = textureView ?: return
    val previewSize = previewSize ?: return
    val cameraDevice = cameraDevice ?: return

    try {
      val texture = textureView.surfaceTexture

      // We configure the size of default buffer to be the size of camera preview we want.
      texture?.setDefaultBufferSize(previewSize.width, previewSize.height)

      // This is the output Surface we need to start preview.
      val surface = Surface(texture)

      // We set up a CaptureRequest.Builder with the output Surface.
      val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      previewRequestBuilder.addTarget(surface)

      // Here, we create a CameraCaptureSession for camera preview.
      cameraDevice.createCaptureSession(
        Arrays.asList(surface),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            // When the session is ready, we start displaying the preview.
            captureSession = cameraCaptureSession
            try {
              // Auto focus should be continuous for camera preview.
              previewRequestBuilder.set(
                      CaptureRequest.CONTROL_AF_MODE,
                      CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

              // Finally, we start displaying the camera preview.
              val previewRequest = previewRequestBuilder.build()
              cameraCaptureSession.setRepeatingRequest(
                      previewRequest, captureCallback, null)
            } catch (e: CameraAccessException) {
              e.printStackTrace()
            }

          }

          override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
            showText("Failed")
          }
        },
        null
      )
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }

  }

  /**
   * Configures the necessary [android.graphics.Matrix] transformation to `textureView`. This
   * method should be called after the camera preview size is determined in setUpCameraOutputs and
   * also the size of `textureView` is fixed.
   *
   * @param viewWidth The width of `textureView`
   * @param viewHeight The height of `textureView`
   */
  private fun configureTransform(viewWidth: Int, viewHeight: Int) {
    val activity = activity ?: return
    val previewSize = previewSize ?: return
    val textureView = textureView ?: return

    val rotation = activity.windowManager.defaultDisplay.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
      val scale = Math.max(
              viewHeight.toFloat() / previewSize.height,
              viewWidth.toFloat() / previewSize.width)
      matrix.postScale(scale, scale, centerX, centerY)
      matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180f, centerX, centerY)
    }
    textureView.setTransform(matrix)
  }

  /** Classifies a frame from the preview stream.  */
  private suspend fun classifyFrame() {
    val classifier = classifier
    val bitmap = textureView?.bitmap

    // Check if initialization tasks have all finished.
    // If not, then wait for some time before allowing retry.
    if (classifier == null || activity == null || cameraDevice == null || bitmap == null) {
      showText("Uninitialized Classifier or invalid context.")
      delay(RETRY_INTERVAL)
      return
    }

    // Classify current frame captured by the camera
    suspendCoroutine<Unit> { continuation ->
      classifier.classifyFrame(bitmap).addOnCompleteListener { task ->
        if (task.isSuccessful) {
          showText(task.result)
        } else {
          val e = task.exception
          Log.e(TAG, "Error classifying frame", e)
          showText(e?.message)
        }

        bitmap.recycle()
        continuation.resume(Unit)
      }
    }
  }

  /** Shows an error message dialog.  */
  class ErrorDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
      return activity?.let {
        AlertDialog.Builder(it)
                .setMessage(arguments?.getString(ARG_MESSAGE))
                .setPositiveButton(
                        android.R.string.ok
                ) { _, _ -> it.finish() }
                .create()
      } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {

      private const val ARG_MESSAGE = "message"

      fun newInstance(message: String): ErrorDialog {
        val dialog = ErrorDialog()
        val args = Bundle()
        args.putString(ARG_MESSAGE, message)
        dialog.arguments = args
        return dialog
      }
    }
  }

  companion object {

    /** Tag for the [Log].  */
    private const val TAG = "MLKitAutoMLCodelab"

    private const val FRAGMENT_DIALOG = "dialog"

    private const val PERMISSIONS_REQUEST_CODE = 1

    /** Wait time to retry classification task if the camera or classifier is not yet ready.  */
    private const val RETRY_INTERVAL = 100L

    /** Max preview width that is guaranteed by Camera2 API.  */
    private const val MAX_PREVIEW_WIDTH = 1920

    /** Max preview height that is guaranteed by Camera2 API.  */
    private const val MAX_PREVIEW_HEIGHT = 1080

    /** Compares two `Size`s based on their areas.  */
    private val compareSizesByArea = compareBy<Size> { it.width.toLong() * it.height }

    /**
     * Resizes image.
     *
     * Attempting to use too large a preview size could  exceed the camera bus' bandwidth
     * limitation, resulting in gorgeous previews but the storage of garbage capture data.
     *
     * Given `choices` of `Size`s supported by a camera, choose the smallest one that is
     * at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param textureViewWidth The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth The maximum width that can be chosen
     * @param maxHeight The maximum height that can be chosen
     * @param aspectRatio The aspect ratio
     * @return The optimal `Size`, or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size
    ): Size {

      // Collect the supported resolutions that are at least as big as the preview Surface
      val bigEnough = ArrayList<Size>()
      // Collect the supported resolutions that are smaller than the preview Surface
      val notBigEnough = ArrayList<Size>()
      val w = aspectRatio.width
      val h = aspectRatio.height
      for (option in choices) {
        if (option.width <= maxWidth
                && option.height <= maxHeight
                && option.height == option.width * h / w) {
          if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
            bigEnough.add(option)
          } else {
            notBigEnough.add(option)
          }
        }
      }

      // Pick the smallest of those big enough. If there is no one big enough, pick the
      // largest of those not big enough.
      return bigEnough.minWith(compareSizesByArea)
              ?: notBigEnough.maxWith(compareSizesByArea)
              ?: choices[0].also {
                Log.e(TAG, "Couldn't find any suitable preview size")
              }
    }
  }
}
