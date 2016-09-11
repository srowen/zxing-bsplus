/*
 * Copyright Sean Owen
 */

package com.google.zxing.common.advanced.rowedge;


import java.util.Comparator;

final class ByAbsDiffSumComparator implements Comparator<Transition> {

  static final ByAbsDiffSumComparator INSTANCE = new ByAbsDiffSumComparator();

  private ByAbsDiffSumComparator() {}

  @Override
  public int compare(Transition a, Transition b) {
    // Order by sum descending
    return b.absDiffSum - a.absDiffSum;
  }

}
