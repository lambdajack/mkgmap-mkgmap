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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.reader.osm.boundary.Boundary;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryQuadTree;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.Java2DConverter;

/**
 * Hook to add tag mkgmap:residential to elements that lie within a landuse=residential area.
 * @author Gerd Petermann
 *
 */
public class ResidentialHook implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(ResidentialHook.class);

	
	private BoundaryQuadTree residentialBoundaries;
	
	private ElementSaver saver;
	private NameFinder nameFinder;

	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props, Style style) {
		if (!props.getProperty("residential-hook", true))
			return false; 
		this.nameFinder = new NameFinder(props);
		this.saver = saver;
		return true;
	}

	@Override
	public void end() {
		log.info("Starting with residential hook");

		long t1 = System.currentTimeMillis();
		residentialBoundaries = buildResidentialBoundaryTree();
		long t2 = System.currentTimeMillis();
		log.info("Creating residential bounds took", (t2 - t1), "ms");
		
		// process all nodes that might be converted to a garmin node (tagcount > 0)
		for (Node node : saver.getNodes().values()) {
			if (node.getTagCount() > 0 && saver.getBoundingBox().contains(node.getLocation())) {
				processElem(node);
			}
		}

		// process  all ways that might be converted to a garmin way (tagcount > 0)
		for (Way way : saver.getWays().values()) {
			if (way.getTagCount() > 0) {
				processElem(way);
			}
		}
		long t3 = System.currentTimeMillis();
		log.info("Using residential bounds took", (t3 - t2), "ms");
		// free memory
		residentialBoundaries = null;
	}

	private static final short TK_LANDUSE = TagDict.getInstance().xlate("landuse"); 
	private static final short TK_NAME = TagDict.getInstance().xlate("name");  
	private static final short TKM_STYLEFILTER = TagDict.getInstance().xlate("mkgmap:stylefilter");
	private static final short TKM_OTHER = TagDict.getInstance().xlate("mkgmap:other");
	
	private BoundaryQuadTree buildResidentialBoundaryTree() {
		List<Boundary> residentials = new ArrayList<>();
		Tags tags = new Tags();
		
		for (Way way : saver.getWays().values()) {
			if (way.hasIdenticalEndPoints() && "residential".equals(way.getTag(TK_LANDUSE))) {
				if ("polyline".equals(way.getTag(TKM_STYLEFILTER)))
					continue;
				String name = nameFinder.getName(way);
				tags.put(TK_NAME, name == null ? "yes": name);
				Boundary b = new Boundary(Java2DConverter.createArea(way.getPoints()), tags, "w"+way.getId());
				residentials.add(b);
			}
		}
		
		return new BoundaryQuadTree(saver.getBoundingBox(), residentials, null);
	}

	/**
	 * Extract the location info and perform a test 
	 * against the BoundaryQuadTree. If found, assign the tag.
	 * @param elem A way or Node
	 */
	private void processElem(Element elem){
		Tags residentialTags = null;

		if (elem instanceof Node){
			Node node = (Node) elem;
			residentialTags = residentialBoundaries.get(node.getLocation());
		} else if (elem instanceof Way){
			Way way = (Way) elem;
			// try the mid point of the way first
			int middle = way.getPoints().size() / 2;
			Coord loc = way.hasIdenticalEndPoints() ? way.getCofG() : way.getPoints().get(middle);
			if (! "residential".equals(way.getTag(TK_LANDUSE)))
				residentialTags = residentialBoundaries.get(loc);
		}

		if (residentialTags != null) {
			elem.addTag("mkgmap:residential", residentialTags.get(TKM_OTHER));
		}
	}
	
	@Override
	public Set<String> getUsedTags() {
		return new HashSet<>(Arrays.asList("landuse", "name"));
	}
}


