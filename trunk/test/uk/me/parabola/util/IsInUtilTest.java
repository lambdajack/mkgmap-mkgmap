/*
 * Copyright (C) 2020.
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

import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import func.lib.Args;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.osmstyle.function.IsInFunction;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FeatureKind;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmMapDataSource;
import uk.me.parabola.mkgmap.reader.osm.Way;

/*
Source: test/resources/in/osm/is-in-samples.osm
errors: test-reports/uk/me/parabola/util/62_IsInUtilTest-err.html
*/

public class IsInUtilTest {

	Area testSourceBbox = null;

	private static final String allPointMethods = "in,in_or_on,on";
	private static final String allLineMethods = "all,all_in_or_on,on,any,none";
	private static final String allPolygonMethods = "all,any";

	private static final Map<Integer, String> pointMethods = new HashMap<>();
	private static final Map<Integer, String> lineMethods = new HashMap<>();
	private static final Map<Integer, String> polygonMethods = new HashMap<>();

	public IsInUtilTest() {
		// set up the methods that should return true for the 'expected' value
		pointMethods.put(1, "in,in_or_on");
		pointMethods.put(2, "in_or_on,on");
		pointMethods.put(4, "");

/* all=someInNoneOut, any=anyIn, none=someOutNoneIn
     1  2  4
a 1) IN        all allInOrOn    any
b 3) IN ON     all allInOrOn    any
c 7) IN ON OUT                  any
d 2)    ON         allInOrOn on
e 6)    ON OUT                      none
f 4)       OUT                      none
*/
		lineMethods.put(1, "all,all_in_or_on,any");
		lineMethods.put(2, "all_in_or_on,on");
		lineMethods.put(3, "all,all_in_or_on,any");
		lineMethods.put(4, "none");
		//lineMethods.put(5, "");
		lineMethods.put(6, "none");
		lineMethods.put(7, "any");

		polygonMethods.put(1, "all,any");
		polygonMethods.put(2, "all,any");
		polygonMethods.put(3, "all,any");
		polygonMethods.put(4, "");
		//polygonMethods.put(5, "");
		polygonMethods.put(6, "");
		polygonMethods.put(7, "any");
	}

	private static boolean invokeMethod(IsInFunction anInst, String method, FeatureKind kind, Element el) {
		anInst.setParams(Arrays.asList("landuse", "residential", method), kind); // tag key/value don't matter
		String rslt = anInst.calcImpl(el);
		return "true".equals(rslt);
	}

	public List<String> testWithVariants(FeatureKind kind, Element el, String name, Set<Way> polygons) {
		List<String> errors = new ArrayList<>();

		IsInFunction anInst = new IsInFunction();
		List<Element> matchingPolygons = new ArrayList<>();
		for (Way polygon : polygons)
			matchingPolygons.add(polygon);
		anInst.unitTestAugment(new ElementQuadTree(testSourceBbox, matchingPolygons));
		
		String expectedVal = el.getTag("expected");
		if (expectedVal != null && !"?".equals(expectedVal)) {
			int expected = Integer.parseInt(expectedVal);
			String allMethods = "";
			Map<Integer, String> methods = null;
			switch (kind) {
			case POINT:
				allMethods = allPointMethods;
				methods = pointMethods;
				break;
			case POLYLINE:
				allMethods = allLineMethods;
				methods = lineMethods;
				break;
			case POLYGON:
				allMethods = allPolygonMethods;
				methods = polygonMethods;
				break;
			}
			if (!methods.containsKey(expected)) {
				errors.add(name + " failed, no methods for expected: " + expectedVal);
				return errors;
			}
			String[] trueMethods = methods.get(expected).split(",");
			if (trueMethods[0].isEmpty())
				trueMethods = new String[0];
			List<String> falseMethods = new ArrayList<>();
			for (String tstMethod : allMethods.split(",")) {
				boolean inList = false;
				for (String trueMethod : trueMethods)
					if (tstMethod.equals(trueMethod)) {
						inList = true;
						break;
					}
				if (!inList)
					falseMethods.add(tstMethod);
			}

			for (String tstMethod : trueMethods)
				if (!invokeMethod(anInst, tstMethod, kind, el))
					errors.add(name + " failed, expected: " + expectedVal + ". " + tstMethod + " should be true");
			for (String tstMethod : falseMethods)
				if (invokeMethod(anInst, tstMethod, kind, el))
					errors.add(name + " failed, expected: " + expectedVal + ". " + tstMethod + " should be false");

			if (!errors.isEmpty() || !(el instanceof Way))
				return errors;
			Way w2 = (Way) el.copy();
			Collections.reverse(w2.getPoints());
			for (String tstMethod : trueMethods)
				if (!invokeMethod(anInst, tstMethod, kind, w2))
					errors.add(name + " failed reversed, expected: " + expectedVal + ". " + tstMethod + " should be true");
			for (String tstMethod : falseMethods)
				if (invokeMethod(anInst, tstMethod, kind, w2))
					errors.add(name + " failed reversed, expected: " + expectedVal + ". " + tstMethod + " should be false");

			if (!errors.isEmpty() || !w2.hasIdenticalEndPoints())
				return errors;
			List<Coord> points = w2.getPoints();
			for (int i = 1; i < w2.getPoints().size(); i++) {
				points.remove(points.size() - 1);
				Collections.rotate(points, 1);
				points.add(points.get(0));
				for (String tstMethod : trueMethods)
					if (!invokeMethod(anInst, tstMethod, kind, w2))
						errors.add(name + " failed rotated, expected: " + expectedVal + ". " + tstMethod + " should be true");
				for (String tstMethod : falseMethods)
					if (invokeMethod(anInst, tstMethod, kind, w2))
						errors.add(name + " failed rotated, expected: " + expectedVal + ". " + tstMethod + " should be false");
			}
		}
		return errors;
	}

	@Test
	public void testBasic() throws FileNotFoundException {

		// just loads the file
		class TestSource extends OsmMapDataSource {
			@Override
			public Set<String> getUsedTags() {
				// return null => all tags are used
				return null;
			}
			
			@Override
			public void load(String name, boolean addBackground) throws FileNotFoundException {
				try (InputStream is = Utils.openFile(name)) {
					parse(is, name);
				} catch (IOException e) {
					// exception thrown from implicit call to close() on resource variable 'is'
				}
				
				elementSaver.finishLoading();
			}
		}
		TestSource src = new TestSource();
		src.config(new EnhancedProperties());
		src.load(Args.TEST_RESOURCE_OSM + "is-in-samples.osm", false);
		testSourceBbox = src.getElementSaver().getBoundingBox();
		
		ElementQuadTree qt = IsInFunction.buildTree(src.getElementSaver(), "landuse", "residential");
		ArrayList<String> allErrors = new ArrayList<>();
		for (Node n: src.getElementSaver().getNodes().values()) {
			String name = n.getTag("name");
			if (name != null) {
				Area elementBbox = Area.getBBox(Collections.singletonList((n).getLocation()));
				Set<Way> polygons = qt.get(elementBbox).stream().map(e -> (Way) e)
						.collect(Collectors.toCollection(LinkedHashSet::new));
				allErrors.addAll(testWithVariants(FeatureKind.POINT, n, name, polygons));
			}
		}
		for (Way w: src.getElementSaver().getWays().values()) {
			String name = w.getTag("name");
			if (name != null) {
				Area elementBbox = Area.getBBox(w.getPoints());
				Set<Way> polygons = qt.get(elementBbox).stream().map(e -> (Way) e)
						.collect(Collectors.toCollection(LinkedHashSet::new));
				if (name.startsWith("w"))
					allErrors.addAll(testWithVariants(FeatureKind.POLYLINE, w, name, polygons));
				else
					allErrors.addAll(testWithVariants(FeatureKind.POLYGON, w, name, polygons));
			}
		}
		for (String msg : allErrors) {
			System.err.println(msg);
		}
		assertTrue("Found errors. Check System.err content", allErrors.isEmpty());
	}
}
