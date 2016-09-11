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

package com.srowen.bs.android;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.preference.PreferenceManager;
import com.google.zxing.ResultPoint;
import com.srowen.bs.android.camera.CameraManager;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This view is overlaid on top of the camera preview. It adds the viewfinder rectangle and partial
 * transparency outside it, as well as the laser scanner animation and result points.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class ViewfinderView extends View {

  private static final int RESULT_POINT_GENERATIONS = 4;
  private static final long ANIMATION_DELAY = 400L / RESULT_POINT_GENERATIONS;
  private static final int CURRENT_POINT_OPACITY = 0xA0;
  private static final int MAX_RESULT_POINTS = 50;
  private static final int MAX_SCANNER_ALPHA = 0x40;
  private static final int[] SCANNER_ALPHA;
  static {
    SCANNER_ALPHA = new int[32];
    for (int i = 0; i < SCANNER_ALPHA.length; i++) {
      SCANNER_ALPHA[i] = (int) (MAX_SCANNER_ALPHA * (1.0 + Math.cos(i * 2.0 * Math.PI / SCANNER_ALPHA.length)) / 2.0);
    }
  }
  private static final int POINT_SIZE = 6;

  private CameraManager cameraManager;
  private final Paint paint;
  private Bitmap resultBitmap;
  private int scannerAlpha;
  private Map<ResultPoint,String> currentResultPoints;
  private final List<Map<ResultPoint,String>> oldResultPoints;
  private Rect frame;
  private final Bitmap qrBitmap;
  private Rect qrRect;
  private final Bitmap upcBitmap;
  private Rect upcRect;
  private final Bitmap pdf417Bitmap;
  private Rect pdf417Rect;
  private boolean isAnyOneDFormatSelected;
  private boolean isAnyTwoDFormatSelected;
  private boolean isPDF417Selected;

  // This constructor is used when the class is built from an XML resource.
  public ViewfinderView(Context context, AttributeSet attrs) {
    super(context, attrs);

    // Initialize these once for performance rather than calling them every time in onDraw().
    paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.DEFAULT);
    paint.setSubpixelText(true);
    paint.setTextSize(POINT_SIZE * 4.0f);
    Resources resources = getResources();
    paint.setColor(resources.getColor(R.color.possible_result_points));
    scannerAlpha = 0;
    currentResultPoints = new LinkedHashMap<>(5);
    oldResultPoints = new ArrayList<>(RESULT_POINT_GENERATIONS);
    qrBitmap = BitmapFactory.decodeResource(resources, R.drawable.cyan_qr);
    upcBitmap = BitmapFactory.decodeResource(resources, R.drawable.magenta_upc);
    pdf417Bitmap = BitmapFactory.decodeResource(resources, R.drawable.green_pdf417);

    PreferenceManager.setDefaultValues(context, R.xml.preferences, false);
  }

  void onResume() {
    isAnyOneDFormatSelected = isAnyFormatSelected(PreferencesActivity.ONE_D_FORMAT_KEYS);
    isAnyTwoDFormatSelected = isAnyFormatSelected(PreferencesActivity.TWO_D_FORMAT_KEYS);
    isPDF417Selected = isAnyFormatSelected(PreferencesActivity.KEY_DECODE_PDF417);
  }

  void onDestroy() {
    qrBitmap.recycle();
    upcBitmap.recycle();
    pdf417Bitmap.recycle();
  }

  public void setCameraManager(CameraManager cameraManager) {
    this.cameraManager = cameraManager;
  }

  @SuppressLint("DrawAllocation")
  @Override
  public void onDraw(Canvas canvas) {
    if (cameraManager == null) {
      return; // not ready yet, early draw before done configuring
    }
    Rect theFrame = frame;
    if (theFrame == null) {
      theFrame = cameraManager.getFramingRect();
      if (theFrame == null) {
        return;
      }
      frame = theFrame;
      qrRect = fitRectInRect(qrBitmap, theFrame);
      upcRect = fitRectInRect(upcBitmap, theFrame);
      pdf417Rect = fitRectInRect(pdf417Bitmap, theFrame);
    }

    if (isAnyTwoDFormatSelected) {
      paint.setAlpha(SCANNER_ALPHA[scannerAlpha]);
      canvas.drawBitmap(qrBitmap, null, qrRect, paint);
    }
    if (isAnyOneDFormatSelected) {
      int alphaOffset = (scannerAlpha + SCANNER_ALPHA.length / 2) % SCANNER_ALPHA.length;
      paint.setAlpha(SCANNER_ALPHA[alphaOffset]);
      canvas.drawBitmap(upcBitmap, null, upcRect, paint);
    }
    if (isPDF417Selected) {
      int alphaOffset = (scannerAlpha + 3 * SCANNER_ALPHA.length / 4) % SCANNER_ALPHA.length;
      paint.setAlpha(SCANNER_ALPHA[alphaOffset]);
      canvas.drawBitmap(pdf417Bitmap, null, pdf417Rect, paint);
    }
    scannerAlpha = (scannerAlpha + 1) % SCANNER_ALPHA.length;

    if (resultBitmap != null) {
      // Draw the opaque result bitmap over the scanning rectangle
      paint.setAlpha(CURRENT_POINT_OPACITY);
      canvas.drawBitmap(resultBitmap, null, frame, paint);
    } else {

      Rect previewFrame = cameraManager.getFramingRectInPreview();
      if (previewFrame == null) {
        return;
      }
      
      float scaleX = theFrame.width() / (float) previewFrame.width();
      float scaleY = theFrame.height() / (float) previewFrame.height();

      int frameLeft = theFrame.left;
      int frameTop = theFrame.top;
      
      if (oldResultPoints.size() == RESULT_POINT_GENERATIONS) {
        oldResultPoints.remove(RESULT_POINT_GENERATIONS - 1);
      }
      oldResultPoints.add(0, currentResultPoints);
      currentResultPoints = new LinkedHashMap<>(5);

      for (int i = 0; i < oldResultPoints.size(); i++) {
        Map<ResultPoint, String> generation = oldResultPoints.get(i);
        int factor = i + 1;
        float radius = (float) POINT_SIZE / factor;
        paint.setAlpha(CURRENT_POINT_OPACITY / factor);
        synchronized (generation) {
          for (Map.Entry<ResultPoint, String> entry : generation.entrySet()) {
            ResultPoint point = entry.getKey();
            String fragment = entry.getValue();
            float centerX = frameLeft + (int) (point.getX() * scaleX);
            float centerY = frameTop + (int) (point.getY() * scaleY);
            if (fragment == null) {
              canvas.drawCircle(centerX, centerY, radius, paint);
            } else {
              canvas.drawText(fragment, centerX, centerY, paint);
            }
          }
        }
      }

      postInvalidateDelayed(ANIMATION_DELAY,
                            theFrame.left - POINT_SIZE,
                            theFrame.top - POINT_SIZE,
                            theFrame.right + POINT_SIZE,
                            theFrame.bottom + POINT_SIZE);
    }
  }

  private static Rect fitRectInRect(Bitmap image, Rect into) {
    int width = image.getWidth();
    int height = image.getHeight();
    if (width > height) {
      int rectWidth = into.width();
      int rectHeight = rectWidth * height / width;
      int topBottomMargin = (into.height() - rectHeight) / 2;
      return new Rect(into.left, into.top + topBottomMargin, into.right, into.bottom - topBottomMargin);
    } else {
      int rectHeight = into.height();
      int rectWidth = rectHeight * width / height;
      int leftRightMargin = (into.width() - rectWidth) / 2;
      return new Rect(into.left + leftRightMargin, into.top, into.right - leftRightMargin, into.bottom);
    }
  }

  public void drawViewfinder() {
    Bitmap resultBitmap = this.resultBitmap;
    this.resultBitmap = null;
    if (resultBitmap != null) {
      resultBitmap.recycle();
    }
    invalidate();
  }

  /**
   * Draw a bitmap with the result points highlighted instead of the live scanning display.
   *
   * @param barcode An image of the decoded barcode.
   */
  public void drawResultBitmap(Bitmap barcode) {
    resultBitmap = barcode;
    invalidate();
  }

  public void addPossibleResultPoint(ResultPoint point, String fragment) {
    Map<ResultPoint,String> points = currentResultPoints;
    synchronized (points) {
      points.put(point, fragment);
      int size = points.size();
      if (size > MAX_RESULT_POINTS) {
        // trim it
        Iterator<?> it = points.entrySet().iterator();
        for (int i = MAX_RESULT_POINTS / 2; i >= 0; i--) {
          it.next();
          it.remove();
        }
      }
    }
  }

  private boolean isAnyFormatSelected(String... formatKeys) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
    for (String formatKey : formatKeys) {
      if (prefs.getBoolean(formatKey, false)) {
        return true;
      }
    }
    return false;
  }

}
