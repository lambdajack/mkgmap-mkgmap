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
 */
package uk.me.parabola.mkgmap.filters;

import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapRoad;

public class RoundCoordsFilter implements MapFilter {

	private int shift;
	private boolean keepNodes;
	private int level;

	public void init(FilterConfig config) {
		shift = config.getShift();
		keepNodes = config.getLevel() == 0 && config.hasNet();
		level = config.getLevel();
	}

	/**
	 * @param element A map element that will be a line or a polygon.
	 * @param next This is used to pass the possibly transformed element onward.
	 */
	public void doFilter(MapElement element, MapFilterChain next) {
		MapLine line = (MapLine) element;
		if(shift == 0) {
			// do nothing
			next.doFilter(line);
		}
		else {
			int half = 1 << (shift - 1);	// 0.5 shifted
			int mask = ~((1 << shift) - 1); // to remove fraction bits
			
			// round lat/lon values to nearest for shift
			List<Coord> newPoints = new ArrayList<>(line.getPoints().size());
			Coord lastP = null;
			boolean hasNumbers = level == 0 && line.isRoad() && ((MapRoad) line).getRoadDef().hasHouseNumbers();
			for(Coord p : line.getPoints()) {
				if (level > 0 && p.isAddedNumberNode()) {
					// ignore nodes added by housenumber processing for levels > 0   
					continue;
				}
				
				int lat = (p.getLatitude() + half) & mask;
				int lon = (p.getLongitude() + half) & mask;
				Coord newP;
				
				if(p instanceof CoordNode && keepNodes)
					newP = new CoordNode(lat, lon, p.getId(), p.getOnBoundary(), p.getOnCountryBorder());
				else {
					newP = new Coord(lat, lon);
					newP.preserved(p.preserved());
					newP.setNumberNode(hasNumbers && p.isNumberNode());
				}
				
				// only add the new point if it has different
				// coordinates to the last point or if it's a
				// special node
				if (lastP == null || !lastP.equals(newP) || newP.getId() > 0|| (hasNumbers && newP.isNumberNode())) {
					newPoints.add(newP);
					lastP = newP;
				} else if (newP.preserved()) {
					// this point is not going to be used because it
					// has the same (rounded) coordinates as the last
					// node but it has been marked as being "preserved" -
					// transfer that property to the previous point so
					// that it's not lost in further filters
					lastP.preserved(true);
				}
			}
			if(newPoints.size() > 1) {
				MapLine newLine = line.copy();
				newLine.setPoints(newPoints);
				next.doFilter(newLine);
			}
		}
	}
}
