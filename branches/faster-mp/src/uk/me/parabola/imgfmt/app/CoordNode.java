/*
 * Copyright (C) 2008 Steve Ratcliffe
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
 * Create date: 13-Jul-2008
 */
package uk.me.parabola.imgfmt.app;

/**
 * A coordinate that is known to be a routing node.  You can tell by the fact
 * that getId() returns != 0.
 * 
 * @author Steve Ratcliffe
 */
public class CoordNode extends Coord {
	private final int id;

	/**
	 * Construct from co-ordinates that are already in map-units.
	 *
	 * @param latitude The latitude in map units.
	 * @param longitude The longitude in map units.
	 * @param id The ID of this routing node.
	 * @param boundary This is a routing node on the boundary.
	 * @param onCountryBorder This is a routing node on a country boundary.
	 */
	public CoordNode(int latitude, int longitude, int id, boolean boundary, boolean onCountryBorder) {
		super(latitude, longitude);
		this.id = id;
		setOnBoundary(boundary);
		setOnCountryBorder(onCountryBorder);
		setNumberNode(true);
	}

	public CoordNode(Coord other, int id, boolean boundary, boolean onCountryBorder) {
		super(other);
		this.id = id;
		setOnBoundary(boundary);
		setOnCountryBorder(onCountryBorder);
		setNumberNode(true);
	}
	
	@Override
	public int getId() {
		return id;
	}
}
