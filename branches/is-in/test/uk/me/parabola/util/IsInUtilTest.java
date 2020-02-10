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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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

public class IsInUtilTest {

	public static List<String> testWithVariants(FeatureKind kind, Element el, String name, Set<Way> polygons) {
		List<String> errors = new ArrayList<>();
		int res = IsInUtil.calcInsideness(kind, el, polygons);
		
		String expectedVal = el.getTag("expected");
		if (expectedVal != null && !"?".equals(expectedVal)) {
			int expected = Integer.parseInt(expectedVal);
			if (expected != res) {
				errors.add(name + " failed, expected: " + expected + " got "+ res);
				return errors;
			}
			if (el instanceof Way) {
				Way w2 = (Way) el.copy();
				Collections.reverse(w2.getPoints());
				int res2 = IsInUtil.calcInsideness(kind, w2, polygons);
				if (expected != res2) {
					errors.add(name + " failed reversed, expected: " + expected + " got " + res2);
				}
				if (w2.hasIdenticalEndPoints()) {
					List<Coord> points = w2.getPoints();
					for (int i = 1; i < w2.getPoints().size(); i++) {
						points.remove(points.size() - 1);
						Collections.rotate(points, 1);
						points.add(points.get(0));
						res2 = IsInUtil.calcInsideness(kind, w2, polygons);
						if (expected != res2) {
							errors.add(name + " failed rotated " + i + " , expected: " + expected + " got " + res2);
						}
					}
				}
			}
		}
		return errors;
	}
	
	/**
	 * A very basic check that the size of all the sections has not changed.
	 * This can be used to make sure that a change that is not expected to
	 * change the output does not do so.
	 *
	 * The sizes will have to be always changed when the output does change
	 * though.
	 */
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
