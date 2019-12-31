package uk.me.parabola.mkgmap.osmstyle.function;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.ElementQuadTree;
import uk.me.parabola.util.Java2DConverter;

public class IsInFunction extends StyleFunction {

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
						//TODO: need to know if this function is used in lines or polygons rules
						if (w.hasIdenticalEndPoints()) {
							java.awt.geom.Area a = Java2DConverter.createArea(w.getPoints());
							java.awt.geom.Area inter = new java.awt.geom.Area(a);
							inter.intersect(polygonsArea);
							if (!inter.isEmpty()) {
								if ("any".equals(params.get(2))) {
									answer = true;
								} else if ("all".equals(params.get(2))) {
									answer = polygonsArea.getBounds2D().contains(a.getBounds2D()) && inter.equals(a);
								}
							}
						} else {
							// TODO: Check if POLYLINE is inside polygons 
						}
					}
				}
			}
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
