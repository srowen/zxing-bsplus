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

package com.srowen.bs.android.result;

import com.srowen.bs.android.LocaleManager;
import com.srowen.bs.android.R;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.URIParsedResult;

import android.app.Activity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

/**
 * Offers appropriate actions for URLS.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class URIResultHandler extends ResultHandler {
  // URIs beginning with entries in this array will not be saved to history or copied to the
  // clipboard for security.
  private static final String[] SECURE_PROTOCOLS = {
    "otpauth:"
  };

  private final Collection<Integer> buttonIDsToShow;

  public URIResultHandler(Activity activity, ParsedResult result) {
    super(activity, result);
    buttonIDsToShow = new ArrayList<>();
    buttonIDsToShow.add(R.id.button_open_browser);
    buttonIDsToShow.add(R.id.button_share_by_email);
    buttonIDsToShow.add(R.id.button_share_by_sms);
    if (LocaleManager.isBookSearchUrl(((URIParsedResult) getResult()).getURI())) {
      buttonIDsToShow.add(R.id.button_search_book_contents);
    }
  }

  @Override
  public Collection<Integer> getButtonIDsToShow() {
    return buttonIDsToShow;
  }

  @Override
  public Integer getDefaultButtonID() {
    return R.id.button_open_browser;
  }

  @Override
  public void handleClick(int buttonID) {
    URIParsedResult uriResult = (URIParsedResult) getResult();
    String uri = uriResult.getURI();
    switch (buttonID) {
      case R.id.button_open_browser:
        openURL(uri);
        break;
      case R.id.button_share_by_email:
        shareByEmail(uri);
        break;
      case R.id.button_share_by_sms:
        shareBySMS(uri);
        break;
      case R.id.button_search_book_contents:
        searchBookContents(uri);
        break;
    }
  }

  @Override
  public boolean areContentsSecure() {
    URIParsedResult uriResult = (URIParsedResult) getResult();
    String uri = uriResult.getURI().toLowerCase(Locale.ENGLISH);
    for (String secure : SECURE_PROTOCOLS) {
      if (uri.startsWith(secure)) {
        return true;
      }
    }
    return false;
  }
}
