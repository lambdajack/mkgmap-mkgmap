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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.app.ImgFileWriter;

/**
 * POI types.  A simple list of the types that are used?
 * If you don't have this section, then the ability to select POI categories
 * goes away.
 * 
 * @author Steve Ratcliffe
 */
public class Mdr4 extends MdrSection implements HasHeaderFlags {
	private final Set<Mdr4Record> poiTypes = new HashSet<>();

	public Mdr4(MdrConfig config) {
		setConfig(config);
	}

	
	public void writeSectData(ImgFileWriter writer) {
		List<Mdr4Record> list = new ArrayList<>(poiTypes);
		list.sort(null);

		for (Mdr4Record r : list) {
			writer.put1u(r.getType());
			writer.put1u(r.getUnknown());
			writer.put1u(r.getSubtype());
		}
	}

	public int getItemSize() {
		return 3;
	}

	public void addType(int type) {
		poiTypes.add(new Mdr4Record((type >> 8) & 0xff, type & 0xff));
	}

	/**
	 * The number of records in this section.
	 *
	 * @return The number of items in the section.
	 */
	protected int numberOfItems() {
		return poiTypes.size();
	}


	public int getExtraValue() {
		return 0x00;
	}
}
