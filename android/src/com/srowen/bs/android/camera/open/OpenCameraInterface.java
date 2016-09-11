/*
 * Copyright (C) 2012 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.srowen.bs.android.camera.open;

import android.hardware.Camera;
import android.util.Log;

/**
 * Abstraction over the {@link Camera} API that helps open them and return their metadata.
 */
public final class OpenCameraInterface {

  private static final String TAG = OpenCameraInterface.class.getName();

  private OpenCameraInterface() {
  }

  /**
   * Opens a rear-facing camera with {@link Camera#open(int)}, if one exists, or opens camera 0.
   *
   * @param desiredFacing orientation of camera to open
   * @return handle to {@link Camera} that was opened
   */
  public static OpenCamera open(CameraFacing desiredFacing) {

    int numCameras = Camera.getNumberOfCameras();
    if (numCameras == 0) {
      Log.w(TAG, "No cameras!");
      return null;
    }

    Camera.CameraInfo selectedCameraInfo = null;
    int index = 0;
    while (index < numCameras) {
      Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
      Camera.getCameraInfo(index, cameraInfo);
      CameraFacing reportedFacing = CameraFacing.values()[cameraInfo.facing];
      if (reportedFacing == desiredFacing) {
        selectedCameraInfo = cameraInfo;
        break;
      }
      index++;
    }

    Camera camera;
    if (index < numCameras) {
      Log.i(TAG, "Opening camera #" + index);
      camera = Camera.open(index);
    } else {
      Log.i(TAG, "No camera facing " + desiredFacing + "; returning camera #0");
      camera = Camera.open(0);
      selectedCameraInfo = new Camera.CameraInfo();
      Camera.getCameraInfo(0, selectedCameraInfo);
    }

    if (camera == null) {
      return null;
    }
    return new OpenCamera(index,
                          camera,
                          CameraFacing.values()[selectedCameraInfo.facing],
                          selectedCameraInfo.orientation);
  }

}
