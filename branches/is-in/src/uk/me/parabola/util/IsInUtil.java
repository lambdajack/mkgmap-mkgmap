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
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.Node;
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
	public static final int IN = 0x01;
	public static final int ON = 0x02;
	public static final int OUT = 0x04;

	public static final int IN_ON_OUT = IN | ON | OUT;

	private IsInUtil() {
		// hide public constructor
	}

	/**
	 * Calculate status of element regarding a list of polygons.  
	 * @param kind type of rule (POINT, POLYLINE, POLYGON) 
	 * @param el the element 
	 * @param polygons the polygons which should be tested 
	 * @return integer containing flags IN, ON, and OUT 
	 */
	public static int calcInsideness(FeatureKind kind, Element el, Set<Way> polygons) {
		int result = 0;  
		if (polygons == null || polygons.isEmpty()) { 
			return OUT;
		}
		
		Area elementBbox;
		if (el instanceof Node) {
			Coord c = ((Node) el).getLocation();
			for (Way polygon : polygons) {
				switch (isPointInShape(c, polygon.getPoints())) {
				case IN:
					return IN;
				case ON:
					result |= ON;
				default:
				}
			}
			return result == 0 ? OUT : ON;
		} else if (el instanceof Way) {
			Way w = (Way) el;
			if (w.isComplete()) {
				elementBbox = Area.getBBox(w.getPoints());
				if (polygons.size() > 1) {
					// combine all polygons which intersect the bbox of the element if possible
					Path2D.Double path = new Path2D.Double();
					for (Way polygon : polygons) {
						path.append(Java2DConverter.createPath2D(polygon.getPoints()), false);
					}
					java.awt.geom.Area polygonsArea = new java.awt.geom.Area(path);
					List<List<Coord>> mergedShapes = Java2DConverter.areaToShapes(polygonsArea);

					// combination of polygons may contain holes. They are counter clockwise.
					List<List<Coord>> holes = new ArrayList<>();
					List<List<Coord>> outers = new ArrayList<>();
					for (List<Coord> shape : mergedShapes) {
						(Way.clockwise(shape) ? outers : holes).add(shape);
					}
					
					// check if any outer intersects with the way
					for (List<Coord> shape : outers) {
						int tmpRes = isLineInShape(kind, w.getPoints(), shape, elementBbox);
						if (tmpRes != OUT) {
							result |= tmpRes;
							if ((tmpRes & IN) != 0) {
								result = tmpRes;
								break;
							}
						}
					}
					if ((result & IN) != 0 && !holes.isEmpty()) {
						// an outer ring matched
						// check if any hole intersects with the way
						for (List<Coord> hole : holes) {
							int tmpRes = isLineInShape(kind, w.getPoints(), hole, elementBbox);
							if (tmpRes == IN_ON_OUT)
								return tmpRes;
							if ((tmpRes & IN) != 0) 
								result = OUT;
							result |= tmpRes & ON;
							
						}
					}
					
				} else if (polygons.size() == 1) {
					result = isLineInShape(kind, w.getPoints(), (polygons.iterator().next()).getPoints(), elementBbox);
				}
			}
		}
		if (result == 0)
			result = OUT;
		return result;
	}

	private enum IntersectionStatus {
		TOUCHING, CROSSING, SPLITTING, JOINING,SIMILAR, DOUBLE_SPIKE
	}
	
	
	private static int isLineInShape(FeatureKind kind, List<Coord> lineToTest, List<Coord> shape, Area elementBbox) {
		final int n = lineToTest.size();
		int status = isPointInShape(lineToTest.get(0), shape);
		BitSet onBoundary = new BitSet();
		boolean statusFromFirst = true;
		
		for (int i = 0; i < shape.size() - 1; i++) {
			Coord p11 = shape.get(i);
			Coord p12 = shape.get(i + 1);
			if (p11.highPrecEquals(p12)) {
				// maybe we should even skip very short segments (< 0.01 m)?
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
				if (p21.highPrecEquals(p22)) {
					// maybe we should even skip very short segments (< 0.01 m)?
					continue;
				}
				Coord inter = Utils.getSegmentSegmentIntersection(p11, p12, p21, p22);
				if (inter != null) {
					// segments have at least one common point 
					boolean isCrossing = false;
					if (inter.distance(p21) < 0.01) {
						onBoundary.set(k);
						if (k == 0) {
							// first segment of line and first point on boundary
							if (status != ON && statusFromFirst) {
								status = ON;
							}
						} else {
							if (p21.highPrecEquals(p11)) {
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
									// it is unlikely but not impossible that pTest is on boundary :(
									int testStat = isPointInShape(pTest, shape);
									if (status == ON) {
										status = testStat;
										statusFromFirst = false;
									} else if (status != testStat) {
										return IN_ON_OUT; 
									}
								}
							} else if (p21.highPrecEquals(p12)) {
								// handled in next iteration (k+1) or (i+1) 
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
					} else if (inter.distance(p22) < 0.01) {
						onBoundary.set(k + 1);
						// handle intersection on next iteration
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
				status |= insidePolygon(pTest, false, shape) ? IN : OUT;
				return status;
			}
			status |= checkAllOn(kind, lineToTest, shape);
		}
		return status;
	}


	/** 
	 * Handle special case that all points of {@code lineToTest} are on the edge of shape
	 * @param kind 
	 * @param lineToTest
	 * @param shape
	 * @return
	 */
	private static int checkAllOn(FeatureKind kind, List<Coord> lineToTest, List<Coord> shape) {
		int n = lineToTest.size();
		// all points are on boundary
		for (int i = 0; i < n-1; i++) {
			Coord p1 = lineToTest.get(i);
			Coord p2 = lineToTest.get(i + 1);
			// TODO: may not work with b14 (element is inner ring in mp)
			if (!isOnOrCloseToEdgeOfShape(shape, p1, p2)) {
				Coord pTest = p1.makeBetweenPoint(p2, 0.01);
				int resMidPoint = isPointInShape(pTest, shape);
				if (resMidPoint != ON)
					return resMidPoint;
			}
		}
		
		// if we get here we can assume that the line runs along the shape
		if (kind == FeatureKind.POLYGON) {
			// lineToTest is a polygon and all segments are on boundary
			// find a node inside lineToTest and check if this point is in shape
			
			// find topmost node(s)  
			int maxLat = Integer.MIN_VALUE;
			List<SimpleEntry<Coord, Integer>> topNodes = new ArrayList<>();
			for (int i = 0; i < lineToTest.size() - 1; i++) {
				Coord c = lineToTest.get(i);
				int latHp = c.getHighPrecLat();
				if (latHp > maxLat) {
					maxLat = latHp;
					topNodes.clear();
				} 
				if (latHp >= maxLat) {
					topNodes.add(new SimpleEntry<>(c,i));
				}
			}
			
			for (SimpleEntry<Coord, Integer> topNode : topNodes) {
				int pos = topNode.getValue();
				Coord top = topNode.getKey();
				Coord prev = lineToTest.get(pos == 0 ? n - 2 : pos - 1);
				Coord next = lineToTest.get(pos == n - 1 ? 1 : pos + 1);
				double b1 = top.bearingTo(prev);
				double b2 = top.bearingTo(next);
				// b1 and b2 must be heading south or exactly east or west
				// find heading of angle bisector
				double bisectorBearing = (b1 + b2) / 2;
				if (bisectorBearing > -90 && bisectorBearing < 90) {
					// don't go north of top
					bisectorBearing -= 180;
					if (bisectorBearing < -180)
						bisectorBearing += 360;
				}
				Coord pTest = topNode.getKey().destOnRhumLine(0.1, bisectorBearing);
				// double check: the calculated point may not be inside the
				// element
				if (isPointInShape(pTest, lineToTest) == IN && isPointInShape(pTest, shape) == IN) {
					return IN; // all ON and areas intersect
				}
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
		//TODO: Can we do this sorting without the costly bearing calculations? 
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
			if (!p.highPrecEquals(p1)) 
				continue;
			
			int posPrev = i > 0 ? i - 1 : shape.size() - 2;
			int posNext = i < shape.size() - 1 ? i + 1 : 1;
			if (shape.get(posPrev).highPrecEquals(p2) || shape.get(posNext).highPrecEquals(p2))
				return true;

			int posPrev2 = posPrev > 0 ? posPrev - 1 : shape.size() - 2;
			int posNext2 = posNext < shape.size() - 1 ? posNext + 1 : 1;
			if (shape.get(posPrev2).highPrecEquals(p2) && Math.abs(Utils.getAngle(p1, shape.get(posPrev), p2)) < 0.1) {
				// shape segments between p1 and p2 are almost straight
				return true;
			}
			if (shape.get(posNext2).highPrecEquals(p2) && Math.abs(Utils.getAngle(p1, shape.get(posNext), p2)) < 0.1) {
				// shape segments between p1 and p2 are almost straight
				return true;
			}
		}
		
		return false;
	}

	private static int isPointInShape(Coord p, List<Coord> shape) {
		boolean inOrOn = insidePolygon(p, true, shape);
		if (inOrOn) {
			// point is in or on
			boolean in = insidePolygon(p, false, shape);
			return inOrOn != in ? ON : IN;
		}
		return OUT;
	}
	
    /**
     * Check if node is in polygon using winding counter. 
     * Based on code from Dan Sunday, but allows to define how to handle nodes on boundary.   
     * @param p the point to test
     * @param onBoundary the value that should be returned if the point is on the boundary
     * @param shape list of points describing the polygon
     * @return true if p is inside the polygon, false if outside, the value of onBoundary if its on the boundary
     * <p>
     * Copyright 2000 softSurfer, 2012 Dan Sunday
     * This code may be freely used and modified for any purpose
     * providing that this copyright notice is included with it.
     * SoftSurfer makes no warranty for this code, and cannot be held
     * liable for any real or imagined damage resulting from its use.
     * Users of this code must verify correctness for their application.     
     * See http://geomalgorithms.com/a03-_inclusion.html
     */
    public static final boolean insidePolygon (final Coord p, boolean onBoundary, List<Coord> shape) {
        final int y = p.getHighPrecLat();
        final int len = shape.size();
        // the winding number counter
        int wn = 0;
        Coord c = shape.get(0), n; // current & next points from vertex array
       
		for (int i = 0; ++i < len; c = n) {
			n = shape.get(i);
			if (p.highPrecEquals(n)) {
				return onBoundary;
			}
			if (c.getHighPrecLat() <= y) { // starting y <= p.y
				// an upward crossing and p left of edge:
				if (n.getHighPrecLat() > y) {
					long l = p.isLeft(c, n);
					if (l > 0) {
						++wn;
					} else if (l == 0) {
						return onBoundary; 
					}
				}

			} else if (n.getHighPrecLat() <= y) {
				// a downward crossing and p right of edge.
				long l = p.isLeft(c, n);
				if (l < 0) {
					--wn;
				} else if (l == 0) {
					return onBoundary; 
				}
			}
		}
       
        return wn != 0; // if 0 point is outside
    }
	
}
