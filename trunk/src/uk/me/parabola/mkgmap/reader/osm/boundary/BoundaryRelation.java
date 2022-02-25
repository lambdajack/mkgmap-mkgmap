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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.Java2DConverter;


public class BoundaryRelation extends MultiPolygonRelation {
	private java.awt.geom.Area outerResultArea;
	
	/** keeps the result of the multipolygon processing */
	private Boundary boundary;
	
	public BoundaryRelation(Relation other, Map<Long, Way> wayMap, Area bbox) {
		super(other, wayMap, bbox);
	}
	
	public Boundary getBoundary() {
		if (boundary == null) {
			if (outerResultArea == null) {
				return null;
			}
			boundary = new Boundary(outerResultArea, this, "r"+this.getId());
			outerResultArea = null;
		}
		return boundary;
	}
	
	@Override
	protected boolean isUsable() {
		return true; // we assume that tags were already checked by calling code
	}

	/**
	 * Tile bounds have a different meaning when boundaries are compiled. We expect
	 * either planet or a bbox around a country extract in tile bounds. A country
	 * extract typically only contains the complete admin_level boundaries for one
	 * country but also many incomplete boundaries for neighbouring countries. <br>
	 * We may either ignore all incomplete boundaries or try to close them using the
	 * shape ([country].poly) file. The latter should improve LocationHook results
	 * for data outside the country.  
	 * 
	 * @return false
	 */
	@Override
	protected boolean assumeDataInBoundsIsComplete() {
		return false;
	}
	
	@Override
	protected boolean needsWaysForOutlines() {
		return false; 
	}
	
	@Override
	protected void processQueue(Partition partition, Queue<PolygonStatus> polygonWorkingQueue) {
		if (outerResultArea == null)
			outerResultArea = new java.awt.geom.Area();
		
		while (!polygonWorkingQueue.isEmpty()) {
	
			// the polygon is not contained by any other unfinished polygon
			PolygonStatus currentPolygon = polygonWorkingQueue.poll();
	
			// this polygon is now processed and should not be used by any
			// further step
			partition.markFinished(currentPolygon);
	
			List<PolygonStatus> holes = partition.getPolygonStatus(currentPolygon);

			// these polygons must all be checked for holes
			polygonWorkingQueue.addAll(holes);
	
			if (currentPolygon.outer) {
				// add the original ways to the list of ways that get the line tags of the mp
				// the joined ways may be changed by the auto closing algorithm
				outerWaysForLineTagging.addAll(currentPolygon.polygon.getOriginalWays());

				java.awt.geom.Area toAdd = Java2DConverter.createArea(currentPolygon.polygon.getPoints());
				if (outerResultArea.isEmpty())
					outerResultArea = toAdd;
				else
					outerResultArea.add(toAdd);
			} else {
				outerResultArea.subtract(Java2DConverter.createArea(currentPolygon.polygon.getPoints()));
			}
		}
	}

	@Override
	protected boolean doReporting() {
		return false;
	}

	@Override
	protected double getMaxCloseDist() {
		String admString = getTag("admin_level");
		if (admString == null)
			return 1000;
		switch (admString) {
		case "2": return 50000; 
		case "3": return 20000;
		case "4": return 4000; 
		default:
			return 1000;
		}
	}

	
	@Override
	public String toString() {
		String basicInfo = "boundary r" + getId();
		String admLevel = getTag("admin_level");
		if (admLevel != null)
			return basicInfo + " admlvl=" + admLevel + " (" + getTag("name") + ")";
		String postal = getTag("postal_code");
		if (postal != null)
			return basicInfo + " postal_code=" + postal;
		return basicInfo;
	}

	@Override
	protected void cleanup() {
		super.cleanup();
		this.getElements().clear();
		((ArrayList<?>)this.getElements()).trimToSize();
	}
}
