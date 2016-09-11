/*
 * Copyright (C) 2008 ZXing authors
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

package com.srowen.bs.android.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.srowen.bs.android.PreferencesActivity;
import com.srowen.bs.android.camera.open.CameraFacing;
import com.srowen.bs.android.camera.open.OpenCamera;
import com.srowen.bs.android.camera.open.OpenCameraInterface;

import java.io.IOException;

/**
 * This object wraps the Camera service object and expects to be the only one talking to it. The
 * implementation encapsulates the steps needed to take preview-sized images, which are used for
 * both preview and decoding.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class CameraManager {

  private static final String TAG = CameraManager.class.getSimpleName();

  private static final byte[] EMPTY = new byte[0];

  private final Context context;
  private final CameraConfigurationManager configManager;
  private OpenCamera camera;
  private AutoFocusManager autoFocusManager;
  private Rect framingRect;
  private Rect framingRectInPreview;
  private boolean initialized;
  private boolean previewing;
  private byte[] destDataCache;
  /**
   * Preview frames are delivered here, which we pass on to the registered handler. Make sure to
   * clear the handler so it will only receive one message.
   */
  private final PreviewCallback previewCallback;
  private byte[] previewBuffer;

  /**
   * Initializes this object with the {@link Context} of the calling Activity.
   *
   * @param context The Activity which wants to use the camera.
   */
  public CameraManager(Context context) {
    this.context = context;
    this.configManager = new CameraConfigurationManager(context);
    destDataCache = EMPTY;
    previewCallback = new PreviewCallback();
    previewBuffer = destDataCache;
  }

  /**
   * Opens the camera driver and initializes the hardware parameters.
   *
   * @param holder The surface object which the camera will draw preview frames into.
   * @throws IOException Indicates the camera driver failed to open.
   */
  public synchronized void openDriver(SurfaceHolder holder) throws IOException {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    CameraFacing desiredFacing =
        prefs.getBoolean(PreferencesActivity.KEY_FRONT_CAMERA, false) ? CameraFacing.FRONT : CameraFacing.BACK;

    OpenCamera theCamera = camera;
    if (theCamera == null) {
      theCamera = OpenCameraInterface.open(desiredFacing);
      if (theCamera == null) {
        throw new IOException("Camera.open() failed to return object from driver");
      }
      camera = theCamera;
      if (theCamera.getFacing() != desiredFacing) {
        boolean newFrontCameraPref = desiredFacing != CameraFacing.FRONT;
        SharedPreferences.Editor editor =
            prefs.edit().putBoolean(PreferencesActivity.KEY_FRONT_CAMERA, newFrontCameraPref);
        editor.commit();
      }
    }
    if (!initialized) {
      initialized = true;
      configManager.initFromCameraParameters(theCamera);
    }

    Camera cameraObject = theCamera.getCamera();
    Camera.Parameters parameters = cameraObject.getParameters();
    String parametersFlattened = parameters == null ? null : parameters.flatten(); // Save these, temporarily
    try {
      configManager.setDesiredCameraParameters(theCamera, false);
    } catch (RuntimeException re) {
      // Driver failed
      Log.w(TAG, "Camera rejected parameters. Setting only minimal safe-mode parameters");
      Log.i(TAG, "Resetting to saved camera params: " + parametersFlattened);
      // Reset:
      if (parametersFlattened != null) {
        parameters = cameraObject.getParameters();
        parameters.unflatten(parametersFlattened);
        try {
          cameraObject.setParameters(parameters);
          configManager.setDesiredCameraParameters(theCamera, true);
        } catch (RuntimeException re2) {
          // Well, darn. Give up
          Log.w(TAG, "Camera rejected even safe-mode parameters! No configuration");
        }
      }
    }
    cameraObject.setPreviewDisplay(holder);

    cameraObject.setPreviewCallbackWithBuffer(previewCallback);
    Camera.Parameters cameraParameters = cameraObject.getParameters();
    Camera.Size previewSize = cameraParameters.getPreviewSize();
    int bitsPerPixel = ImageFormat.getBitsPerPixel(cameraParameters.getPreviewFormat());
    previewBuffer = new byte[(previewSize.height * previewSize.width * bitsPerPixel) / 8];
  }

  public synchronized boolean isOpen() {
    return camera != null;
  }

  /**
   * Closes the camera driver if still in use.
   */
  public synchronized void closeDriver() {
    if (camera != null) {
      camera.getCamera().release();
      camera = null;

      // Make sure to clear these each time we close the camera, so that any scanning rect
      // requested by intent is forgotten.
      framingRect = null;
      framingRectInPreview = null;
      previewBuffer = null;
    }
  }

  /**
   * Asks the camera hardware to begin drawing preview frames to the screen.
   */
  public synchronized void startPreview() {
    OpenCamera theCamera = camera;
    if (theCamera != null && !previewing) {
      theCamera.getCamera().startPreview();
      previewing = true;
      autoFocusManager = new AutoFocusManager(context, theCamera.getCamera());
    }
  }

  /**
   * Tells the camera to stop drawing preview frames.
   */
  public synchronized void stopPreview() {
    if (autoFocusManager != null) {
      autoFocusManager.stop();
      autoFocusManager = null;
    }
    if (camera != null && previewing) {
      camera.getCamera().stopPreview();
      previewCallback.setHandler(null, 0);
      previewing = false;
    }
  }

  /**
   * Convenience method for {@link com.srowen.bs.android.CaptureActivity}
   *
   * @param newSetting if {@code true}, light should be turned on if currently off. And vice versa.
   */
  public synchronized void setTorch(boolean newSetting) {
    OpenCamera theCamera = camera;
    if (theCamera != null) {
      if (newSetting != configManager.getTorchState(theCamera.getCamera())) {
        if (autoFocusManager != null) {
          autoFocusManager.stop();
        }
        configManager.setTorch(theCamera.getCamera(), newSetting);
        if (autoFocusManager != null) {
          autoFocusManager.start();
        }
      }
    }
  }

  /**
   * A single preview frame will be returned to the handler supplied. The data will arrive as byte[]
   * in the message.obj field, with width and height encoded as message.arg1 and message.arg2,
   * respectively.
   *
   * @param handler The handler to send the message to.
   * @param message The what field of the message to be sent.
   */
  public synchronized void requestPreviewFrame(Handler handler, int message) {
    OpenCamera theCamera = camera;
    if (theCamera != null && previewing) {
      previewCallback.setHandler(handler, message);
      theCamera.getCamera().addCallbackBuffer(previewBuffer);
    }
  }

  /**
   * Calculates the framing rect which the UI should draw to show the user where to place the
   * barcode. This target helps with alignment as well as forces the user to hold the device
   * far enough away to ensure the image will be in focus.
   *
   * @return The rectangle to draw on screen in window coordinates.
   */
  public synchronized Rect getFramingRect() {
    if (framingRect == null) {
      if (camera == null) {
        return null;
      }
      Point screenResolution = configManager.getScreenResolution();
      if (screenResolution == null) {
        // Called early, before init even finished
        return null;
      }

      // Establish base dimension as 3/4 of smaller screen dimension.
      // Maximum of 900 and minimum of 240.
      int dimension = Math.min(screenResolution.x, screenResolution.y);
      if (dimension > 1200) {
        dimension = 900;
      } else if (dimension > 320) {
        dimension = dimension * 3 / 4;
      } else if (dimension > 240) {
        dimension = 240;
      }

      // 4:3 aspect ratio in reticle
      int wideDimension = 4 * dimension / 3;

      int leftOffset = (screenResolution.x - wideDimension) / 2;
      if (leftOffset < 0) {
        leftOffset = 0;
      }
      int topOffset = (screenResolution.y - dimension) / 2;
      if (topOffset < 0) {
        topOffset = 0;
      }

      framingRect = new Rect(leftOffset, topOffset, leftOffset + wideDimension, topOffset + dimension);
      Log.i(TAG, "Calculated framing rect: " + framingRect);
    }
    return framingRect;
  }

  /**
   * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
   * not UI / screen.
   *
   * @return {@link Rect} expressing barcode scan area in terms of the preview size
   */
  public synchronized Rect getFramingRectInPreview() {
    if (framingRectInPreview == null) {
      Rect framingRect = getFramingRect();
      if (framingRect == null) {
        return null;
      }
      Rect rect = new Rect(framingRect);
      Point cameraResolution = configManager.getPreviewSizeOnScreen();
      Point screenResolution = configManager.getScreenResolution();
      if (cameraResolution == null || screenResolution == null) {
        // Called early, before init even finished
        return null;
      }
      rect.left = rect.left * cameraResolution.x / screenResolution.x;
      rect.right = rect.right * cameraResolution.x / screenResolution.x;
      rect.top = rect.top * cameraResolution.y / screenResolution.y;
      rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
      framingRectInPreview = rect;
      Log.i(TAG, "Calculated framing rect in preview: " + framingRectInPreview);
    }
    return framingRectInPreview;
  }

  public synchronized PlanarYUVLuminanceSource buildLuminanceSource(byte[] data) {
    Rect rectInPreview = getFramingRectInPreview();
    Point configuredPreviewSize = configManager.getBestPreviewSize();
    Point screenResolution = configManager.getScreenResolution();
    if (rectInPreview == null || configuredPreviewSize == null || screenResolution == null) {
      return null;
    }

    boolean isScreenPortrait = screenResolution.x < screenResolution.y;
    boolean isPreviewSizePortrait = configuredPreviewSize.x < configuredPreviewSize.y;

    int cwNeededRotation = configManager.getCWNeededRotation();
    boolean isScreenAlignedWithCameraData = cwNeededRotation % 180 == 0;

    int dataWidth;
    int dataHeight;
    if (isScreenAlignedWithCameraData == (isScreenPortrait == isPreviewSizePortrait)) {
      dataWidth = configuredPreviewSize.x;
      dataHeight = configuredPreviewSize.y;
    } else {
      dataWidth = configuredPreviewSize.y;
      dataHeight = configuredPreviewSize.x;
    }

    Rect rectInData;
    if (isScreenAlignedWithCameraData) {
      rectInData = rectInPreview;
    } else {
      rectInData = new Rect(rectInPreview.top,
                            dataHeight - rectInPreview.right,
                            rectInPreview.bottom,
                            dataHeight - rectInPreview.left);
    }

    switch (cwNeededRotation) {
      case 0:
        // no rotation
        return new PlanarYUVLuminanceSource(data,
                                            dataWidth,
                                            dataHeight,
                                            rectInData.left,
                                            rectInData.top,
                                            rectInData.width(),
                                            rectInData.height(),
                                            false);
      case 90:
        return rotate90(data, dataWidth, rectInData);
      case 180:
        return rotate180(data, dataWidth, rectInData);
      case 270:
        return rotate270(data, dataWidth, rectInData);
      default:
        throw new IllegalArgumentException("Bad rotation: " + cwNeededRotation);
    }
  }

  private synchronized PlanarYUVLuminanceSource rotate90(byte[] data, int dataWidth, Rect rect) {
    int sourceWidth = rect.width();
    int sourceHeight = rect.height();
    int desiredSize = sourceHeight * sourceWidth;
    if (destDataCache.length != desiredSize) {
      destDataCache = new byte[desiredSize];
    }
    byte[] destData = destDataCache;
    int destOffset = 0;
    for (int sourceX = 0; sourceX < sourceWidth; sourceX++) {
      int sourceOffset = (rect.bottom - 1) * dataWidth + rect.left + sourceX;
      for (int sourceY = sourceHeight - 1; sourceY >= 0; sourceY--) {
        destData[destOffset++] = data[sourceOffset];
        sourceOffset -= dataWidth;
      }
    }
    return new PlanarYUVLuminanceSource(destData,
                                        sourceHeight,
                                        sourceWidth,
                                        0,
                                        0,
                                        sourceHeight,
                                        sourceWidth,
                                        false);
  }

  private synchronized PlanarYUVLuminanceSource rotate180(byte[] data, int dataWidth, Rect rect) {
    int sourceWidth = rect.width();
    int sourceHeight = rect.height();
    int desiredSize = sourceHeight * sourceWidth;
    if (destDataCache.length != desiredSize) {
      destDataCache = new byte[desiredSize];
    }
    byte[] destData = destDataCache;
    int destOffset = 0;

    for (int sourceY = sourceHeight - 1; sourceY >= 0; sourceY--) {
      int sourceOffset = rect.left + sourceWidth - 1 + (rect.top + sourceY) * dataWidth;
      for (int sourceX = sourceWidth - 1; sourceX >= 0; sourceX--) {
        destData[destOffset++] = data[sourceOffset--];
      }
    }
    return new PlanarYUVLuminanceSource(destData,
                                        sourceWidth,
                                        sourceHeight,
                                        0,
                                        0,
                                        sourceWidth,
                                        sourceHeight,
                                        false);
  }

  private synchronized PlanarYUVLuminanceSource rotate270(byte[] data, int dataWidth, Rect rect) {
    int sourceWidth = rect.width();
    int sourceHeight = rect.height();
    int desiredSize = sourceHeight * sourceWidth;
    if (destDataCache.length != desiredSize) {
      destDataCache = new byte[desiredSize];
    }
    byte[] destData = destDataCache;
    int destOffset = 0;
    for (int sourceX = sourceWidth - 1; sourceX >= 0; sourceX--) {
      int sourceOffset = rect.left + sourceX + rect.top * dataWidth;
      for (int sourceY = 0; sourceY < sourceHeight; sourceY++) {
        destData[destOffset++] = data[sourceOffset];
        sourceOffset += dataWidth;
      }
    }
    return new PlanarYUVLuminanceSource(destData,
                                        sourceHeight,
                                        sourceWidth,
                                        0,
                                        0,
                                        sourceHeight,
                                        sourceWidth,
                                        false);
  }

}
