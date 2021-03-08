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
	private Map<Integer, Lab> pixelMap = new HashMap<>();

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
			double nerr = nerr2 * sqr(lab2.alpha - lab1.alpha) / Math.exp(1.0);
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
	protected Integer[] pnnquan(final int[] pixels, int nMaxColors, boolean quan_sqrt)
	{
		Pnnbin[] bins = new Pnnbin[65536];

		/* Build histogram */
		for (final int pixel : pixels) {
			// !!! Can throw gamma correction in here, but what to do about perceptual
			// !!! nonuniformity then?
			int index = getColorIndex(pixel, hasSemiTransparency);
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

			double d = 1.0 / (float)bins[i].cnt;
			bins[i].ac *= d;
			bins[i].Lc *= d;
			bins[i].Ac *= d;
			bins[i].Bc *= d;
			
			bins[maxbins++] = bins[i];
		}

		double proportional = sqr(nMaxColors) / maxbins;
		if ((proportional < .022 || proportional > .5) && nMaxColors < 64)
			quan_sqrt = false;
		
		if (quan_sqrt)
			bins[0].cnt = (int) Math.sqrt(bins[0].cnt);
		for (int i = 0; i < maxbins - 1; ++i) {
			bins[i].fw = i + 1;
			bins[i + 1].bk = i;
			
			if (quan_sqrt)
				bins[i + 1].cnt = (int) Math.sqrt(bins[i + 1].cnt);
		}

        int h, l, l2;
		if(quan_sqrt && nMaxColors < 64)
			ratio = Math.min(1.0, proportional + nMaxColors * Math.exp(3.845) / pixelMap.size());
		else if(quan_sqrt)
			ratio = Math.min(1.0, Math.pow(nMaxColors, 1.05) / pixelMap.size());			
		else
			ratio = Math.min(1.0, Math.pow(nMaxColors, 2.31) / maxbins);
		
		/* Initialize nearest neighbors and build heap of them */
		int[] heap = new int[65537];
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
		double mindist = SHORT_MAX;
		Lab lab1 = getLab(c);
		for (short i=0; i<palette.length; ++i) {
			int c2 = palette[i];
			Lab lab2 = getLab(c2);

			double curdist = sqr(Color.alpha(c2) - Color.alpha(c)) / Math.exp(1.0);
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
		short[] closest = new short[5];
		short[] got = closestMap.get(c);
		if (got == null) {
			closest[2] = closest[3] = SHORT_MAX;
			Lab lab1 = getLab(c);

			for (; k < palette.length; k++) {
				int c2 = palette[k];
				Lab lab2 = getLab(c2);

				closest[4] = (short) (sqr(lab2.alpha - lab1.alpha) + CIELABConvertor.CIEDE2000(lab2, lab1));
				//closest[4] = Math.abs(lab2.alpha - lab1.alpha) + Math.abs(lab2.L - lab1.L) + Math.abs(lab2.A - lab1.A) + Math.abs(lab2.B - lab1.B);
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

			if (closest[3] == SHORT_MAX)
				closest[2] = 0;
		}
		else
			closest = got;

		Random rand = new Random();
		if (closest[2] == 0 || (rand.nextInt(SHORT_MAX) % (closest[3] + closest[2])) <= closest[3])
			k = closest[0];
		else
			k = closest[1];

		closestMap.put(c, closest);
		return k;
	}	

}