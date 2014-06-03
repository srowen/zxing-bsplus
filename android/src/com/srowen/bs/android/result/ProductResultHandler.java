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

import com.google.zxing.Result;
import com.srowen.bs.android.simple.R;
import com.google.zxing.client.result.ExpandedProductParsedResult;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.ProductParsedResult;

import android.app.Activity;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Handles generic products which are not books.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class ProductResultHandler extends ResultHandler {
  private final Collection<Integer> buttonIDsToShow;

  public ProductResultHandler(Activity activity, ParsedResult result, Result rawResult) {
    super(activity, result, rawResult);
    buttonIDsToShow = new ArrayList<>();
    buttonIDsToShow.add(R.id.button_product_search);
    buttonIDsToShow.add(R.id.button_web_search);
    if (hasCustomProductSearch()) {
      buttonIDsToShow.add(R.id.button_custom_product_search);
    }
  }

  @Override
  public Collection<Integer> getButtonIDsToShow() {
    return buttonIDsToShow;
  }

  @Override
  public void handleClick(int buttonID) {
    String productID = getProductIDFromResult(getResult());
    switch (buttonID) {
      case R.id.button_product_search:
        openProductSearch(productID);
        break;
      case R.id.button_web_search:
        webSearch(productID);
        break;
      case R.id.button_custom_product_search:
        openURL(fillInCustomSearchURL(productID));
        break;
    }
  }

  private static String getProductIDFromResult(ParsedResult rawResult) {
    if (rawResult instanceof ProductParsedResult) {
      return ((ProductParsedResult) rawResult).getNormalizedProductID();
    }
    if (rawResult instanceof ExpandedProductParsedResult) {
      return ((ExpandedProductParsedResult) rawResult).getRawText();
    }
    throw new IllegalArgumentException(rawResult.getClass().toString());
  }

}
