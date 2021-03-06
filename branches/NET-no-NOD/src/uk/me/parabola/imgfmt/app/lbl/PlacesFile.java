/*
 * Copyright (C) 2007 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: Jan 1, 2008
 */
package uk.me.parabola.imgfmt.app.lbl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import uk.me.parabola.imgfmt.app.Exit;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.srt.CombinedSortKey;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.srt.SortKey;
import uk.me.parabola.imgfmt.app.trergn.Subdivision;

/**
 * This is really part of the LBLFile.  We split out all the parts of the file
 * that are to do with location to here.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class PlacesFile {
	public static final int MIN_INDEXED_POI_TYPE = 0x29;
	public static final int MAX_INDEXED_POI_TYPE = 0x30;
	private final Map<String, Country> countries = new LinkedHashMap<>();
	private final List<Country> countryList = new ArrayList<>();

	private final Map<String, Region> regions = new LinkedHashMap<>();
	private final List<Region> regionList = new ArrayList<>();

	private final Map<String, City> cities = new LinkedHashMap<>();
	private final List<City> cityList = new ArrayList<>();

	private final Map<String, Zip> postalCodes = new LinkedHashMap<>();
	private final List<Zip> zipList = new ArrayList<>();

	private final List<Highway> highways = new ArrayList<>();
	private final List<ExitFacility> exitFacilities = new ArrayList<>();
	private final List<POIRecord> pois = new ArrayList<>();
	private final List[] poiIndex = new ArrayList[256];

	private LBLFile lblFile;
	private PlacesHeader placeHeader;
	private boolean poisClosed;

	private Sort sort;

	private final Random random = new Random();

	/**
	 * We need to have links back to the main LBL file and need to be passed
	 * the part of the header that we manage here.
	 *
	 * @param file The main LBL file, used so that we can create labels.
	 * @param pheader The place header.
	 */
	void init(LBLFile file, PlacesHeader pheader) {
		lblFile = file;
		placeHeader = pheader;
	}

	void write(ImgFileWriter writer) {
		for (Country c : countryList)
			c.write(writer);
		placeHeader.endCountries(writer.position());

		for (Region region : regionList)
			region.write(writer);
		placeHeader.endRegions(writer.position());

		for (City sc : cityList)
			sc.write(writer);

		placeHeader.endCity(writer.position());

		for (List<POIIndex> pil : poiIndex) {
			if(pil != null) {
				// sort entries by POI name
				List<SortKey<POIIndex>> sorted = new ArrayList<>();
				for (POIIndex index : pil) {
					SortKey<POIIndex> sortKey = sort.createSortKey(index, index.getName());
					sorted.add(sortKey);
				}
				sorted.sort(null);

				for (SortKey<POIIndex> key : sorted) {
					key.getObject().write(writer);
				}
			}
		}
		placeHeader.endPOIIndex(writer.position());

		int poistart = writer.position();
		int poiglobalflags = placeHeader.getPOIGlobalFlags();
		for (POIRecord p : pois)
			p.write(writer, poiglobalflags,
				writer.position() - poistart, cityList.size(), postalCodes.size(), highways.size(), exitFacilities.size());
		placeHeader.endPOI(writer.position());

		int numPoiIndexEntries = 0;
		for (int i = 0; i < 256; ++i) {
			if(poiIndex[i] != null) {
				writer.put1u(i);
				writer.put3u(numPoiIndexEntries + 1);
				numPoiIndexEntries += poiIndex[i].size();
			}
		}
		placeHeader.endPOITypeIndex(writer.position());

		for (Zip z : zipList)
			z.write(writer);
		placeHeader.endZip(writer.position());

		int extraHighwayDataOffset = 0;
		for (Highway h : highways) {
		    h.setExtraDataOffset(extraHighwayDataOffset);
		    extraHighwayDataOffset += h.getExtraDataSize();
		    h.write(writer, false);
		}
		placeHeader.endHighway(writer.position());

		for (ExitFacility ef : exitFacilities)
			ef.write(writer);
		placeHeader.endExitFacility(writer.position());

		for (Highway h : highways)
			h.write(writer, true);
		placeHeader.endHighwayData(writer.position());
	}

	Country createCountry(String name, String abbr) {
	
		String s = abbr != null ? name + (char)0x1d + abbr : name;
			
		Country c = countries.get(s);
	
		if(c == null) {
			c = new Country(countries.size()+1);

			Label l = lblFile.newLabel(s);
			c.setLabel(l);
			countries.put(s, c);
		}
		return c;
	}

	Region createRegion(Country country, String name, String abbr) {
	
		String s = abbr != null ? name + (char)0x1d + abbr : name;

		String uniqueRegionName = s.toUpperCase() + "_C" + country.getLabel().getOffset();
	
		Region r = regions.get(uniqueRegionName);
		
		if(r == null) {
			r = new Region(country);
			Label l = lblFile.newLabel(s);
			r.setLabel(l);
			regionList.add(r);
			regions.put(uniqueRegionName, r);
		}
		return r;
	}

	City createCity(Country country, String name, boolean unique) {

		String uniqueCityName = name.toUpperCase() + "_C" + country.getLabel().getOffset();
		
		// if unique is true, make sure that the name really is unique
		if(unique && cities.get(uniqueCityName) != null) {
			do {
				// add random suffix
				uniqueCityName += "_" + new Random().nextInt(0x10000);
			} while(cities.get(uniqueCityName) != null);
		}

		City c = null;
		if (!unique)
			c = cities.get(uniqueCityName);
		
		if (c == null) {
			c = new City(country);

			Label l = lblFile.newLabel(name);
			c.setLabel(l);

			cityList.add(c);
			cities.put(uniqueCityName, c);
			assert cityList.size() == cities.size() : " cityList and cities are different lengths after inserting " + name + " and " + uniqueCityName;
		}

		return c;
	}

	City createCity(Region region, String name, boolean unique) {
		
		String uniqueCityName = name.toUpperCase() + "_R" + region.getLabel().getOffset();
		
		// if unique is true, make sure that the name really is unique
		if (unique && cities.get(uniqueCityName) != null) {
			do {
				// add semi-random suffix.
				uniqueCityName += "_" + random.nextInt(0x10000);
			} while(cities.get(uniqueCityName) != null);
		}

		City c = null;
		if(!unique)
			c = cities.get(uniqueCityName);
		
		if(c == null) {
			c = new City(region);

			Label l = lblFile.newLabel(name);
			c.setLabel(l);

			cityList.add(c);
			cities.put(uniqueCityName, c);
			assert cityList.size() == cities.size() : " cityList and cities are different lengths after inserting " + name + " and " + uniqueCityName;
		}

		return c;
	}

	Zip createZip(String code) {
		Zip z = postalCodes.get(code);

		if(z == null) {
			z = new Zip();

			Label l = lblFile.newLabel(code);
			z.setLabel(l);

			zipList.add(z);
			postalCodes.put(code, z);
		}
		return z;
	}

	Highway createHighway(Region region, String name) {
		Highway h = new Highway(region, highways.size()+1);

		Label l = lblFile.newLabel(name);
		h.setLabel(l);

		highways.add(h);
		return h;
	}

	public ExitFacility createExitFacility(int type, char direction, int facilities, String description, boolean last) {
		Label d = lblFile.newLabel(description);
		ExitFacility ef = new ExitFacility(type, direction, facilities, d, last, exitFacilities.size()+1);
		exitFacilities.add(ef);
		return ef;
	}

	POIRecord createPOI(String name) {
		assert !poisClosed;
		// TODO...
		POIRecord p = new POIRecord();

		Label l = lblFile.newLabel(name);
		p.setLabel(l);

		pois.add(p);
		
		return p;
	}

	POIRecord createExitPOI(String name, Exit exit) {
		assert !poisClosed;
		// TODO...
		POIRecord p = new POIRecord();

		Label l = lblFile.newLabel(name);
		p.setLabel(l);

		p.setExit(exit);

		pois.add(p);

		return p;
	}

	void createPOIIndex(String name, int index, Subdivision group, int type) {
		assert index < 0x100 : "Too many POIS in division";
		int t = type >> 8;
		if (t < MIN_INDEXED_POI_TYPE || t > MAX_INDEXED_POI_TYPE) 
			return;
		
		POIIndex pi = new POIIndex(name, index, group, type & 0xff);
		if(poiIndex[t] == null)
			poiIndex[t] = new ArrayList<POIIndex>();
		poiIndex[t].add(pi);
	}

	void allPOIsDone() {
		sortCountries();
		sortRegions();
		sortCities();
		sortZips();

		poisClosed = true;

		int poiFlags = 0;
		for (POIRecord p : pois) {
			poiFlags |= p.getPOIFlags();
		}
		placeHeader.setPOIGlobalFlags(poiFlags);

		int ofs = 0;
		for (POIRecord p : pois)
			ofs += p.calcOffset(ofs, poiFlags, cityList.size(), postalCodes.size(), highways.size(), exitFacilities.size());
	}

	/**
	 * I don't know that you have to sort these (after all most tiles will
	 * only be in one country or at least a very small number).
	 *
	 * But why not?
	 */
	private void sortCountries() {
		List<SortKey<Country>> keys = new ArrayList<>();
		for (Country c : countries.values()) {
			SortKey<Country> key = sort.createSortKey(c, c.getLabel());
			keys.add(key);
		}
		keys.sort(null);

		countryList.clear();
		int index = 1;
		for (SortKey<Country> key : keys) {
			Country c = key.getObject();
			c.setIndex(index++);
			countryList.add(c);
		}
	}

	/**
	 * Sort the regions by the defined sort.
	 */
	private void sortRegions() {
		List<SortKey<Region>> keys = new ArrayList<>();
		for (Region r : regionList) {
			SortKey<Region> key = sort.createSortKey(r, r.getLabel(), r.getCountry().getIndex());
			keys.add(key);
		}
		keys.sort(null);

		regionList.clear();
		int index = 1;
		for (SortKey<Region> key : keys) {
			Region r = key.getObject();
			r.setIndex(index++);
			regionList.add(r);
		}
	}

	/**
	 * Sort the cities by the defined sort.
	 */
	private void sortCities() {
		List<SortKey<City>> keys = new ArrayList<>();
		for (City c : cityList) {
			SortKey<City> sortKey = sort.createSortKey(c, c.getLabel());
			sortKey = new CombinedSortKey<>(sortKey, c.getRegionNumber(), c.getCountryNumber());
			keys.add(sortKey);
		}
		keys.sort(null);

		cityList.clear();
		int index = 1;
		for (SortKey<City> sc: keys) {
			City city = sc.getObject();
			city.setIndex(index++);
			cityList.add(city);
		}
	}

	private void sortZips() {
		List<SortKey<Zip>> keys = new ArrayList<>();
		for (Zip c : postalCodes.values()) {
			SortKey<Zip> sortKey = sort.createSortKey(c, c.getLabel());
			keys.add(sortKey);
		}
		keys.sort(null);

		zipList.clear();
		int index = 1;
		for (SortKey<Zip> sc: keys) {
			Zip zip = sc.getObject();
			zip.setIndex(index++);
			zipList.add(zip);
		}
	}

	public int numCities() {
		return cityList.size();
	}

	public int numZips() {
		return postalCodes.size();
	}

	public int numHighways() {
		return highways.size();
	}

	public int numExitFacilities() {
		return exitFacilities.size();
	}

	public void setSort(Sort sort) {
		this.sort = sort;
	}
}
