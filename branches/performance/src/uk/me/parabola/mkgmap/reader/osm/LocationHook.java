/*
 * Copyright (C) 2006, 2012.
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

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.build.LocatorUtil;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryGrid;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryQuadTree;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryUtil;
import uk.me.parabola.util.EnhancedProperties;

public class LocationHook extends OsmReadingHooksAdaptor {
	private static final boolean PRINT_RESULT = false;
	private static final Logger log = Logger.getLogger(LocationHook.class);
	long cntQTSearch = 0;
	long cntNotFnd = 0;
	long cntwayNotFnd = 0;
	
	private BoundaryGrid boundaryGrid;

	private ElementSaver saver;
	private final Set<String> autofillOptions = new HashSet<String>();
	
	//private File boundaryDir;
	private String boundaryDirName;

	
	public static final String BOUNDS_OPTION = "bounds";
	/** this static object is used to synchronize the check if the bounds directory contains any bounds */
	private static final Object BOUNDS_CHECK_LOCK = new Object();
	
	
	private static String checkedBoundaryDirName;
	private static boolean checkBoundaryDirOk;
	private EnhancedProperties props;

	public boolean init(ElementSaver saver, EnhancedProperties props) {
		if (props.containsKey("index") == false) {
			log.info("Disable LocationHook because index option is not set.");
			return false;
		}

		this.props = props;
		this.saver = saver;

		autofillOptions.addAll(LocatorUtil.parseAutofillOption(props));

		if (autofillOptions.isEmpty()) {
			log.info("Disable LocationHook because no location-autofill option set.");
			return false;
		}

		if (autofillOptions.contains(BOUNDS_OPTION)) {
			boundaryDirName = props.getProperty("bounds", "bounds");
			long t1 = System.currentTimeMillis();

			synchronized (BOUNDS_CHECK_LOCK) {
				// checking of the boundary dir is expensive
				// check once only and reuse the result
				if (boundaryDirName.equals(checkedBoundaryDirName)) {
					if (checkBoundaryDirOk == false) {
						log.error("Disable LocationHook because boundary directory is unusable. Dir: "+boundaryDirName);
						return false;
					}
				} else {
					checkedBoundaryDirName = boundaryDirName;
					checkBoundaryDirOk = false;

					// boundaryDir.list() is much quicker than boundaryDir.listFiles(FileFilter)
					List<String> boundaryFiles = BoundaryUtil.getBoundaryDirContent(boundaryDirName);
					if (boundaryFiles == null || boundaryFiles.size() == 0) {
						log.error("LocationHook is disabled because no boundary files are available. Dir: "
								+ boundaryDirName);
						return false;
					}

					// passed all checks => boundaries are okay
					checkBoundaryDirOk = true;
				}
			}
			log.info("Checking bounds dir took "
					+ (System.currentTimeMillis() - t1) + " ms");
		}
		return true;
	}

	public void end() {
		long t1 = System.currentTimeMillis();
		log.info("Starting with location hook");

		if (autofillOptions.contains(BOUNDS_OPTION)) {
			boundaryGrid = new BoundaryGrid(boundaryDirName, saver.getBoundingBox(), props);
			processLocationRelevantElements();
		}

		long dt = (System.currentTimeMillis() - t1);
		log.info("Location hook finished in "+
				dt+ " ms");
		System.out.println("======= Stats =====");             
		System.out.println("QuadTree searches    : "  + cntQTSearch);             
		System.out.println("unsuccesfull         : "  + cntNotFnd);             
		System.out.println("unsuccesfull for ways: "  + cntwayNotFnd);             
		System.out.println("Location hook finished in "+
				dt+ " ms");

	}

	/**
	 * Retrieve all elements for which the boundary assignment should be performed.
	 */
	private void processLocationRelevantElements() {
		// process all nodes that might be converted to a garmin node (tagcount > 0)
		for (Node node : saver.getNodes().values()) {
			if (node.getTagCount() > 0) {
				if (saver.getBoundingBox().contains(node.getLocation())){
					processElem(node);
					if (PRINT_RESULT)
						System.out.println("N " + node.getId() + " " + calcLocationTagsMask(node) 
								+ " " + tagsToString(node));
				}
			}
		}

		// process  all ways that might be converted to a garmin way (tagcount > 0)
		for (Way way : saver.getWays().values()) {
			if (way.getTagCount() > 0) {
				processElem(way);
				if (PRINT_RESULT)
					System.out.println("W " + way.getId() + " " + calcLocationTagsMask(way)
							+ " " + tagsToString(way));
			}
		}
	}

	/**
	 * For each element in elemList, performs a test against the quadtree. 
	 * If found, assign the tags and check if the element is done  
	 * @param elemList the list of elements
	 * @param currBoundaries a list of boundaries handled in the current level
	 */
	private void processElem(Element elem){
		Tags tags = null;

		if (elem instanceof Node){
			Node node = (Node) elem;
			tags = search(node.getLocation());
		}
		else if (elem instanceof Way){
			Way way = (Way) elem;
			// try the mid point of the way first
			int middle = way.getPoints().size() / 2;
			tags = search(way.getPoints().get(middle));
			if (tags == null){
				// try 1st point next
				tags = search(way.getPoints().get(0));
			}
			if (tags == null){
				// try last point next
				tags = search(way.getPoints().get(way.getPoints().size()-1));
			}
			if (tags == null){
				// still not found, try rest
				for (int i = 1; i < way.getPoints().size()-1; i++){
					if (i == middle)
						continue;
					tags = search(way.getPoints().get(i));
					if (tags != null) 
						break;
				}
			}
			if (tags == null)
				++cntwayNotFnd;
		}

		if (tags == null){
			++cntNotFnd;
		}
		else{
			// tag the element with all tags referenced by the boundary
			Iterator<Entry<String,String>> tagIter = tags.entryIterator();
			while (tagIter.hasNext()) {
				Entry<String,String> tag = tagIter.next();
				if (elem.getTag(tag.getKey()) == null){
					elem.addTag(tag.getKey(),tag.getValue());
				}
			}
		}
	}
	
	/**
	 * perform search in grid and maintain statistic counter
	 * @param co a point that is to be searched
	 * @return location relevant tags or null
	 */
	private Tags search(Coord co){
		if (saver.getBoundingBox().contains(co)){
			++cntQTSearch;
			return boundaryGrid.get(co);
		}
		else 
			return null;
	}
			
	/**
	 * Calculate a short value that represents the location relevant tags that are set 
	 * @param elem
	 * @return the short value. Each bit in the value that is 1 means that the corresponding entry 
	 * in mkgmapTagsArray is available.
	 */
	private short calcLocationTagsMask(Element elem){
		short res = 0;
		for (int i = 0; i < BoundaryQuadTree.mkgmapTagsArray.length; i++){
			if (elem.getTag(BoundaryQuadTree.mkgmapTagsArray[i] ) != null)
				res |= (1 << i);
		}
		return res;
	}

	/**
	 * Create a string with location relevant tags ordered by admin_level
	 * @param elem
	 * @return A new String object
	 */
	private String tagsToString(Element elem){
		StringBuilder res = new StringBuilder();
		for (int i = BoundaryQuadTree.mkgmapTagsArray.length-1; i >= 0; --i){
			String tagVal = elem.getTag(BoundaryQuadTree.mkgmapTagsArray[i] );
			if (tagVal != null)
				res.append(tagVal);
			res.append(";");
		}
		return res.toString();
	}

}


