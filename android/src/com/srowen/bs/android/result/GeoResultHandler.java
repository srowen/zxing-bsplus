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

import com.srowen.bs.android.R;
import com.google.zxing.client.result.GeoParsedResult;
import com.google.zxing.client.result.ParsedResult;

import android.app.Activity;

import java.util.Arrays;
import java.util.Collection;

/**
 * Handles geographic coordinates (typically encoded as geo: URLs).
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class GeoResultHandler extends ResultHandler {
  private static final Collection<Integer> BUTTON_IDS = Arrays.asList(
      R.id.button_show_map,
      R.id.button_get_directions
  );

  public GeoResultHandler(Activity activity, ParsedResult result) {
    super(activity, result);
  }

  @Override
  public Collection<Integer> getButtonIDsToShow() {
    return BUTTON_IDS;
  }

  @Override
  public void handleClick(int buttonID) {
    GeoParsedResult geoResult = (GeoParsedResult) getResult();
    switch (buttonID) {
      case R.id.button_show_map:
        openMap(geoResult.getGeoURI());
        break;
      case R.id.button_get_directions:
        getDirections(geoResult.getLatitude(), geoResult.getLongitude());
        break;
    }
  }

}
