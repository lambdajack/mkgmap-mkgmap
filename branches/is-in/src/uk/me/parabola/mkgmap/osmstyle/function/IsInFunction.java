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

package uk.me.parabola.mkgmap.osmstyle.function;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryUtil;
import uk.me.parabola.mkgmap.scan.SyntaxException;
import uk.me.parabola.util.ElementQuadTree;
import uk.me.parabola.util.Java2DConverter;

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
public class IsInFunction extends StyleFunction {
	private static final Logger log = Logger.getLogger(IsInFunction.class);
	private static final boolean SIMULATE_UNIT_TEST = false; 
	private boolean isLineRule; 

	private ElementQuadTree qt;

	public IsInFunction() {
		super(null);
		reqdNumParams = 3; // ??? maybe have something to indicate variable...
		// maybe param:
		// 1: polygon tagName
		// 2: value for above tag
		// 3: type of accuracy, various keywords
	}

	protected String calcImpl(Element el) {
		boolean answer = false;
		if (qt == null || qt.isEmpty()) { 
			return String.valueOf(answer);
		}
		
		String mode = params.get(2);
		
		Set<Element> polygons;
		Area bbox;
		// this is just some test code to give different answers...
		if (el instanceof Node) {
			Coord c = ((Node) el).getLocation();
			bbox = Area.getBBox(Collections.singletonList(c));
			polygons = qt.get(bbox);
			for (Element e : polygons) {
				Way shape = (Way) e;
				if (BoundaryUtil.insidePolygon(c, true, shape.getPoints().toArray(new Coord[0]))) {
					answer = true;
					break;
				}
			}
		} else if (el instanceof Way) {
			Way w = (Way) el;
			if (w.isComplete()) {
				bbox = Area.getBBox(w.getPoints());
				polygons = qt.get(bbox);
				if (polygons.size() > 1) {
					// combine all polygons which intersect the bbox of the
					// element if possible
					Path2D.Double path = new Path2D.Double();
					for (Element e : polygons) {
						Way poly = (Way) e;
						Path2D polyPath = Java2DConverter.createPath2D(poly.getPoints());
						path.append(polyPath, false);
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
						answer = isLineInShape(w.getPoints(), shape, mode);
						if(answer) 
							break;

					}
					if(answer && !holes.isEmpty()) {
						// an outer ring matched
						// check if any hole intersects with the way
						String holeMode = "all".equals(mode) ? "any" : "all";
						for (List<Coord> hole : holes) {
							boolean test = isLineInShape(w.getPoints(), hole, holeMode);
							if (test) {
								answer = false;
								break;
							}

						}
					}
				} else if (polygons.size() == 1) {
					answer = isLineInShape(w.getPoints(), ((Way) polygons.iterator().next()).getPoints(), mode);
				}
			}
		}
		return String.valueOf(answer);
	}

	private enum Status {
		IN, ON, OUT;
	}

	@Override
	public String value(Element el) {
//		if ("w15".equals(el.getTag("name"))) {
//			long dd = 4;
//		}
		String res = calcImpl(el);
		if (SIMULATE_UNIT_TEST) { 
			String expected = el.getTag("expected");
			if (expected != null && !"?".equals(expected) && "landuse".equals(params.get(0)) && "residential".equals(params.get(1))) {
				if (el instanceof Way) {

					Way w2 = (Way) el.copy();
					Collections.reverse(w2.getPoints());
					String res2 = calcImpl(w2);
					if (!res.equals(res2)) {
						log.error(el.getTag("name"), res, res2, params, "oops reverse");
					}
					if (w2.hasIdenticalEndPoints()) {
						List<Coord> points = w2.getPoints();
						for (int i = 1; i < w2.getPoints().size(); i++) {
							points.remove(points.size() - 1);
							Collections.rotate(points, 1);
							points.add(points.get(0));
							res2 = calcImpl(w2);
							if (!res.equals(res2)) {
								log.error(el.getTag("name"), res, res2, params, "oops rotate",i);
								calcImpl(w2);
							}

						}
					}
				}
				boolean b1 = Boolean.parseBoolean(res);
				boolean in = "in".equals(expected);
				boolean straddle = "straddle".equals(expected);
				boolean out = "out".equals(expected);
				if (b1 && out) {
					log.error(el.getTag("name"), res, params, "oops");
				}
				if ("any".equals(params.get(2))) {
					if (!b1 && (in || straddle)) {
						log.error(el.getTag("name"), res, params, "oops");
					}
				}
				if ("all".equals(params.get(2))) {
					if (!b1 && in) {
						log.error(el.getTag("name"), res, params, "oops");
					}
					if (b1 && (straddle)) {
						log.error(el.getTag("name"), res, params, "oops");
					}
				}
			}
		}
		return res;
	}
	
	@Override
	public void setParams(List<String> params) {
		super.setParams(params);
		if (!Arrays.asList("any", "all").contains(params.get(2))) {
			throw new SyntaxException(String.format("Third parameter '%s' of function %s is not supported: %s. Must be 'any' or 'all'.",
					params.get(2), getName(), params));
		}
	}

	@Override
	public String getName() {
		return "is_in";
	}

	@Override
	public boolean supportsNode() {
		return true;
	}

	@Override
	public boolean supportsWay() {
		return true;
	}

	@Override
	public Set<String> getUsedTags() {
		return Collections.singleton(params.get(0));
	}

	@Override
	public String toString() {
		// TODO: check what is needed for class ExpressionArranger and
		// RuleSet.compile()
		return super.toString() + params;
	}

	@Override
	public void augmentWith(uk.me.parabola.mkgmap.reader.osm.ElementSaver elementSaver, boolean isLineRule) {
		this.isLineRule = isLineRule;
		List<Element> matchingPolygons = new ArrayList<>();
		for (Way w : elementSaver.getWays().values()) {
			if (w.isComplete() && w.hasIdenticalEndPoints()
					&& !"polyline".equals(w.getTag(MultiPolygonRelation.STYLE_FILTER_TAG))) {
				String val = w.getTag(params.get(0));
				if (val != null && val.equals(params.get(1))) {
					matchingPolygons.add(w);
				}
			}
		}
		if (!matchingPolygons.isEmpty()) {
			qt = new ElementQuadTree(elementSaver.getBoundingBox(), matchingPolygons);
		}
	}

	private boolean isLineInShape(List<Coord> lineToTest, List<Coord> shape, String mode) {
		final int n = lineToTest.size();
		Status statusFirst = isPointInShape(lineToTest.get(0), shape);
		// can we stop early?
//		if (statusFirst == Status.IN && "any".equals(mode))  
//			return true;
//		if (statusFirst == Status.OUT && "all".equals(mode))
//			return false;
		
		
		for (int i = 0; i < shape.size() - 1; i++) {
			Coord p11 = shape.get(i);
			Coord p12 = shape.get(i + 1);
			if (p11.highPrecEquals(p12)) {
				// maybe we should even skip very short segments (< 0.01 m)?
				continue;
			}
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
						if (k == 0) {
							// first segment of line and first point on boundary
							if (statusFirst != Status.ON) {
//								log.error("Rounding error? First point is very close to shape but status is not ON at",
//										p21.toDegreeString(), params);
								statusFirst = Status.ON;
							}
						} else {
							if (p21.highPrecEquals(p11)) {
								Coord p20 = lineToTest.get(k - 1);
								Coord p10 = shape.get(i - 1 >= 0 ? i - 1 : shape.size() - 2);
								isCrossing = analyseCrossingInPoint(p11, p20, p22, p10, p12);
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
						// handle next time
					} else {
						isCrossing = true;
					}
					if (isCrossing) {
						// real intersection found 
						if ("any".equals(mode))
							return true;
						if ("all".equals(mode))
							return false;
					}
				}
			}
		}
		// found no intersection
		if (statusFirst == Status.ON) {
			for (int i = 1; i < n - 1; i++) {
				Status inner = isPointInShape(lineToTest.get(i), shape);
				if (inner != Status.ON)
					return inner == Status.IN;
			}
			// all points are on boundary
			for (int i = 0; i < n-1; i++) {
				Coord p1 = lineToTest.get(i);
				Coord p2 = lineToTest.get(i + 1);
				// TODO: may not work with b14 (element is inner ring in mp)
				if (!isSequenceInShape(shape, p1, p2)) {
					Coord pTest = p1.makeBetweenPoint(p2, 0.001);
					Status midPoint = isPointInShape(pTest, shape);
					if (midPoint != Status.ON)
						return midPoint == Status.IN;
				}
			}
			
			if (!isLineRule && n > 2) {
				// lineToTest is a polygon
				// TODO: find a point inside lineToTest and check it    
			}
		}
		return statusFirst ==  Status.IN;
	}

	/**
	 * two linestrings a-s-c and x-s-y the same mid point. Check if they are crossing. This is the case
	 * if a-s-c is between x-s-y or if x-s-y is between a-s-c. 
	 * @param s the share point
	 * @param a 1st point 1st line-string
	 * @param b 2nd point 1st line-string 
	 * @param x 1st point 2nd line-string
	 * @param y 2nd point 2nd line-string
	 * @return true if the line strings are crossing, false if they are only touching or overlapping.
	 */
	private static boolean analyseCrossingInPoint(Coord s, Coord a, Coord b, Coord x, Coord y) {
		//TODO: Can we do this sorting without the costly bearing calculations? 
		TreeMap<Double, Character> map = new TreeMap<>();
		map.put(s.bearingTo(a), 'a');
		map.put(s.bearingTo(b), 'b');
		map.put(s.bearingTo(x), 'x');
		map.put(s.bearingTo(y), 'y');
		if (map.size() == 4) {
			List<Character> sortedByBearing = new ArrayList<>(map.values());
			int xpos = sortedByBearing.indexOf('x');
			int ypos = sortedByBearing.indexOf('y');
			
			if (Math.abs(xpos-ypos) == 2) {
				// pair xy is eiher on 0 and 2 or 1 and 3, so only one of a and b is between them
				// shape segments x-s-y is nether between nor outside of way segments a-s-b
				return true;
			}
		} else {
			// two or more segments have the same bearing, can be a spike in shape or way or an overlap
			// ignore this for now
		}
		return false;
	}

	private static boolean isSequenceInShape(List<Coord> shape, Coord p1, Coord p2) {
		for (int i  = 0; i < shape.size(); i++) {
			Coord p = shape.get(i);
			if (p.highPrecEquals(p1)) {
				int pos2Prev = i > 0 ? i - 1 : shape.size() - 2;
				if (shape.get(pos2Prev).highPrecEquals(p2))
					return true;
				int pos2Next = i < shape.size() - 1 ? i + 1 : 1;
				if (shape.get(pos2Next).highPrecEquals(p2))
					return true;
			}
		}
		
		return false;
	}

	private static Status isPointInShape(Coord p, List<Coord> shape) {
		boolean res0 = BoundaryUtil.insidePolygon(p, true, shape.toArray(new Coord[0]));
		Status status = res0 ? Status.IN : Status.OUT;
		if (status == Status.IN) {
			// point is in or on
			boolean res1 = BoundaryUtil.insidePolygon(p, false, shape.toArray(new Coord[0]));
			if (res0 != res1) {
				status = Status.ON;
			}
		}
		return status;
	}
}
