package com.android.nQuant;
/* Generalized Hilbert ("gilbert") space-filling curve for rectangular domains of arbitrary (non-power of two) sizes.
Copyright (c) 2021 - 2023 Miller Cy Chan
* A general rectangle with a known orientation is split into three regions ("up", "right", "down"), for which the function calls itself recursively, until a trivial path can be produced. */

import android.graphics.Color;
import java.util.ArrayDeque;
import java.util.Queue;

import static com.android.nQuant.BitmapUtilities.BYTE_MAX;

public class GilbertCurve {
	
	private static final class ErrorBox
	{
		private final float[] p;
		private ErrorBox() {
			p = new float[4];
		}
		
		private ErrorBox(int c) {
			p = new float[] {
				Color.red(c),
				Color.green(c),
				Color.blue(c),
				Color.alpha(c)
			};
		}
	}
	
	private final int width;
	private final int height;
	private final int[] pixels;
	private final Integer[] palette;
	private final int[] qPixels;
	private final Ditherable ditherable;
	private final float[] saliencies;
	private final Queue<ErrorBox> errorq;
	private final float[] weights;
	private final int[] lookup;

	private final byte DITHER_MAX;
	private final float DIVISOR;
	private static final float BLOCK_SIZE = 343f;


	private GilbertCurve(final int width, final int height, final int[] image, final Integer[] palette, final int[] qPixels, final Ditherable ditherable, final float[] saliencies, final double weight)
	{
		this.width = width;
		this.height = height;
		this.pixels = image;
		this.palette = palette;
		this.qPixels = qPixels;
		this.ditherable = ditherable;
		this.saliencies = saliencies;
		errorq = new ArrayDeque<>();
		DITHER_MAX = weight < .01 ? (weight > .002) ? (byte) 25 : (byte) 16 : 9;
		DIVISOR = Math.min(3f, (float) weight);
		weights = new float[DITHER_MAX];
		lookup = new int[65536];
	}

	private void ditherPixel(int x, int y) {
		final int bidx = x + y * width;
		final int pixel = pixels[bidx];
		ErrorBox error = new ErrorBox(pixel);
		int i = 0;
		float maxErr = DITHER_MAX - 1;
		for(ErrorBox eb : errorq) {
			for(int j = 0; j < eb.p.length; ++j) {
				error.p[j] += eb.p[j] * weights[i];
				if(error.p[j] > maxErr)
					maxErr = error.p[j];
			}
			++i;
		}

		int r_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[0], 0.0));
		int g_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[1], 0.0));
		int b_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[2], 0.0));
		int a_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[3], 0.0));
		
		int c2 = Color.argb(a_pix, r_pix, g_pix, b_pix);
		if (palette.length <= 32 && a_pix > 0xF0) {
			int offset = ditherable.getColorIndex(c2);
			if (lookup[offset] == 0)
				lookup[offset] = ditherable.nearestColorIndex(palette, c2, bidx) + 1;
			qPixels[bidx] = palette[lookup[offset] - 1];
			
			if(saliencies != null && saliencies[bidx] > .65f && saliencies[bidx] < .75f) {
				final float strength = 1 / 3f;
				c2 = BlueNoise.diffuse(pixel, palette[qPixels[bidx]], 1 / saliencies[bidx], strength, x, y);
				qPixels[bidx] = palette[ditherable.nearestColorIndex(palette, c2, bidx)];
			}
		}
		else
			qPixels[bidx] = palette[ditherable.nearestColorIndex(palette, c2, bidx)];

		errorq.poll();
		int c1 = qPixels[bidx];
		error.p[0] = r_pix - Color.red(c1);
		error.p[1] = g_pix - Color.green(c1);
		error.p[2] = b_pix - Color.blue(c1);
		error.p[3] = a_pix - Color.alpha(c1);

		boolean dither = (palette.length < 3 || DIVISOR < 2) ? false : true;
		boolean diffuse = BlueNoise.RAW_BLUE_NOISE[bidx & 4095] > -88;
		double yDiff = diffuse ? 1 : CIELABConvertor.Y_Diff(c1, c2);

		int errLength = dither ? error.p.length : 0;
		for(int j = 0; j < errLength; ++j) {
			if(Math.abs(error.p[j]) >= DITHER_MAX) {
				if (diffuse)
					error.p[j] = (float) Math.tanh(error.p[j] / maxErr * 20) * (DITHER_MAX - 1);
				else
					error.p[j] = (float) (error.p[j] / maxErr * yDiff) * (DITHER_MAX - 1);
			}
		}
		errorq.add(error);
	}
	
	private void generate2d(int x, int y, int ax, int ay, int bx, int by) {    	
		int w = Math.abs(ax + ay);
		int h = Math.abs(bx + by);
		int dax = Integer.signum(ax);
		int day = Integer.signum(ay);
		int dbx = Integer.signum(bx);
		int dby = Integer.signum(by);

		if (h == 1) {
			for (int i = 0; i < w; ++i){
				ditherPixel(x, y);
				x += dax;
				y += day;
			}
			return;
		}

		if (w == 1) {
			for (int i = 0; i < h; ++i){
				ditherPixel(x, y);
				x += dbx;
				y += dby;
			}
			return;
		}

		int ax2 = ax / 2;
		int ay2 = ay / 2;
		int bx2 = bx / 2;
		int by2 = by / 2;

		int w2 = Math.abs(ax2 + ay2);
		int h2 = Math.abs(bx2 + by2);

		if (2 * w > 3 * h) {
			if ((w2 % 2) != 0 && w > 2) {
				ax2 += dax;
				ay2 += day;
			}
			generate2d(x, y, ax2, ay2, bx, by);
			generate2d(x + ax2, y + ay2, ax - ax2, ay - ay2, bx, by);
			return;
		}

		if ((h2 % 2) != 0 && h > 2) {
			bx2 += dbx;
			by2 += dby;
		}

		generate2d(x, y, bx2, by2, ax2, ay2);
		generate2d(x + bx2, y + by2, ax, ay, bx - bx2, by - by2);
		generate2d(x + (ax - dax) + (bx2 - dbx), y + (ay - day) + (by2 - dby), -bx2, -by2, -(ax - ax2), -(ay - ay2));
	}

	private void run()
	{
		/* Dithers all pixels of the image in sequence using
		 * the Hilbert path, and distributes the error in
		 * a sequence of 9 pixels.
		 */
		final float weightRatio = (float) Math.pow(BLOCK_SIZE + 1f,  1f / (DITHER_MAX - 1f));
		float weight = 1f, sumweight = 0f;
		for(int c = 0; c < DITHER_MAX; ++c)
		{
			errorq.add(new ErrorBox());
			sumweight += (weights[DITHER_MAX - c - 1] = 1.0f / weight);
			weight *= weightRatio;
		}

		weight = 0f; /* Normalize */
		for(int c = 0; c < DITHER_MAX; ++c)
			weight += (weights[c] /= sumweight);
		weights[0] += 1f - weight;
		
		if (width >= height)
			generate2d(0, 0, width, 0, 0, height);
		else
			generate2d(0, 0, 0, height, width, 0);
	}

	public static int[] dither(final int width, final int height, final int[] pixels, final Integer[] palette, final Ditherable ditherable, final float[] saliencies, final double weight)
	{
		int[] qPixels = new int[pixels.length];
		new GilbertCurve(width, height, pixels, palette, qPixels, ditherable, saliencies, weight).run();
		return qPixels;
	}
}
