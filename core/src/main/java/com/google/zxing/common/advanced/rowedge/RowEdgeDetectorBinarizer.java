/*
 * Copyright Sean Owen
 */

package com.google.zxing.common.advanced.rowedge;

import java.util.Arrays;

import com.google.zxing.Binarizer;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;

/**
 * {@link Binarizer} which uses an edge detection algorithm.
 */
public final class RowEdgeDetectorBinarizer extends Binarizer {
  
  private static final int MIN_TRANSITIONS = 40;
  private static final int MIN_TRANSITION_SIZE = 4;
  private static final int MAX_TRANSITIONS = 300;

  public static final int DEFAULT_SCALE = 2;
  public static final int TRY_HARDER_SCALE = 4;

  private int zoomFactor;
  private final byte[] luminance;
  private final int[] diffs;
  private final Transition[] transitions;

  public RowEdgeDetectorBinarizer(LuminanceSource source) {
    super(source);
    int sourceWidth = source.getWidth();
    luminance = new byte[sourceWidth];
    diffs = new int[sourceWidth];
    zoomFactor = 2;
    transitions = new Transition[MAX_TRANSITIONS];
  }

  public void setTryHarder(boolean tryHarder) {
    zoomFactor = tryHarder ? TRY_HARDER_SCALE : DEFAULT_SCALE;
  }

  public int getZoomFactor() {
    return zoomFactor;
  }

  @Override
  public int getWidth() {
    return zoomFactor * super.getWidth();
  }

  @Override
  public int getHeight() {
    return zoomFactor * super.getHeight();
  }

  @Override
  public BitArray getBlackRow(int y, BitArray row) {

    int width = getWidth();
    if (row == null || row.getSize() < width) {
      row = new BitArray(width);
    } else {
      row.clear();
    }

    if (y < 0 || y >= getHeight()) {
      return row;
    }

    byte[] luminance = this.luminance;
    int zoomFactor = this.zoomFactor;

    getLuminanceSource().getRow(y / zoomFactor, luminance);

    int rawWidth = width / zoomFactor;

    int[] diffs = this.diffs;
    for (int i = 1; i < rawWidth; i++) {
      diffs[i] = (luminance[i] & 0xFF) - (luminance[i - 1] & 0xFF);
    }

    Transition[] transitions = this.transitions;

    int numTransitions = 0;
    int runStart = 0;
    while (runStart < rawWidth) {
      int startDiff = diffs[runStart];
      boolean positiveRun = startDiff >= 0;
      int runEnd = runStart + 1;
      float weightedDiffTotal = startDiff * runStart;
      int diffTotal = startDiff;
      while (runEnd < rawWidth) {
        int currentDiff = diffs[runEnd];
        if (currentDiff == 0 || positiveRun == currentDiff > 0) {
          weightedDiffTotal += currentDiff * runEnd;
          diffTotal += currentDiff;
          runEnd++;
        } else {
          break;
        }
      }
      int absDiffTotal;
      boolean positive;
      if (diffTotal < 0) {
        absDiffTotal = -diffTotal;
        positive = false;
      } else {
        absDiffTotal = diffTotal;
        positive = true;
      }

      if (absDiffTotal >= MIN_TRANSITION_SIZE && numTransitions < MAX_TRANSITIONS) {
        Transition t = transitions[numTransitions];
        if (t == null) {
          t = new Transition();
          transitions[numTransitions] = t;
        }
        t.center = weightedDiffTotal / diffTotal;
        t.absDiffSum = absDiffTotal;
        t.positive = positive;
        numTransitions++;
      }
      runStart = runEnd;
    }

    if (numTransitions == 0) {
      return row;
    }

    Arrays.sort(transitions, 0, numTransitions, ByAbsDiffSumComparator.INSTANCE);

    int maxOccursAt = MIN_TRANSITIONS;
    if (maxOccursAt < numTransitions) {
      int lastSum = transitions[maxOccursAt - 1].absDiffSum;
      int maxScore = 0;
      for (int i = maxOccursAt; i < numTransitions; i++) {
        int sum = transitions[i].absDiffSum;
        int score = Math.abs(sum - lastSum);
        lastSum = sum;
        if (score > maxScore) {
          maxScore = score;
          maxOccursAt = i;
        }
      }
      if (maxOccursAt < numTransitions) {
        numTransitions = maxOccursAt;
      }
    }

    if (numTransitions == 0) {
      return row;
    }

    Arrays.sort(transitions, 0, numTransitions, ByPositionComparator.INSTANCE);

    boolean inBlack = transitions[0].positive;
    int start = 0;
    for (int i = 0; i < numTransitions; i++) {
      Transition transition = transitions[i];
      int end = (int) (zoomFactor * transition.center + 0.5f);
      if (inBlack) {
        row.setRange(start, end);
      }
      inBlack = !transition.positive;
      start = end;
    }
    if (inBlack) {
      row.setRange(start, width);
    }

    return row;
  }

  @Override
  public BitMatrix getBlackMatrix() {
    int width = getWidth();
    int height = getHeight();
    BitMatrix result = new BitMatrix(width, height);
    BitArray row = new BitArray(width);
    for (int y = 0; y < height; y++) {
      result.setRow(y, getBlackRow(y, row));
    }
    return result;
  }

  @Override
  public Binarizer createBinarizer(LuminanceSource source) {
    return new RowEdgeDetectorBinarizer(source);
  }

}
