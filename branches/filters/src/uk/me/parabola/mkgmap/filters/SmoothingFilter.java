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
 * Create date: Dec 1, 2007
 */
package uk.me.parabola.mkgmap.filters;

import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.log.Logger;
import uk.me.parabola.imgfmt.app.Coord;

import java.util.List;

/**
 * A filter that ensures that a line does not exceed the allowed number of
 * points that a line can have.
 *
 * @author Steve Ratcliffe
 */
public class SmoothingFilter implements MapFilter {
	private static final Logger log = Logger.getLogger(LineSplitterFilter.class);

	// Not sure of the value, probably 255.  Say 250 here.
	private static final int MAX_POINTS_IN_LINE = 250;

	/**
	 * If the line is short enough then we just pass it on straight away.
	 * Otherwise we cut it into pieces that are short enough and hand them
	 * on.
	 *
	 * @param element A map element.
	 * @param next This is used to pass the possibly transformed element onward.
	 */
	public void doFilter(MapElement element, MapFilterChain next) {
		MapLine line = (MapLine) element;

		List<Coord> points = line.getPoints();

		for (Coord co : points) {
			
		}
	}
}