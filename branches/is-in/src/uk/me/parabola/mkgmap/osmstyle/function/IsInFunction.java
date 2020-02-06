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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.scan.SyntaxException;
import uk.me.parabola.util.ElementQuadTree;
import uk.me.parabola.util.IsInUtil;

/**
 * 
 * @author Ticker Berkin
 *
 */
public class IsInFunction extends StyleFunction {
	private static final Logger log = Logger.getLogger(IsInFunction.class);
	private static final boolean SIMULATE_UNIT_TEST = false; 

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
		Area elementBbox;
		if (kind == FeatureKind.POINT) {
			elementBbox = Area.getBBox(Collections.singletonList(((Node) el).getLocation()));
		} else {
			elementBbox = Area.getBBox(((Way)el).getPoints());
		}
		// cast Element type to Way
		Set<Way> polygons = qt.get(elementBbox).stream().map(e -> (Way) e)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		int flags = IsInUtil.calcInsideness(kind, el, polygons);
		// decode flags for any + all
		String mode = params.get(2);
		if (kind == FeatureKind.POINT && ((flags & IsInUtil.OUT) == 0)) 
			answer = true;
		else if ((flags & IsInUtil.IN) != 0 && ("any".equals(mode) || (flags & IsInUtil.OUT) == 0)) {
			answer = true;
		} else if (flags == (IsInUtil.ON | IsInUtil.HAS_COMMON_POINTS)) {
			answer = true;
		} 
		
		return String.valueOf(answer);
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
	public void setParams(List<String> params, FeatureKind kind) {
		super.setParams(params, kind);
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
		// see RuleSet.compile()
		return getName() + "(" + kind + ", " + String.join(", ", params) + ")";
	}

	@Override
	public void augmentWith(uk.me.parabola.mkgmap.reader.osm.ElementSaver elementSaver) {
		if (qt != null)
			return;
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

	@Override
	public int getComplexity() {
		return 5;
	}
}
