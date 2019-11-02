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
 * Create date: Jan 5, 2008
 */
package uk.me.parabola.imgfmt.app.net;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.BufferedImgFileWriter;
import uk.me.parabola.imgfmt.app.ImgFile;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.lbl.City;
import uk.me.parabola.imgfmt.app.lbl.Zip;
import uk.me.parabola.imgfmt.app.srt.DoubleSortKey;
import uk.me.parabola.imgfmt.app.srt.IntegerSortKey;
import uk.me.parabola.imgfmt.app.srt.MultiSortKey;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.srt.SortKey;
import uk.me.parabola.imgfmt.fs.ImgChannel;

/**
 * The NET file.  This consists of information about roads.  It is not clear
 * what this file brings on its own (without NOD) but may allow some better
 * searching, street addresses etc.
 *
 * @author Steve Ratcliffe
 */
public class NETFile extends ImgFile {
	private final NETHeader netHeader = new NETHeader();
	private List<RoadDef> roads;
	private Sort sort;

	public NETFile(ImgChannel chan) {
		setHeader(netHeader);
		setWriter(new BufferedImgFileWriter(chan));
		position(NETHeader.HEADER_LEN);
	}

	/**
	 * Write out NET1.
	 * @param numCities The number of cities in the map. Needed for the size of the written fields.
	 * @param numZips The number of zips in the map. Needed for the size of the written fields.
	 */
	public void write(int numCities, int numZips) {
		// Write out the actual file body.
		ImgFileWriter writer = netHeader.makeRoadWriter(getWriter());
		try {
			for (RoadDef rd : roads)
				rd.writeNet1(writer, numCities, numZips);

		} finally {
			Utils.closeFile(writer);
		}
	}

	/**
	 * Final writing out of net sections.
	 *
	 * We patch the NET offsets into the RGN file and create the sorted roads section.
	 *
	 * @param rgn The region file, this has to be patched with the calculated net offsets.
	 */
	public void writePost(ImgFileWriter rgn) {
		for (RoadDef rd : roads)
			rd.writeRgnOffsets(rgn);

		ImgFileWriter writer = netHeader.makeSortedRoadWriter(getWriter());
		try {
			List<LabeledRoadDef> labeledRoadDefs = deDupRoads();
			sortByName(labeledRoadDefs);
			for (LabeledRoadDef labeledRoadDef : labeledRoadDefs)
				labeledRoadDef.roadDef.putSortedRoadEntry(writer, labeledRoadDef.label);
		} finally {
			Utils.closeFile(writer);
		}

		getHeader().writeHeader(getWriter());
	}

	/**
	 * Sort the roads by name and remove duplicates.
	 * <p>
	 * We want a list of roads such that every entry in the list is a different
	 * road. In some areas we have multiple roads with the same name, typically
	 * connected to each other. For each group we find networks of connected
	 * roads. We must store all roads with house numbers, else some numbers are
	 * not found. For networks without any road with numbers we store only one
	 * to reduce NET size.
	 * <p>
	 * Special case: With OSM data and certain styles all normally unnamed roads
	 * get a name describing the type of road, e.g. all ways with tag
	 * highway=footway get the name "fw". This can produce large groups of roads
	 * with equal names.
	 * 
	 * @return A list of road labels that identify all the different roads
	 */
	private List<LabeledRoadDef> deDupRoads() {
		List<SortKey<LabeledRoadDef>> sortKeys = createSortKeysyNameAndCity();
		sortKeys.sort(null);

		List<LabeledRoadDef> out = new ArrayList<>(sortKeys.size());

		List<LabeledRoadDef> dupes = new ArrayList<>();
		SortKey<LabeledRoadDef> lastKey = null;

		// Since they are sorted we can easily remove the duplicates.
		// The duplicates are saved to the dupes list.
		for (SortKey<LabeledRoadDef> key : sortKeys) {
			if (lastKey == null || key.compareTo(lastKey) != 0) {
				analyseRoadsOfCity(dupes, out);
				dupes.clear();
				lastKey = key;
			}
			dupes.add(key.getObject());
		}
		// Finish off the final set of duplicates.
		analyseRoadsOfCity(dupes, out);
		return out;
	}

	private List<SortKey<LabeledRoadDef>> createSortKeysyNameAndCity() {
		List<SortKey<LabeledRoadDef>> sortKeys = new ArrayList<>(roads.size());

		for (RoadDef rd : roads) {
			Label[] labels = rd.getLabels();
			for (int i = 0; i < labels.length && labels[i] != null; ++i) {
				Label label = labels[i];
				if (label.getLength() == 0)
					continue;

				// Sort by name, city, region/country and subdivision number.
				LabeledRoadDef lrd = new LabeledRoadDef(label, rd);
				SortKey<LabeledRoadDef> nameKey = new IntegerSortKey<>(lrd, label.getOffset(), 0);

				// If there is a city add it to the sort.
				City city = (rd.getCities().isEmpty() ? null : rd.getCities().get(0));
				SortKey<LabeledRoadDef> cityKey;
				if (city != null) {
					int region = city.getRegionNumber();
					int country = city.getCountryNumber();
					cityKey = sort.createSortKey(null, city.getLabel(), (region & 0xffff) << 16 | (country & 0xffff));
				} else {
					cityKey = sort.createSortKey(null, Label.NULL_OUT_LABEL, 0);
				}

				// If there is a zip code add it to the sort.
				Zip zip = (rd.getZips().isEmpty() ? null : rd.getZips().get(0));
				Label zipLabel = zip == null ?  Label.NULL_OUT_LABEL: zip.getLabel();
				SortKey<LabeledRoadDef> zipKey = sort.createSortKey(null, zipLabel);
				
				sortKeys.add(new MultiSortKey<>(nameKey, cityKey, zipKey));
			}
		}
		return sortKeys;
	}

	/**
	 * Sort by partial name first, then by full name.
	 * @param roads list of labeled roads
	 */
	private void sortByName(List<LabeledRoadDef> roads) {
		List<SortKey<LabeledRoadDef>> sortKeys = new ArrayList<>(roads.size());
		Map<Label, byte[]> cachePartial = new HashMap<>();
		Map<Label, byte[]> cacheFull = new HashMap<>();
		// we have to use two different caches, as both use the label as key
		for (LabeledRoadDef lrd : roads) {
			SortKey<LabeledRoadDef> sk1 = sort.createSortKeyPartial(lrd, lrd.label, 0, cachePartial);
			SortKey<LabeledRoadDef> sk2 = sort.createSortKey(null, lrd.label, 0, cacheFull);
			sortKeys.add(new DoubleSortKey<>(sk1, sk2));
		}
		sortKeys.sort(null);
		roads.clear();
		for (SortKey<LabeledRoadDef> key : sortKeys) {
			roads.add(key.getObject());
		}		
	}

	/**
	 * Take a set of roads with the same name/city etc and find sets of roads that do not
	 * connect with each other. One of the members of each set is added to the road list.
	 *
	 * @param in A list of duplicate roads.
	 * @param out The list of sorted roads. Any new road is added to this.
	 */
	private static void analyseRoadsOfCity(List<LabeledRoadDef> in, List<LabeledRoadDef> out) {
		// switch out to different routines depending on the input size. A normal number of
		// roads with the same name in the same city is a few tens.
		if (in.size() > 200) {
			analyseRoadsOfCityLarge(in, out);
		} else {
			analyseRoadsOfCitySmall(in, out);
		}
	}

	/**
	 * Split the input set of roads into disconnected groups and output one member from each group.
	 *
	 * This is done in an accurate manner which is slow for large numbers (eg thousands) of items in the
	 * input.
	 *
	 * @param in Input set of roads with the same name and city.
	 * @param out List to add the discovered groups.
	 */
	private static void analyseRoadsOfCitySmall(List<LabeledRoadDef> in, List<LabeledRoadDef> out) {
		if (in.size() < 2) {
			out.addAll(in);
		} else {
			// sort so that roads with numbers and those with more than one city appear first 
			in.sort((o1, o2) -> Boolean.compare(needed(o2), needed(o1)));

			// write all roads with numbers or multiple cities so that they are found in address search
			int posOther = -1;
			for (int i = 0; i < in.size(); i++) {
				if (needed(in.get(i))) {
					out.add(in.get(i));
				} else {
					posOther = i;
					break;
				}
			}
			if (posOther >= 0) {
				findRoadNetworks(in, posOther, out);
			}
		}
	}

	/**
	 * Split the input set of roads into disconnected groups and output one member from each group.
	 *
	 * This is an modified algorithm for large numbers in the input set (eg thousands).
	 * First sort into groups by subdivision and then call {@link #analyseRoadsOfCitySmall} on each
	 * one. Since roads in the same subdivision are near each other this finds most connected roads, but
	 * since there is a maximum number of roads in a subdivision, the test can be done very quickly.
	 * You will get a few extra duplicate entries in the index.
	 *
	 * In normal cases this routine gives almost the same results as {@link #analyseRoadsOfCitySmall}.
	 *
	 * @param in Input set of roads with the same name.
	 * @param out List to add the discovered groups.
	 */
	private static void analyseRoadsOfCityLarge(List<LabeledRoadDef> in, List<LabeledRoadDef> out) {
	    in.sort(Comparator.comparingInt(lr -> lr.roadDef.getStartSubdivNumber()));
	
		int lastDiv = 0;
		List<LabeledRoadDef> dupes = new ArrayList<>();
		for (LabeledRoadDef lrd : in) {
			int sd = lrd.roadDef.getStartSubdivNumber();
			if (sd != lastDiv) {
				analyseRoadsOfCitySmall(dupes, out);
				dupes.clear();
				lastDiv = sd;
			}
			dupes.add(lrd);
		}
		// the rest
		analyseRoadsOfCitySmall(dupes, out);
	}

	private static boolean needed (LabeledRoadDef lr) {
		return lr.roadDef.hasHouseNumbers() || lr.roadDef.getCities().size() > 1;
	}
	
	/**
	 * Find road networks which are not connected to the roads with numbers, write one of each.
	 * @param in Input set of roads with the same name, sorted so that roads with numbers appear first
	 * @param posOther position of first road without numbers or multiple cities in input 
	 * @param out List to add the discovered groups with roads not connected to the roads with numbers
	 */
	private static void findRoadNetworks(List<LabeledRoadDef> in, int posOther, List<LabeledRoadDef> out) {
		// Each road starts out with a different group number
		int inSize = in.size();
		int[] groups = new int[inSize];
		for (int i = 0; i < groups.length; i++)
			groups[i] = i;

		// cache for results of RoadDef#connectedTo(RoadDef) where result was false.
		BitSet unconnected = new BitSet(inSize * inSize);
		
		// Go through pairs of roads, any that are connected we mark with the same (lowest) group number.
		boolean done;
		do {
			done = true;
			for (int current = 0; current < groups.length; current++) {
				RoadDef first = in.get(current).roadDef;
				
				for (int i = current + 1; i < groups.length; i++) {
					// If the groups are already the same or roads are known to be unconnected, then no need to test
					if (groups[current] != groups[i] && !unconnected.get(current * inSize + i)) {
						// we have to do the costly connectedTo() test
						if (first.connectedTo(in.get(i).roadDef)) {
							groups[current] = groups[i] = Math.min(groups[current], groups[i]);
							done = false;
						} else {
							unconnected.set(current * inSize + i);
						}
					}
				}
			}
		} while (!done);

		// Output the first road in each group that was not yet added
		int last = posOther - 1;
		for (int i = posOther; i < groups.length; i++) {
			if (groups[i] > last) {
				LabeledRoadDef lrd = in.get(i);
				out.add(lrd);
				last = groups[i];
			}
		}
	}

	public void setNetwork(List<RoadDef> roads) {
		this.roads = roads;
	}

	public void setSort(Sort sort) {
		this.sort = sort;
	}

	/**
	 * A road can have several names. Keep an association between a road def
	 * and one of its names.
	 */
	class LabeledRoadDef {
		private final Label label;
		private final RoadDef roadDef;

		LabeledRoadDef(Label label, RoadDef roadDef) {
			this.label = label;
			this.roadDef = roadDef;
		}
	}
}
