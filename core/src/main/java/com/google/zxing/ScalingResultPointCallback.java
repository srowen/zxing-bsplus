/*
 * Copyright Sean Owen
 */

package com.google.zxing;

/**
 * {@link ResultPointCallback} which can scale result coordinates based on a known scale factor.
 */
public final class ScalingResultPointCallback implements ResultPointCallback {
  
  private final ResultPointCallback delegate;
  private final float scale;
  
  public ScalingResultPointCallback(ResultPointCallback delegate, float scale) {
    this.delegate = delegate;
    this.scale = scale;
  }
  
  @Override
  public void foundPossibleResultPoint(ResultPoint point) {
    delegate.foundPossibleResultPoint(scale(point));
  }

  @Override
  public void foundPossibleResultPoint(ResultPoint point, String fragment) {
    delegate.foundPossibleResultPoint(scale(point), fragment);
  }
  
  private ResultPoint scale(ResultPoint original) {
    return new ResultPoint(original.getX() / scale, original.getY() / scale);
  }
  
}
