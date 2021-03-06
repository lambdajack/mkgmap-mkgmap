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

import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.app.ImgFileWriter;

/**
 * POIs ordered by type. Section 18 is the index into this.
 *
 * @author Steve Ratcliffe
 */
public class Mdr19 extends MdrSection implements HasHeaderFlags {
	private List<Mdr11Record> pois;
	private final List<Mdr18Record> poiTypes = new ArrayList<>();

	public Mdr19(MdrConfig config) {
		setConfig(config);
	}

	/**
	 * Sort the pois by type.
	 */
	@Override
	public void preWriteImpl() {
		// For mainly historical reasons, we keep the element type in a number
		// of different formats. Need to normalise it before sorting.
		pois.sort((o1, o2) -> Integer.compare(MdrUtils.fullTypeToNaturalType(o1.getType()),
				MdrUtils.fullTypeToNaturalType(o2.getType())));
	}

	/**
	 * Write out the contents of this section.
	 *
	 * @param writer Where to write it.
	 */
	public void writeSectData(ImgFileWriter writer) {
		int n = getSizes().getPoiSizeFlagged();
		int flag = getSizes().getPoiFlag();
		
		String lastName = null;
		int lastType = -1;
		int record = 1;
		int lastKey2 = -1;
		for (Mdr11Record p : pois) {
			int index = p.getRecordNumber();
			String name = p.getName();
			int key2 = p.isCity() ? p.getRegionIndex() : p.getCityIndex();
			if (!name.equals(lastName) || key2 != lastKey2) {
				index |= flag;
				lastName = name;
				lastKey2 = key2;
			}
			writer.putNu(n, index);

			int type = MdrUtils.fullTypeToNaturalType(p.getType());
			if (type != lastType) {
				Mdr18Record mdr18 = new Mdr18Record();
				mdr18.setType(type);
				mdr18.setRecord(record);
				poiTypes.add(mdr18);
				lastType = type;
			}
			record++;
		}

		Mdr18Record m18 = new Mdr18Record();
		m18.setRecord(record);
		m18.setType(0xffff);
		poiTypes.add(m18);
	}

	/**
	 * Release the copy of the pois. The other index is small and not worth
	 * worrying about.
	 */
	@Override
	protected void releaseMemory() {
		pois = null;
	}

	/**
	 * Records in this section are record numbers into mdr 11 with a flag
	 * so has to be large enough for a flagged index.
	 *
	 * @return The size of a record in this section.
	 */
	public int getItemSize() {
		return getSizes().getPoiSizeFlagged();
	}

	/**
	 * Method to be implemented by subclasses to return the number of items in the section. This will only be valid after
	 * the section is completely finished etc.
	 *
	 * @return The number of items in the section.
	 */
	protected int numberOfItems() {
		return pois.size();
	}

	/**
	 * Not yet known.
	 * 
	 * @return The correct value based on the contents of the section.  Zero if nothing needs to be done.
	 */
	public int getExtraValue() {
		return getItemSize() - 1;
	}

	public void setPois(List<Mdr11Record> pois) {
		this.pois = pois;
	}

	public List<Mdr18Record> getPoiTypes() {
		return poiTypes;
	}
}
