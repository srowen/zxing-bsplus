/*
 * Copyright Sean Owen
 */

package com.google.zxing;

/**
 * {@link LuminanceSource} which can be rendered into RGB pixels.
 */
public abstract class RenderableLuminanceSource extends LuminanceSource {

  static final int THUMBNAIL_SCALE_FACTOR = 2;

  protected RenderableLuminanceSource(int width, int height) {
    super(width, height);
  }

  /**
   * @return a thumbnail of the image as an array of luminances, which represent the image from which the barcode
   *  was decoded, but in grayscale. The image's dimensions are a fraction of the original since it is used
   *  at only a fraction of its original resolution for display anyway. The array represents the pixels
   *  of the image in row-major order.
   */
  public abstract int[] renderThumbnail();

  /**
   * @return width of image from {@link #renderThumbnail()}
   */
  public final int getThumbnailWidth() {
    return getWidth() / THUMBNAIL_SCALE_FACTOR;
  }
  
  /**
   * @return height of image from {@link #renderThumbnail()}
   */  
  public final int getThumbnailHeight() {
    return getHeight() / THUMBNAIL_SCALE_FACTOR;
  }

}
