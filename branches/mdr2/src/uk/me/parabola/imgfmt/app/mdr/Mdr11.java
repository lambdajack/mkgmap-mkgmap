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

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.srt.CombinedSortKey;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.srt.Sort.SrtCollator;
import uk.me.parabola.imgfmt.app.srt.SortKey;
import uk.me.parabola.imgfmt.app.trergn.Point;

/**
 * Holds all the POIs, including cities.  Arranged alphabetically by
 * the name, mapindex and city/region index. 
 *
 * @author Steve Ratcliffe
 */
public class Mdr11 extends MdrMapSection {
	private ArrayList<Mdr11Record> pois = new ArrayList<>();
	private Mdr10 mdr10;

	public Mdr11(MdrConfig config) {
		setConfig(config);
	}

	public Mdr11Record addPoi(int mapIndex, Point point, String name, int strOff) {
		Mdr11Record poi = new Mdr11Record();
		poi.setMapIndex(mapIndex);
		poi.setPointIndex(point.getNumber());
		poi.setSubdiv(point.getSubdiv().getNumber());
		poi.setLblOffset(point.getLabel().getOffset());
		poi.setName(name);
		poi.setStrOffset(strOff);

		pois.add(poi);
		return poi;
	}

	/**
	 * Sort by name, mapindex and city/region index and fill in the mdr10 information.
	 *
	 * The POI index contains individual references to POI by subdiv and index, so they are not
	 * de-duplicated in the index in the same way that streets and cities are.
	 */
	@Override
	protected void preWriteImpl() {
		pois.trimToSize();
		Sort sort = getConfig().getSort();
		LargeListSorter<Mdr11Record> sorter = new LargeListSorter<Mdr11Record>(sort) {
			
			@Override
			protected SortKey<Mdr11Record> makeKey(Mdr11Record r, Sort sort, Map<String, byte[]> cache) {
				SortKey<Mdr11Record> key1 = sort.createSortKey(r, r.getName(), r.getMapIndex(), cache);
				int key2 = r.isCity() ? r.getRegionIndex() : r.getCityIndex(); 
				return new CombinedSortKey<>(key1, key2, 0);
			}
		};
		sorter.sort(pois);
		for (Mdr11Record poi : pois) {
			mdr10.addPoiType(poi);
		}
	}

	public void writeSectData(ImgFileWriter writer) {
		int count = 1;
		boolean hasStrings = hasFlag(2);
		for (Mdr11Record poi : pois) {
			addIndexPointer(poi.getMapIndex(), count);
			poi.setRecordNumber(count++);

			putMapIndex(writer, poi.getMapIndex());
			writer.put1u(poi.getPointIndex());
			writer.put2u(poi.getSubdiv());
			writer.put3u(poi.getLblOffset());
			if (poi.isCity())
				putRegionIndex(writer, poi.getRegionIndex());
			else
				putCityIndex(writer, poi.getCityIndex());
			if (hasStrings)
				putStringOffset(writer, poi.getStrOffset());
		}
	}

	public int getItemSize() {
		PointerSizes sizes = getSizes();
		int size = sizes.getMapSize() + 6 + sizes.getCitySizeFlagged();
		if (hasFlag(0x2))
			size += sizes.getStrOffSize();
		return size;
	}

	protected int numberOfItems() {
		return pois.size();
	}

	public int getNumberOfPois() {
		return getNumberOfItems();
	}

	public int getExtraValue() {
		int mdr11flags = 0x11;
		PointerSizes sizes = getSizes();

		// two bit field for city bytes.  minimum size of 2
		int citySize = sizes.getCitySizeFlagged();
		if (citySize > 2)
			mdr11flags |= (citySize-2) << 2;

		if (isForDevice()) {
			if (!getConfig().getSort().isMulti()) {
				// mdr17 sub section present (not with unicode)
				mdr11flags |= 0x80;  // without roads this might be 0x40
			}
		}
		else 
			mdr11flags |= 0x2;
		
		return mdr11flags;
	}

	public List<Mdr8Record> getIndex() {
		List<Mdr8Record> list = new ArrayList<>();
		Sort.SrtCollator collator = (SrtCollator) getConfig().getSort().getCollator();
		for (int number = 1; number <= pois.size(); number += 10240) {
			char[] prefix = getPrefixForRecord(number);

			// need to step back to find the first...
			int rec = number;
			while (rec > 1) {
				char[] p = getPrefixForRecord(rec);
				int cmp = collator.compareOneStrengthWithLength(prefix, p, Collator.PRIMARY,
						MdrUtils.POI_INDEX_PREFIX_LEN);
				if (cmp != 0) {
					rec++;
					break;
				}
				rec--;
			}

			list.add(new Mdr12Record(prefix, rec));
		}
		return list;
	}

	/**
	 * Get the prefix of the name at the given record.
	 * @param number The record number.
	 * @return The first 4 (or whatever value is set) characters of the POI name.
	 */
	private char[] getPrefixForRecord(int number) {
		Mdr11Record record = pois.get(number-1);
		String name = record.getName();
		return getConfig().getSort().getPrefix(name, MdrUtils.POI_INDEX_PREFIX_LEN);
	}

	public void setMdr10(Mdr10 mdr10) {
		this.mdr10 = mdr10;
	}

	@Override
	public void releaseMemory() {
		pois = null;
		mdr10 = null;
	}

	public List<Mdr11Record> getPois() {
		return new ArrayList<>(pois);
	}
}
