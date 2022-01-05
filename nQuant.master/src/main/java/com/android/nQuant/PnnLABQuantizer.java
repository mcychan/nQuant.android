package com.android.nQuant;

import android.graphics.Color;

import com.android.nQuant.CIELABConvertor.Lab;
import com.android.nQuant.CIELABConvertor.MutableDouble;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class PnnLABQuantizer extends PnnQuantizer {
	protected double ratio = 1.0;
	private final Map<Integer, Lab> pixelMap = new HashMap<>();

	public PnnLABQuantizer(String fname) throws IOException {
		super(fname);
	}

	private static final class Pnnbin {
		float ac = 0, Lc = 0, Ac = 0, Bc = 0, err = 0;
		float cnt = 0;
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

	private void find_nn(Pnnbin[] bins, int idx, boolean texicab)
	{
		int nn = 0;
		double err = 1e100;

		Pnnbin bin1 = bins[idx];
		float n1 = bin1.cnt;

		Lab lab1 = new Lab();
		lab1.alpha = bin1.ac; lab1.L = bin1.Lc; lab1.A = bin1.Ac; lab1.B = bin1.Bc;
		for (int i = bin1.fw; i != 0; i = bins[i].fw) {
			float n2 = bins[i].cnt;
			double nerr2 = (n1 * n2) / (n1 + n2);
			if (nerr2 >= err)
				continue;

			Lab lab2 = new Lab();
			lab2.alpha = bins[i].ac; lab2.L = bins[i].Lc; lab2.A = bins[i].Ac; lab2.B = bins[i].Bc;
			double alphaDiff = hasSemiTransparency ? (lab2.alpha - lab1.alpha) / Math.exp(1.5) : 0;
			double nerr = nerr2 * BitmapUtilities.sqr(alphaDiff);
			if (nerr >= err)
				continue;

			if(hasSemiTransparency || !texicab) {
				nerr += (1 - ratio) * nerr2 * BitmapUtilities.sqr(lab2.L - lab1.L);
				if (nerr >= err)
					continue;

				nerr += (1 - ratio) * nerr2 * BitmapUtilities.sqr(lab2.A - lab1.A);
				if (nerr >= err)
					continue;

				nerr += (1 - ratio) * nerr2 * BitmapUtilities.sqr(lab2.B - lab1.B);
			}
			else {
				nerr += (1 - ratio) * nerr2 * Math.abs(lab2.L - lab1.L);
				if (nerr >= err)
					continue;
	
				nerr += (1 - ratio) * nerr2 * Math.sqrt(BitmapUtilities.sqr(lab2.A - lab1.A) + BitmapUtilities.sqr(lab2.B - lab1.B));
			}

			if (nerr > err)
				continue;

			double deltaL_prime_div_k_L_S_L = CIELABConvertor.L_prime_div_k_L_S_L(lab1, lab2);
			nerr += ratio * nerr2 * BitmapUtilities.sqr(deltaL_prime_div_k_L_S_L);
			if (nerr > err)
				continue;

			MutableDouble a1Prime = new MutableDouble(), a2Prime = new MutableDouble(), CPrime1 = new MutableDouble(), CPrime2 = new MutableDouble();
			double deltaC_prime_div_k_L_S_L = CIELABConvertor.C_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2);
			nerr += ratio * nerr2 * BitmapUtilities.sqr(deltaC_prime_div_k_L_S_L);
			if (nerr > err)
				continue;

			MutableDouble barCPrime = new MutableDouble(), barhPrime = new MutableDouble();
			double deltaH_prime_div_k_L_S_L = CIELABConvertor.H_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2, barCPrime, barhPrime);
			nerr += ratio * nerr2 * BitmapUtilities.sqr(deltaH_prime_div_k_L_S_L);
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
	
	protected QuanFn getQuanFn(int nMaxColors, short quan_rt) {
		if (quan_rt > 0) {
			if (quan_rt > 1)
				return cnt -> (int) Math.pow(cnt, 0.75);
			if (nMaxColors < 64)
				return cnt -> (int) Math.sqrt(cnt);
			return cnt -> (float) Math.sqrt(cnt);
		}
		return cnt -> cnt;
	}

	@Override
	protected Integer[] pnnquan(final int[] pixels, int nMaxColors, short quan_rt)
	{
		Pnnbin[] bins = new Pnnbin[65536];

		/* Build histogram */
		for (int pixel : pixels) {
			if (Color.alpha(pixel) <= alphaThreshold)
				pixel = m_transparentColor;
			
			int index = BitmapUtilities.getColorIndex(pixel, hasSemiTransparency, nMaxColors < 64 || m_transparentPixelIndex >= 0);

			Lab lab1 = getLab(pixel);
			if(bins[index] == null)
				bins[index] = new Pnnbin();
			Pnnbin tb = bins[index];
			tb.ac += lab1.alpha;
			tb.Lc += lab1.L;
			tb.Ac += lab1.A;
			tb.Bc += lab1.B;
			tb.cnt += 1.0f;			
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

		double proportional = BitmapUtilities.sqr(nMaxColors) / maxbins;
		if((m_transparentPixelIndex >= 0 || hasSemiTransparency) && nMaxColors < 32)
			quan_rt = -1;
		
		double weight = Math.min(0.9, nMaxColors * 1.0 / maxbins);
		if (weight > .0015 && weight < .002)
			quan_rt = 2;
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

		final boolean texicab = proportional > .0275;		
		
		if(quan_rt != 0 && nMaxColors < 64) {
			if (proportional > .018 && proportional < .022)
				ratio = Math.min(1.0, proportional + weight * Math.exp(3.13));
			else if (proportional > .1)
				ratio = Math.min(1.0, 1.0 - weight);
			else if(proportional > .04)
				ratio = Math.min(1.0, weight * Math.exp(1.56));
			else if(proportional > .03)
				ratio = Math.min(1.0, proportional + weight * Math.exp(3.66));
			else
				ratio = Math.min(1.0, proportional + weight * Math.exp(1.718));
		}
		else if(nMaxColors > 256)
			ratio = Math.min(1.0, 1 - 1.0 / proportional);
		else
			ratio = Math.min(1.0, 1 - weight * .7);

		if (quan_rt < 0)
			ratio = Math.min(1.0, weight * Math.exp(3.13));

		int h, l, l2;
		/* Initialize nearest neighbors and build heap of them */
		int[] heap = new int[bins.length + 1];
		for (int i = 0; i < maxbins; ++i) {
			find_nn(bins, i, texicab);
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

		if (quan_rt > 0 && nMaxColors < 64 && proportional > .035 && proportional < .1) {
			int dir = proportional > .04 ? 1 : -1;
			ratio = Math.min(1.0, proportional + dir * weight * Math.exp(1.632));
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
					find_nn(bins, b1, texicab && proportional < 1);
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
		Integer[] palette = new Integer[extbins > 0 ? nMaxColors : maxbins];
		short k = 0;
		for (int i = 0;; ++k) {
			Lab lab1 = new Lab();
			lab1.alpha = (int) bins[i].ac;
			lab1.L = bins[i].Lc; lab1.A = bins[i].Ac; lab1.B = bins[i].Bc;
			palette[k] = CIELABConvertor.LAB2RGB(lab1);

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

		double mindist = Integer.MAX_VALUE;
		Lab lab1 = getLab(c);
		for (short i=0; i<palette.length; ++i) {
			int c2 = palette[i];			

			double curdist = hasSemiTransparency ? Math.abs(Color.alpha(c2) - Color.alpha(c)) / Math.exp(0.75) : 0;
			if (curdist > mindist)
				continue;

			Lab lab2 = getLab(c2);
			if (palette.length <= 4) {
				curdist = BitmapUtilities.sqr(Color.red(c2) - Color.red(c)) + BitmapUtilities.sqr(Color.green(c2) - Color.green(c)) + BitmapUtilities.sqr(Color.blue(c2) - Color.blue(c));
				if(hasSemiTransparency)
					curdist += BitmapUtilities.sqr(Color.alpha(c2) - Color.alpha(c));
			}
			else if (palette.length > 32 || hasSemiTransparency) {
				curdist += Math.abs(lab2.L - lab1.L);
				if (curdist > mindist)
					continue;

				curdist += Math.sqrt(BitmapUtilities.sqr(lab2.A - lab1.A) + BitmapUtilities.sqr(lab2.B - lab1.B));
			}
			else {
				double deltaL_prime_div_k_L_S_L = CIELABConvertor.L_prime_div_k_L_S_L(lab1, lab2);
				curdist += BitmapUtilities.sqr(deltaL_prime_div_k_L_S_L);
				if (curdist > mindist)
					continue;

				MutableDouble a1Prime = new MutableDouble(), a2Prime = new MutableDouble(), CPrime1 = new MutableDouble(), CPrime2 = new MutableDouble();
				double deltaC_prime_div_k_L_S_L = CIELABConvertor.C_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2);
				curdist += BitmapUtilities.sqr(deltaC_prime_div_k_L_S_L);
				if (curdist > mindist)
					continue;

				MutableDouble barCPrime = new MutableDouble(), barhPrime = new MutableDouble();
				double deltaH_prime_div_k_L_S_L = CIELABConvertor.H_prime_div_k_L_S_L(lab1, lab2, a1Prime, a2Prime, CPrime1, CPrime2, barCPrime, barhPrime);
				curdist += BitmapUtilities.sqr(deltaH_prime_div_k_L_S_L);
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
		Random rand = new Random();
		int idx = 1;
		if (closest[2] == 0 || (rand.nextInt(32767) % (closest[3] + closest[2])) <= closest[3])
			idx = 0;

		if(closest[idx + 2] >= MAX_ERR)
			return nearestColorIndex(palette, c);
		return (short) closest[idx];
	}

	protected Ditherable getDitherFn() {
		return new Ditherable() {
			@Override
			public int getColorIndex(int c) {
				return BitmapUtilities.getColorIndex(c, hasSemiTransparency, m_transparentPixelIndex >= 0);
			}

			@Override
			public short nearestColorIndex(Integer[] palette, int c, final int pos) {
				return PnnLABQuantizer.this.closestColorIndex(palette, c, pos);
			}
		};
	}

	@Override
	protected int[] dither(final int[] cPixels, Integer[] palette, int nMaxColors, int width, int height, boolean dither)
	{
		int[] qPixels;
		Ditherable ditherable = getDitherFn();
		if(hasSemiTransparency)
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, ditherable, 1.25f);
		else if(nMaxColors <= 32)
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, ditherable, 1.5f);
		else
			qPixels = GilbertCurve.dither(width, height, cPixels, palette, ditherable);

		if(!dither) {
			double delta = BitmapUtilities.sqr(nMaxColors) / pixelMap.size();
			float weight = delta > 0.023 ? 1.0f : (float) (37.013 * delta + 0.906);
			BlueNoise.dither(width, height, cPixels, palette, ditherable, qPixels, weight);
		}

		closestMap.clear();
		nearestMap.clear();
		pixelMap.clear();

		return qPixels;
	}

}
