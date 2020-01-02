package uk.me.parabola.mkgmap.osmstyle.function;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.ElementQuadTree;
import uk.me.parabola.util.Java2DConverter;

public class IsInFunction extends StyleFunction {
	private static final Logger log = Logger.getLogger(IsInFunction.class);

	private ElementQuadTree qt;
	
	public IsInFunction() {
		super(null);
		reqdNumParams = 3;  // ??? maybe have something to indicate variable...
		// maybe param:
		// 1: polygon tagName
		// 2: value for above tag
		// 3: type of accuracy, various keywords
	}

	protected String calcImpl(Element el) {
		boolean answer = false;
		if (qt != null && !qt.isEmpty()) {
			Set<Element> polygons;
			Area bbox;
			/**
			 * TODO: calculate bbox of element, 
			 * retrieve closed ways which match the tag given with parameters
			 * use spatial index if that improves performance
			 * calculate insideness  
			 */
			
			// this is just some test code to give different answers...
			if (el instanceof Node) {
				Coord c = ((Node) el).getLocation();
				bbox = Area.getBBox(Collections.singletonList(c));
				polygons = qt.get(bbox);
				for (Element e : polygons) {
					Way poly = (Way) e;
					if (Java2DConverter.createHighPrecPolygon(poly.getPoints()).contains(c.getHighPrecLon(), c.getHighPrecLat())) {
						answer = true;
						break;
					}
				}
			} else if (el instanceof Way) {
				Way w = (Way) el;
				if (w.isComplete()) {
					bbox = Area.getBBox(w.getPoints());
					polygons = qt.get(bbox);
					if (!polygons.isEmpty()) {
						// combine all polygons which intersect the bbox of the element if possible
						Path2D.Double path = new Path2D.Double();
						for (Element e : polygons) {
							Way poly = (Way) e;
							Path2D polyPath = Java2DConverter.createPath2D(poly.getPoints());
							path.append(polyPath, false);
						}
						java.awt.geom.Area polygonsArea = new java.awt.geom.Area(path);
						List<Coord> intersections = new ArrayList<>();
						//TODO: need to know if this function is used in lines or polygons rules
						List<List<Coord>> mergedShapes = Java2DConverter.areaToShapes(polygonsArea);
						// brute force search for intersections between way segments
						for (List<Coord> shape : mergedShapes) {
							for (int i = 0; i < shape.size(); i++) {
								Coord p11 = shape.get(i);
								Coord p12 = shape.get(i + 1 < shape.size() ? i+1 : 0); 
								for (int k = 0; k < w.getPoints().size() - 1; k++) {
									Coord p21 = w.getPoints().get(k);
									Coord p22 = w.getPoints().get(k + 1);
									Coord x = Utils.getSegmentSegmentIntersection(p11, p12, p21, p22);
									if (x != null) {
										intersections.add(x);
										// intersection found, maybe they are crossing, maybe they are touching 
										double x1 = (double)p21.getHighPrecLon()/ (1<<Coord.DELTA_SHIFT); 
										double y1 = (double)p21.getHighPrecLat() / (1<<Coord.DELTA_SHIFT); 
										double x2 = (double)p22.getHighPrecLon()/ (1<<Coord.DELTA_SHIFT); 
										double y2 = (double)p22.getHighPrecLat() / (1<<Coord.DELTA_SHIFT); 

										// TODO: replace area.contains() 
										// with method that returns only
										// true for nodes which are inside
										// and not on the boundary
										if (polygonsArea.contains(x1,y1) != polygonsArea.contains(x2,y2)) {
											if ("any".equals(params.get(2)))
												return String.valueOf(true);
											if ("all".equals(params.get(2)))
												return String.valueOf(false);
										}
									}
								}
							}
						}
						// found no intersection, way is either inside or outside, use result for 1st point
						// TODO: make sure that tested node is not on boundary
						double x1 = (double) w.getFirstPoint().getHighPrecLon() / (1 << Coord.DELTA_SHIFT);
						double y1 = (double) w.getFirstPoint().getHighPrecLat() / (1 << Coord.DELTA_SHIFT);
						answer = polygonsArea.contains(x1, y1);
					}
				}
			}
		}
		if (answer) {
//			log.error(el.toBrowseURL(), params, answer);
		}
		return String.valueOf(answer);
	}

	@Override
	public String value(Element el) {
		return calcImpl(el);
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
		// TODO: check what is needed for class ExpressionArranger and RuleSet.compile()
		return super.toString() + params;
	}

	@Override
	public void augmentWith(uk.me.parabola.mkgmap.reader.osm.ElementSaver elementSaver) {
		List<Element> matchingPolygons = new ArrayList<>();
		for (Way w : elementSaver.getWays().values()) {
			if (w.isComplete() && w.hasIdenticalEndPoints()) {
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
}
