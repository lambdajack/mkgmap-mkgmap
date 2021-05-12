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
import uk.me.parabola.mkgmap.reader.osm.GType;

public class RoundCoordsFilter implements MapFilter {

	private int shift;
	private boolean keepNodes;
	private int level;

	@Override
	public void init(FilterConfig config) {
		shift = config.getShift();
		level = config.getLevel();
		keepNodes = level == 0 && config.hasNet();
	}

	/**
	 * @param element A map element that will be a line or a polygon.
	 * @param next This is used to pass the possibly transformed element onward.
	 */
	@Override
	public void doFilter(MapElement element, MapFilterChain next) {
		if (shift == 0) {
			// do nothing
			next.doFilter(element);
		} else {
			MapLine line = (MapLine) element;
			int full = 1 << shift;
			int half = 1 << (shift - 1);	// 0.5 shifted
			int mask = ~((1 << shift) - 1); // to remove fraction bits
			
			// round lat/lon values to nearest for shift
			List<Coord> newPoints = new ArrayList<>(line.getPoints().size());

			List<Coord> coords = line.getPoints();
			int endIndex = coords.size() -1;

			Coord lastP = null;
			boolean hasNumbers = keepNodes && line.isRoad() && ((MapRoad) line).getRoadDef().hasHouseNumbers();
			boolean isContourLine = GType.isContourLine(line);
			for(int i = 0; i <= endIndex; i++) {
				Coord p = coords.get(i);
				if (!keepNodes && p.isAddedNumberNode()) {
					// ignore nodes added by housenumber processing for levels > 0   
					continue;
				}

				final boolean keepNumberNode = hasNumbers && p.isNumberNode(); 
				Coord newP;
				if (p instanceof CoordNode && keepNodes) {
					int lat = (p.getLatitude() + half) & mask;
					int lon = (p.getLongitude() + half) & mask;
					newP = new CoordNode(lat, lon, p.getId(), p.getOnBoundary(), p.getOnCountryBorder());
					newP.preserved(true);
				} else if (!isContourLine || i == 0 || i == endIndex) {
					int lat = (p.getLatitude() + half) & mask;
					int lon = (p.getLongitude() + half) & mask;
					newP = new Coord(lat, lon);

					newP.setNumberNode(keepNumberNode);
					newP.preserved(p.preserved() || keepNumberNode);					
				} else { // find best match, used only with contour lines so far
					Coord a = coords.get(i -1);
					Coord b = coords.get(i +1);

					// point 0,0
					int lat = p.getLatitude() & mask;
					int lon = p.getLongitude() & mask;
					newP = new Coord(lat, lon);
					double minDistortion = calcDistortion(newP, a, p, b);

					Coord testP;
					double testDistortion;

					// point 0,1
					lon = (p.getLongitude() + full) & mask;
					testP = new Coord(lat, lon);
					testDistortion = calcDistortion(testP, a, p, b);
					if (testDistortion < minDistortion) {
						minDistortion = testDistortion;
						newP = testP;
					}

					// point 1,1
					lat = (p.getLatitude() + full) & mask;
					testP = new Coord(lat, lon);
					testDistortion = calcDistortion(testP, a, p, b);
					if (testDistortion < minDistortion) {
						minDistortion = testDistortion;
						newP = testP;
					}

					// point 1,0
					lon = p.getLongitude() & mask;
					testP = new Coord(lat, lon);
					testDistortion = calcDistortion(testP, a, p, b);
					if (testDistortion < minDistortion) {
						newP = testP;
					}

					newP.setNumberNode(keepNumberNode);
					newP.preserved(p.preserved() || keepNumberNode);					
				}
				
				// only add the new point if it has different
				// coordinates to the last point or if it's a
				// special node
				if (lastP == null || !lastP.equals(newP) || newP.getId() > 0 || (hasNumbers && newP.isNumberNode())) {
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
			if (newPoints.size() > 1) {
				MapLine newLine = line.copy();
				newLine.setPoints(newPoints);
				next.doFilter(newLine);
			}
		}
	}

	/**
	 * Calculation a value that measures the distortion caused by rounding.
	 * 
	 * @param roundedMid the place where the rounded mid would be
	 * @param before     the exact position of the point before mid
	 * @param mid        the exact position of the middle point
	 * @param after      the exact position of the point after mid
	 * @return a value that measures the distortion, lower value means less
	 *         distortion
	 */
	private static double calcDistortion(Coord roundedMid, Coord before, Coord mid, Coord after) {
		// distances are a simple measure
		return roundedMid.shortestDistToLineSegment(before, mid) + roundedMid.shortestDistToLineSegment(mid, after);
	}
}
