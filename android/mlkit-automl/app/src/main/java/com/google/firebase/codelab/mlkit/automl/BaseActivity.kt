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

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Base activity that requests all needed permission at launch */
abstract class BaseActivity : AppCompatActivity(),
  ActivityCompat.OnRequestPermissionsResultCallback {

  private val requiredPermissions: Array<String> by lazy {
    try {
      this.packageManager.getPackageInfo(
        this.packageName,
        PackageManager.GET_PERMISSIONS
      ).requestedPermissions ?: arrayOf()
    } catch (e: PackageManager.NameNotFoundException) {
      arrayOf<String>()
    }
  }

  private fun allPermissionsGranted() = requiredPermissions.none { !isPermissionGranted(it) }

  private fun requestRuntimePermissions() {
    val allNeededPermissions = requiredPermissions.filter { !isPermissionGranted(it) }

    if (allNeededPermissions.isNotEmpty()) {
      ActivityCompat.requestPermissions(
        this,
        allNeededPermissions.toTypedArray(),
        PERMISSION_REQUESTS
      )
    }
  }

  private fun isPermissionGranted(permission: String): Boolean {
    when (ContextCompat.checkSelfPermission(this, permission)) {
      PackageManager.PERMISSION_GRANTED -> {
        Log.i(TAG, "Permission granted: $permission")
        return true
      }
      else -> {
        Log.i(TAG, "Permission NOT granted: $permission")
        return false
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (!allPermissionsGranted()) {
      requestRuntimePermissions()
    }
  }

  companion object {

    /** Tag for the [Log].  */
    private const val TAG = "BaseActivity"

    private const val PERMISSION_REQUESTS = 1

  }
}
