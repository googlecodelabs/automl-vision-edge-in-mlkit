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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView

/**
 * Demo app chooser which takes care of runtime permission requesting and allows you to pick from
 * all available testing Activities.
 */
class ChooserActivity : AppCompatActivity(),
        OnRequestPermissionsResultCallback,
        AdapterView.OnItemClickListener {

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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate")

    setContentView(R.layout.activity_chooser)

    // Set up ListView and Adapter
    val listView: ListView = findViewById(R.id.testActivityListView)

    val adapter = MyArrayAdapter(this, android.R.layout.simple_list_item_2, CLASSES)

    listView.adapter = adapter
    listView.onItemClickListener = this

    if (!allPermissionsGranted()) {
      requestRuntimePermissions()
    }
  }

  override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
    val clicked = CLASSES[position]
    startActivity(Intent(this, clicked))
  }

  private fun allPermissionsGranted() = requiredPermissions.none { !isPermissionGranted(it) }

  private fun requestRuntimePermissions() {
    val allNeededPermissions = requiredPermissions.filter { !isPermissionGranted( it) }

    if (allNeededPermissions.isNotEmpty()) {
      ActivityCompat.requestPermissions(this,
              allNeededPermissions.toTypedArray(),
              PERMISSION_REQUESTS
      )
    }
  }

  private fun isPermissionGranted(permission: String) =
    when (ContextCompat.checkSelfPermission(this, permission)) {
      PackageManager.PERMISSION_GRANTED -> {
        Log.i(TAG, "Permission granted: $permission")
        true
      }
      else -> {
        Log.i(TAG, "Permission NOT granted: $permission")
        false
      }
    }

  private class MyArrayAdapter(context: Context,
                               resource: Int,
                               private val classes: Array<Class<*>>
  ) : ArrayAdapter<Class<*>>(context, resource, classes) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      return (convertView ?: run {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.inflate(android.R.layout.simple_list_item_2, null)
      }).apply {
        findViewById<TextView>(android.R.id.text1).text = classes[position].simpleName
        findViewById<TextView>(android.R.id.text2).setText(DESCRIPTION_IDS[position])
      }
    }
  }

  companion object {

    /** Tag for the [Log].  */
    private const val TAG = "ChooserActivity"

    private const val PERMISSION_REQUESTS = 1

    private val CLASSES = arrayOf<Class<*>>(
            CameraActivity::class.java,
            StillImageActivity::class.java
    )

    private val DESCRIPTION_IDS = intArrayOf(
            R.string.desc_camera_source_activity,
            R.string.desc_still_image_activity)

  }
}
