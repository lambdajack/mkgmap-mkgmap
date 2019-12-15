/*
 * Copyright (C) 2017.
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

package uk.me.parabola.mkgmap.reader.osm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.reader.osm.boundary.Boundary;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryQuadTree;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.Java2DConverter;

/**
 * Hook to add information about given landuse areas to an element. If the element lies within the given area xxx
 * a tag mkgmap:lu:xxx is added. Example:
 * tag mkgmap:lu:cemetery is added to elements that lie within a landuse=cemetery area.
 * Special case: Legacy support for old ResidentialHook
 * If style uses mkgmap:residential instead of mkgmap:lu:residential we add this tag.
 * 
 * @author Gerd Petermann
 *
 */
public class LanduseHook implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(LanduseHook.class);

	private static final String LANDUSE_PREFIX = "mkgmap:lu:";
	private Map<String, BoundaryQuadTree> landuseTreeMap = new LinkedHashMap<>();
	private String[] wantedTrees;
	private boolean legacyStyle;
	private ElementSaver saver;
	private NameFinder nameFinder;

	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props) {
		String opt = props.getProperty("is-in-landuse");
		if (!props.getProperty("residential-hook", true)) {
			System.err.println("Found undocumented option residential-hook=false, is now ignored, please see option --is-in-landuse");
		}
		wantedTrees = getCommaSeparatedTrimmedList(opt);
		if (wantedTrees.length == 0) 
			return false; // hook is disabled
		
		legacyStyle = props.getProperty(OsmMapDataSource.ADD_MKGMAP_RESIDENTIAL, false);
		this.nameFinder = new NameFinder(props);
		this.saver = saver;
		return true;
	}
	
	/**
	 * Compute list of values from a comma separated list, remove enclosing hyphens and blanks
	 * @param s the string to parse
	 * @return array of strings, length = 0 if s is null or empty
	 */
	private static String[] getCommaSeparatedTrimmedList(String s) {
		String[] list = {};
		if (s != null) {
			if (s.startsWith("'") || s.startsWith("\""))
				s = s.substring(1);
			if (s.endsWith("'") || s.endsWith("\""))
				s = s.substring(0, s.length() - 1);
			list = s.split(",");
			for (int i = 0; i < list.length; i++) {
				list[i] = list[i].trim();
			}
		}
		return list;
	}


	@Override
	public void end() {
		log.info("Starting with landuse hook");
		long t1 = System.currentTimeMillis();
		for (String landuse: wantedTrees) {
			landuseTreeMap.put(landuse, buildInsideBoundaryTree(landuseTagKey, landuse));
		}
		long t2 = System.currentTimeMillis();
		log.info("Creating landuse bounds took", (t2 - t1), "ms");
		
		// process all nodes that might be converted to a garmin node (tag count > 0)
		for (Node node : saver.getNodes().values()) {
			if (node.getTagCount() > 0 && saver.getBoundingBox().contains(node.getLocation())) {
				processElem(node);
			}
		}

		// process  all ways that might be converted to a garmin way (tag count > 0)
		for (Way way : saver.getWays().values()) {
			if (way.getTagCount() > 0) {
				processElem(way);
			}
		}
		long t3 = System.currentTimeMillis();
		log.info("Using landuse hook took", (t3 - t2), "ms");
		// free memory
		landuseTreeMap.clear();
	}

	private static final short landuseTagKey = TagDict.getInstance().xlate("landuse"); 
	private static final short nameTagKey = TagDict.getInstance().xlate("name");  
	private static final short styleFilterTagKey = TagDict.getInstance().xlate("mkgmap:stylefilter");
	private static final short otherKey = TagDict.getInstance().xlate("mkgmap:other");
	
	private BoundaryQuadTree buildInsideBoundaryTree(short tagKey, String val) {
		List<Boundary> rings = new ArrayList<>();
		Tags tags = new Tags();

		for (Way way : saver.getWays().values()) {
			if (way.hasIdenticalEndPoints() && val.equals(way.getTag(tagKey))) {
				if ("polyline".equals(way.getTag(styleFilterTagKey)))
					continue;
				String name = nameFinder.getName(way);
				tags.put(nameTagKey, name == null ? "yes" : name);
				Boundary b = new Boundary(Java2DConverter.createArea(way.getPoints()), tags, "w" + way.getId());
				rings.add(b);
			}
		}
		log.info("adding ", rings.size(), "areas for", val, "to spatial index");
		return new BoundaryQuadTree(saver.getBoundingBox(), rings, null);
	}

	/**
	 * Extract the location info and perform a test 
	 * against the BoundaryQuadTree. If found, assign the tag.
	 * @param elem A way or Node
	 */
	private void processElem(Element elem) {
		for (Entry<String, BoundaryQuadTree> entry : landuseTreeMap.entrySet()) {
			BoundaryQuadTree tree = entry.getValue();
			String tagVal = entry.getKey();
			Tags isinTags = null;
			if (elem instanceof Node) {
				Node node = (Node) elem;
				isinTags = tree.get(node.getLocation());
			} else if (elem instanceof Way) {
				Way way = (Way) elem;
				// try the mid point of the way first
				int middle = way.getPoints().size() / 2;
				Coord loc = way.hasIdenticalEndPoints() ? way.getCofG() : way.getPoints().get(middle);
				if (!tagVal.equals(way.getTag(landuseTagKey)))
					isinTags = tree.get(loc);
			}

			if (isinTags != null) {
				String prefix = LANDUSE_PREFIX;
				if (legacyStyle && "residential".equals(tagVal)) {
					prefix = "mkgmap:";
				}
				elem.addTag(prefix + tagVal, isinTags.get(otherKey));
			}
		}
	}
	
	@Override
	public Set<String> getUsedTags() {
		Set<String> used = new HashSet<>();
		used.add("landuse");
		used.add("name");
		return used;
	}
}
