/*
 * Copyright Sean Owen
 */

package com.google.zxing.common.advanced.rowedge;


import java.util.Comparator;

final class ByPositionComparator implements Comparator<Transition> {

  static final ByPositionComparator INSTANCE = new ByPositionComparator();

  private ByPositionComparator() {}

  @Override
  public int compare(Transition a, Transition b) {
    return (int) (a.center - b.center);
  }

}
