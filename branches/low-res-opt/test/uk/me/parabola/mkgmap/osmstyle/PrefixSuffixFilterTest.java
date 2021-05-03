/*
 * Copyright (C) 2020.
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
package uk.me.parabola.mkgmap.osmstyle;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.util.EnhancedProperties;


/**
 * Unit test for the code which implements the --road-name-config option
 */
public class PrefixSuffixFilterTest {

	@Test
	public void testFilter() {
		EnhancedProperties props = new EnhancedProperties();
		props.put("road-name-config", "resources/roadNameConfig.txt");
		PrefixSuffixFilter filter = new PrefixSuffixFilter(props);
		MapRoad road;
		road = genRoad("Rue de la Concorde", "FRA");
		filter.filter(road);
		assertEquals("Rue de la" + (char) 0x1e + "Concorde", road.getName());
		road = genRoad("Place de l'Etoile", "FRA");
		filter.filter(road);
		assertEquals("Place de l'" + (char) 0x1b + "Etoile", road.getName());
		road = genRoad("Rue de la Normandie", "DEU"); // no change in Germany
		filter.filter(road);
		assertEquals("Rue de la Normandie", road.getName());
		road = genRoad("Karl-Mustermann-Straße", "DEU");
		filter.filter(road);
		assertEquals("Karl-Mustermann" + (char) 0x1c + "-Straße", road.getName());
		road = genRoad("Karl-Mustermann-Straße", "DEU");
		filter.filter(road);
		assertEquals("Karl-Mustermann" + (char) 0x1c + "-Straße", road.getName());
		
	}

	private static MapRoad genRoad(String name, String isoCountry) {
		MapLine line = new MapLine();
		line.setPoints(Arrays.asList(new Coord(2.0,2.0), new Coord(2.01, 2.0)));
		MapRoad r = new MapRoad(1, 1, line);
		r.setName(name);
		r.setCountry(isoCountry);
		return r;
	}

}
