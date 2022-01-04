package com.android.nQuant;
/* Fast pairwise nearest neighbor based algorithm for multilevel thresholding
Copyright (C) 2004-2016 Mark Tyler and Dmitry Groshev
Copyright (c) 2018-2021 Miller Cy Chan
* error measure; time used is proportional to number of bins squared - WJ */

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static com.android.nQuant.BitmapUtilities.BYTE_MAX;

public class PnnQuantizer {
	protected short alphaThreshold = 0xF;
	protected boolean hasSemiTransparency = false;
	protected int m_transparentPixelIndex = -1;
	protected int width, height;
	protected int[] pixels = null;
	protected Integer m_transparentColor = Color.argb(0, BYTE_MAX, BYTE_MAX, BYTE_MAX);

	protected double PR = .2126, PG = .7152, PB = .0722, PA = .3333;
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
			double n2 = bins[i].cnt, nerr2 = (n1 * n2) / (n1 + n2);
			if (nerr2 >= err)
				continue;
			
			double nerr = nerr2 * PA * BitmapUtilities.sqr(bins[i].ac - wa);
			if (nerr >= err)
				continue;
			
			nerr += nerr2 * PR * BitmapUtilities.sqr(bins[i].rc - wr);
			if (nerr >= err)
				continue;

			nerr += nerr2 * PG * BitmapUtilities.sqr(bins[i].gc - wg);
			if (nerr >= err)
				continue;

			nerr += nerr2 * PB * BitmapUtilities.sqr(bins[i].bc - wb);
			if (nerr >= err)
				continue;
			err = nerr;
			nn = i;
		}
		bin1.err = (float) err;
		bin1.nn = nn;
	}
	
	@FunctionalInterface
	protected interface QuanFn {
		float get(float cnt);
	}
	
	protected QuanFn getQuanFn(int nMaxColors, short quan_rt) {
		if (quan_rt > 0) {
			if (nMaxColors < 64)
				return cnt -> (float) Math.sqrt(cnt);
			return cnt -> (int) Math.sqrt(cnt);
		}
		if (quan_rt < 0)
			return cnt -> (int) Math.cbrt(cnt);
		return cnt -> cnt;
	}

	protected Integer[] pnnquan(final int[] pixels, int nMaxColors, short quan_rt)
	{
		Pnnbin[] bins = new Pnnbin[65536];

		/* Build histogram */
		for (int pixel : pixels) {
			// !!! Can throw gamma correction in here, but what to do about perceptual
			// !!! nonuniformity then?
			int index = BitmapUtilities.getColorIndex(pixel, hasSemiTransparency, nMaxColors < 64 || m_transparentPixelIndex >= 0);

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
		
		double weight = nMaxColors * 1.0 / maxbins;
		if (weight > .003 && weight < .005)
			quan_rt = 0;
		if (weight < .025 && PG < 1) {
			double delta = 3 * (.025 + weight);
			PG -= delta;
			PB += delta;
		}
		
		QuanFn quanFn = getQuanFn(nMaxColors, quan_rt);

		int j = 0;
		for (; j < maxbins - 1; ++j) {
			bins[j].fw = j + 1;
			bins[j + 1].bk = j;
			
			bins[j].cnt = quanFn.get(bins[j].cnt);
		}
		bins[j].cnt = quanFn.get(bins[j].cnt);

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
		Integer[] palette = new Integer[extbins > 0 ? nMaxColors : maxbins];
		short k = 0;
		for (int i = 0;; ++k) {
			palette[k] = Color.argb((int) bins[i].ac, (int) bins[i].rc, (int) bins[i].gc, (int) bins[i].bc);

			if ((i = bins[i].fw) == 0)
				break;
		}

		return palette;
	}

	protected short nearestColorIndex(final Integer[] palette, final int c)
	{
		Short got = nearestMap.get(c);
		if (got != null)
			return got;
		
		short k = 0;
		if (Color.alpha(c) <= alphaThreshold && !nearestMap.isEmpty())
			return k;

		double mindist = Integer.MAX_VALUE;
		for (short i=0; i<palette.length; ++i) {
			int c2 = palette[i];

			double curdist = PA * BitmapUtilities.sqr(Color.alpha(c2) - Color.alpha(c));
			if (curdist > mindist)
				continue;

			curdist += PR * BitmapUtilities.sqr(Color.red(c2) - Color.red(c));
			if (curdist > mindist)
				continue;

			curdist += PG * BitmapUtilities.sqr(Color.green(c2) - Color.green(c));
			if (curdist > mindist)
				continue;

			curdist += PB * BitmapUtilities.sqr(Color.blue(c2) - Color.blue(c));
			if (curdist > mindist)
				continue;

			mindist = curdist;
			k = i;
		}
		nearestMap.put(c, k);
		return k;
	}

	protected short closestColorIndex(final Integer[] palette, final int c, final int pos)
	{
		short k = 0;
		if (Color.alpha(c) <= alphaThreshold)
			return k;
		
		int[] closest = closestMap.get(c);
		if (closest == null) {
			closest = new int[4];
			closest[2] = closest[3] = Integer.MAX_VALUE;

			for (; k < palette.length; ++k) {
				int c2 = palette[k];

				double err = PR * BitmapUtilities.sqr(Color.red(c2) - Color.red(c)) + PG * BitmapUtilities.sqr(Color.green(c2) - Color.green(c)) + PB * BitmapUtilities.sqr(Color.blue(c2) - Color.blue(c));
				if (hasSemiTransparency)
					err += PA * BitmapUtilities.sqr(Color.alpha(c2) - Color.alpha(c));
				
				if (err < closest[2]) {
					closest[1] = closest[0];
					closest[3] = closest[2];
					closest[0] = k;
					closest[2] = (int) err;
				}
				else if (err < closest[3]) {
					closest[1] = k;
					closest[3] = (int) err;
				}
			}

			if (closest[3] == Integer.MAX_VALUE)
				closest[1] = closest[0];
			
			closestMap.put(c, closest);
		}

		int MAX_ERR = palette.length;
		int idx = (pos + 1) % 2;
		if (closest[3] * .67 < (closest[3] - closest[2]))
			idx = 0;
		else if (closest[0] > closest[1])
			idx = pos % 2;

		if(closest[idx + 2] >= MAX_ERR)
			return nearestColorIndex(palette, c);
		return (short) closest[idx];
	}

	protected Ditherable getDitherFn(final boolean dither) {
		return new Ditherable() {
			@Override
			public int getColorIndex(int c) {
				return BitmapUtilities.getColorIndex(c, hasSemiTransparency, m_transparentPixelIndex >= 0);
			}

			@Override
			public short nearestColorIndex(Integer[] palette, int c, final int pos) {
				if(dither)
					return PnnQuantizer.this.nearestColorIndex(palette, c);
				return PnnQuantizer.this.closestColorIndex(palette, c, pos);
			}
		};
	}

	protected int[] dither(final int[] cPixels, Integer[] palette, int nMaxColors, int width, int height, boolean dither)
	{
		int[] qPixels;
		Ditherable ditherable = getDitherFn(dither);
		if(hasSemiTransparency)
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, ditherable, 1.25f);
		else if(nMaxColors <= 32)
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, ditherable, nMaxColors > 2 ? 1.8f : 1.5f);
		else
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, ditherable);

		if(!dither)
			BlueNoise.dither(width, height, cPixels, palette, ditherable, qPixels, 1.0f);

		closestMap.clear();
		nearestMap.clear();

		return qPixels;
	}

	public Bitmap convert(int nMaxColors, boolean dither) {
		for (int i = 0; i < pixels.length; ++i) {
			int pixel = pixels[i];
			int alfa = (pixel >> 24) & 0xff;
			int r   = (pixel >> 16) & 0xff;
			int g = (pixel >>  8) & 0xff;
			int b  = (pixel      ) & 0xff;
			pixels[i] = Color.argb(alfa, r, g, b);
			if (alfa < 0xE0) {
				if(nMaxColors > 2 && alfa == 0) {
					m_transparentColor = pixels[i];
					m_transparentPixelIndex = i;
				}
				
				if (alfa <= alphaThreshold)
					pixels[i] = m_transparentColor;
				
				if (alfa > alphaThreshold)
					hasSemiTransparency = true;				
			}
		}

		if (nMaxColors <= 32)
			PR = PG = PB = PA = 1;
		else if(width < 512 || height < 512) {
			PR = 0.299; PG = 0.587; PB = 0.114;
		}

		Integer[] palette;
		if (nMaxColors > 2)
			palette = pnnquan(pixels, nMaxColors, (short)1);
		else {
			palette = new Integer[nMaxColors];
			if (m_transparentPixelIndex >= 0) {
				palette[0] = m_transparentColor;
				palette[1] = Color.BLACK;
			}
			else {
				palette[0] = Color.BLACK;
				palette[1] = Color.WHITE;
			}
		}		

		if (m_transparentPixelIndex >= 0) {
			int k = nearestColorIndex(palette, pixels[m_transparentPixelIndex]);
			if (k > 0) {
				int c1 = palette[0]; palette[0] = palette[1]; palette[1] = c1;
			}
		}
		int[] qPixels = dither(pixels, palette, nMaxColors, width, height, dither);

		if (m_transparentPixelIndex >= 0)
			return Bitmap.createBitmap(qPixels, width, height, Bitmap.Config.ARGB_8888);
		return Bitmap.createBitmap(qPixels, width, height, Bitmap.Config.RGB_565);
	}

}
