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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.ElementSaver;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.scan.SyntaxException;
import uk.me.parabola.util.ElementQuadTree;
import uk.me.parabola.util.IsInUtil;

/**
 *
 * @author Ticker Berkin
 *
 */
public class IsInFunction extends CachedFunction { // StyleFunction
	private static final Logger log = Logger.getLogger(IsInFunction.class);

	private enum MethodArg {

		//                                       can stop when: IN     ON     OUT
		POINT_IN("in",                    FeatureKind.POINT,    true,  false, false),
		POINT_IN_OR_ON("in_or_on",        FeatureKind.POINT,    true,  true,  false),
		POINT_ON("on",                    FeatureKind.POINT,    false, true,  false),

		LINE_SOME_IN_NONE_OUT("all",      FeatureKind.POLYLINE, false, false, true),
		LINE_ALL_IN_OR_ON("all_in_or_on", FeatureKind.POLYLINE, false, false, true),
		LINE_ALL_ON("on",                 FeatureKind.POLYLINE, true,  false, true),
		LINE_ANY_IN("any",                FeatureKind.POLYLINE, true,  false, false),
		LINE_ANY_IN_OR_ON("any_in_or_on", FeatureKind.POLYLINE, true,  true,  false),

		POLYGON_ALL("all",                FeatureKind.POLYGON,  false, false, true),
		POLYGON_ANY("any",                FeatureKind.POLYGON,  true,  false, false);

		private final String methodName;
		private final FeatureKind kind;
		private final boolean stopIn;
		private final boolean stopOn;
		private final boolean stopOut;

		MethodArg(String methodName, FeatureKind kind, boolean stopIn, boolean stopOn, boolean stopOut) {
			this.methodName = methodName;
			this.kind = kind;
			this.stopIn = stopIn;
			this.stopOn = stopOn;
			this.stopOut = stopOut;
		}

		public String toString() {
			return methodName;
		}

		public FeatureKind getKind() {
			return kind;
		}

		public boolean canStopIn() {
			return stopIn;
		}
		public boolean canStopOn() {
			return stopOn;
		}
		public boolean canStopOut() {
			return stopOut;
		}
	}

	private class CanStopProcessing extends RuntimeException {};

	private MethodArg method;
	private boolean hasIn;
	private boolean hasOn;
	private boolean hasOut;
	private ElementQuadTree qt = null;

	public IsInFunction() {
		super(null);
		reqdNumParams = 3;
		// 1: polygon tagName
		// 2: value for above tag
		// 3: method keyword, see above
		log.info("isInFunction", System.identityHashCode(this));
	}

	private void resetHasFlags() {
		// the instance is per unique call in rules, then applied repeatedly to each point/line/polygon
		hasIn = false;
		hasOn = false;
		hasOut = false;
	}

	public String calcImpl(Element el) {
		log.info("calcImpl", System.identityHashCode(this), kind, params, el);
		assert qt != null : "invoked the non-augmented instance";
	    	resetHasFlags();

		if (qt.isEmpty())
			return String.valueOf(false);
		try {
			switch (kind) {
			case POINT:
				doPointTest((Node) el);
				break;
			case POLYLINE:
				doLineTest((Way) el);
				break;
			case POLYGON:
				doPolygonTest((Way) el);
				break;
			}
		} catch (CanStopProcessing e) {}
		log.info("done", hasIn, hasOn, hasOut);
		return String.valueOf(mapHasFlagsAnswer());
	}

/* don't have this for CachedFunction
	@Override
	public String value(Element el) {
		return calcImpl(el);
	}
*/

	@Override
	public void setParams(List<String> params, FeatureKind kind) {
		super.setParams(params, kind);
		log.info("setParams", System.identityHashCode(this), kind, params);
		String methodStr = params.get(2);
		boolean knownMethod = false;
		List<String> methodsForKind = new ArrayList<>();
		for (MethodArg tstMethod : MethodArg.values()) {
			if (methodStr.equalsIgnoreCase(tstMethod.toString())) {
				if (tstMethod.getKind() == kind) {
					this.method = tstMethod;
					return;
				} else
					knownMethod = true;
			} else if (tstMethod.getKind() == kind)
				methodsForKind.add(tstMethod.toString());
		}
		throw new SyntaxException(String.format("Third parameter '%s' of function %s is not " +
					(knownMethod ? "supported for this style section" : "understood") +
					", valid are: %s" , methodStr, getName(), methodsForKind));
	}

	private void setIn() {
		log.info("setIn", hasIn, hasOn, hasOut);
		hasIn = true;
		if (method.canStopIn() || (hasOn && hasOut))
			throw new CanStopProcessing();
	}

	private void setOn() {
		log.info("setOn", hasIn, hasOn, hasOut);
		hasOn = true;
		if (method.canStopOn() || (hasIn && hasOut))
			throw new CanStopProcessing();
	}
	private void setOut() {
		log.info("setOut", hasIn, hasOn, hasOut);
		hasOut = true;
		if (method.canStopOut() || (hasIn && hasOn))
			throw new CanStopProcessing();
	}

	private void setHasFromFlags(int flags) {
		if ((flags & IsInUtil.IN) != 0)
			setIn();
		if ((flags & IsInUtil.ON) != 0)
			setOn();
		if ((flags & IsInUtil.OUT) != 0)
			setOut();
	}

	private boolean mapHasFlagsAnswer() {
		switch (method) {
		case POINT_IN:
			return hasIn;
		case POINT_IN_OR_ON:
			return hasIn || hasOn;
		case POINT_ON:
			return hasOn;
		case LINE_SOME_IN_NONE_OUT:
			return hasIn && !hasOut;
		case LINE_ALL_IN_OR_ON:
			return !hasOut;
		case LINE_ALL_ON:
			return !(hasIn || hasOut);
		case LINE_ANY_IN:
			return hasIn;
		case LINE_ANY_IN_OR_ON:
			return hasIn || hasOn;
		case POLYGON_ALL:
			return !hasOut;
		case POLYGON_ANY:
			return hasIn;
		}
		return false;
	}

	private static boolean notInHole(Coord c, List<List<Coord>> holes) {
		if (holes == null)
			return true;
		for (List<Coord> hole : holes)
			if (IsInUtil.insidePolygon(c, true, hole))
				return false;
		return true;
	}

	private void checkPointInShape(Coord c, List<Coord> shape, List<List<Coord>> holes) {
		/*
		Because we are processing polygons one-by-one, OUT is only meaningful once we have
		checked all the polygons and haven't satisfied IN/ON, so no point is calling setOut()
		and it wouldn't stop the processing or effect the answer anyway
		*/
		switch (method) { // Use the method to control the onBoundary condition of insidePolygon.
		case POINT_IN:
			if (IsInUtil.insidePolygon(c, false, shape))
				if (notInHole(c, holes))
					setIn();
				else // in hole in this shape, no point in looking at more shapes
					throw new CanStopProcessing();
			break;
		case POINT_IN_OR_ON:
			if (IsInUtil.insidePolygon(c, true, shape))
				// no need to check holes for this as didn't need to merge polygons
				setIn(); // don't care about setOn()
			break;
		case POINT_ON:
			if (IsInUtil.insidePolygon(c, true, shape) &&
			    !IsInUtil.insidePolygon(c, false, shape))
				// hole checking is a separate pass
				setOn(); // don't care about setIn()
			break;
		}
	}

	private void doPointTest(Node el) {
		Coord c = el.getLocation();
		Area elementBbox = Area.getBBox(Collections.singletonList(c));
		Set<Way> polygons = qt.get(elementBbox).stream().map(e -> (Way) e)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if ((method == MethodArg.POINT_IN || method == MethodArg.POINT_ON) && polygons.size() > 1) {
			// need to merge shapes so that POI on shared boundary becomes IN rather than ON
			List<List<Coord>> outers = new ArrayList<>();
			List<List<Coord>> holes = new ArrayList<>();
			IsInUtil.mergePolygons(polygons, outers, holes);
			log.info("pointMerge", polygons.size(), outers.size(), holes.size());
			for (List<Coord> shape : outers)
				checkPointInShape(c, shape, holes);
			if (method == MethodArg.POINT_ON && !holes.isEmpty())
				// need to check if on edge of hole
				for (List<Coord> hole : holes)
					checkPointInShape(c, hole, null);
		} else { // just one polygon or IN_OR_ON, which can do one-by-one
			log.info("point1by1", polygons.size());
			for (Way polygon : polygons)
				checkPointInShape(c, polygon.getPoints(), null);
		}
	}

	private void doLineTest(Way el) {
		doCommonTest(el);
	}

	private void doPolygonTest(Way el) {
		doCommonTest(el);
	}

	private void checkHoles(List<Coord> polyLine, List<List<Coord>> holes, Area elementBbox) {
		for (List<Coord> hole : holes) {
			int flags = IsInUtil.isLineInShape(kind, polyLine, hole, elementBbox);
			if ((flags & IsInUtil.IN) != 0) {
				setOut();
				if ((flags & IsInUtil.ON) != 0)
					setOn();
				if ((flags & IsInUtil.OUT) != 0)
					setIn();
				return;
			}
		}
	}

	private void doCommonTest(Element el) {
		List<Coord> polyLine = ((Way)el).getPoints();
		Area elementBbox = Area.getBBox(polyLine);
		Set<Way> polygons = qt.get(elementBbox).stream().map(e -> (Way) e)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if ((method == MethodArg.LINE_SOME_IN_NONE_OUT ||
		     method == MethodArg.LINE_ALL_IN_OR_ON ||
		     method == MethodArg.LINE_ALL_ON ||
		     method == MethodArg.POLYGON_ALL) && polygons.size() > 1) {
			// ALL-like methods need to merge shapes
			List<List<Coord>> outers = new ArrayList<>();
			List<List<Coord>> holes = new ArrayList<>();
			IsInUtil.mergePolygons(polygons, outers, holes);
			log.info("polyMerge", polygons.size(), outers.size(), holes.size());
			for (List<Coord> shape : outers) {
				int flags = IsInUtil.isLineInShape(kind, polyLine, shape, elementBbox);
				if ((flags & (IsInUtil.IN | IsInUtil.ON)) != 0) {
				    	// this shape is the one to consider
					setHasFromFlags(flags); // might set OUT and stop
					if ((flags & IsInUtil.IN) != 0)
						checkHoles(polyLine, holes, elementBbox);
					break;
				}
			}
		} else { // an ANY-like method
			log.info("poly1by1", polygons.size());
			for (Way polygon : polygons)
				setHasFromFlags(IsInUtil.isLineInShape(kind, polyLine, polygon.getPoints(), elementBbox));
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
	protected String getCacheTag() {
		return "mkgmap:cache_is_in_" + kind + "_" + String.join("_", params);
	}

	@Override
	public void augmentWith(ElementSaver elementSaver) {
		log.info("augmentWith", System.identityHashCode(this), kind, params);
		// the cached function mechanism creates an instance for each occurance in the rule file
		// but then just uses one of them for augmentWith() and calcImpl().
		if (qt != null)
			return;
		qt = buildTree(elementSaver, params.get(0), params.get(1));
	}

	public static ElementQuadTree buildTree(ElementSaver elementSaver, String tagKey, String tagVal) {
		List<Element> matchingPolygons = new ArrayList<>();
		for (Way w : elementSaver.getWays().values()) {
			if (w.hasIdenticalEndPoints()
					&& !"polyline".equals(w.getTag(MultiPolygonRelation.STYLE_FILTER_TAG))) {
				String val = w.getTag(tagKey);
				if (val != null && val.equals(tagVal)) {
					matchingPolygons.add(w);
				}
			}
		}
		if (!matchingPolygons.isEmpty()) {
			return new ElementQuadTree(elementSaver.getBoundingBox(), matchingPolygons);
		}
		return null;
	}

	public void unitTestAugment(ElementQuadTree qt) {
		this.qt = qt;
	}

	@Override
	public int getComplexity() {
		return 5;
	}
}
