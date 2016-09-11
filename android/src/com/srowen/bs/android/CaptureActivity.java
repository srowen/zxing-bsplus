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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.srowen.bs.android.camera.CameraManager;
import com.srowen.bs.android.clipboard.ClipboardInterface;
import com.srowen.bs.android.history.HistoryActivity;
import com.srowen.bs.android.history.HistoryItem;
import com.srowen.bs.android.history.HistoryManager;
import com.srowen.bs.android.nfc.NFCInterface;
import com.srowen.bs.android.result.ResultHandler;
import com.srowen.bs.android.result.ResultHandlerFactory;
import com.srowen.bs.android.result.supplement.SupplementalInfoRetriever;
import com.srowen.bs.android.share.ShareActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {

  private static final String TAG = CaptureActivity.class.getSimpleName();

  private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
  private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

  private static final String[] ZXING_URLS = { "http://zxing.appspot.com/scan", "zxing://scan/" };

  private static final int DECODE_RESOURCE_REQUEST_CODE = 0x0000ba4c;
  private static final int HISTORY_REQUEST_CODE = 0x0000bacc;

  private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
      EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                 ResultMetadataType.SUGGESTED_PRICE,
                 ResultMetadataType.ERROR_CORRECTION_LEVEL,
                 ResultMetadataType.POSSIBLE_COUNTRY);

  private CameraManager cameraManager;
  private CaptureActivityHandler handler;
  private Bitmap savedBitmapToDecode;
  private Result savedResultToShow;
  private ViewfinderView viewfinderView;
  private View resultView;
  private Result lastResult;
  private boolean hasSurface;
  private boolean copyToClipboard;
  private IntentSource source;
  private String sourceUrl;
  private ScanFromWebPageManager scanFromWebPageManager;
  private Collection<BarcodeFormat> decodeFormats;
  private Map<DecodeHintType,?> decodeHints;
  private String characterSet;
  private HistoryManager historyManager;
  private InactivityTimer inactivityTimer;
  private BeepManager beepManager;
  private AmbientLightManager ambientLightManager;

  ViewfinderView getViewfinderView() {
    return viewfinderView;
  }

  public Handler getHandler() {
    return handler;
  }

  CameraManager getCameraManager() {
    return cameraManager;
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.capture);

    hasSurface = false;
    inactivityTimer = new InactivityTimer(this);
    beepManager = new BeepManager(this);
    ambientLightManager = new AmbientLightManager(this);

    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
  }

  @Override
  protected void onResume() {
    super.onResume();

    // historyManager must be initialized here to update the history preference
    historyManager = new HistoryManager(this);
    historyManager.trimHistory();

    // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
    // want to open the camera driver and measure the screen size if we're going to show the help on
    // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
    // off screen.
    cameraManager = new CameraManager(getApplication());

    viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
    viewfinderView.setCameraManager(cameraManager);
    viewfinderView.onResume();

    resultView = findViewById(R.id.result_view);

    handler = null;
    lastResult = null;

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

    if (prefs.getBoolean(PreferencesActivity.KEY_DISABLE_AUTO_ORIENTATION, true)) {
      setRequestedOrientation(getCurrentOrientation());
    } else {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
    }

    resetStatusView();

    SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    SurfaceHolder surfaceHolder = surfaceView.getHolder();
    if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
      initCamera(surfaceHolder);
    } else {
      // Install the callback and wait for surfaceCreated() to init the camera.
      surfaceHolder.addCallback(this);
    }

    beepManager.updatePrefs();
    ambientLightManager.start(cameraManager);

    inactivityTimer.onResume();

    Intent intent = getIntent();

    copyToClipboard = prefs.getBoolean(PreferencesActivity.KEY_COPY_TO_CLIPBOARD, true)
        && (intent == null || intent.getBooleanExtra(Intents.Scan.SAVE_HISTORY, true));

    source = IntentSource.NONE;
    sourceUrl = null;
    scanFromWebPageManager = null;
    decodeFormats = null;
    characterSet = null;

    NFCInterface.listenForNFC(this);

    if (intent != null) {
      handleIntent(intent);
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    setIntent(intent);
  }

  private void handleIntent(Intent intent) {
    String action = intent.getAction();
    String dataString = intent.getDataString();

    if (Intents.Scan.ACTION.equals(action)) {

      // Scan the formats the intent requested, and return the result to the calling activity.
      source = IntentSource.NATIVE_APP_INTENT;
      decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);
      decodeHints = DecodeHintManager.parseDecodeHints(intent);

    } else if (Intent.ACTION_SEND.equals(action) ||
               Intent.ACTION_SENDTO.equals(action) ||
               Intents.Decode.ACTION.equals(action)) {

      Uri uri = intent.getData();
      if (uri == null) {
        Bundle extras = intent.getExtras();
        if (extras != null) {
          uri = (Uri) extras.get(Intent.EXTRA_STREAM);
        }
      }
      if (uri != null) {
        decodeUri(uri);
      }

    } else if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
               NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_NFC, false)) {
        Bundle extras = intent.getExtras();
        if (extras != null) {
          String readablePayload = NFCInterface.parseNFCStringPayload(extras);
          handleDecode(new Result(readablePayload, null, null, BarcodeFormat.NFC));
        }
      }

    } else if (dataString != null &&
               dataString.contains("http://www.google") &&
               dataString.contains("/m/products/scan")) {

      // Scan only products and send the result to mobile Product Search.
      source = IntentSource.PRODUCT_SEARCH_LINK;
      sourceUrl = dataString;
      decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;

    } else if (isZXingURL(dataString)) {

      // Scan formats requested in query string (all formats if none specified).
      // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
      source = IntentSource.ZXING_LINK;
      sourceUrl = dataString;
      Uri inputUri = Uri.parse(dataString);
      scanFromWebPageManager = new ScanFromWebPageManager(inputUri);
      decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri);
      // Allow a sub-set of the hints to be specified by the caller.
      decodeHints = DecodeHintManager.parseDecodeHints(inputUri);

    }

    characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);
  }

  private int getCurrentOrientation() {
    int rotation = getWindowManager().getDefaultDisplay().getRotation();
    if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
      switch (rotation) {
        case android.view.Surface.ROTATION_0:
        case android.view.Surface.ROTATION_90:
          return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        default:
          return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
      }
    } else {
      switch (rotation) {
        case Surface.ROTATION_0:
        case Surface.ROTATION_270:
          return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        default:
          return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
      }
    }
  }
  
  private static boolean isZXingURL(String dataString) {
    if (dataString == null) {
      return false;
    }
    for (String url : ZXING_URLS) {
      if (dataString.startsWith(url)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void onPause() {
    if (handler != null) {
      handler.quitSynchronously();
      handler = null;
    }
    inactivityTimer.onPause();
    ambientLightManager.stop();
    beepManager.close();
    NFCInterface.unlistenForNFC(this);
    cameraManager.closeDriver();
    //historyManager = null; // Keep for onActivityResult
    if (!hasSurface) {
      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(this);
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    inactivityTimer.shutdown();
    ViewfinderView viewfinderView = this.viewfinderView;
    if (viewfinderView != null) {
      viewfinderView.onDestroy();
    }
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    switch (keyCode) {
      case KeyEvent.KEYCODE_BACK:
        if (source == IntentSource.NATIVE_APP_INTENT) {
          setResult(RESULT_CANCELED);
          finish();
          return true;
        }
        if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK) && lastResult != null) {
          restartPreviewAfterDelay(0L);
          return true;
        }
        break;
      case KeyEvent.KEYCODE_FOCUS:
      case KeyEvent.KEYCODE_CAMERA:
        // Handle these events so they don't launch the Camera app
        return true;
      // Use volume up/down to turn on light
      case KeyEvent.KEYCODE_VOLUME_DOWN:
        cameraManager.setTorch(false);
        return true;
      case KeyEvent.KEYCODE_VOLUME_UP:
        cameraManager.setTorch(true);
        return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater menuInflater = getMenuInflater();
    menuInflater.inflate(R.menu.capture, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
    switch (item.getItemId()) {
      case R.id.menu_share:
        intent.setClassName(this, ShareActivity.class.getName());
        startActivity(intent);
        break;
      case R.id.menu_history:
        intent.setClassName(this, HistoryActivity.class.getName());
        startActivityForResult(intent, HISTORY_REQUEST_CODE);
        break;
      case R.id.menu_decode_image:
        Intent getIntent = new Intent(Intent.ACTION_GET_CONTENT);
        getIntent.setType("image/*");
        Intent chooseIntent = Intent.createChooser(getIntent, getResources().getString(R.string.menu_decode_image));
        chooseIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        try {
          startActivityForResult(chooseIntent, DECODE_RESOURCE_REQUEST_CODE);
        } catch (ActivityNotFoundException anfe) {
          Log.w(TAG, anfe);
        }
        break;
      case R.id.menu_settings:
        intent.setClassName(this, PreferencesActivity.class.getName());
        startActivity(intent);
        break;
      case R.id.menu_help:
        intent.setClassName(this, HelpActivity.class.getName());
        startActivity(intent);
        break;
      default:
        return super.onOptionsItemSelected(item);
    }
    return true;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (resultCode == RESULT_OK) {
      switch (requestCode) {
        case DECODE_RESOURCE_REQUEST_CODE:
          Uri uri = intent.getData();
          if (uri != null) {
            decodeUri(uri);
          }
          break;
        case HISTORY_REQUEST_CODE:
          int itemNumber = intent.getIntExtra(Intents.History.ITEM_NUMBER, -1);
          if (itemNumber >= 0) {
            HistoryItem historyItem = historyManager.buildHistoryItem(itemNumber);
            decodeOrStoreSavedBitmap(null, historyItem.getResult());
          }
          break;
      }
    }
  }

  private void decodeUri(Uri decodeUri) {
    Log.i(TAG, "Decoding image URI " + decodeUri);

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;

    ContentResolver resolver = getContentResolver();

    InputStream in = null;
    try {
      in = resolver.openInputStream(decodeUri);
      BitmapFactory.decodeStream(in, null, options);
    } catch (SecurityException | IOException e) {
      // Seen when sharing from GMail? Fails without READ_GMAIL permission
      Log.w(TAG, e);
      return;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
        }
      }
    }

    int height = options.outHeight;
    int width = options.outWidth;
    options.inJustDecodeBounds = false;
    options.inSampleSize = (int) Math.round(Math.sqrt(height * width / (double) (320 * 240)));

    in = null;
    Bitmap bitmap;
    try {
      in = resolver.openInputStream(decodeUri);
      bitmap = BitmapFactory.decodeStream(in, null, options);
    } catch (SecurityException | IOException e) {
      // Seen when sharing from GMail? Fails without READ_GMAIL permission
      Log.w(TAG, e);
      return;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
        }
      }
    }

    decodeOrStoreSavedBitmap(bitmap, null);
  }

  private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
    if (handler == null) {
      savedBitmapToDecode = bitmap;
      savedResultToShow = result;
    } else {
      if (bitmap != null) {
        savedBitmapToDecode = bitmap;
      }
      if (result != null) {
        savedResultToShow = result;
      }
      if (savedBitmapToDecode != null) {
        Message message = Message.obtain(handler, R.id.decode_image, savedBitmapToDecode);
        handler.sendMessage(message);
      } else if (savedResultToShow != null) {
        Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
        handler.sendMessage(message);
      }
      savedBitmapToDecode = null;
      savedResultToShow = null;
    }
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    if (!hasSurface) {
      hasSurface = true;
      initCamera(holder);
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    hasSurface = false;
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

  }

  private void handleDecode(Result result) {
    handleDecode(result, null, 1.0f, false);
  }

  /**
   * A valid barcode has been found, so give an indication of success and show the results.
   *
   * @param rawResult The contents of the barcode.
   * @param barcode   A greyscale bitmap of the camera data which was decoded.
   * @param scaleFactor amount by which thumbnail was scaled
   * @param fromHistory if true, scan was not new but from history
   */
  void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor, boolean fromHistory) {
    inactivityTimer.onActivity();
    lastResult = rawResult;
    ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);

    if (!fromHistory) {
      historyManager.addHistoryItem(rawResult, resultHandler);
      beepManager.playBeepSoundAndVibrate();
    }
    if (barcode != null) {
      drawResultPoints(barcode, scaleFactor, rawResult);
    }

    switch (source) {
      case NATIVE_APP_INTENT:
      case PRODUCT_SEARCH_LINK:
        handleDecodeExternally(rawResult, resultHandler, barcode);
        break;
      case ZXING_LINK:
        if (scanFromWebPageManager == null || !scanFromWebPageManager.isScanFromWebPage()) {
          handleDecodeInternally(rawResult, resultHandler, barcode);
        } else {
          handleDecodeExternally(rawResult, resultHandler, barcode);
        }
        break;
      case NONE:
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!fromHistory && prefs.getBoolean(PreferencesActivity.KEY_BULK_MODE, false)) {
          Toast.makeText(getApplicationContext(),
                         getResources().getString(R.string.msg_bulk_mode_scanned) + " (" + rawResult.getText() + ')',
                         Toast.LENGTH_SHORT).show();
          // Wait a moment or else it will scan the same barcode continuously about 3 times
          restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
        } else {
          handleDecodeInternally(rawResult, resultHandler, barcode);
        }
        break;
    }
  }

  /**
   * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
   *
   * @param barcode   A bitmap of the captured image.
   * @param rawResult The decoded results which contains the points to draw.
   */
  private void drawResultPoints(Bitmap barcode, float scaleFactor, Result rawResult) {
    ResultPoint[] points = rawResult.getResultPoints();
    if (points != null && points.length > 0) {
      Canvas canvas = new Canvas(barcode);
      Paint paint = new Paint();
      paint.setStrokeWidth(4.0f);
      paint.setColor(getResources().getColor(R.color.result_points));
      if (points.length == 2) {
        drawLine(canvas, paint, points[0], points[1], scaleFactor);
      } else if (points.length == 4 &&
                 (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
                  rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
        // Hacky special case -- draw two lines, for the barcode and metadata
        drawLine(canvas, paint, points[0], points[1], scaleFactor);
        drawLine(canvas, paint, points[2], points[3], scaleFactor);
      } else {
        ResultPoint lastPoint = points[points.length - 1];
        for (ResultPoint point : points) {
          drawLine(canvas, paint, lastPoint, point, scaleFactor);
          lastPoint = point;
        }
      }
    }
  }

  private static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b, float scaleFactor) {
    if (a != null && b != null) {
      canvas.drawLine(scaleFactor * a.getX(), 
                      scaleFactor * a.getY(), 
                      scaleFactor * b.getX(), 
                      scaleFactor * b.getY(), 
                      paint);
    }
  }

  // Put up our own UI for how to handle the decoded contents.
  private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

    CharSequence displayContents = resultHandler.getDisplayContents();

    if (copyToClipboard && !resultHandler.areContentsSecure()) {
      ClipboardInterface.setText(displayContents, this);
    }

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

    if (resultHandler.getDefaultButtonID() != null && prefs.getBoolean(PreferencesActivity.KEY_AUTO_OPEN_WEB, false)) {
      resultHandler.handleClick(resultHandler.getDefaultButtonID());
      return;
    }

    viewfinderView.setVisibility(View.GONE);
    resultView.setVisibility(View.VISIBLE);

    ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
    if (barcode == null) {
      barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.barcode_placeholder));
    } else {
      barcodeImageView.setImageBitmap(barcode);
    }

    TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
    formatTextView.setText(rawResult.getBarcodeFormat().toString());

    TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
    typeTextView.setText(resultHandler.getType().toString());

    DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
    timeTextView.setText(formatter.format(new Date(rawResult.getTimestamp())));


    TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
    View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
    metaTextView.setVisibility(View.GONE);
    metaTextViewLabel.setVisibility(View.GONE);
    Map<ResultMetadataType,Object> metadata = rawResult.getResultMetadata();
    if (metadata != null) {
      StringBuilder metadataText = new StringBuilder(20);
      for (Map.Entry<ResultMetadataType,Object> entry : metadata.entrySet()) {
        if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
          metadataText.append(entry.getValue()).append('\n');
        }
      }
      if (metadataText.length() > 0) {
        metadataText.setLength(metadataText.length() - 1);
        metaTextView.setText(metadataText);
        metaTextView.setVisibility(View.VISIBLE);
        metaTextViewLabel.setVisibility(View.VISIBLE);
      }
    }

    TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
    contentsTextView.setText(displayContents);

    int defaultContentsTextSize = getResources().getDimensionPixelSize(R.dimen.contents_text_size);
    int scaledSize = Math.max(defaultContentsTextSize,
                              2 * defaultContentsTextSize - displayContents.length() / 6);
    contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledSize);

    TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
    supplementTextView.setText("");
    supplementTextView.setOnClickListener(null);
    if (prefs.getBoolean(PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
      SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
                                                     resultHandler.getResult(),
                                                     historyManager,
                                                     this);
    }

    Collection<Integer> buttonIDsToShow = resultHandler.getButtonIDsToShow();
    ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
    buttonView.requestFocus();
    for (int i = 0; i < buttonView.getChildCount(); i++) {
      TextView button = (TextView) buttonView.getChildAt(i);
      if (buttonIDsToShow.contains(button.getId())) {
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(resultHandler);
      } else {
        button.setVisibility(View.GONE);
        button.setOnClickListener(null);
      }
    }

  }

  // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
  private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

    if (barcode != null) {
      viewfinderView.drawResultBitmap(barcode);
    }

    if (copyToClipboard && !resultHandler.areContentsSecure()) {
      ClipboardInterface.setText(resultHandler.getDisplayContents(), this);
    }

    if (source == IntentSource.NATIVE_APP_INTENT) {

      Intent intent = new Intent(Intents.Scan.ACTION);
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
      intent.putExtra(Intents.Scan.RESULT, rawResult.toString());
      intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult.getBarcodeFormat().toString());
      byte[] rawBytes = rawResult.getRawBytes();
      if (rawBytes != null && rawBytes.length > 0) {
        intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes);
      }
      Map<ResultMetadataType,?> metadata = rawResult.getResultMetadata();
      if (metadata != null) {
        if (metadata.containsKey(ResultMetadataType.UPC_EAN_EXTENSION)) {
          intent.putExtra(Intents.Scan.RESULT_UPC_EAN_EXTENSION,
                          metadata.get(ResultMetadataType.UPC_EAN_EXTENSION).toString());
        }
        Number orientation = (Number) metadata.get(ResultMetadataType.ORIENTATION);
        if (orientation != null) {
          intent.putExtra(Intents.Scan.RESULT_ORIENTATION, orientation.intValue());
        }
        String ecLevel = (String) metadata.get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
        if (ecLevel != null) {
          intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL, ecLevel);
        }
        @SuppressWarnings("unchecked")
        Iterable<byte[]> byteSegments = (Iterable<byte[]>) metadata.get(ResultMetadataType.BYTE_SEGMENTS);
        if (byteSegments != null) {
          int i = 0;
          for (byte[] byteSegment : byteSegments) {
            intent.putExtra(Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i, byteSegment);
            i++;
          }
        }
      }
      sendReplyMessage(R.id.return_scan_result, intent);
      
    } else if (source == IntentSource.PRODUCT_SEARCH_LINK) {

      // Reformulate the URL which triggered us into a query, so that the request goes to the same
      // TLD as the scan URL.
      int end = sourceUrl.lastIndexOf("/scan");
      String replyURL = sourceUrl.substring(0, end) + "?q=" + resultHandler.getDisplayContents() + "&source=zxing";      
      sendReplyMessage(R.id.launch_product_query, replyURL);
      
    } else if (source == IntentSource.ZXING_LINK) {

      if (scanFromWebPageManager != null && scanFromWebPageManager.isScanFromWebPage()) {
        String replyURL = scanFromWebPageManager.buildReplyURL(rawResult, resultHandler);
        scanFromWebPageManager = null;
        sendReplyMessage(R.id.launch_product_query, replyURL);
      }

    }
  }

  private void sendReplyMessage(int id, Object arg) {
    if (handler != null) {
      Message message = Message.obtain(handler, id, arg);
      long resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                                                       DEFAULT_INTENT_RESULT_DURATION_MS);
      if (resultDurationMS > 0L) {
        handler.sendMessageDelayed(message, resultDurationMS);
      } else {
        handler.sendMessage(message);
      }
    }
  }

  private void initCamera(SurfaceHolder surfaceHolder) {
    if (surfaceHolder == null) {
      throw new IllegalStateException("No SurfaceHolder provided");
    }
    if (cameraManager.isOpen()) {
      Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
      return;
    }
    try {
      cameraManager.openDriver(surfaceHolder);
      // Creating the handler starts the preview, which can also throw a RuntimeException.
      if (handler == null) {
        handler = new CaptureActivityHandler(this, decodeFormats, decodeHints, characterSet, cameraManager);
      }
      decodeOrStoreSavedBitmap(null, null);
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
      displayFrameworkBugMessageAndExit();
    } catch (RuntimeException e) {
      // Barcode Scanner has seen crashes in the wild of this variety:
      // java.?lang.?RuntimeException: Fail to connect to camera service
      Log.w(TAG, "Unexpected error initializing camera", e);
      displayFrameworkBugMessageAndExit();
    }
  }

  private void displayFrameworkBugMessageAndExit() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.app_name));
    builder.setMessage(getString(R.string.msg_camera_framework_bug));
    builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
    builder.setOnCancelListener(new FinishListener(this));
    builder.show();
  }

  public void restartPreviewAfterDelay(long delayMS) {
    if (handler != null) {
      handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
    }
    resetStatusView();
  }

  private void resetStatusView() {
    resultView.setVisibility(View.GONE);
    viewfinderView.setVisibility(View.VISIBLE);
    lastResult = null;
  }

  public void drawViewfinder() {
    viewfinderView.drawViewfinder();
  }
}
