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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.build.Locator;
import uk.me.parabola.mkgmap.build.LocatorUtil;
import uk.me.parabola.mkgmap.reader.osm.boundary.Boundary;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryQuadTree;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryUtil;
import uk.me.parabola.util.EnhancedProperties;

public class LocationHook extends OsmReadingHooksAdaptor {
	private static final Logger log = Logger.getLogger(LocationHook.class);
	long cntQTSearch = 0;
	long cntNotFnd = 0;
	long cntwayNotFnd = 0;

	private ElementSaver saver;
	private final List<String> nameTags = new ArrayList<String>();
	private Locator locator;
	private final Set<String> autofillOptions = new HashSet<String>();
	
	private File boundaryDir;

	private Map<String, Boundary> boundaryById = null;
	
	private static final Pattern COMMA_OR_SEMICOLON_PATTERN = Pattern
			.compile("[,;]+");
	
	public static final String BOUNDS_OPTION = "bounds";
	
	private static File checkedBoundaryDir;
	private static boolean checkBoundaryDirOk;

	private BoundaryQuadTree boundaryQuadTree;
	public final static String[] mkgmapTagsArray =  {
		"mkgmap:admin_level1",
		"mkgmap:admin_level2",
		"mkgmap:admin_level3",
		"mkgmap:admin_level4",
		"mkgmap:admin_level5",
		"mkgmap:admin_level6",
		"mkgmap:admin_level7",
		"mkgmap:admin_level8",
		"mkgmap:admin_level9",
		"mkgmap:admin_level10",
		"mkgmap:admin_level11",
		"mkgmap:postcode"
	};

	public boolean init(ElementSaver saver, EnhancedProperties props) {
		if (props.containsKey("index") == false) {
			log.info("Disable LocationHook because index option is not set.");
			return false;
		}

		this.locator = new Locator(props);
		
		this.saver = saver;

		autofillOptions.addAll(LocatorUtil.parseAutofillOption(props));

		if (autofillOptions.isEmpty()) {
			log.info("Disable LocationHook because no location-autofill option set.");
			return false;
		}
		
		nameTags.addAll(LocatorUtil.getNameTags(props));

		if (autofillOptions.contains(BOUNDS_OPTION)) {
			boundaryDir = new File(props.getProperty("bounds", "bounds"));
			long t1 = System.currentTimeMillis();

			synchronized (BOUNDS_OPTION) {
				// checking of the boundary dir is expensive
				// check once only and reuse the result
				if (boundaryDir.equals(checkedBoundaryDir)) {
					if (checkBoundaryDirOk == false) {
						log.error("Disable LocationHook because boundary directory is unusable. Dir: "+boundaryDir);
						return false;
					}
				} else {
					checkedBoundaryDir = boundaryDir;
					checkBoundaryDirOk = false;
					
					if (boundaryDir.exists() == false) {
						log.error("Disable LocationHook because boundary directory does not exist. Dir: "
								+ boundaryDir);
						return false;
					}
					
					// boundaryDir.list() is much quicker than boundaryDir.listFiles(FileFilter)
					String[] boundaryFiles = boundaryDir.list();
					if (boundaryFiles == null || boundaryFiles.length == 0) {
						log.error("Disable LocationHook because boundary directory contains no boundary files. Dir: "
								+ boundaryDir);
						return false;
					}
					boolean boundsFileFound = false;
					for (String boundsFile : boundaryFiles) {
						if (boundsFile.endsWith(".bnd")) {
							boundsFileFound = true;
							break;
						}
					}
					if (boundsFileFound == false) {
						log.error("Disable LocationHook because boundary directory contains no boundary files. Dir: "
								+ boundaryDir);
						return false;						
					}
					
					// passed all checks => boundaries are ok
					checkBoundaryDirOk = true;
				}
			}
			log.info("Checking bounds dir took "
					+ (System.currentTimeMillis() - t1) + " ms");
		}
		return true;
	}
	
	private static long sumLocHook = 0;
	
	public void end() {
		long t1 = System.currentTimeMillis();
		log.info("Starting with location hook");
	
		if (autofillOptions.contains(BOUNDS_OPTION)) {
			assignPreprocBounds();
			
			//printLocationRelevantElements();
		}
	
		long dt = (System.currentTimeMillis() - t1);
		sumLocHook += dt;
		log.info("Location hook finished in "+
				dt+ " ms");
		log.info("Overall Location hook time "+
				sumLocHook+ " ms");
		System.out.println("======= Stats =====");             
		System.out.println("QuadTree searches    : "  + cntQTSearch);             
		System.out.println("unsuccesfull         : "  + cntNotFnd);             
		System.out.println("unsuccesfull for ways: "  + cntwayNotFnd);             
		System.out.println("Location hook finished in "+
				dt+ " ms");
		System.out.println("Overall Location hook time "+
				sumLocHook+ " ms");

	}

	/**
	 * Retrieve a list of all elements for which the boundary assignment should be performed.
	 * @return a list of elements (points + ways + shapes)
	 */
	private List<Element> processLocationRelevantElements() {
		List<Element> elemList = new ArrayList<Element>();

		// add all nodes that might be converted to a garmin node (tagcount > 0)
		for (Node node : saver.getNodes().values()) {
			if (node.getTagCount() > 0) {
				if (saver.getBoundingBox().contains(node.getLocation()) ){
					processElem(node);
				}
			}
		}

		// add all ways that might be converted to a garmin way (tagcount > 0)
		// and save all polygons that contains location information
		for (Way way : saver.getWays().values()) {
			if (way.getTagCount() > 0) {
				processElem(way);
			}
		}
		return elemList;
	}
	
	/**
	 * for debugging
	 */
	private void printLocationRelevantElements() {

		// add all nodes that might be converted to a garmin node (tagcount > 0)
		for (Node node : saver.getNodes().values()) {
			if (node.getTagCount() > 0) {
				System.out.println("N " + node.getId() + " " + calcLocationTagsMask(node) 
						+ " " + tagsToString(node));
			}
		}

		// add all ways that might be converted to a garmin way (tagcount > 0)
		// and save all polygons that contains location information
		for (Way way : saver.getWays().values()) {
			if (way.getTagCount() > 0) {
				System.out.println("W " + way.getId() + " " + calcLocationTagsMask(way)
						+ " " + tagsToString(way));
			}
		}
	}
	
	/**
	 * Calculate a short value that represents the location relevant tags that are set 
	 * @param elem
	 * @return the short value. Each bit in the value that is 1 means that the corresponding entry 
	 * in mkgmapTagsArray is available.
	 */
	private short calcLocationTagsMask(Element elem){
		short res = 0;
		for (int i = 0; i < mkgmapTagsArray.length; i++){
			if (elem.getTag(mkgmapTagsArray[i] ) != null)
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
		for (int i = mkgmapTagsArray.length-1; i >= 0; --i){
			String tagVal = elem.getTag(mkgmapTagsArray[i] );
			if (tagVal != null)
				res.append(tagVal);
				res.append(";");
		}
		return res.toString();
	}
	
	/**
	 * Loads the preprocessed boundaries that intersects the bounding box of the tile.
	 * The bounds are sorted in descending order of admin_levels.
	 * @return the preprocessed bounds
	 */
	private List<Boundary> loadBoundaries() {
		long tb1 = System.currentTimeMillis();
		// Load the boundaries that intersect with the bounding box of the tile
		List<Boundary> boundaries = BoundaryUtil.loadBoundaries(boundaryDir,
				saver.getBoundingBox());
		
		long tb2 = System.currentTimeMillis();
		log.info("Loading boundaries took "+(tb2-tb1)+" ms");
		
		// Map the boundaryid to the boundary for fast access
		boundaryById = new HashMap<String, Boundary>();

		// go through all boundaries, check if the necessary tags are available
		// and standardize the country name to the 3 letter ISO code
		ListIterator<Boundary> bIter = boundaries.listIterator();
		while (bIter.hasNext()) {
			Boundary b = bIter.next();
			String name = getName(b.getTags());
			
			String zip =null;
			if (b.getTags().get("postal_code") != null || "postal_code".equals(b.getTags().get("boundary")))
				zip = getZip(b.getTags());
			
			if (name == null && zip == null) {
				log.warn("Cannot process boundary element because it contains no name and no zip tag. "+b.getTags());

				bIter.remove();
				continue;
			}

			if ("2".equals(b.getTags().get("admin_level"))) {
				String isoCode = locator.addCountry(b.getTags());
				if (isoCode != null) {
					name = isoCode;
				} else {
					log.warn("Country name",name,"not in locator config. Country may not be assigned correctly.");
				}
				log.debug("Coded:",name);
			}
			
			b.setId(b.getTags().get("mkgmap:boundaryid"));
			boundaryById.put(b.getId(), b);

			if (name != null){
				b.setName (name);
			}
			
			if (zip != null){
				b.setZip(zip);
			}
		}
		// Sort them by the admin level
		Collections.sort(boundaries, new BoundaryLevelCollator());
		// Reverse the sorting because we want to start with the highest admin level (11)
		Collections.reverse(boundaries);
		return boundaries;
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
			++cntQTSearch;
			tags = boundaryQuadTree.get(node.getLocation());
		}
		else if (elem instanceof Way){
			Way way = (Way) elem;

			int middle = way.getPoints().size() / 2;
			Coord coMiddle = way.getPoints().get(middle);
			
			tags = boundaryQuadTree.get(coMiddle);
			++cntQTSearch;
			if (tags == null){
				for (Coord co: way.getPoints()){
					tags = boundaryQuadTree.get(co);
					++cntQTSearch;
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
	 * Assign information from the boundaries to elements.
	 */
	private void assignPreprocBounds() {
		List<Boundary> boundaries = loadBoundaries();
		buildQuadTree(boundaries);
		processLocationRelevantElements();
		boundaryQuadTree.stats();
		
		// clear the data structures 
		boundaryQuadTree = null;
		boundaryById = null;
	}

	private void buildQuadTree(List<Boundary> boundaries){
		long t1 = System.currentTimeMillis();
		
		// boundaries that are valid in this level
		ArrayList<Boundary> levelBoundaryList = new ArrayList<Boundary>();

		ListIterator<Boundary> bIter = boundaries.listIterator();
		while (bIter.hasNext()) {
			Boundary boundary = bIter.next();
			String admName = null;
			int admLevel = getAdminLevel(boundary.getTags().get("admin_level"));
			
			if (admLevel >= 1 && admLevel <= 11)
				admName = boundary.getName();
			String zip = boundary.getZip();
			
			if ((admLevel <= 0 || admLevel > 11) && zip == null) {
				log.error("Cannot find any location relevant tag for " + boundary.getTags());
				continue;
			}

			//Map<String, String> boundarySetTags = new HashMap<String,String>();
			boundary.setLocTags(new Tags());
			if (admName != null) {
				boundary.getLocTags().put(mkgmapTagsArray[admLevel-1], admName);
			}
			if (zip != null) {
				boundary.getLocTags().put("mkgmap:postcode", zip);
			}
			
			parseLiesInTag(boundary);
			
			// this boundary should be processed in this level
			
				levelBoundaryList.add(boundary);
			
		}
		System.out.println("building boundary list took " + (System.currentTimeMillis() - t1) + " ms");
		boundaryQuadTree = new BoundaryQuadTree (saver.getBoundingBox(), levelBoundaryList, mkgmapTagsArray);
		
		System.out.println("building full quadtree took " + (System.currentTimeMillis() - t1) + " ms");
		
		
	}
	
/**
 * Parse the lies_in tag and fill related data structures of the boundary
 * @param boundary
	 */
	private void parseLiesInTag(Boundary boundary){
		// check in which other boundaries this boundary lies in
		String liesIn = boundary.getTags().get("mkgmap:lies_in");
		if (liesIn != null) {
			// the common format of mkgmap:lies_in is:
			// mkgmap:lies_in=2:r19884;4:r20039;6:r998818
			String[] relBounds = liesIn.split(Pattern.quote(";"));
			for (String relBound : relBounds) {
				String[] relParts = relBound.split(Pattern.quote(":"));
				if (relParts.length != 2) {
					log.error("Wrong mkgmap:lies_in format. Value: " +liesIn);
					continue;
				}
				Boundary bAdditional = boundaryById.get(relParts[1]);
				if (bAdditional == null) {
					log.warn("Referenced boundary not available: "+boundary.getTags()+" refs "+relParts[1]);
					continue;
				}
				int addAdmLevel = getAdminLevel(bAdditional.getTags().get("admin_level"));
				String addAdmName = null;
				if (addAdmLevel >= 1 && addAdmLevel <= 11)
					addAdmName = bAdditional.getName();
				String addZip = bAdditional.getZip();
				
				if (addAdmName != null){
					if (boundary.getLocTags().get(mkgmapTagsArray[addAdmLevel-1]) == null)
						boundary.getLocTags().put(mkgmapTagsArray[addAdmLevel-1], addAdmName);
				}
				if (addZip != null){
					if (boundary.getLocTags().get("mkgmap:postcode") == null)
						boundary.getLocTags().put("mkgmap:postcode", addZip);
				}
			}
		}
	}
	
	/** 
	 * These tags are used to retrieve the name of admin_level=2 boundaries. They need to
	 * be handled special because their name is changed to the 3 letter ISO code using
	 * the Locator class and the LocatorConfig.xml file. 
	 */
	private static final String[] LEVEL2_NAMES = new String[]{"name","name:en","int_name"};
	
	private String getName(Tags tags) {
		if ("2".equals(tags.get("admin_level"))) {
			for (String enNameTag : LEVEL2_NAMES)
			{
				String nameTagValue = tags.get(enNameTag);
				if (nameTagValue == null) {
					continue;
				}

				String[] nameParts = COMMA_OR_SEMICOLON_PATTERN.split(nameTagValue);
				if (nameParts.length == 0) {
					continue;
				}
				return nameParts[0].trim().intern();
			}
		}
		
		for (String nameTag : nameTags) {
			String nameTagValue = tags.get(nameTag);
			if (nameTagValue == null) {
				continue;
			}

			String[] nameParts = COMMA_OR_SEMICOLON_PATTERN.split(nameTagValue);
			if (nameParts.length == 0) {
				continue;
			}
			return nameParts[0].trim().intern();
		}
		return null;
	}

	private String getZip(Tags tags) {
		String zip = tags.get("postal_code");
		if (zip == null) {
			String name = tags.get("name"); 
			if (name == null) {
				name = getName(tags);
			}
			if (name != null) {
				String[] nameParts = name.split(Pattern.quote(" "));
				if (nameParts.length > 0) {
					zip = nameParts[0].trim();
				}
			}
		}
		return zip;
	}

	private static final int UNSET_ADMIN_LEVEL = 100; // must be higher than real levels 
	private int getAdminLevel(String level) {
		if (level == null) {
			return UNSET_ADMIN_LEVEL;
		}
		try {
			return Integer.valueOf(level);
		} catch (NumberFormatException nfe) {
			return UNSET_ADMIN_LEVEL;
		}
	}
	
	private class BoundaryLevelCollator implements Comparator<Boundary> {

		public int compare(Boundary o1, Boundary o2) {
			if (o1 == o2) {
				return 0;
			}

			String adminLevel1 = o1.getTags().get("admin_level");
			String adminLevel2 = o2.getTags().get("admin_level");

			if (getName(o1.getTags()) == null) {
				// admin_level tag is set but no valid name available
				adminLevel1= null;
			}
			if (getName(o2.getTags()) == null) {
				// admin_level tag is set but no valid name available
				adminLevel2= null;
			}
			
			int admCmp =  compareAdminLevels(adminLevel1, adminLevel2);
			if (admCmp != 0) {
				return admCmp;
			}
			
			if (getAdminLevel(adminLevel1) == 2) {
				// prefer countries that are known by the Locator
				String iso1 = locator.getCountryISOCode(o1.getTags());
				String iso2 = locator.getCountryISOCode(o2.getTags());
				if (iso1 != null && iso2 == null) {
					return 1;
				} else if (iso1==null && iso2 != null) {
					return -1;
				}
			}
			boolean post1set = getZip(o1.getTags()) != null;
			boolean post2set = getZip(o2.getTags()) != null;
			
			if (post1set) {
				return (post2set ? 0 : 1);
			} else {
				return (post2set ? -1 : 0);
			}
			
		}
		
		public int compareAdminLevels(String level1, String level2) {
			int l1 = getAdminLevel(level1);
			int l2 = getAdminLevel(level2);
			if (l1 == l2) {
				return 0;
			} else if (l1 > l2) {
				return 1;
			} else {
				return -1;
			}
		}
	}
} 

