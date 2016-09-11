/*
 * Copyright Sean Owen
 */

package com.srowen.bs.android.result;

import android.app.Activity;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.VINParsedResult;
import com.srowen.bs.android.R;

import java.util.Collection;
import java.util.Collections;

/**
 * Handles VIN results.
 */
public final class VINResultHandler extends ResultHandler {

  private static final Collection<Integer> BUTTON_IDS = Collections.singleton(R.id.button_open_browser);

  public VINResultHandler(Activity activity, ParsedResult result) {
    super(activity, result);
  }

  @Override
  public Collection<Integer> getButtonIDsToShow() {
    return BUTTON_IDS;
  }

  @Override
  public void handleClick(int buttonID) {
    VINParsedResult vinResult = (VINParsedResult) getResult();
    if (buttonID == R.id.button_open_browser) {
      webSearch(vinResult.getVIN());
    }
  }

  @Override
  public CharSequence getDisplayContents() {
    return getResult().toString();
  }

}