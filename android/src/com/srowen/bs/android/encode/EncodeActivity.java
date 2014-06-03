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

package com.srowen.bs.android.encode;

import android.graphics.Point;
import android.view.Display;
import android.view.MenuInflater;
import android.view.WindowManager;
import com.google.zxing.WriterException;
import com.srowen.bs.android.Contents;
import com.srowen.bs.android.FinishListener;
import com.srowen.bs.android.Intents;
import com.srowen.bs.android.simple.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * This class encodes data from an Intent into a QR code, and then displays it full screen so that
 * another person can scan it with their device.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class EncodeActivity extends Activity {

  private static final String TAG = EncodeActivity.class.getSimpleName();

  private static final String USE_VCARD_KEY = "USE_VCARD";

  private QRCodeEncoder qrCodeEncoder;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    Intent intent = getIntent();
    if (intent == null) {
      finish();
    } else {
      String action = intent.getAction();
      if (Intents.Encode.ACTION.equals(action) || Intent.ACTION_SEND.equals(action)) {
        setContentView(R.layout.encode);
      } else {
        finish();
      }
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater menuInflater = getMenuInflater();
    menuInflater.inflate(R.menu.encode, menu);
    boolean useVcard = qrCodeEncoder != null && qrCodeEncoder.isUseVCard();
    int encodeNameResource = useVcard ? R.string.menu_encode_mecard : R.string.menu_encode_vcard;
    MenuItem encodeItem = menu.findItem(R.id.menu_encode);
    encodeItem.setTitle(encodeNameResource);
    Intent intent = getIntent();
    if (intent != null) {
      String type = intent.getStringExtra(Intents.Encode.TYPE);
      encodeItem.setVisible(Contents.Type.CONTACT.equals(type));
    }
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.menu_encode) {
      Intent intent = getIntent();
      intent.putExtra(USE_VCARD_KEY, !qrCodeEncoder.isUseVCard());
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(getIntent());
      finish();
      return true;
    }
    return false;
  }

  @Override
  protected void onResume() {
    super.onResume();
    // This assumes the view is full screen, which is a good assumption
    WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
    Display display = manager.getDefaultDisplay();
    Point displaySize = new Point();
    display.getSize(displaySize);
    int width = displaySize.x;
    int height = displaySize.y;
    int smallerDimension = width < height ? width : height;
    smallerDimension = smallerDimension * 7 / 8;

    Intent intent = getIntent();
    if (intent == null) {
      return;
    }

    try {
      boolean useVCard = intent.getBooleanExtra(USE_VCARD_KEY, false);
      qrCodeEncoder = new QRCodeEncoder(this, intent, smallerDimension, useVCard);
      Bitmap bitmap = qrCodeEncoder.encodeAsBitmap();
      if (bitmap == null) {
        Log.w(TAG, "Could not encode barcode");
        showErrorMessage(R.string.msg_encode_contents_failed);
        qrCodeEncoder = null;
        return;
      }

      ImageView view = (ImageView) findViewById(R.id.image_view);
      view.setImageBitmap(bitmap);

      TextView contents = (TextView) findViewById(R.id.contents_text_view);
      if (intent.getBooleanExtra(Intents.Encode.SHOW_CONTENTS, true)) {
        contents.setText(qrCodeEncoder.getDisplayContents());
        setTitle(qrCodeEncoder.getTitle());
      } else {
        contents.setText("");
        setTitle("");
      }
    } catch (WriterException e) {
      Log.w(TAG, "Could not encode barcode", e);
      showErrorMessage(R.string.msg_encode_contents_failed);
      qrCodeEncoder = null;
    }
  }

  private void showErrorMessage(int message) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setMessage(message);
    builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
    builder.setOnCancelListener(new FinishListener(this));
    builder.show();
  }
}
