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

import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapShape;

/**
 * @author Steve Ratcliffe
 */
public class PolygonSplitterFilter implements MapFilter {
	private static final int MAX_POINT_IN_ELEMENT = 250;

	/**
	 * Split up polygons that have more than the max allowed number of points.
	 * Initially I shall just throw out polygons that have too many points
	 * to see if this is causing particular problems.
	 *
	 * @param element A map element, only polygons will be processed.
	 * @param next	This is used to pass the possibly transformed element onward.
	 */
	public void doFilter(MapElement element, MapFilterChain next) {
		System.out.println(element.getClass().getName());
		assert element instanceof MapShape;
		MapShape shape = (MapShape) element;

		System.out.println("number of points " + shape.getPoints().size());
		if (shape.getPoints().size() > MAX_POINT_IN_ELEMENT) {
			// Temporary, just drop 'em.
			System.out.println("dropping too-big polygon " + shape);
			return;
		}

		next.doFilter(element);
	}
}
