/*
 * Copyright (C) 2019.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */
package uk.me.parabola.util;


import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Way;

/**
 * Implements insideness tests for points, polyline and polygon. We distinguish
 * 3 cases for points: <br>
 * 1: the point is outside the polygon <br>
 * 2: the point is on the boundary of the polygon (or very close to it) <br>
 * 3: the point in inside the polygon
 *
 * We distinguish 6 cases for lines: <br>
 * 1: all of the line is outside the polygon <br>
 * 2: some of the line is outside and the rest touches or runs along the polygon
 * edge <br>
 * 3: all of the line runs along the polygon edge <br>
 * 4: some of the line is inside and the rest touches or runs along. <br>
 * 5: all of the line is inside the polygon <br>
 * 6: some is inside and some outside the polygon. Obviously some point is on
 * the polygon edge but we don't care if runs along the edge.
 *
 * @author Gerd Petermann
 *
 */
public class IsInUtil {
	private static final Logger log = Logger.getLogger(IsInUtil.class);
	public static final int IN = 0x01;
	public static final int ON = 0x02;
	public static final int OUT = 0x04;

	public static final int IN_ON_OUT = IN | ON | OUT;

	private IsInUtil() {
		// hide public constructor
	}

	public static void mergePolygons(Set<Way> polygons, List<List<Coord>> outers, List<List<Coord>> holes) {
		// combine all polygons which intersect the bbox of the element if possible
		Path2D.Double path = new Path2D.Double();
		for (Way polygon : polygons) {
			path.append(Java2DConverter.createPath2D(polygon.getPoints()), false);
		}
		java.awt.geom.Area polygonsArea = new java.awt.geom.Area(path);
		List<List<Coord>> mergedShapes = Java2DConverter.areaToShapes(polygonsArea);

		// combination of polygons may contain holes. They are counter clockwise.
		for (List<Coord> shape : mergedShapes) {
			(Way.clockwise(shape) ? outers : holes).add(shape);
		}
	}
					
	private enum IntersectionStatus {
		TOUCHING, CROSSING, SPLITTING, JOINING,SIMILAR, DOUBLE_SPIKE
	}
	
	private static final int EPS_HP = 4; // ~0.15 meters at equator
	private static final int EPS_HP_SQRD = EPS_HP * EPS_HP;
	private static final double EPS = 0.15; // meters. needed for distToLineSegment()

	public static int isLineInShape(List<Coord> lineToTest, List<Coord> shape, Area elementBbox) {
		final int n = lineToTest.size();
		int status = isPointInShape(lineToTest.get(0), shape);
		BitSet onBoundary = new BitSet();
		
		for (int i = 0; i < shape.size() - 1; i++) {
			Coord p11 = shape.get(i);
			Coord p12 = shape.get(i + 1);
			if (p11.distanceInHighPrecSquared(p12) < EPS_HP_SQRD) { // skip very short segments
				continue;
			}
			// check if shape segment is clearly below, above, right or left of bbox
			if ((Math.min(p11.getLatitude(), p12.getLatitude()) > elementBbox.getMaxLat() + 1)
					|| (Math.max(p11.getLatitude(), p12.getLatitude()) < elementBbox.getMinLat() - 1)
					|| (Math.min(p11.getLongitude(), p12.getLongitude()) > elementBbox.getMaxLong() + 1)
					|| (Math.max(p11.getLongitude(), p12.getLongitude()) < elementBbox.getMinLong() - 1))
				continue;
			for (int k = 0; k < n - 1; k++) {
				Coord p21 = lineToTest.get(k);
				Coord p22 = lineToTest.get(k + 1);
				if (p21.distanceInHighPrecSquared(p22) < EPS_HP_SQRD) { // skip very short segments
					continue;
				}
				Coord inter = Utils.getSegmentSegmentIntersection(p11, p12, p21, p22);
				if (inter != null) {
					// segments have at least one common point
					boolean isCrossing = false;
					if (inter.distanceInHighPrecSquared(p21) < EPS_HP_SQRD) {
						onBoundary.set(k);
						if (k == 0) {
							status |= ON;
						} else {
							if (p21.distanceInHighPrecSquared(p11) < EPS_HP_SQRD) {
								Coord p20 = lineToTest.get(k - 1);
								Coord p10 = shape.get(i - 1 >= 0 ? i - 1 : shape.size() - 2);
								IntersectionStatus x = analyseCrossingInPoint(p11, p20, p22, p10, p12);
								Coord pTest = null;
								if (x == IntersectionStatus.CROSSING) {
									isCrossing = true;
								} else if (x == IntersectionStatus.JOINING) {
									if (!isOnOrCloseToEdgeOfShape(shape, p21, p20)) {
										pTest = p21.makeBetweenPoint(p20, 0.01);
									}
								} else if (x == IntersectionStatus.SPLITTING) {
									if (!isOnOrCloseToEdgeOfShape(shape, p21, p22)) {
										pTest = p21.makeBetweenPoint(p22, 0.01);
									}
								}
								if (pTest != null) {
									int testStat = isPointInShape(pTest, shape);
									status |= testStat;
									if ((status|ON) == IN_ON_OUT)
									    return IN_ON_OUT;
								}
							} else if (p21.distanceInHighPrecSquared(p12) < EPS_HP_SQRD) {
								// handled in next iteration (k+1) or (i+1)b
							} else {
								// way segment starts on a shape segment
								// somewhere between p11 and p12
								// it may cross the shape or just touch it,
								// check if previous way segment is on the same
								// side or not
								long isLeftPrev = lineToTest.get(k-1).isLeft(p11, p12);
								long isLeftNext = p22.isLeft(p11, p12);
								if (isLeftPrev< 0 && isLeftNext > 0 || isLeftPrev > 0 && isLeftNext < 0) {
									// both way segments are not on the shape
									// segment and they are on different sides
									isCrossing = true;
								}
							}
						}
					} else if (inter.distanceInHighPrecSquared(p22) < EPS_HP_SQRD) {
						onBoundary.set(k + 1);
						// handle intersection on next iteration
					} else if (inter.distanceInHighPrecSquared(p11) < EPS_HP_SQRD || inter.distanceInHighPrecSquared(p12) < EPS_HP_SQRD) {
						// intersection is very close to end of shape segment
						if (inter.distToLineSegment(p21, p22) > EPS)
							isCrossing = true;
					} else {
						isCrossing = true;
					}
					if (isCrossing) {
						// real intersection found
						return IN_ON_OUT;
					}
				}
			}
		}
		
		if (!onBoundary.isEmpty())
			status |= ON;
		if (status == ON) {
			// found no intersection and first point is on boundary
			if (onBoundary.cardinality() != n) {
				// return result for first point which is not on boundary
				Coord pTest = lineToTest.get(onBoundary.nextClearBit(0));
				status |= isPointInShape(pTest, shape);
				return status;
			}
			status |= checkAllOn(lineToTest, shape);
		}
		return status;
	}


	/**
	 * Handle special case that all points of {@code lineToTest} are on the edge of shape
	 * @param lineToTest
	 * @param shape
	 * @return
	 */
	private static int checkAllOn(List<Coord> lineToTest, List<Coord> shape) {
		int n = lineToTest.size();
		// all points are on boundary
		for (int i = 0; i < n-1; i++) {
			Coord p1 = lineToTest.get(i);
			Coord p2 = lineToTest.get(i + 1);
			if (!isOnOrCloseToEdgeOfShape(shape, p1, p2)) {
				Coord pTest = p1.makeBetweenPoint(p2, 0.01);
				int resMidPoint = isPointInShape(pTest, shape);
				if (resMidPoint != ON)
					return resMidPoint;
			}
		}
		return ON;
	}

	/**
	 * two line-strings a-s-c and x-s-y the same mid point. Check if they are crossing. This is the case
	 * if a-s-c is between x-s-y or if x-s-y is between a-s-c.
	 * @param s the share point
	 * @param a 1st point 1st line-string
	 * @param b 2nd point 1st line-string
	 * @param x 1st point 2nd line-string
	 * @param y 2nd point 2nd line-string
	 * @return kind of crossing or touching
	 */
	private static IntersectionStatus analyseCrossingInPoint(Coord s, Coord a, Coord b, Coord x, Coord y) {
		TreeMap<Long, Character> map = new TreeMap<>();
		long ba = Math.round(s.bearingTo(a) * 1000);
		long bb = Math.round(s.bearingTo(b) * 1000);
		long bx = Math.round(s.bearingTo(x) * 1000);
		long by = Math.round(s.bearingTo(y) * 1000);
		map.put(ba, 'a');
		map.put(bb, 'b');
		map.put(bx, 'x');
		map.put(by, 'y');
		List<Character> sortedByBearing = new ArrayList<>(map.values());
		int apos = sortedByBearing.indexOf('a');
		int bpos = sortedByBearing.indexOf('b');
		int xpos = sortedByBearing.indexOf('x');
		int ypos = sortedByBearing.indexOf('y');
		
		if (map.size() == 4) {
			if (Math.abs(xpos-ypos) == 2) {
				// pair xy is either on 0 and 2 or 1 and 3, so only one of a and b is between them
				// shape segments x-s-y is nether between nor outside of way segments a-s-b
				return IntersectionStatus.CROSSING;
			}
			return IntersectionStatus.TOUCHING;
		}

		if (map.size() == 3) {
			if (xpos < 0) {
				// x-s-y is a spike that touches a-s-b
				return IntersectionStatus.TOUCHING;
			}
			if (bpos < 0) {
				// either s-x or s-y is overlaps s-b
				return IntersectionStatus.JOINING;
			}
			if (ba == bx || ba == by) {
				return IntersectionStatus.SPLITTING;
			}
			return IntersectionStatus.TOUCHING;
		}
		if (map.size() == 2) {
			if (apos > 0 || bpos > 0) {
				// two spikes meeting
				return IntersectionStatus.TOUCHING;
			}
			// a-s-b and x-s-y are overlapping (maybe have different directions)
			return IntersectionStatus.SIMILAR;
		}
		// both a-s-b and x-s-y come from and go to the same direction
		return IntersectionStatus.DOUBLE_SPIKE;
	}

	/**
	 * Check if the sequence p1-p2 or p2-p1 appears in the shape or if there is only one point c between and the sequence p1-c-p2
	 * is nearly straight.
	 * @param shape list of points describing the shape
	 * @param p1 first point
	 * @param p2 second point
	 * @return true if the sequence p1-p2 or p2-p1 appears in the shape or if there is only one point c between and the sequence p1-c-p2
	 * is nearly straight, else false.
	 */
	private static boolean isOnOrCloseToEdgeOfShape(List<Coord> shape, Coord p1, Coord p2) {
		for (int i  = 0; i < shape.size(); i++) {
			Coord p = shape.get(i);
			if (p.distanceInHighPrecSquared(p1) >= EPS_HP_SQRD)
				continue;
			
			int posPrev = i > 0 ? i - 1 : shape.size() - 2;
			int posNext = i < shape.size() - 1 ? i + 1 : 1;
			if (shape.get(posPrev).distanceInHighPrecSquared(p2) < EPS_HP_SQRD || shape.get(posNext).distanceInHighPrecSquared(p2) < EPS_HP_SQRD)
				return true;

			int posPrev2 = posPrev > 0 ? posPrev - 1 : shape.size() - 2;
			int posNext2 = posNext < shape.size() - 1 ? posNext + 1 : 1;
			if (shape.get(posPrev2).distanceInHighPrecSquared(p2) < EPS_HP_SQRD && Math.abs(Utils.getAngle(p1, shape.get(posPrev), p2)) < 0.1) {
				// shape segments between p1 and p2 are almost straight
				return true;
			}
			if (shape.get(posNext2).distanceInHighPrecSquared(p2) < EPS_HP_SQRD && Math.abs(Utils.getAngle(p1, shape.get(posNext), p2)) < 0.1) {
				// shape segments between p1 and p2 are almost straight
				return true;
			}
		}
		
		return false;
	}

	/**
	 * Check if node is in polygon using crossing number counter, with some some tolerance
	 * @param node the point to test
	 * @param shape list of points describing the polygon
	 * @return IN/ON/OUT
	 */
	public static int isPointInShape(Coord node, List<Coord> shape) {
			final int nodeLat = node.getHighPrecLat();
		final int nodeLon = node.getHighPrecLon();
		if (log.isDebugEnabled()) {
			log.debug("node ", node, nodeLon, nodeLat, shape.size(), shape);
		}
		int trailLat = 0, trailLon = 0;
		int lhsCount = 0, rhsCount = 0; // count both, to be sure
		int minLat, maxLat, minLon, maxLon;
		double lonDif, latDif, distSqrd;
		boolean subsequent = false;
		for (Coord leadCoord : shape) {
			final int leadLat = leadCoord.getHighPrecLat();
			final int leadLon = leadCoord.getHighPrecLon();
			if (subsequent) { // use first point as trailing (poly is closed)
				if (leadCoord.distanceInHighPrecSquared(node) < EPS_HP_SQRD)
					return ON;
				if (leadLat < trailLat) {
					minLat = leadLat;
					maxLat = trailLat;
				} else {
					minLat = trailLat;
					maxLat = leadLat;
				}
				if (leadLon < trailLon) {
					minLon = leadLon;
					maxLon = trailLon;
				} else {
					minLon = trailLon;
					maxLon = leadLon;
				}
				if (minLat - EPS_HP > nodeLat) {
					// line segment is all slightly above, ignore
				} else if (maxLat + EPS_HP < nodeLat) {
					// line segment is all slightly below, ignore
				} else if (minLon - EPS_HP > nodeLon && minLat < nodeLat && maxLat > nodeLat) {
					++rhsCount; // definite line segment all slightly to the right
				} else if (maxLon + EPS_HP < nodeLon && minLat < nodeLat && maxLat > nodeLat) {
					++lhsCount; // definite line segment all slightly to the left
				} else { // need to consider this segment more carefully.
					if (leadLat == trailLat)
						lonDif = 0; // dif meaningless; will be ignored in crossing calc, 0 handled for distToLine calc
					else
						lonDif = nodeLon - trailLon - (double)(nodeLat - trailLat) / (leadLat - trailLat) * (leadLon - trailLon);
					if (leadLon == trailLon)
						latDif = 0; // ditto
					else
						latDif = nodeLat - trailLat - (double)(nodeLon - trailLon) / (leadLon - trailLon) * (leadLat - trailLat);
					// calculate distance to segment using right-angle attitude theorem
					final double lonDifSqrd = lonDif*lonDif;
					final double latDifSqrd = latDif*latDif;
					log.debug("inBox", leadLon-nodeLon, leadLat-nodeLat, trailLon-nodeLon, trailLat-nodeLat, lonDif, latDif, lhsCount, rhsCount);
					// there a small area between the square EPS_HP*2 and the circle within, where, if polygon vertix and
					// segments are the other side, it might still be calculated as ON.
					if (lonDif == 0)
						distSqrd = latDifSqrd;
					else if (latDif == 0)
						distSqrd = lonDifSqrd;
					else
						distSqrd = lonDifSqrd * latDifSqrd / (lonDifSqrd + latDifSqrd);
					if (distSqrd < EPS_HP_SQRD)
						return ON;
					if ((trailLat <= nodeLat && leadLat >  nodeLat) || //  an upward crossing
					    (trailLat >  nodeLat && leadLat <= nodeLat)) { // a downward crossing
						if (lonDif < 0)
							++rhsCount; // a valid crossing right of nodeLon
						else
							++lhsCount;
					}
				}
			} // if not first Coord
			subsequent = true;
			trailLat = leadLat;
			trailLon = leadLon;
		} // for leadCoord
		log.debug("lhs | rhs", lhsCount, rhsCount);
		assert (lhsCount & 1) == (rhsCount & 1) : "LHS: " + lhsCount + " RHS: " + rhsCount;
		return (rhsCount & 1) == 1 ? IN : OUT;
	}

}
