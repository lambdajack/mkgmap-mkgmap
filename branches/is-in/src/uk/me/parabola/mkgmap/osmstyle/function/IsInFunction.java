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
public class IsInFunction extends StyleFunction {

	private enum MethodArg {
		// TODO: also maybe aliases, which flags can stop processing, etc

		POINT_IN("in", FeatureKind.POINT),
		POINT_IN_OR_ON("in_or_on", FeatureKind.POINT),
		POINT_ON("on", FeatureKind.POINT),

		LINE_SOME_IN_NONE_OUT("all", FeatureKind.POLYLINE),
		LINE_ALL_IN_OR_ON("all_in_or_on", FeatureKind.POLYLINE),
		LINE_ALL_ON("on", FeatureKind.POLYLINE),
		LINE_ANY_IN("any", FeatureKind.POLYLINE),
		LINE_ANY_IN_OR_ON("any_in_or_on", FeatureKind.POLYLINE),

		POLYGON_ALL("all", FeatureKind.POLYGON),
		POLYGON_ANY("any", FeatureKind.POLYGON);

		private final String methodName;
		private final FeatureKind kind;

		MethodArg(String methodName, FeatureKind kind) {
			this.methodName = methodName;
			this.kind = kind;
		}

		public String toString() {
			return methodName;
		}

		public FeatureKind getKind() {
			return kind;
		}

/*
following 3 methods are not implement yet and not applicable to current interface of IsInUtil.calcInsideness
An idea was that it would call setIn/On/Out and logic flags here would know if this was enough to
determine the answer and stop further processing. probably with flag per IN/ON/OUT for each enum...
*/
		public boolean canStopIn() {
			return false;
		}
		public boolean canStopOn() {
			return false;
		}
		public boolean canStopOut() {
			return false;
		}
	}

	private class CanStopProcessing extends RuntimeException {};

	private MethodArg method;
	private boolean hasIn = false;
	private boolean hasOn = false;
	private boolean hasOut = false;
	private ElementQuadTree qt;

	public IsInFunction() {
		super(null);
		reqdNumParams = 3; // ??? maybe have something to indicate variable...
		// 1: polygon tagName
		// 2: value for above tag
		// 3: method keyword, eg any, all, on...
	}

	protected String calcImpl(Element el) {
		if (qt == null || qt.isEmpty())
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
		return String.valueOf(mapHasFlagsAnswer());
	}

	@Override
	public String value(Element el) {
		return calcImpl(el);
	}
	
	@Override
	public void setParams(List<String> params, FeatureKind kind) {
		super.setParams(params, kind);
		String methodStr = params.get(2);
		for (MethodArg tstMethod : MethodArg.values()) {
			if (tstMethod.getKind() == kind && methodStr.equalsIgnoreCase(tstMethod.toString())) {
				this.method = tstMethod;
				return;
			}
		}
		throw new SyntaxException(String.format("Third parameter '%s' of function %s is not supported", methodStr, getName()));
	}

	private void setIn() {
		hasIn = true;
		if (method.canStopIn())
			throw new CanStopProcessing();
	}

	private void setOn() {
		hasOn = true;
		if (method.canStopOn())
			throw new CanStopProcessing();
	}
	private void setOut() {
		hasOut = true;
		if (method.canStopOut())
			throw new CanStopProcessing();
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

	private void doPointTest(Node el) {
		doCommonTest(el);
	}

	private void doLineTest(Way el) {
		doCommonTest(el);
	}

	private void doPolygonTest(Way el) {
		doCommonTest(el);
	}

	private void doCommonTest(Element el) {
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
		if ((flags & IsInUtil.IN) != 0)
			setIn();
		if ((flags & IsInUtil.ON) != 0)
			setOn();
		if ((flags & IsInUtil.OUT) != 0)
			setOut();
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
	public void augmentWith(ElementSaver elementSaver) {
		if (qt != null)
			return;
		qt = buildTree(elementSaver, params.get(0), params.get(1));
	}

	
	public static ElementQuadTree buildTree(ElementSaver elementSaver, String tagKey, String tagVal) {
		List<Element> matchingPolygons = new ArrayList<>();
		for (Way w : elementSaver.getWays().values()) {
			if (w.isComplete() && w.hasIdenticalEndPoints()
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

	@Override
	public int getComplexity() {
		return 5;
	}
}
