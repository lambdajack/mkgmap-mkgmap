/*
 * Copyright (C) 2010.
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
package uk.me.parabola.mkgmap.combiners;

import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileNotWritableException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.map.Map;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.build.MapBuilder;
import uk.me.parabola.mkgmap.general.MapShape;

/**
 * Build the overview map.  This is a low resolution map that covers the whole
 * of a map set.  It also contains areas that correspond to the areas
 * covered by the individual map tiles.
 *
 * @author Steve Ratcliffe
 */
public class OverviewBuilder implements Combiner {
	private final OverviewMap overviewSource;
	private String areaName;
	private String overviewMapname;
	private String overviewMapnumber;

	public OverviewBuilder(OverviewMap overviewSource) {
		this.overviewSource = overviewSource;
	}

	public void init(CommandArgs args) {
		areaName = args.get("area-name", "Overview Map");
		overviewMapname = args.get("overview-mapname", "osmmap");
		overviewMapnumber = args.get("overview-mapnumber", "63240000");
	}

	public void onMapEnd(FileInfo finfo) {
		addToOverviewMap(finfo);
	}

	public void onFinish() {
		writeOverviewMap();
	}

	/**
	 * Write out the overview map.
	 */
	private void writeOverviewMap() {
		MapBuilder mb = new MapBuilder();
		mb.setEnableLineCleanFilters(false);

		FileSystemParam params = new FileSystemParam();
		params.setBlockSize(512);
		params.setMapDescription(areaName);

		try {
			Map map = Map.createMap(overviewMapname, params, overviewMapnumber);
			mb.makeMap(map, overviewSource);
			map.close();
		} catch (FileExistsException e) {
			throw new ExitException("Could not create overview map", e);
		} catch (FileNotWritableException e) {
			throw new ExitException("Could not write to overview map", e);
		}
	}

	/**
	 * Add an individual .img file to the overview map.
	 *
	 * @param finfo Information about an individual map.
	 */
	private void addToOverviewMap(FileInfo finfo) {
		Area bounds = finfo.getBounds();

		//System.out.printf("overview shift %d\n", overviewSource.getShift());
		int overviewMask = ((1 << overviewSource.getShift()) - 1);
		//System.out.printf("mask %x\n", overviewMask);
		//System.out.println("overviewSource.getShift() = " + overviewSource.getShift());

		int maxLon = roundDown(bounds.getMaxLong(), overviewMask);
		int maxLat = roundUp(bounds.getMaxLat(), overviewMask);
		int minLat = roundUp(bounds.getMinLat(), overviewMask);
		int minLon = roundDown(bounds.getMinLong(), overviewMask);

		//System.out.printf("maxLat 0x%x, modified=0x%x\n", bounds.getMaxLat(), maxLat);
		//System.out.printf("maxLat %f, modified=%f\n", Utils.toDegrees(bounds.getMaxLat()), Utils.toDegrees(maxLat));
		//System.out.printf("minLat 0x%x, modified=0x%x\n", bounds.getMinLat(), minLat);
		//System.out.printf("minLat %f, modified=%f\n", Utils.toDegrees(bounds.getMinLat()), Utils.toDegrees(minLat));
		//System.out.printf("maxLon 0x%x, modified=0x%x\n", bounds.getMaxLong(), maxLon);
		//System.out.printf("maxLon %f, modified=%f\n", Utils.toDegrees(bounds.getMaxLong()), Utils.toDegrees(maxLon));
		//System.out.printf("minLon 0x%x, modified=0x%x\n", bounds.getMinLong(), minLon);
		//System.out.printf("minLon %f, modified=%f\n", Utils.toDegrees(bounds.getMinLong()), Utils.toDegrees(minLon));

		// Add a background polygon for this map.
		List<Coord> points = new ArrayList<Coord>();

		Coord start = new Coord(minLat, minLon);
		points.add(start);
		overviewSource.addToBounds(start);

		Coord co = new Coord(maxLat, minLon);
		points.add(co);
		overviewSource.addToBounds(co);

		co = new Coord(maxLat, maxLon);
		points.add(co);
		overviewSource.addToBounds(co);

		co = new Coord(minLat, maxLon);
		points.add(co);
		overviewSource.addToBounds(co);

		points.add(start);

		// Create the background rectangle
		MapShape bg = new MapShape();
		bg.setType(0x4a);
		bg.setPoints(points);
		bg.setMinResolution(10);
		bg.setName(finfo.getDescription() + '\u001d' + finfo.getMapname());

		overviewSource.addShape(bg);
	}

	private int roundUp(int len, int overviewMask) {
		//System.out.printf("before up 0x%x\n", len);
		if (len > 0)
			return (len + overviewMask) & ~overviewMask;
		else
			return len & ~overviewMask;
	}

	private int roundDown(int len, int overviewMask) {
		//System.out.printf("before down 0x%x\n", len);
		if (len > 0)
			return len & ~overviewMask;
		else
			return -(-len +overviewMask & ~overviewMask);
	}

	public Area getBounds() {
		return overviewSource.getBounds();
	}
}
