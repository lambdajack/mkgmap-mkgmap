/*
 * Copyright (C) 2011.
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

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.srt.SortKey;

/**
 * Cities sorted by region name and city record number. One record for each combination.
 *
 * @author Steve Ratcliffe
 */
public class Mdr27 extends MdrSection {
	private final List<Mdr5Record> cities = new ArrayList<>();

	public Mdr27(MdrConfig config) {
		setConfig(config);
	}

	/**
	 * Cities are sorted by region and then by the mdr5 city record number.
	 * @param list The complete list of cities from mdr5.
	 */
	public void sortCities(List<Mdr5Record> list) {
		Sort sort = getConfig().getSort();

		List<SortKey<Mdr5Record>> keys = new ArrayList<>();
		for (Mdr5Record c : list) {
			Mdr13Record mdrRegion = c.getMdrRegion();
			if (mdrRegion != null) {
				SortKey<Mdr5Record> key = sort.createSortKey(c, mdrRegion.getName(), c.getGlobalCityIndex());
				keys.add(key);
			}
		}

		keys.sort(null);
		Mdr5Record lastCity = null;
		String lastName = null;
		int record = 0;
		Collator collator = sort.getCollator();
		for (SortKey<Mdr5Record> key : keys) {
			Mdr5Record city = key.getObject();
			if(!city.isSameByMapAndName(collator, lastCity)) {
				record++;

				Mdr13Record mdrRegion = city.getMdrRegion();
				Mdr28Record mdr28 = mdrRegion.getMdr28();
				String name = mdr28.getName();
				if (!name.equals(lastName)) {
					mdr28.setMdr27(record);
					lastName = name;
				}

				cities.add(city);
				lastCity = city;
			}
		}
	}

	/**
	 * Write out the contents of this section.
	 *
	 * @param writer Where to write it.
	 */
	public void writeSectData(ImgFileWriter writer) {
		int size = getItemSize();
		for (Mdr5Record city : cities) {
			writer.putNu(size,  city.getGlobalCityIndex());
		}
	}

	/**
	 * The size of a record in the section.  This is not a constant and might vary
	 * on various factors, such as the file version, if we are preparing for a
	 * device, the number of maps etc.
	 *
	 * @return The size of a record in this section.
	 */
	public int getItemSize() {
		return getSizes().getCitySize();
	}

	/**
	 * The number of records in this section.
	 *
	 * @return The number of items in the section.
	 */
	protected int numberOfItems() {
		return cities.size();
	}
}
