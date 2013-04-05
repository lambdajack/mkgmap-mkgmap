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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileNotWritableException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.map.Map;
import uk.me.parabola.imgfmt.app.map.MapReader;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.trergn.Point;
import uk.me.parabola.imgfmt.app.trergn.Polygon;
import uk.me.parabola.imgfmt.app.trergn.Polyline;
import uk.me.parabola.imgfmt.app.trergn.Zoom;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.build.MapBuilder;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapShape;

/**
 * Build the overview map.  This is a low resolution map that covers the whole
 * of a map set.  It also contains polygons that correspond to the areas
 * covered by the individual map tiles.
 *
 * @author Steve Ratcliffe
 */
public class OverviewBuilder implements Combiner {
	Logger log = Logger.getLogger(OverviewBuilder.class);

	private final OverviewMap overviewSource;
	private String areaName;
	private String overviewMapname;
	private String overviewMapnumber;
	private Zoom[] levels;
	private String outputDir;		
	private Sort sort;
	private boolean addPoints = false;
	private boolean addLines = false;
	private boolean addPolygons = false;
	HashMap<String, HashSet<Integer>> typeFilterMap = new HashMap<String, HashSet<Integer>>();
	private String overviewConfig;

	public OverviewBuilder(OverviewMap overviewSource) {
		this.overviewSource = overviewSource;
	}

	public void init(CommandArgs args) {
		areaName = args.get("area-name", "Overview Map");
		overviewMapname = args.get("overview-mapname", "osmmap");
		overviewMapnumber = args.get("overview-mapnumber", "63240000");
		outputDir = args.getOutputDir();
		sort = args.getSort();
		addPoints = args.getProperties().containsKey("overview-add-points");
		addLines = args.getProperties().containsKey("overview-add-lines");
		addPolygons = args.getProperties().containsKey("overview-add-polygons");
		overviewConfig = args.get("overview-cfg", null);
		if (overviewConfig != null)
			readConfig();
	}

	private void readConfig() {
		String[] objects = {"point","line","polygon"}; 
		try {
			LineNumberReader cfgReader = new LineNumberReader(new InputStreamReader(Utils.openFile(overviewConfig)));
					
			Pattern csvSplitter = Pattern.compile(Pattern.quote(":"));
			String cfgLine = null;

			try {
				while ((cfgLine = cfgReader.readLine()) != null) {
					if (cfgLine.startsWith("#")) {
						// comment
						continue; 
					}
					String[] items = csvSplitter.split(cfgLine); 
					//System.out.println(items.length + " " + cfgLine);
					if (items.length < 2){
						log.warn("Invalid format in ",
								cfgLine);
						continue; 					
					}
					for (String object :objects){
						if (object.equals(items[0])){
							HashSet<Integer> set = typeFilterMap.get(object);
							if (set == null){
								set = new HashSet<Integer>();
								typeFilterMap.put(object, set);
							}
							if ("*".equals(items[1])){
								set.add(Integer.MAX_VALUE);
							}
							else {
								Integer type = Integer.decode(items[1]);
								set.add(type);
							}
						}
					}
				}
				cfgReader.close();
			} catch (IOException e) {
				log.error("Can't read file " + overviewConfig);
			}
		} catch (FileNotFoundException e1) {
			log.error("Can't open file " + overviewConfig);
		}
	}

	public void onMapEnd(FileInfo finfo) {
		if (!finfo.isImg())
			return;

		try {
			readFileIntoOverview(finfo);
		} catch (FileNotFoundException e) {
			throw new MapFailedException("Could not read detail map " + finfo.getFilename(), e);
		}
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
			Map map = Map.createMap(overviewMapname, outputDir, params, overviewMapnumber, sort);
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
	private void readFileIntoOverview(FileInfo finfo) throws FileNotFoundException {
		addMapCoverageArea(finfo);

		MapReader mapReader = new MapReader(finfo.getFilename());

		levels = mapReader.getLevels();

		if (addPoints || typeFilterMap.containsKey("point"))
			readPoints(mapReader);
		if (addLines || typeFilterMap.containsKey("line"))
			readLines(mapReader);
		if (addPolygons || typeFilterMap.containsKey("polygon"))
			readShapes(mapReader);
	}

	/**
	 * Read the points from the .img file and add them to the overview map.
	 * We read from the least detailed level (apart from the empty one).
	 *
	 * @param mapReader Map reader on the detailed .img file.
	 */
	private void readPoints(MapReader mapReader) {
		HashSet<Integer> filterSet = typeFilterMap.get("point");
		boolean addAll;
		if (filterSet!= null) 
			addAll = (filterSet.contains(Integer.MAX_VALUE));
		else 
			addAll = true;

		int min = levels[1].getLevel();
		List<Point> pointList = mapReader.pointsForLevel(min, MapReader.WITH_EXT_TYPE_DATA);
		for (Point point: pointList) {
			log.debug("got point", point);
			if (!addAll){
				if (filterSet.contains(point.getType()) == false)
					continue;
			}
			MapPoint mp = new MapPoint();
			mp.setType(point.getType());
			mp.setName(point.getLabel().getText());
			mp.setMaxResolution(24); // TODO
			mp.setMinResolution(5);  // TODO
			mp.setLocation(point.getLocation());
			overviewSource.addPoint(mp);
		}
	}

	/**
	 * Read the lines from the .img file and add them to the overview map.
	 * We read from the least detailed level (apart from the empty one).
	 *
	 * @param mapReader Map reader on the detailed .img file.
	 */
	private void readLines(MapReader mapReader) {
		HashSet<Integer> filterSet = typeFilterMap.get("line");
		boolean addAll;
		if (filterSet!= null) 
			addAll = (filterSet.contains(Integer.MAX_VALUE));
		else 
			addAll = true;

		int min = levels[1].getLevel();
		List<Polyline> lineList = mapReader.linesForLevel(min);
		for (Polyline line : lineList) {
			log.debug("got line", line);
			if (!addAll){
				if (filterSet.contains(line.getType()) == false)
					continue;
			}
			
			MapLine ml = new MapLine();

			List<Coord> list = line.getPoints();
			log.debug("line point list", list);
			if (list.size() < 2)
				continue;

			ml.setType(line.getType());
			if (line.getLabel() != null)
				ml.setName(line.getLabel().getText());
			ml.setMaxResolution(24); // TODO
			ml.setMinResolution(5);  // TODO
			ml.setPoints(list);
			
			overviewSource.addLine(ml);
		}
	}

	/**
	 * Read the polygons from the .img file and add them to the overview map.
	 * We read from the least detailed level (apart from the empty one).
	 *
	 * @param mapReader Map reader on the detailed .img file.
	 */
	private void readShapes(MapReader mapReader) {
		HashSet<Integer> filterSet = typeFilterMap.get("polygon");
		boolean addAll;
		if (filterSet!= null) 
			addAll = (filterSet.contains(Integer.MAX_VALUE));
		else 
			addAll = true;
		
		int min = levels[1].getLevel();
		List<Polygon> list = mapReader.shapesForLevel(min);
		for (Polygon shape : list) {
			if (!addAll){
				if (filterSet.contains(shape.getType()) == false)
					continue;
			}
			MapShape ms = new MapShape();

			List<Coord> points = shape.getPoints();
			if (points.size() < 3)
				continue;

			ms.setType(shape.getType());
			if (shape.getLabel() != null)
				ms.setName(shape.getLabel().getText());
			ms.setMaxResolution(24); // TODO
			ms.setMinResolution(5);  // TODO
			ms.setPoints(points);

			overviewSource.addShape(ms);
		}
	}

	/**
	 * Add an area that shows the area covered by a detailed map.  This can
	 * be an arbitary shape, although at the current time we only support
	 * rectangles.
	 *
	 * @param finfo Information about a detail map.
	 */
	private void addMapCoverageArea(FileInfo finfo) {
		Area bounds = finfo.getBounds();

		int maxLon = bounds.getMaxLong();
		int maxLat = bounds.getMaxLat();
		int minLat = bounds.getMinLat();
		int minLon = bounds.getMinLong();
 
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

		overviewSource.addShape(bg);  	}

	public Area getBounds() {
		return overviewSource.getBounds();
	}
}