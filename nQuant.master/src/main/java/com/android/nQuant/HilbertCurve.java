package com.android.nQuant;
/* The Hilbert curve is a space filling curve that visits every point in a square grid with a size of any other power of 2.
Copyright (c) 2021 Miller Cy Chan
* It was first described by David Hilbert in 1892. Applications of the Hilbert curve are in image processing: especially image compression and dithering. */

import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;

import static com.android.nQuant.HilbertCurve.Direction.*;

public class HilbertCurve {
	public enum Direction { LEFT, RIGHT, DOWN, UP };
	
	private final class ErrorBox
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
	
	private int x, y;
	private final int width;
	private final int height;
	private final int[] pixels;
	private final Integer[] palette;
	private final int[] qPixels;
	private final Ditherable ditherable;
	private final List<ErrorBox> errorq;
	private final float[] weights;
	private final int[] lookup;
    
	private static final byte DITHER_MAX = 16;
	private static final float BLOCK_SIZE = 256f;	    
    
    private HilbertCurve(final int width, final int height, final int[] image, final Integer[] palette, final int[] qPixels, final Ditherable ditherable)
    {
    	x = 0;
    	y = 0;
    	this.width = width;
    	this.height = height;
        this.pixels = image;
        this.palette = palette;
        this.qPixels = qPixels;
        this.ditherable = ditherable;	        
        errorq = new ArrayList<>();
        weights = new float[DITHER_MAX];
        lookup = new int[65536];
    }
    
    private void ditherCurrentPixel()
	{
	    if(x >= 0 && y >= 0 && x < width && y < height) {
	    	int pixel = pixels[x + y * width];
	    	ErrorBox error = new ErrorBox(pixel);	    	
	        for(int c = 0; c < DITHER_MAX; ++c) {
	        	ErrorBox eb = errorq.get(c);
	        	for(int j = 0; j < eb.p.length; ++j)
	        		error.p[j] += eb.p[j] * weights[c];
	        }

	        int r_pix = (int) Math.min(0xFF, Math.max(error.p[0], 0.0));
	        int g_pix = (int) Math.min(0xFF, Math.max(error.p[1], 0.0));
	        int b_pix = (int) Math.min(0xFF, Math.max(error.p[2], 0.0));
	        int a_pix = (int) Math.min(0xFF, Math.max(error.p[3], 0.0));
	        
	        int c2 = Color.argb(a_pix, r_pix, g_pix, b_pix);
	        if (palette.length < 64) {
	        	int offset = ditherable.getColorIndex(c2);
				if(lookup[offset] == 0)
					lookup[offset] = (Color.alpha(pixel) == 0) ? 1 : ditherable.nearestColorIndex(palette, c2) + 1;
				qPixels[x + y * width] = lookup[offset] - 1;
	        }
	        else
	        	qPixels[x + y * width] = ditherable.nearestColorIndex(palette, c2);

	        errorq.remove(0);
	        c2 = palette[qPixels[x + y * width]];
	        error.p[0] = r_pix > 255 ? 255 : r_pix - Color.red(c2);
	        error.p[1] = g_pix > 255 ? 255 : g_pix - Color.green(c2);
	        error.p[2] = b_pix > 255 ? 255 : b_pix - Color.blue(c2);
	        error.p[3] = a_pix > 255 ? 255 : a_pix - Color.alpha(c2);
	        
	        for(int j = 0; j < error.p.length; ++j) {
	        	if(Math.abs(error.p[j]) < DITHER_MAX)
	        		continue;

				error.p[j] -= error.p[j] < 0 ? -DITHER_MAX : DITHER_MAX;
				if(Math.abs(error.p[j]) < DITHER_MAX)
					continue;

				error.p[j] = error.p[j] < 0 ? -DITHER_MAX + 1 : DITHER_MAX - 1;
	        }
	        errorq.add(error);
	    }
	}
    
    private void run()
    {
        /* Dithers all pixels of the image in sequence using
         * the Hilbert path, and distributes the error in
         * a sequence of 16 pixels.
         */
        x = y = 0;
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
        /* Walk the path. */
        int i = Math.max(width, height), depth = 0;
        while(i > 0) {
        	++depth;
        	i >>= 1;
        }
        
        curve(depth, LEFT,UP,UP,RIGHT, DOWN,RIGHT,UP);
        ditherCurrentPixel();
    }
    
    private void navTo(Direction dir)
	{
    	ditherCurrentPixel();
		switch(dir)
        {
            case LEFT:
            	--x;
            	break;
            case RIGHT:
            	++x;
            	break;
            case UP:
            	--y;
            	break;
            case DOWN:
            	++y;
            	break;
        }
	}
	
    private void curve(final int level, Direction a, Direction b, Direction c, Direction d, Direction e, Direction f, Direction g)
    {
		iter(level-1, a);
		navTo(e);
		iter(level-1, b);
		navTo(f);
        iter(level-1, c);
        navTo(g);
        iter(level-1, d);
    }

    private void iter(final int level, Direction dir) {
    	if(level <= 0)
    		return;    	

        switch(dir)
        {
            case LEFT:
            	curve(level, UP,LEFT,LEFT,DOWN, RIGHT,DOWN,LEFT);
            	break;
            case RIGHT:
            	curve(level, DOWN,RIGHT,RIGHT,UP, LEFT,UP,RIGHT);
            	break;
            case UP:
            	curve(level, LEFT,UP,UP,RIGHT, DOWN,RIGHT,UP);
            	break;
            case DOWN:
            	curve(level, RIGHT,DOWN,DOWN,LEFT, UP,LEFT,DOWN);
            	break;
        }
    }
    
    public static int[] dither(final int width, final int height, final int[] pixels, final Integer[] palette, final Ditherable ditherable)
    {
    	int[] qPixels = new int[pixels.length];
    	new HilbertCurve(width, height, pixels, palette, qPixels, ditherable).run();        
        return qPixels;
    }
}
