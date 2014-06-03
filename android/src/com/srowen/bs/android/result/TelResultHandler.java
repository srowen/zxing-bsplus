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

import com.srowen.bs.android.simple.R;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.TelParsedResult;

import android.app.Activity;
import android.telephony.PhoneNumberUtils;

import java.util.Collection;
import java.util.Collections;

/**
 * Offers relevant actions for telephone numbers.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class TelResultHandler extends ResultHandler {

  private static final Collection<Integer> BUTTON_IDS = Collections.singletonList(
      R.id.button_dial
  );

  public TelResultHandler(Activity activity, ParsedResult result) {
    super(activity, result);
  }

  @Override
  public Collection<Integer> getButtonIDsToShow() {
    return BUTTON_IDS;
  }

  @Override
  public void handleClick(int buttonID) {
    TelParsedResult telResult = (TelParsedResult) getResult();
    if (buttonID == R.id.button_dial) {
      dialPhoneFromUri(telResult.getTelURI());
      // When dialer comes up, it allows underlying display activity to continue or something,
      // but app can't get camera in this state. Avoid issues by just quitting, only in the
      // case of a phone number
      getActivity().finish();
    }
  }

  // Overriden so we can take advantage of Android's phone number hyphenation routines.
  @Override
  public CharSequence getDisplayContents() {
    String contents = getResult().getDisplayResult();
    contents = contents.replace("\r", "");
    return PhoneNumberUtils.formatNumber(contents);
  }

}
