package com.android.nQuant;

import android.graphics.Color;

import com.android.nQuant.CIELABConvertor.Lab;
import com.android.nQuant.CIELABConvertor.MutableDouble;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class PnnLABQuantizer extends PnnQuantizer {
	protected double PR = .299, PG = .587, PB = .114;
	protected double ratio = 1.0;
	private final Map<Integer, Lab> pixelMap = new HashMap<>();

	public PnnLABQuantizer(String fname) throws IOException {
		super(fname);
	}

	private static final class Pnnbin {
		float ac = 0, Lc = 0, Ac = 0, Bc = 0, err = 0;
		int cnt = 0;
		int nn, fw, bk, tm, mtm;
	}

	private Lab getLab(final int c)
	{
		Lab lab1 = pixelMap.get(c);
		if (lab1 == null) {
			lab1 = CIELABConvertor.RGB2LAB(c);
			pixelMap.put(c, lab1);
		}
		return lab1;
	}

	private void find_nn(Pnnbin[] bins, int idx, int nMaxColors)
	{
		int nn = 0;
		double err = 1e100;

		Pnnbin bin1 = bins[idx];
		int n1 = bin1.cnt;

		Lab lab1 = new Lab();
		lab1.alpha = bin1.ac; lab1.L = bin1.Lc; lab1.A = bin1.Ac; lab1.B = bin1.Bc;
		for (int i = bin1.fw; i != 0; i = bins[i].fw) {
			double n2 = bins[i].cnt;
			double nerr2 = (n1 * n2) / (n1 + n2);
			if (nerr2 >= err)
				continue;

			Lab lab2 = new Lab();
			lab2.alpha = bins[i].ac; lab2.L = bins[i].Lc; lab2.A = bins[i].Ac; lab2.B = bins[i].Bc;
			double alphaDiff = hasSemiTransparency ? Math.abs(lab2.alpha - lab1.alpha) : 0;
			double nerr = nerr2 * sqr(alphaDiff) / Math.exp(1.5);
			if (nerr >= err)
				continue;

			nerr += (1 - ratio) * nerr2 * sqr(lab2.L - lab1.L);
			if (nerr >= err)
				continue;

			nerr += (1 - ratio) * nerr2 * sqr(lab2.A - lab1.A);
			if (nerr >= err)
				continue;

			nerr += (1 - ratio) * nerr2 * sqr(lab2.B - lab1.B);

			if (nerr > err)
				continue;

			double deltaL_prime_div_k_L_S_L = CIELABConvertor.L_prime_div_k_L_S_L(lab1, lab2);
			nerr += ratio * nerr2 * sqr(deltaL_prime_div_k_L_S_L);
			if (nerr > err)
				continue;

			MutableDouble a1Prime = new MutableDouble(), a2Prime = new MutableDouble(), CPrime1 = new MutableDouble(), CPrime2 = new MutableDouble();
			double deltaC_prime_div_k_L_S_L = CIELABConvertor.C_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2);
			nerr += ratio * nerr2 * sqr(deltaC_prime_div_k_L_S_L);
			if (nerr > err)
				continue;

			MutableDouble barCPrime = new MutableDouble(), barhPrime = new MutableDouble();
			double deltaH_prime_div_k_L_S_L = CIELABConvertor.H_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2, barCPrime, barhPrime);
			nerr += ratio * nerr2 * sqr(deltaH_prime_div_k_L_S_L);
			if (nerr > err)
				continue;

			nerr += ratio * nerr2 * CIELABConvertor.R_T(barCPrime, barhPrime, deltaC_prime_div_k_L_S_L, deltaH_prime_div_k_L_S_L);
			if (nerr > err)
				continue;

			err = nerr;
			nn = i;
		}
		bin1.err = (float) err;
		bin1.nn = nn;
	}

	@Override
	protected Integer[] pnnquan(final int[] pixels, int nMaxColors, short quan_rt)
	{
		if(hasSemiTransparency)
			PR = PG = PB = 1.0;

		Pnnbin[] bins = new Pnnbin[65536];

		/* Build histogram */
		for (int pixel : pixels) {
			// !!! Can throw gamma correction in here, but what to do about perceptual
			// !!! nonuniformity then?
			int index = getColorIndex(pixel, hasSemiTransparency, nMaxColors < 64 || m_transparentPixelIndex >= 0);

			Lab lab1 = getLab(pixel);
			if(bins[index] == null)
				bins[index] = new Pnnbin();
			Pnnbin tb = bins[index];
			tb.ac += lab1.alpha;
			tb.Lc += lab1.L;
			tb.Ac += lab1.A;
			tb.Bc += lab1.B;
			tb.cnt++;
		}

		/* Cluster nonempty bins at one end of array */
		int maxbins = 0;

		for (int i = 0; i < bins.length; ++i) {
			if (bins[i] == null)
				continue;

			float d = 1f / (float)bins[i].cnt;
			bins[i].ac *= d;
			bins[i].Lc *= d;
			bins[i].Ac *= d;
			bins[i].Bc *= d;

			bins[maxbins++] = bins[i];
		}

		double proportional = sqr(nMaxColors) / maxbins;
		if((m_transparentPixelIndex >= 0 || hasSemiTransparency) && nMaxColors < 32)
			quan_rt = -1;
		else if ((proportional < .018 || proportional > .5) && nMaxColors < 64)
			quan_rt = 0;

		if (quan_rt > 0)
			bins[0].cnt = (int) Math.sqrt(bins[0].cnt);
		for (int i = 0; i < maxbins - 1; ++i) {
			bins[i].fw = i + 1;
			bins[i + 1].bk = i;

			if (quan_rt > 0)
				bins[i + 1].cnt = (int) Math.sqrt(bins[i + 1].cnt);
		}

		if(quan_rt != 0 && nMaxColors < 64) {
			if (proportional > .018 && proportional < .022)
				ratio = Math.min(1.0, proportional + nMaxColors * Math.exp(4.732) / pixelMap.size());
			else
				ratio = Math.min(1.0, proportional + nMaxColors * Math.exp(3.845) / pixelMap.size());
		}
		else if(quan_rt > 0)
			ratio = 1.0;
		else
			ratio = Math.min(1.0, proportional + nMaxColors * Math.exp(4.732) / pixelMap.size());

		if (quan_rt < 0) {
			ratio += 0.5;
			ratio = Math.min(1.0, ratio);
		}

		int h, l, l2;
		/* Initialize nearest neighbors and build heap of them */
		int[] heap = new int[bins.length + 1];
		for (int i = 0; i < maxbins; ++i) {
			find_nn(bins, i, nMaxColors);
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
			Pnnbin tb;
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
					find_nn(bins, b1, nMaxColors);
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
			float d = 1.0f / (n1 + n2);
			tb.ac = d * (n1 * tb.ac + n2 * nb.ac);
			tb.Lc = d * (n1 * tb.Lc + n2 * nb.Lc);
			tb.Ac = d * (n1 * tb.Ac + n2 * nb.Ac);
			tb.Bc = d * (n1 * tb.Bc + n2 * nb.Bc);
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
			Lab lab1 = new Lab();
			lab1.alpha = (int) bins[i].ac;
			lab1.L = bins[i].Lc; lab1.A = bins[i].Ac; lab1.B = bins[i].Bc;
			palette[k] = CIELABConvertor.LAB2RGB(lab1);
			if (m_transparentPixelIndex >= 0 && m_transparentColor.equals(palette[k])) {
				Integer temp = palette[0]; palette[0] = palette[k]; palette[k] = temp;
			}

			if ((i = bins[i].fw) == 0)
				break;
		}

		return palette;
	}

	@Override
	protected short nearestColorIndex(final Integer[] palette, final int c)
	{
		Short got = nearestMap.get(c);
		if (got != null)
			return got;

		short k = 0;
		if (Color.alpha(c) <= alphaThreshold)
			return k;

		double mindist = SHORT_MAX;
		Lab lab1 = getLab(c);
		for (short i=0; i<palette.length; ++i) {
			int c2 = palette[i];
			Lab lab2 = getLab(c2);

			double curdist = sqr(Color.alpha(c2) - Color.alpha(c));
			if (curdist > mindist)
				continue;

			if (palette.length > 32 || hasSemiTransparency) {
				curdist += PR * sqr(Color.red(c2) - Color.red(c));
				if (curdist > mindist)
					continue;

				curdist += PG * sqr(Color.green(c2) - Color.green(c));
				if (curdist > mindist)
					continue;

				curdist += PB * sqr(Color.blue(c2) - Color.blue(c));
				if (PB < 1) {
					if (curdist > mindist)
						continue;

					curdist += sqr(lab2.B - lab1.B) / 2.0;
				}
			}
			else {
				double deltaL_prime_div_k_L_S_L = CIELABConvertor.L_prime_div_k_L_S_L(lab1, lab2);
				curdist += sqr(deltaL_prime_div_k_L_S_L);
				if (curdist > mindist)
					continue;

				MutableDouble a1Prime = new MutableDouble(), a2Prime = new MutableDouble(), CPrime1 = new MutableDouble(), CPrime2 = new MutableDouble();
				double deltaC_prime_div_k_L_S_L = CIELABConvertor.C_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2);
				curdist += sqr(deltaC_prime_div_k_L_S_L);
				if (curdist > mindist)
					continue;

				MutableDouble barCPrime = new MutableDouble(), barhPrime = new MutableDouble();
				double deltaH_prime_div_k_L_S_L = CIELABConvertor.H_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2, barCPrime, barhPrime);
				curdist += sqr(deltaH_prime_div_k_L_S_L);
				if (curdist > mindist)
					continue;

				curdist += CIELABConvertor.R_T(barCPrime, barhPrime, deltaC_prime_div_k_L_S_L, deltaH_prime_div_k_L_S_L);
			}

			if (curdist > mindist)
				continue;
			mindist = curdist;
			k = i;
		}
		nearestMap.put(c, k);
		return k;
	}

	@Override
	protected short closestColorIndex(final Integer[] palette, final int c)
	{
		short k = 0;
		int[] closest = closestMap.get(c);
		if (closest == null) {
			closest = new int[5];
			closest[2] = closest[3] = Integer.MAX_VALUE;
			Lab lab1 = getLab(c);

			for (; k < palette.length; ++k) {
				Integer c2 = palette[k];
				if(c2 == null)
					break;

				Lab lab2 = getLab(c2);
				closest[4] = (int) (PR * sqr(Color.red(c2) - Color.red(c)) + PG * sqr(Color.green(c2) - Color.green(c)) +
						PB * sqr(Color.blue(c2) - Color.blue(c)) + sqr(lab2.B - lab1.B) / 2.0);

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
		if (closest[2] == 0 || (rand.nextInt(32769) % (closest[3] + closest[2])) <= closest[3])
			k = (short) closest[0];
		else
			k = (short) closest[1];

		closestMap.put(c, closest);
		return k;
	}

	@Override
	protected int[] quantize_image(final int[] pixels, final Integer[] palette, final boolean dither)
	{
		int[] qPixels = new int[pixels.length];
		int nMaxColors = palette.length;

		int pixelIndex = 0;
		if (dither) {
			final int DJ = 4;
			final int BLOCK_SIZE = 256;
			final int DITHER_MAX = 16;
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
						short qIndex = (Color.alpha(c) == 0) ? 0 : closestColorIndex(palette, c1);
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

		if(hasSemiTransparency || nMaxColors < 64) {
			for (int i = 0; i < qPixels.length; ++i)
				qPixels[i] = palette[nearestColorIndex(palette, pixels[i])];
		}
		else {
			for (int i = 0; i < qPixels.length; ++i)
				qPixels[i] = palette[closestColorIndex(palette, pixels[i])];
		}

		return qPixels;
	}

	@Override
	protected Ditherable getDitherFn() {
		return new Ditherable() {
			@Override
			public int getColorIndex(int c) {
				return PnnLABQuantizer.this.getColorIndex(c, hasSemiTransparency, m_transparentPixelIndex >= 0);
			}

			@Override
			public short nearestColorIndex(Integer[] palette, int c) {
				boolean noBias = hasSemiTransparency || palette.length < 64;
				if(noBias)
					return PnnLABQuantizer.this.nearestColorIndex(palette, c);
				return PnnLABQuantizer.this.closestColorIndex(palette, c);
			}
		};
	}

	@Override
	protected int[] dither(final int[] cPixels, Integer[] palette, int nMaxColors, int width, int height, boolean dither)
	{
		int[] qPixels;
		if (nMaxColors < 64)
			qPixels = quantize_image(cPixels, palette, dither);
		else
			qPixels = HilbertCurve.dither(width, height, cPixels, palette, getDitherFn());

		if(!dither)
			BlueNoise.dither(width, height, cPixels, palette, getDitherFn(), qPixels, 1.0f);

		closestMap.clear();
		nearestMap.clear();
		pixelMap.clear();
		return qPixels;
	}

}