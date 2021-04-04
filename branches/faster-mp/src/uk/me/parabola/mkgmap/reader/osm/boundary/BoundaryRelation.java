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
import java.util.BitSet;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
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
		return true;
	}

	@Override
	protected boolean allowCloseOutsideBBox() {
		return false; 
	}
	
	@Override
	protected void processQueue(Queue<PolygonStatus> polygonWorkingQueue, BitSet nestedOuterPolygons,
			BitSet nestedInnerPolygons) {
		boolean outmostPolygonProcessing = true;
		
		
		outerResultArea = new java.awt.geom.Area();
		
		while (!polygonWorkingQueue.isEmpty()) {
	
			// the polygon is not contained by any other unfinished polygon
			PolygonStatus currentPolygon = polygonWorkingQueue.poll();
	
			// this polygon is now processed and should not be used by any
			// further step
			unfinishedPolygons.clear(currentPolygon.index);
	
			BitSet holeIndexes = checkRoleAgainstGeometry(currentPolygon, unfinishedPolygons, nestedOuterPolygons, nestedInnerPolygons);
	
			ArrayList<PolygonStatus> holes = getPolygonStatus(holeIndexes, (currentPolygon.outer ? "inner" : "outer"));
	
			// these polygons must all be checked for holes
			polygonWorkingQueue.addAll(holes);
	
			if (currentPolygon.outer) {
				// add the original ways to the list of ways that get the line tags of the mp
				// the joined ways may be changed by the auto closing algorithm
				outerWaysForLineTagging.addAll(currentPolygon.polygon.getOriginalWays());
			}
			
			if (currentPolygon.outer) {
				java.awt.geom.Area toAdd = Java2DConverter.createArea(currentPolygon.polygon.getPoints());
				if (outerResultArea.isEmpty())
					outerResultArea = toAdd;
				else
					outerResultArea.add(toAdd);
	
				for (Way outerWay : currentPolygon.polygon.getOriginalWays()) {
					if (outmostPolygonProcessing) {
						for (Entry<String, String> tag : outerWay.getTagEntryIterator()) {
							outerTags.put(tag.getKey(), tag.getValue());
						}
						outmostPolygonProcessing = false;
					} else {
						for (String tag : new ArrayList<>(outerTags.keySet())) {
							if (!outerTags.get(tag).equals(outerWay.getTag(tag))) {
								outerTags.remove(tag);
							}
						}
					}
				}
			} else {
				outerResultArea.subtract(Java2DConverter
						.createArea(currentPolygon.polygon.getPoints()));
			}
		}
	}

	@Override
	protected void doReporting(BitSet outmostInnerPolygons, BitSet unfinishedPolygons, BitSet nestedOuterPolygons,
			BitSet nestedInnerPolygons) {
		
		// do nothing for BoundaryRelation
	}

	@Override
	protected void createOuterLines() {
		outerTags.clear();
		for (Entry<String,String> mpTags : getTagEntryIterator()) {
			if (!"type".equals(mpTags.getKey())) {
				outerTags.put(mpTags.getKey(), mpTags.getValue());
			}
		}
		for (Way orgOuterWay : outerWaysForLineTagging) {
			for (Entry<String,String> tag : outerTags.entrySet()) {
				// remove the tag from the original way if it has the same value
				if (tag.getValue().equals(orgOuterWay.getTag(tag.getKey()))) {
					markTagsForRemovalInOrgWays(orgOuterWay, tag.getKey());
				}
			}
		}
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
	protected void cleanup() {
		super.cleanup();
		this.getElements().clear();
		((ArrayList<?>)this.getElements()).trimToSize();
	}
}
