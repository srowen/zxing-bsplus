/*
 * Copyright (C) 2010 ZXing authors
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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.RenderableLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.ScalingResultPointCallback;
import com.google.zxing.common.advanced.rowedge.RowEdgeDetectorBinarizer;
import com.srowen.bs.android.simple.R;
import com.google.zxing.common.HybridBinarizer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class DecodeHandler extends Handler {

  private static final String TAG = DecodeHandler.class.getSimpleName();

  public static final String BARCODE_BITMAP = "barcode_bitmap";
  public static final String BARCODE_SCALED_FACTOR = "barcode_scaled_factor";

  private final CaptureActivity activity;
  private final Map<DecodeHintType,?> hints;
  private boolean running;
  private final MultiFormatReader multiFormatReader;
  private boolean enableEnhanced;
  private final ExecutorService enhancedDecodeExecutor;
  private final AtomicBoolean isEnhancedRunning;
  private final Pattern allowedContentPattern;

  DecodeHandler(CaptureActivity activity, Map<DecodeHintType,?> hints) {
    this.activity = activity;
    this.hints = hints;
    running = true;
    multiFormatReader = new MultiFormatReader();
    multiFormatReader.setHints(hints);
    enableEnhanced = true;
    enhancedDecodeExecutor = Executors.newSingleThreadExecutor();
    isEnhancedRunning = new AtomicBoolean(false);
    allowedContentPattern = parseAllowedContentPattern(activity);
  }

  private static Pattern parseAllowedContentPattern(Context context) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    String patternString = prefs.getString(PreferencesActivity.KEY_ALLOWED_CONTENT_PATTERN, null);
    if (patternString != null && !patternString.isEmpty()) {
      try {
        return Pattern.compile(patternString);
      } catch (PatternSyntaxException pse) {
        // continue
        Log.w(TAG, "Bad allowed content pattern: " + patternString);
      }
    }
    return null;
  }

  @Override
  public void handleMessage(Message message) {
    if (running) {
      switch (message.what) {
        case R.id.decode:
          RenderableLuminanceSource source = null;
          byte[] data = (byte[]) message.obj;
          if (data != null) {
            source = activity.getCameraManager().buildLuminanceSource(data);
          }
          decode(source);
          break;
        case R.id.decode_image:
          Bitmap bitmap = (Bitmap) message.obj;
          int width = bitmap.getWidth();
          int height = bitmap.getHeight();
          int[] pixels = new int[width * height];
          bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
          decodeLocalImage(new RGBLuminanceSource(width, height, pixels));
          break;
        case R.id.quit:
          running = false;
          enhancedDecodeExecutor.shutdown();
          Looper.myLooper().quit();
          break;
      }
    }
  }

  /**
   * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
   * reuse the same reader objects from one decode to the next.
   *
   * @param source The YUV preview frame or other image data
   */
  private void decode(RenderableLuminanceSource source) {
    Handler handler = activity.getHandler();
    // Dummy message; keep cycle going anyway with a failed message
    if (source == null) {
      if (handler != null) {
        Message message = Message.obtain(handler, R.id.decode_failed);
        message.sendToTarget();
      }
      return;
    }

    if (enableEnhanced && !isEnhancedRunning.get()) {
      enhancedDecodeExecutor.submit(new EnhancedDecodeRunnable(source));
    }

    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
    Result rawResult = null;
    try {
      try {
        rawResult = multiFormatReader.decodeWithState(bitmap);
      } catch (ReaderException ignored) {
        rawResult = null;
      } finally {
        multiFormatReader.reset();
      }
    } catch (Exception e) {
      Log.e(TAG, "Unexpected exception in decoding", e);
    }

    if (rawResult != null && allowedContentPattern != null) {
      String text = rawResult.getText();
      if (text != null && !allowedContentPattern.matcher(text).matches()) {
        Log.i(TAG, "Found result " + text + " but it does not match allowed content pattern");
        rawResult = null;
      }
    }

    if (rawResult == null) {
      if (handler != null) {
        Message message = Message.obtain(handler, R.id.decode_failed);
        message.sendToTarget();
      }
    } else {
      if (handler != null) {
        Message message = Message.obtain(handler, R.id.decode_succeeded, rawResult);
        Bundle bundle = new Bundle();
        bundleThumbnail(source, bundle);
        message.setData(bundle);
        message.sendToTarget();
      }
    }
  }

  private static void bundleThumbnail(RenderableLuminanceSource source, Bundle bundle) {
    int[] pixels = source.renderThumbnail();
    int width = source.getThumbnailWidth();
    int height = source.getThumbnailHeight();
    Bitmap bitmap = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream out = new ByteArrayOutputStream();    
    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
    bundle.putByteArray(BARCODE_BITMAP, out.toByteArray());
    bundle.putFloat(BARCODE_SCALED_FACTOR, (float) width / source.getWidth());
  }

  /**
   * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
   * reuse the same reader objects from one decode to the next.
   *
   * @param source The YUV preview frame or other image data
   */
  private void decodeLocalImage(RenderableLuminanceSource source) {

    Handler handler = activity.getHandler();

    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
    Result rawResult = null;
    try {
      try {
        rawResult = multiFormatReader.decodeWithState(bitmap);
      } catch (ReaderException ignored) {
        rawResult = null;
      } finally {
        multiFormatReader.reset();
      }
    } catch (Exception e) {
      Log.e(TAG, "Unexpected exception in decoding", e);
    }
    
    if (rawResult == null) {
      RowEdgeDetectorBinarizer binarizer = new RowEdgeDetectorBinarizer(source);
      //boolean tryHarder = hints != null && hints.containsKey(DecodeHintType.TRY_HARDER);
      //binarizer.setTryHarder(tryHarder);
      bitmap = new BinaryBitmap(binarizer);
      try {
        rawResult = multiFormatReader.decodeWithState(bitmap);
      } catch (ReaderException ignored) {
        rawResult = null;
      } finally {
        multiFormatReader.reset();
      }
      if (rawResult != null) {
        unzoomResultPoints(rawResult, binarizer);
      }
    }

    if (rawResult != null && allowedContentPattern != null) {
      String text = rawResult.getText();
      if (text != null && !allowedContentPattern.matcher(text).matches()) {
        Log.i(TAG, "Found result " + text + " but it does not match allowed content pattern");
        rawResult = null;
      }
    }

    if (rawResult == null) {
      if (handler != null) {
        Message message = Message.obtain(handler, R.id.decode_failed);
        message.sendToTarget();
      }
      activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          Toast.makeText(activity.getApplicationContext(), R.string.msg_decode_image_failed, Toast.LENGTH_SHORT).show();
        }
      });
    } else {
      if (handler != null) {
        Message message = Message.obtain(handler, R.id.decode_succeeded, rawResult);
        Bundle bundle = new Bundle();
        bundleThumbnail(source, bundle);
        message.setData(bundle);
        message.sendToTarget();
      }
    }
  }

  private static void unzoomResultPoints(Result rawResult, RowEdgeDetectorBinarizer binarizer) {
    // A little hacky: undoing the zoom from this particular binarizer
    ResultPoint[] resultPoints = rawResult.getResultPoints();
    if (resultPoints != null) {
      int zoomFactor = binarizer.getZoomFactor();
      for (int i = 0; i < resultPoints.length; i++) {
        ResultPoint oldValue = resultPoints[i];
        resultPoints[i] = new ResultPoint(oldValue.getX() / zoomFactor, oldValue.getY() / zoomFactor);
      }
    }
  }

  private final class EnhancedDecodeRunnable implements Runnable {

    private final MultiFormatReader enhancedMultiFormatReader;
    private final RenderableLuminanceSource source;

    private EnhancedDecodeRunnable(RenderableLuminanceSource source) {
      this.source = source;
      enhancedMultiFormatReader = new MultiFormatReader();
      
      Map<DecodeHintType,?> enhancedHints = hints;

      if (enhancedHints != null) {
        if (enhancedHints.containsKey(DecodeHintType.POSSIBLE_FORMATS)) {
          Map<DecodeHintType,Object> newHints = new EnumMap<>(enhancedHints);
          @SuppressWarnings("unchecked")
          Collection<BarcodeFormat> possibleFormats =
              (Collection<BarcodeFormat>) newHints.get(DecodeHintType.POSSIBLE_FORMATS);
          Collection<BarcodeFormat> newFormats = EnumSet.copyOf(possibleFormats);
          newFormats.remove(BarcodeFormat.QR_CODE);
          newFormats.remove(BarcodeFormat.DATA_MATRIX);
          newFormats.remove(BarcodeFormat.AZTEC);
          newFormats.remove(BarcodeFormat.PDF_417);
          newHints.put(DecodeHintType.POSSIBLE_FORMATS, newFormats);
          enhancedHints = newHints;
        }
        if (enhancedHints.containsKey(DecodeHintType.NEED_RESULT_POINT_CALLBACK)) {
          Map<DecodeHintType,Object> newHints = new EnumMap<>(enhancedHints);
          ResultPointCallback callback =
              (ResultPointCallback) newHints.get(DecodeHintType.NEED_RESULT_POINT_CALLBACK);
          newHints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK,
                       new ScalingResultPointCallback(callback, RowEdgeDetectorBinarizer.DEFAULT_SCALE));
          enhancedHints = newHints;
        }
      }
      
      enhancedMultiFormatReader.setHints(enhancedHints);
    }

    @Override
    public void run() {

      isEnhancedRunning.set(true);
      try {

        long start = System.currentTimeMillis();

        RowEdgeDetectorBinarizer binarizer = new RowEdgeDetectorBinarizer(source);
        //boolean tryHarder = hints != null && hints.containsKey(DecodeHintType.TRY_HARDER);
        //binarizer.setTryHarder(tryHarder);
        BinaryBitmap bitmap = new BinaryBitmap(binarizer);
        Result result;
        try {
          result = enhancedMultiFormatReader.decodeWithState(bitmap);
        } catch (ReaderException ignored) {
          long end = System.currentTimeMillis();
          if ((end - start) >= 1500) {
            Log.i(TAG, "Disabling enhanced decoding; took " + (end - start) + "ms last cycle");
            enableEnhanced = false;
          }
          return;
        } finally {
          enhancedMultiFormatReader.reset();
        }

        if (allowedContentPattern != null) {
          String text = result.getText();
          if (text != null && !allowedContentPattern.matcher(text).matches()) {
            Log.i(TAG, "Found result " + text + " but it does not match allowed content pattern");
            return;
          }
        }

        // A little hacky: undoing the zoom from this particular binarizer
        unzoomResultPoints(result, binarizer);

        Message message = Message.obtain(activity.getHandler(), R.id.decode_succeeded, result);
        Bundle bundle = new Bundle();
        bundleThumbnail(source, bundle);
        message.setData(bundle);
        message.sendToTarget();

      } finally {
        isEnhancedRunning.set(false);
      }

    }

  }
}
