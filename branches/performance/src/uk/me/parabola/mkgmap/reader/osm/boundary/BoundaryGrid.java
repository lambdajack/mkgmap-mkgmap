/*
 * Copyright (C) 2006, 2011.
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
package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.util.List;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Tags;
import uk.me.parabola.util.EnhancedProperties;

/**
 * A simple tree that stores pre-compiled boundary information
 * 
 * @author GerdP
 * 
 */
public class BoundaryGrid {
	private static final Logger log = Logger.getLogger(BoundaryGrid.class);

	private final uk.me.parabola.imgfmt.app.Area searchBbox;
	private final BoundaryQuadTree[][] grid;
	private final int minLat;
	private final int minLon;
	private final EnhancedProperties props;

	/**
	 * A simple grid that contains references to BoundaryQuadTrees loaded from
	 * preprocessed boundary files. The grid will fully cover the bounding box
	 * passed in bbox.
	 * 
	 * @param boundaryDir
	 *            the path to the preprocessed boundary files
	 * @param bbox
	 *            the bounding box of all points that might be searched
	 * @param props
	 *            used to determine the ISO code of level 2 boundaries
	 */
	public BoundaryGrid(String boundaryDirName,
			uk.me.parabola.imgfmt.app.Area bbox, EnhancedProperties props) {
		minLat = BoundaryUtil.getSplitBegin(bbox.getMinLat());
		minLon = BoundaryUtil.getSplitBegin(bbox.getMinLong());
		int gridMaxLat = BoundaryUtil.getSplitBegin(bbox.getMaxLat());
		int gridMaxLon = BoundaryUtil.getSplitBegin(bbox.getMaxLong());
		int dimLat = (gridMaxLat - minLat) / BoundaryUtil.RASTER + 1;
		int dimLon = (gridMaxLon - minLon) / BoundaryUtil.RASTER + 1;
		grid = new BoundaryQuadTree[dimLat][dimLon];
		this.searchBbox = bbox;

		this.props = props;
		init(boundaryDirName);
	}

	public Tags get(Coord co) {
		if (!searchBbox.contains(co))
			return null;
		int gridLat = (co.getLatitude() - minLat) / BoundaryUtil.RASTER;
		int gridLon = (co.getLongitude() - minLon) / BoundaryUtil.RASTER;
		if (grid[gridLat][gridLon] == null)
			return null;
		else
			return grid[gridLat][gridLon].get(co);
	}

	public BoundaryQuadTree getTree(int lat, int lon){
		if (grid.length < lat || grid[0].length < lon){
			return grid[lat][lon];
		}
		log.error("grid doesn't contain tree at " + lat + "/" + lon);
		return null;
	}
	/**
	 * Fill the grid. Determine the bnd files that must be loaded and try to
	 * load them
	 * 
	 * @param boundaryDir
	 *            Directory with bnd files
	 */
	private void init(String boundaryDirName){
		List<String> requiredFileNames = BoundaryUtil.getRequiredBoundaryFileNames(searchBbox);
		for (String boundaryFileName : requiredFileNames) {
			System.out.println("loading boundary file: " + boundaryFileName);
			BoundaryQuadTree bqt = BoundaryUtil.loadQuadTree(boundaryDirName, boundaryFileName, searchBbox, props);
			addToGrid(bqt, BoundaryUtil.getBbox(boundaryFileName));
		}
	}
	
	/**
	 * Build the QuadTree for a given grid segment
	 * 
	 * @param bqt 
	 * @param fileBbox
	 */
	private void addToGrid(BoundaryQuadTree bqt, uk.me.parabola.imgfmt.app.Area fileBbox) {
		int gridLat = (fileBbox.getMinLat() - minLat) / BoundaryUtil.RASTER;
		int gridLon = (fileBbox.getMinLong() - minLon) / BoundaryUtil.RASTER;
		grid[gridLat][gridLon] = bqt;

	}


}
