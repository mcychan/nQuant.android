package com.android.nQuant;
/* Generalized Hilbert ("gilbert") space-filling curve for rectangular domains of arbitrary (non-power of two) sizes.
Copyright (c) 2021 - 2025 Miller Cy Chan
* A general rectangle with a known orientation is split into three regions ("up", "right", "down"), for which the function calls itself recursively, until a trivial path can be produced. */

import android.graphics.Color;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

import static com.android.nQuant.BitmapUtilities.BYTE_MAX;

public class GilbertCurve {
	
	private static final class ErrorBox
	{
		private double yDiff = 0;
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

	private byte ditherMax, DITHER_MAX;
	private float beta;
	private float[] weights;
	private final boolean dither, sortedByYDiff;
	private final int width, height;
	private final double weight;
	private final int[] pixels;
	private final Integer[] palette;
	private final int[] qPixels;
	private final Ditherable ditherable;
	private final float[] saliencies;
	private final Queue<ErrorBox> errorq;
	private final int[] lookup;

	private final int margin, thresold;
	private static final float BLOCK_SIZE = 343f;

	private GilbertCurve(final int width, final int height, final int[] image, final Integer[] palette, final int[] qPixels, final Ditherable ditherable, final float[] saliencies, double weight, boolean dither)
	{
		this.width = width;
		this.height = height;
		this.pixels = image;
		this.palette = palette;
		this.qPixels = qPixels;
		this.ditherable = ditherable;
		boolean hasAlpha = weight < 0;
		this.saliencies = hasAlpha ? null : saliencies;
		this.dither = dither;
		this.weight = Math.abs(weight);
		margin = weight < .0025 ? 12 : weight < .004 ? 8 : 6;
		sortedByYDiff = palette.length > 128 && weight >= .02 && (!hasAlpha || weight < .18);
		beta = palette.length > 4 ? (float) (.6f - .00625f * palette.length) : 1;
		if (palette.length > 4) {
			double boundary = .005 - .0000625 * palette.length;
			beta = (float) (weight > boundary ? .25 : Math.min(1.5, beta + palette.length * weight));
			if (palette.length > 32 && palette.length < 256)
				beta += .1f;
			if (palette.length >= 64 && (weight > .012 && weight < .0125) || (weight > .025 && weight < .03))
				beta *= 2f;
		}
		else
			beta *= .95f;
		
		if (palette.length > 64 || (palette.length > 4 && weight > .02))
			beta *= .4f;
		if (palette.length > 64 && weight < .02)
			beta = .2f;

		errorq = sortedByYDiff ? new PriorityQueue<>(new Comparator<ErrorBox>() {

			@Override
			public int compare(ErrorBox o1, ErrorBox o2) {
				return Double.compare(o2.yDiff, o1.yDiff);
			}
			
		}) : new ArrayDeque<>();
		
		DITHER_MAX = weight < .015 ? (weight > .0025) ? (byte) 25 : 16 : 9;
		double edge = hasAlpha ? 1 : Math.exp(weight) - .25;
		double deviation = weight > .002 ? -.25 : 1;
		ditherMax = (hasAlpha || DITHER_MAX > 9) ? (byte) BitmapUtilities.sqr(Math.sqrt(DITHER_MAX) + edge * deviation) : (byte) (DITHER_MAX * (saliencies != null ? 2 : Math.E));
		final int density = palette.length > 16 ? 3200 : 1500;
		if(palette.length / weight > 5000 && (weight > .045 || (weight > .01 && palette.length < 64)))
			ditherMax = (byte) BitmapUtilities.sqr(5 + edge);
		else if(weight < .03 && palette.length / weight < density && palette.length >= 16 && palette.length < 256)
			ditherMax = (byte) BitmapUtilities.sqr(5 + edge);
		thresold = DITHER_MAX > 9 ? -112 : -64;
		weights = new float[0];
		lookup = new int[65536];
	}

	private int ditherPixel(int x, int y, int c2, float beta) {
		final int bidx = x + y * width;
		final int pixel = pixels[bidx];
		int r_pix = Color.red(c2);
		int g_pix = Color.green(c2);
		int b_pix = Color.blue(c2);
		int a_pix = Color.alpha(c2);
		
		final float strength = 1 / 3f;
		final int acceptedDiff = Math.max(2, palette.length - margin);
		if (palette.length <= 4 && saliencies[bidx] > .2f && saliencies[bidx] < .25f)
			c2 = BlueNoise.diffuse(pixel, palette[qPixels[bidx]], beta * 2 / saliencies[bidx], strength, x, y);
		else if (palette.length <= 4 || CIELABConvertor.Y_Diff(pixel, c2) < (2 * acceptedDiff)) {
			if (palette.length <= 128 || BlueNoise.TELL_BLUE_NOISE[bidx & 4095] > 0)
				c2 = BlueNoise.diffuse(pixel, palette[qPixels[bidx]], beta * .5f / saliencies[bidx], strength, x, y);
			if (palette.length <= 4 && CIELABConvertor.U_Diff(pixel, c2) > (8 * acceptedDiff)) {
				int c1 = saliencies[bidx] > .65f ? pixel : Color.argb(a_pix, r_pix, g_pix, b_pix);
				c2 = BlueNoise.diffuse(c1, palette[qPixels[bidx]], beta * saliencies[bidx], strength, x, y);
			}
			if (CIELABConvertor.U_Diff(pixel, c2) > (margin * acceptedDiff))
				c2 = BlueNoise.diffuse(pixel, palette[qPixels[bidx]], beta / saliencies[bidx], strength, x, y);
		}
		
		if (palette.length < 3 || margin > 6) {
			double delta = (weight > .0015 && weight < .0025) ? beta : Math.PI;
			if (palette.length > 4 && (CIELABConvertor.Y_Diff(pixel, c2) > (delta * acceptedDiff) || CIELABConvertor.U_Diff(pixel, c2) > (margin * acceptedDiff))) {
				float kappa = saliencies[bidx] < .4f ? beta * .4f * saliencies[bidx] : beta * .4f / saliencies[bidx];
				int c1 = saliencies[bidx] < .6f ? pixel : Color.argb(a_pix, r_pix, g_pix, b_pix);
				c2 = BlueNoise.diffuse(c1, palette[qPixels[bidx]], kappa, strength, x, y);
			}
		}
		else if (palette.length > 4 && (CIELABConvertor.Y_Diff(pixel, c2) > (beta * acceptedDiff) || CIELABConvertor.U_Diff(pixel, c2) > acceptedDiff)) {
			if(beta < .3f && (palette.length <= 32 || saliencies[bidx] < beta))
				c2 = BlueNoise.diffuse(c2, palette[qPixels[bidx]], beta * .4f * saliencies[bidx], strength, x, y);
			else
				c2 = Color.argb(a_pix, r_pix, g_pix, b_pix);
		}

		if (DITHER_MAX < 16 && palette.length > 4 && saliencies[bidx] < .6f && CIELABConvertor.Y_Diff(pixel, c2) > margin - 1)
			c2 = Color.argb(a_pix, r_pix, g_pix, b_pix);
		if (beta > 1f && CIELABConvertor.Y_Diff(pixel, c2) > DITHER_MAX)
			c2 = Color.argb(a_pix, r_pix, g_pix, b_pix);

		int offset = ditherable.getColorIndex(c2);
		if (lookup[offset] == 0)
			lookup[offset] = ditherable.nearestColorIndex(palette, c2, bidx) + 1;
		return palette[lookup[offset] - 1];
	}

	private void diffusePixel(int x, int y) {
		final int bidx = x + y * width;
		final int pixel = pixels[bidx];
		ErrorBox error = new ErrorBox(pixel);

		float maxErr = DITHER_MAX - 1;
		int i = sortedByYDiff ? weights.length - 1 : 0;
		for (ErrorBox eb : errorq) {
			if (i < 0 || i >= weights.length)
				break;

			for (int j = 0; j < eb.p.length; ++j) {
				error.p[j] += eb.p[j] * weights[i];
				if(error.p[j] > maxErr)
					maxErr = error.p[j];
			}
			i += sortedByYDiff ? -1 : 1;
		}

		int r_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[0], 0.0));
		int g_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[1], 0.0));
		int b_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[2], 0.0));
		int a_pix = (int) Math.min(BYTE_MAX, Math.max(error.p[3], 0.0));

		int c2 = Color.argb(a_pix, r_pix, g_pix, b_pix);
		if (saliencies != null && dither && !sortedByYDiff)
			qPixels[bidx] = ditherPixel(x, y, c2, beta);
		else if (palette.length <= 32 && a_pix > 0xF0) {
			int offset = ditherable.getColorIndex(c2);
			if (lookup[offset] == 0)
				lookup[offset] = ditherable.nearestColorIndex(palette, c2, bidx) + 1;
			qPixels[bidx] = palette[lookup[offset] - 1];

			final int acceptedDiff = Math.max(2, palette.length - margin);
			if(saliencies != null && (CIELABConvertor.Y_Diff(pixel, c2) > acceptedDiff || CIELABConvertor.U_Diff(pixel, c2) > (2 * acceptedDiff))) {
				final float strength = 1 / 3f;
				c2 = BlueNoise.diffuse(pixel, palette[qPixels[bidx]], 1 / saliencies[bidx], strength, x, y);
				qPixels[bidx] = palette[ditherable.nearestColorIndex(palette, c2, bidx)];
			}
		}
		else
			qPixels[bidx] = palette[ditherable.nearestColorIndex(palette, c2, bidx)];

		if(errorq.size() >= DITHER_MAX)
			errorq.poll();
		else if(!errorq.isEmpty())
			initWeights(errorq.size());

		c2 = qPixels[bidx];
		error.p[0] = r_pix - Color.red(c2);
		error.p[1] = g_pix - Color.green(c2);
		error.p[2] = b_pix - Color.blue(c2);
		error.p[3] = a_pix - Color.alpha(c2);

		boolean denoise = palette.length > 2;
		boolean diffuse = BlueNoise.TELL_BLUE_NOISE[bidx & 4095] > thresold;
		error.yDiff = sortedByYDiff ? CIELABConvertor.Y_Diff(pixel, c2) : 1;
		boolean illusion = !diffuse && BlueNoise.TELL_BLUE_NOISE[(int) (error.yDiff * 4096) & 4095] > thresold;

		boolean unaccepted = false;
		int errLength = denoise ? error.p.length - 1 : 0;
		for (int j = 0; j < errLength; ++j) {
			if (Math.abs(error.p[j]) >= ditherMax) {
				if (sortedByYDiff && saliencies != null)
					unaccepted = true;

				if (diffuse)
					error.p[j] = (float) Math.tanh(error.p[j] / maxErr * 20) * (ditherMax - 1);
				else if(illusion)
					error.p[j] = (float) (error.p[j] / maxErr * error.yDiff) * (ditherMax - 1);
				else
					error.p[j] /= (float) (1 + Math.sqrt(ditherMax));
			}

			if (sortedByYDiff && saliencies == null && Math.abs(error.p[j]) >= DITHER_MAX)
				unaccepted = true;
		}

		if (unaccepted) {
			if (saliencies != null)
				qPixels[bidx] = ditherPixel(x, y, c2, 1.25f);
			else if (CIELABConvertor.Y_Diff(pixel, c2) > 3 && CIELABConvertor.U_Diff(pixel, c2) > 3) {
				final float strength = 1 / 3f;
				c2 = BlueNoise.diffuse(pixel, palette[qPixels[bidx]], strength, strength, x, y);
				qPixels[bidx] = ditherable.nearestColorIndex(palette, c2, bidx);
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
				diffusePixel(x, y);
				x += dax;
				y += day;
			}
			return;
		}

		if (w == 1) {
			for (int i = 0; i < h; ++i){
				diffusePixel(x, y);
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

	private void initWeights(int size) {
		/* Dithers all pixels of the image in sequence using
		 * the Gilbert path, and distributes the error in
		 * a sequence of pixels size.
		 */
		final float weightRatio = (float) Math.pow(BLOCK_SIZE + 1f, 1f / (size - 1f));
		float weight = 1f, sumweight = 0f;
		weights = new float[size];
		for(int c = 0; c < size; ++c) {
			errorq.add(new ErrorBox());
			sumweight += (weights[size - c - 1] = weight);
			weight /= weightRatio;
		}

		weight = 0f; /* Normalize */
		for(int c = 0; c < size; ++c)
			weight += (weights[c] /= sumweight);
		weights[0] += 1f - weight;
	}

	private void run()
	{
		if(!sortedByYDiff)
			initWeights(DITHER_MAX);

		if (width >= height)
			generate2d(0, 0, width, 0, 0, height);
		else
			generate2d(0, 0, 0, height, width, 0);
	}

	public static int[] dither(final int width, final int height, final int[] pixels, final Integer[] palette, final Ditherable ditherable, final float[] saliencies, final double weight, final boolean dither)
	{
		int[] qPixels = new int[pixels.length];
		new GilbertCurve(width, height, pixels, palette, qPixels, ditherable, saliencies, weight, dither).run();
		return qPixels;
	}
}
