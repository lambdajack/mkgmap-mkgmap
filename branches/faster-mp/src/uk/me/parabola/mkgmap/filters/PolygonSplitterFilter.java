/*
 * Copyright (C) 2007 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: Dec 2, 2007
 */
package uk.me.parabola.mkgmap.filters;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.util.ShapeSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Split polygons so that they have less than the maximum number of points.
 *
 * @author Gerd Petermann
 */
public class PolygonSplitterFilter implements MapFilter {
	public static final int MAX_POINT_IN_ELEMENT = 250;
	private int shift;
	
	@Override
	public void init(FilterConfig config) {
		shift = config.getShift();
	}
	
	/**
	 * Split the given shape and place the resulting shapes in the outputs list.
	 * @param shape The original shape (that is too big).
	 * @param outputs The output list.
	 */
	private void split(MapShape shape, List<MapShape> outputs) {
		int dividingLine = 0;
		boolean isLongitude = false;
		Area bounds = shape.getBounds();
		if (bounds.getWidth() > bounds.getHeight()) {
			isLongitude = true;
			Area[] tmpAreas = bounds.split(2, 1, shift);
			dividingLine = tmpAreas != null ? tmpAreas[0].getMaxLong() : (bounds.getMinLong() + bounds.getWidth() / 2);
		} else {
			Area[] tmpAreas = bounds.split(1, 2, shift);
			dividingLine = tmpAreas != null ? tmpAreas[0].getMaxLat() : (bounds.getMinLat() + bounds.getHeight() / 2);
		}
		List<List<Coord>> subShapePoints = new ArrayList<>();
		ShapeSplitter.splitShape(shape.getPoints(), dividingLine << Coord.DELTA_SHIFT, isLongitude, subShapePoints, subShapePoints, null);
		for (List<Coord> subShape : subShapePoints) {
			MapShape s = shape.copy();
			s.setPoints(subShape);
			outputs.add(s);
		}
	}

	/**
	 * This filter splits a polygon if any of the subsequent filters throws a
	 * {@link MustSplitException}.
	 * This will not happen often.
	 *
	 * @param element A map element, only polygons will be processed.
	 * @param next	This is used to pass the possibly transformed element onward.
	 */
	@Override
	public void doFilter(MapElement element, MapFilterChain next) {
		MapShape shape = (MapShape) element;

		try {
			next.doFilter(shape);
		} catch (MustSplitException e) {
			List<MapShape> outputs = new ArrayList<>();
			split(shape, outputs); // split in half
			for (MapShape s : outputs) {
				doFilter(s, next); // recurse as components could still be too big
			}
		}
	}
}
