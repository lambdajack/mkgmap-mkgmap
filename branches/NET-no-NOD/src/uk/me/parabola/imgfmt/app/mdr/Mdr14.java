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

import uk.me.parabola.imgfmt.app.ImgFileWriter;

/**
 * The countries that occur in each map.
 *
 * @author Steve Ratcliffe
 */
public class Mdr14 extends MdrSection implements HasHeaderFlags {
	private final List<Mdr14Record> countries = new ArrayList<Mdr14Record>();

	public Mdr14(MdrConfig config) {
		setConfig(config);
	}

	public void writeSectData(ImgFileWriter writer) {
		countries.sort(null);
		
		for (Mdr14Record country : countries) {
			putMapIndex(writer, country.getMapIndex());
			writer.put2u(country.getCountryIndex());
			putStringOffset(writer, country.getStrOff());
		}
	}

	public void addCountry(Mdr14Record country) {
		countries.add(country);
	}

	public int getItemSize() {
		PointerSizes sizes = getSizes();
		return sizes.getMapSize() + 2 + sizes.getStrOffSize();
	}

	/**
	 * The number of records in this section.
	 * @return The number of items in the section.
	 */
	protected int numberOfItems() {
		return countries.size();
	}

	public int getExtraValue() {
		return 0x00;
	}

	public List<Mdr14Record> getCountries() {
		return countries;
	}
}
