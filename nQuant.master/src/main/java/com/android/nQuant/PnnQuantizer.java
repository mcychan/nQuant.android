package com.android.nQuant;
/* Fast pairwise nearest neighbor based algorithm for multilevel thresholding
Copyright (C) 2004-2016 Mark Tyler and Dmitry Groshev
Copyright (c) 2018-2019 Miller Cy Chan
* error measure; time used is proportional to number of bins squared - WJ */

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class PnnQuantizer {
	protected final short SHORT_MAX = Short.MAX_VALUE;
	protected final char BYTE_MAX = -Byte.MIN_VALUE + Byte.MAX_VALUE;
	protected short alphaThreshold = 0;
	protected boolean hasSemiTransparency = false;
	protected int m_transparentPixelIndex = -1;
	protected int width, height;
	protected int[] pixels = null;
	protected Integer m_transparentColor;

	private double PR = .2126, PG = .7152, PB = .0722;
	protected Map<Integer, int[]> closestMap = new HashMap<>();
	protected Map<Integer, Short> nearestMap = new HashMap<>();

	public PnnQuantizer(String fname) throws IOException {
        fromBitmap(fname);
	}

    private void fromBitmap(Bitmap bitmap) throws IOException {
		width = bitmap.getWidth();
		height = bitmap.getHeight();
        pixels = new int [width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    }

	private void fromBitmap(String fname) throws IOException {
		Bitmap bitmap = BitmapFactory.decodeFile(fname);
        fromBitmap(bitmap);
	}

	private static final class Pnnbin {
		double ac = 0, rc = 0, gc = 0, bc = 0;
		float cnt = 0, err = 0;
		int nn, fw, bk, tm, mtm;
	}

	protected int getColorIndex(final int c, boolean hasSemiTransparency, boolean hasTransparency)
	{
		if(hasSemiTransparency)
			return (Color.alpha(c) & 0xF0) << 8 | (Color.red(c) & 0xF0) << 4 | (Color.green(c) & 0xF0) | (Color.blue(c) >> 4);
		if (hasTransparency)
			return (Color.alpha(c) & 0x80) << 8 | (Color.red(c) & 0xF8) << 7 | (Color.green(c) & 0xF8) << 2 | (Color.blue(c) >> 3);
		return (Color.red(c) & 0xF8) << 8 | (Color.green(c) & 0xFC) << 3 | (Color.blue(c) >> 3);
	}

	protected double sqr(double value)
	{
		return value * value;
	}

	private void find_nn(Pnnbin[] bins, int idx)
	{
		int nn = 0;
		double err = 1e100;

		Pnnbin bin1 = bins[idx];
		float n1 = bin1.cnt;
		double wa = bin1.ac;
		double wr = bin1.rc;
		double wg = bin1.gc;
		double wb = bin1.bc;
		for (int i = bin1.fw; i != 0; i = bins[i].fw) {
			double nerr = PR * sqr(bins[i].rc - wr) + PG * sqr(bins[i].gc - wg) + PB * sqr(bins[i].bc - wb);
			if(hasSemiTransparency)
				nerr += sqr(bins[i].ac - wa);
			
			float n2 = bins[i].cnt;
			nerr *= (n1 * n2) / (n1 + n2);
			if (nerr >= err)
				continue;
			err = nerr;
			nn = i;
		}
		bin1.err = (float) err;
		bin1.nn = nn;
	}

	protected Integer[] pnnquan(final int[] pixels, int nMaxColors, short quan_rt)
	{
		Pnnbin[] bins = new Pnnbin[65536];

		/* Build histogram */
		for (int pixel : pixels) {
			// !!! Can throw gamma correction in here, but what to do about perceptual
			// !!! nonuniformity then?
			int index = getColorIndex(pixel, hasSemiTransparency, nMaxColors < 64 || m_transparentPixelIndex >= 0);

			if(bins[index] == null)
				bins[index] = new Pnnbin();
			Pnnbin tb = bins[index];
			tb.ac += Color.alpha(pixel);
			tb.rc += Color.red(pixel);
			tb.gc += Color.green(pixel);
			tb.bc += Color.blue(pixel);
			tb.cnt++;
		}

		/* Cluster nonempty bins at one end of array */
		int maxbins = 0;

		for (int i = 0; i < bins.length; ++i) {
			if (bins[i] == null)
				continue;

			float d = 1f / bins[i].cnt;
			bins[i].ac *= d;
			bins[i].rc *= d;
			bins[i].gc *= d;
			bins[i].bc *= d;
			
			bins[maxbins++] = bins[i];
		}

		if(nMaxColors < 16)
			quan_rt = -1;
		if (sqr(nMaxColors) / maxbins < .03)
			quan_rt = 0;

		int j = 0;
		for (; j < maxbins - 1; ++j) {
			bins[j].fw = j + 1;
			bins[j + 1].bk = j;
			
			if (quan_rt > 0) {
				if(nMaxColors < 64)
					bins[j].cnt = (float) Math.sqrt(bins[j].cnt);
				else
					bins[j].cnt = (int) Math.sqrt(bins[j].cnt);
			}
			else if (quan_rt < 0)
				bins[j].cnt = (int) Math.cbrt(bins[j].cnt);
		}
		if (quan_rt > 0) {
			if(nMaxColors < 64)
				bins[j].cnt = (float) Math.sqrt(bins[j].cnt);
			else
				bins[j].cnt = (int) Math.sqrt(bins[j].cnt);
		}
		else if (quan_rt < 0)
			bins[j].cnt = (int) Math.cbrt(bins[j].cnt);

		int h, l, l2;
		/* Initialize nearest neighbors and build heap of them */
		int[] heap = new int[bins.length + 1];
		for (int i = 0; i < maxbins; i++) {
			find_nn(bins, i);
			/* Push slot on heap */
			float err = bins[i].err;
			for (l = ++heap[0]; l > 1; l = l2) {
				l2 = l >> 1;
				if (bins[h = heap[l2]].err <= err)
					break;
				heap[l] = h;
			}
			heap[l] = i;
		}

		/* Merge bins which increase error the least */
		int extbins = maxbins - nMaxColors;
		for (int i = 0; i < extbins; ) {
			Pnnbin tb = null;
			/* Use heap to find which bins to merge */
			for (;;) {
				int b1 = heap[1];
				tb = bins[b1]; /* One with least error */
											   /* Is stored error up to date? */
				if ((tb.tm >= tb.mtm) && (bins[tb.nn].mtm <= tb.tm))
					break;
				if (tb.mtm == 0xFFFF) /* Deleted node */
					b1 = heap[1] = heap[heap[0]--];
				else /* Too old error value */
				{
					find_nn(bins, b1);
					tb.tm = i;
				}
				/* Push slot down */
				float err = bins[b1].err;
				for (l = 1; (l2 = l + l) <= heap[0]; l = l2) {
					if ((l2 < heap[0]) && (bins[heap[l2]].err > bins[heap[l2 + 1]].err))
						++l2;
					if (err <= bins[h = heap[l2]].err)
						break;
					heap[l] = h;
				}
				heap[l] = b1;
			}

			/* Do a merge */
			Pnnbin nb = bins[tb.nn];
			float n1 = tb.cnt;
			float n2 = nb.cnt;
			float d = 1f / (n1 + n2);
			tb.ac = d * Math.round(n1 * tb.ac + n2 * nb.ac);
			tb.rc = d * Math.round(n1 * tb.rc + n2 * nb.rc);
			tb.gc = d * Math.round(n1 * tb.gc + n2 * nb.gc);
			tb.bc = d * Math.round(n1 * tb.bc + n2 * nb.bc);
			tb.cnt += nb.cnt;
			tb.mtm = ++i;

			/* Unchain deleted bin */
			bins[nb.bk].fw = nb.fw;
			bins[nb.fw].bk = nb.bk;
			nb.mtm = 0xFFFF;
		}

		/* Fill palette */
		Integer[] palette = new Integer[nMaxColors];
		short k = 0;
		for (int i = 0;; ++k) {
			int alpha = (int) bins[i].ac;
			palette[k] = Color.argb(alpha, (int) bins[i].rc, (int) bins[i].gc, (int) bins[i].bc);
			if (m_transparentPixelIndex >= 0 && alpha == 0) {
				Integer temp = palette[0]; palette[0] = m_transparentColor; palette[k] = temp;
			}

			if ((i = bins[i].fw) == 0)
				break;
		}
		
		if (k < nMaxColors - 1)
		{
			nMaxColors = k + 1;
			palette = Arrays.copyOf(palette, nMaxColors);
		}

		return palette;
	}

	protected short nearestColorIndex(final Integer[] palette, final int c)
	{
		Short got = nearestMap.get(c);
		if (got != null)
			return got;
		
		short k = 0;
		if (Color.alpha(c) <= alphaThreshold)
			return k;

		double mindist = SHORT_MAX;
		for (short i=0; i<palette.length; ++i) {
			int c2 = palette[i];

			double curdist = Math.abs(Color.alpha(c2) - Color.alpha(c));
			if (curdist > mindist)
				continue;

			curdist += PR * Math.abs(Color.red(c2) - Color.red(c));
			if (curdist > mindist)
				continue;

			curdist += PG * Math.abs(Color.green(c2) - Color.green(c));
			if (curdist > mindist)
				continue;

			curdist += PB * Math.abs(Color.blue(c2) - Color.blue(c));
			if (curdist > mindist)
				continue;

			mindist = curdist;
			k = i;
		}
		nearestMap.put(c, k);
		return k;
	}

	protected short closestColorIndex(final Integer[] palette, final int c)
	{
		short k = 0;
		int[] closest = closestMap.get(c);
		if (closest == null) {
			closest = new int[5];
			closest[2] = closest[3] = Integer.MAX_VALUE;

			for (; k < palette.length; ++k) {
				Integer c2 = palette[k];
				if(c2 == null)
					break;

				closest[4] = (short) (Math.abs(Color.alpha(c) - Color.alpha(c2)) + Math.abs(Color.red(c) - Color.red(c2)) + Math.abs(Color.green(c) - Color.green(c2)) + Math.abs(Color.blue(c) - Color.blue(c2)));
				if (closest[4] < closest[2]) {
					closest[1] = closest[0];
					closest[3] = closest[2];
					closest[0] = k;
					closest[2] = closest[4];
				}
				else if (closest[4] < closest[3]) {
					closest[1] = k;
					closest[3] = closest[4];
				}
			}

			if (closest[3] == Integer.MAX_VALUE)
				closest[2] = 0;
		}

		Random rand = new Random();
		if (closest[2] == 0 || (rand.nextInt(32767) % (closest[3] + closest[2])) <= closest[3])
			k = (short) closest[0];
		else
			k = (short) closest[1];

		closestMap.put(c, closest);
		return k;
	}

	protected int[] calcDitherPixel(int c, int[] clamp, int[] rowerr, int cursor, boolean noBias)
	{
		int[] ditherPixel = new int[4];
		if (noBias) {
			ditherPixel[0] = clamp[((rowerr[cursor] + 0x1008) >> 4) + Color.red(c)];
			ditherPixel[1] = clamp[((rowerr[cursor + 1] + 0x1008) >> 4) + Color.green(c)];
			ditherPixel[2] = clamp[((rowerr[cursor + 2] + 0x1008) >> 4) + Color.blue(c)];
			ditherPixel[3] = clamp[((rowerr[cursor + 3] + 0x1008) >> 4) + Color.alpha(c)];
			return ditherPixel;
		}

		ditherPixel[0] = clamp[((rowerr[cursor] + 0x2010) >> 5) + Color.red(c)];
		ditherPixel[1] = clamp[((rowerr[cursor + 1] + 0x1008) >> 4) + Color.green(c)];
		ditherPixel[2] = clamp[((rowerr[cursor + 2] + 0x2010) >> 5) + Color.blue(c)];
		ditherPixel[3] = Color.alpha(c);
		return ditherPixel;
	}

	protected int[] quantize_image(final int[] pixels, final Integer[] palette, final boolean dither)
	{
		int[] qPixels = new int[pixels.length];
		int nMaxColors = palette.length;

		int pixelIndex = 0;
		if (dither) {
			final int DJ = 4;
			final int BLOCK_SIZE = 256;
			final int DITHER_MAX = 20;
			final int err_len = (width + 2) * DJ;
			int[] clamp = new int[DJ * BLOCK_SIZE];
			int[] limtb = new int[2 * BLOCK_SIZE];

			for (short i = 0; i < BLOCK_SIZE; ++i) {
				clamp[i] = 0;
				clamp[i + BLOCK_SIZE] = i;
				clamp[i + BLOCK_SIZE * 2] = BYTE_MAX;
				clamp[i + BLOCK_SIZE * 3] = BYTE_MAX;

				limtb[i] = -DITHER_MAX;
				limtb[i + BLOCK_SIZE] = DITHER_MAX;
			}
			for (short i = -DITHER_MAX; i <= DITHER_MAX; ++i)
				limtb[i + BLOCK_SIZE] = i % 4 == 3 ? 0 : i;

			boolean noBias = hasSemiTransparency || nMaxColors < 64;
			int dir = 1;
			int[] row0 = new int[err_len];
			int[] row1 = new int[err_len];
			int[] lookup = new int[65536];
			for (int i = 0; i < height; ++i) {
				if (dir < 0)
					pixelIndex += width - 1;

				int cursor0 = DJ, cursor1 = width * DJ;
				row1[cursor1] = row1[cursor1 + 1] = row1[cursor1 + 2] = row1[cursor1 + 3] = 0;
				for (int j = 0; j < width; ++j) {
					int c = pixels[pixelIndex];
					int[] ditherPixel = calcDitherPixel(c, clamp, row0, cursor0, noBias);
					int r_pix = ditherPixel[0];
					int g_pix = ditherPixel[1];
					int b_pix = ditherPixel[2];
					int a_pix = ditherPixel[3];

					int c1 = Color.argb(a_pix, r_pix, g_pix, b_pix);
					if(noBias) {
						int offset = getColorIndex(c1, hasSemiTransparency, m_transparentPixelIndex >= 0);
						if (lookup[offset] == 0)
							lookup[offset] = (Color.alpha(c) == 0) ? 1 : nearestColorIndex(palette, c1) + 1;
						qPixels[pixelIndex] = lookup[offset] - 1;
					}
					else {
						short qIndex = (Color.alpha(c) == 0) ? 0 : nearestColorIndex(palette, c1);
						qPixels[pixelIndex] = palette[qIndex];
					}

					int c2 = qPixels[pixelIndex];
					r_pix = limtb[r_pix - Color.red(c2) + BLOCK_SIZE];
					g_pix = limtb[g_pix - Color.green(c2) + BLOCK_SIZE];
					b_pix = limtb[b_pix - Color.blue(c2) + BLOCK_SIZE];
					a_pix = limtb[a_pix - Color.alpha(c2) + BLOCK_SIZE];

					int k = r_pix * 2;
					row1[cursor1 - DJ] = r_pix;
					row1[cursor1 + DJ] += (r_pix += k);
					row1[cursor1] += (r_pix += k);
					row0[cursor0 + DJ] += (r_pix + k);

					k = g_pix * 2;
					row1[cursor1 + 1 - DJ] = g_pix;
					row1[cursor1 + 1 + DJ] += (g_pix += k);
					row1[cursor1 + 1] += (g_pix += k);
					row0[cursor0 + 1 + DJ] += (g_pix + k);

					k = b_pix * 2;
					row1[cursor1 + 2 - DJ] = b_pix;
					row1[cursor1 + 2 + DJ] += (b_pix += k);
					row1[cursor1 + 2] += (b_pix += k);
					row0[cursor0 + 2 + DJ] += (b_pix + k);

					k = a_pix * 2;
					row1[cursor1 + 3 - DJ] = a_pix;
					row1[cursor1 + 3 + DJ] += (a_pix += k);
					row1[cursor1 + 3] += (a_pix += k);
					row0[cursor0 + 3 + DJ] += (a_pix + k);

					cursor0 += DJ;
					cursor1 -= DJ;
					pixelIndex += dir;
				}
				if ((i % 2) == 1)
					pixelIndex += width + 1;

				dir *= -1;
				int[] temp = row0; row0 = row1; row1 = temp;
			}
			return qPixels;
		}

		if(hasSemiTransparency || nMaxColors < 256) {
			for (int i = 0; i < qPixels.length; ++i)
				qPixels[i] = palette[nearestColorIndex(palette, pixels[i])];
		}
		else {
			for (int i = 0; i < qPixels.length; ++i)
				qPixels[i] = palette[closestColorIndex(palette, pixels[i])];
		}

		return qPixels;
	}

	protected Ditherable getDitherFn(final boolean dither) {
		return new Ditherable() {
			@Override
			public int getColorIndex(int c) {
				return PnnQuantizer.this.getColorIndex(c, hasSemiTransparency, m_transparentPixelIndex >= 0);
			}

			@Override
			public short nearestColorIndex(Integer[] palette, int c) {
				if(dither)
					return PnnQuantizer.this.nearestColorIndex(palette, c);
				return PnnQuantizer.this.closestColorIndex(palette, c);
			}
		};
	}

	protected int[] dither(final int[] cPixels, Integer[] palette, int nMaxColors, int width, int height, boolean dither)
	{
		int[] qPixels;
		if ((nMaxColors < 64 && nMaxColors > 32) || hasSemiTransparency)
			qPixels = quantize_image(cPixels, palette, dither);
		else if(nMaxColors <= 32)
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, getDitherFn(dither), nMaxColors > 2 ? 1.8f : 1.5f);
		else
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, getDitherFn(dither));

		if(!dither)
			BlueNoise.dither(width, height, cPixels, palette, getDitherFn(dither), qPixels, 1.0f);

		closestMap.clear();
		nearestMap.clear();
		return qPixels;
	}

	public Bitmap convert(int nMaxColors, boolean dither) {
		final int[] cPixels = new int[pixels.length];
		for (int i = cPixels.length - 1; i >= 0; --i) {
			int pixel = pixels[i];
			int alfa = (pixel >> 24) & 0xff;
			int r   = (pixel >> 16) & 0xff;
			int g = (pixel >>  8) & 0xff;
			int b  = (pixel      ) & 0xff;
			cPixels[i] = Color.argb(alfa, r, g, b);
			if (alfa < BYTE_MAX) {
				if (alfa == 0) {
					m_transparentPixelIndex = i;
					m_transparentColor = cPixels[i];
				}
				else
					hasSemiTransparency = true;
			}
		}

		if (hasSemiTransparency || nMaxColors <= 32)
			PR = PG = PB = 1;
		else if(width < 512 || height < 512) {
			PR = 0.299; PG = 0.587; PB = 0.114;
		}

		Integer[] palette;
		if (nMaxColors > 2)
			palette = pnnquan(cPixels, nMaxColors, (short)1);
		else {
			palette = new Integer[nMaxColors];
			if (hasSemiTransparency) {
				palette[0] = Color.argb(0, 0, 0, 0);
				palette[1] = Color.BLACK;
			}
			else {
				palette[0] = Color.BLACK;
				palette[1] = Color.WHITE;
			}
		}

        if (nMaxColors > 256)
            dither = true;		
            int[] qPixels = dither(cPixels, palette, nMaxColors, width, height, dither);

        if (m_transparentPixelIndex >= 0) {
            int k = qPixels[m_transparentPixelIndex];
            if (nMaxColors > 2)
                palette[k] = m_transparentColor;
            else if (!palette[k].equals(m_transparentColor)) {
                int c1 = palette[0]; palette[0] = palette[1]; palette[1] = c1;
            }
        }

        if (m_transparentPixelIndex >= 0)
            return Bitmap.createBitmap(qPixels, width, height, Bitmap.Config.ARGB_8888);
        return Bitmap.createBitmap(qPixels, width, height, Bitmap.Config.RGB_565);
	}

}
