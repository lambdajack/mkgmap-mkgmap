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
package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.build.Locator;
import uk.me.parabola.mkgmap.build.LocatorUtil;
import uk.me.parabola.mkgmap.reader.osm.Tags;
import uk.me.parabola.mkgmap.reader.osm.boundary.Boundary;
import uk.me.parabola.util.EnhancedProperties;

public class BoundaryLocationPreparer {
	private static final Logger log = Logger.getLogger(BoundaryLocationPreparer.class);

	private Locator locator;
	private static final Pattern COMMA_OR_SEMICOLON_PATTERN = Pattern.compile("[,;]+");
	// tag keys for name resolution
	private final List<String> nameList;
	
	public final static short POSTCODE_ONLY = 1 << 11; 

	/**
	 * Create a preparer. 
	 * @param props
	 */
	public BoundaryLocationPreparer(EnhancedProperties props) {
		if (props == null){
			this.locator = null;
			this.nameList = new ArrayList<String>();
			for (String name: BoundaryLocationPreparer.LEVEL2_NAMES){
				this.nameList.add(name);
			}
		}
		else{
			this.locator = new Locator(props);
			this.nameList = LocatorUtil.getNameTags(props);
		}
	}

	/**
	 * Extract location relevant information from tags
	 * @param tags the Tags of a boundary 
	 * @return a new BoundaryLocationInfo instance 
	 */
	public BoundaryLocationInfo parseTags(Tags tags){
		String zip = null;
		if (tags.get("postal_code") != null || "postal_code".equals(tags.get("boundary")))
			zip = getZip(tags);
		int admLevel = getAdminLevel(tags.get("admin_level"));
		boolean isISO = false;
		String name = getName(tags);
		if (locator != null){
			if (admLevel == 2) {
				String isoCode = locator.addCountry(tags);
				if (isoCode != null) {
					isISO = true;
					name = isoCode;
				} else {
					log.warn("Country name",name,"not in locator config. Country may not be assigned correctly.");
				}
				log.debug("Coded:",name);
			}
		}
		return new BoundaryLocationInfo(admLevel, name, zip, isISO);
	}

	/** 
	 * Extract and prepare tag infos from BoundaryList 
	 * @param boundaries list of boundaries
	 * @return A Map that maps boundary Ids to the location relevant tags
	 */
	public HashMap<String, BoundaryLocationInfo> getPreparedLocationInfo(
			List<Boundary> boundaries) {
		HashMap<String, BoundaryLocationInfo> preparedLocationInfo = new HashMap<String, BoundaryLocationInfo> ();
		for (Boundary b :boundaries){
			preparedLocationInfo.put(b.getId(), parseTags(b.getTags())); 
		}
		return preparedLocationInfo;
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
		
		for (String nameTag : nameList) {
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

	public static final int UNSET_ADMIN_LEVEL = 100; // must be higher than real levels 
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
} 

