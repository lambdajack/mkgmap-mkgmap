/*
 * Copyright (C) 2009.
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
package uk.me.parabola.imgfmt.app.mdr;

import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.srt.SortKey;

/**
 * A bunch of static routines for use in creating the MDR file.
 */
public class MdrUtils {

	public static final int STREET_INDEX_PREFIX_LEN = 4;
	public static final int POI_INDEX_PREFIX_LEN = 4;
	public static final int MAX_GROUP = 13;

	/**
	 * Get the group number for the poi.  This is the first byte of the records
	 * in mdr9.
	 *
	 * Not entirely sure about how this works yet.
	 * @param fullType The primary type of the object.
	 * @return The group number.  This is a number between 1 and MAX_GROUP, later
	 * might be as high as 0x40, so do not assume there are no gaps.
	 *
	 * Group Type   Filed under
	 *  1    	Cities	(actual range defined by isCityType)
	 *  2    0x2a   Food and Drink
	 *  3    0x2b   Lodgings
	 *  4    0x2c   Attractions/Recreation/Community
	 *  5    0x2d   Entertainment/Recreation
	 *  6    0x2e   Shopping
	 *  7    0x2f   Auto/Transport/Community/Other
	 *  8    0x30   Civic
	 *  9    0x28   Island. Reason for having this is no longer known
	 * 10  unused
	 * 11    0x64   Geographic > Manmade Places
	 * 12    0x65   Geographic > Water Features
	 * 13    0x66   Geographic > Land Features
	 *
	 * display MdrCheck.java:toType() needs to be in-step with this
	 */
	public static int getGroupForPoi(int fullType) {
		// We group pois based on their type.  This may not be the final thoughts on this.
		int type = getTypeFromFullType(fullType);
		int group = 0;
		if (MapPoint.isCityType(fullType))
			group = 1;
		else if (type >= 0x2a && type <= 0x30)
			group = type - 0x2a + 2;
		else if (type == 0x28)
			group = 9;
		else if (type >= 0x64 && type <= 0x66)
			group = type - 0x64 + 11;
		assert group >= 0 && group <= MAX_GROUP : "invalid group " + Integer.toHexString(group);
		return group;
	}

	public static boolean canBeIndexed(int fullType) {
		return getGroupForPoi(fullType) != 0;
	}

	public static int getTypeFromFullType(int fullType) {
		return (fullType>>8) & 0xfff;
	}

	/**
	 * Gets the subtype 
	 * @param fullType The type in the so-called 'full' format.
	 * @return If there is a subtype, then it is returned, else 0.
	 */
	public static int getSubtypeFromFullType(int fullType) {
		return fullType & 0xff;
	}

	/**
	 * Sort records that are to be sorted by a name. The appropriate sort order will be used.
	 * @param sort The sort to be applied.
	 * @param list The list to be sorted.
	 * @param <T> One of the Mdr?Record types that need to be sorted on a text field, eg street name.
	 * @return A list of sort keys in the sorted order.  The original object is retrieved from the key
	 * by calling getObject().
	 */
	public static <T extends NamedRecord> List<SortKey<T>> sortList(Sort sort, List<T> list) {
		List<SortKey<T>> toSort = new ArrayList<>(list.size());
		for (T m : list) {
			SortKey<T> sortKey = sort.createSortKey(m, m.getName(), m.getMapIndex());
			toSort.add(sortKey);
		}
		toSort.sort(null);
		return toSort;
	}

	/**
	 * The 'natural' type is always a combination of the type and subtype with the type
	 * shifted 5 bits and the sub type in the low 5 bits.
	 *
	 * For various reasons, we use 'fullType' in which the type is shifted up a full byte
	 * or is in the lower byte.
	 *
	 * @param ftype The so-called full type of the object.
	 * @return The natural type as defined above.
	 */
	public static int fullTypeToNaturalType(int ftype) {
		int type = getTypeFromFullType(ftype);
		int sub = getSubtypeFromFullType(ftype);
		assert sub <= 0x1f: "Subtype doesn't fit into 5 bits: " + uk.me.parabola.mkgmap.reader.osm.GType.formatType(ftype);
		return type << 5 | sub;
	}
}
