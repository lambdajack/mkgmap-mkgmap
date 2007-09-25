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
 * Create date: 25-Sep-2007
 */
package uk.me.parabola.mkgmap.reader.overview;

import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.mkgmap.general.LevelInfo;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.reader.plugin.MapperBasedMapDataSource;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class for creating an overview map.  Nothing is actually read in from a file,
 * we just save some detail from the other img files that are going into the
 * map set.
 * 
 * @author Steve Ratcliffe
 */
public class OvermapMapDataSource extends MapperBasedMapDataSource
		implements LoadableMapDataSource, OverviewMap
{
	// We keep all non-duplicated copyright messages from the component maps.
	private Set<String> copyrights = new HashSet<String>();

	// We need the exact bounds that the map covers, so keep our own copy
	private int minLat = Integer.MAX_VALUE;
	private int minLong = Integer.MAX_VALUE;
	private int maxLat = Integer.MIN_VALUE;
	private int maxLong = Integer.MIN_VALUE;

	public boolean isFileSupported(String name) {
		return false;
	}

	public void load(String name) throws FileNotFoundException, FormatException {
		throw new FileNotFoundException("This is not supposed to be called");
	}

	public LevelInfo[] mapLevels() {
		// We use higher zoom levels for the overview map.
		// Lets hope the rest of the code will cope!
		return new LevelInfo[]{
				new LevelInfo(8, 10),
				new LevelInfo(7, 14),
		};
	}

	public String[] copyrightMessages() {
		return copyrights.toArray(new String[copyrights.size()]);
	}

	public Area getBounds() {
		return new Area(minLat, minLong, maxLat, maxLong);
	}

	public void addMapDataSource(LoadableMapDataSource src) {
		// Save all the copyright messages, discarding duplicates.
		copyrights.addAll(Arrays.asList(src.copyrightMessages()));

		Area a = src.getBounds();
		if (a.getMinLat() < minLat)
			minLat = a.getMinLat();
		if (a.getMinLong() < minLong)
			minLong = a.getMinLong();
		if (a.getMaxLat() > maxLat)
			maxLat = a.getMaxLat();
		if (a.getMaxLong() > maxLong)
			maxLong = a.getMaxLong();

		processPoints(src.getPoints());
		processLines(src.getLines());
		processShapes(src.getShapes());
	}

	private void processPoints(List<MapPoint> points) {
	}

	private void processLines(List<MapLine> lines) {
	}

	private void processShapes(List<MapShape> shapes) {

	}
}
