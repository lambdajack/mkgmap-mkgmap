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
package uk.me.parabola.mkgmap.reader.osm;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineClipper;
import uk.me.parabola.util.EnhancedProperties;

/**
 * This is where we save the elements read from any of the file formats that
 * are in OSM format.  OSM format means that there are nodes, ways and relations
 * and they have tags.
 *
 * Both the XML format and the binary format use this.
 *
 * In the early days of mkgmap, the nodes and ways were converted as soon
 * as they were encountered in the input file.  After relations that is not
 * possible, you have to save up all the nodes and ways as they might be
 * needed for relations.
 *
 * We also want access to the other ways/nodes to generate sea polygons,
 * prepare for routing etc.
 *
 * @author Steve Ratcliffe
 */
public class ElementSaver {
	private static final Logger log = Logger.getLogger(ElementSaver.class);

	protected OSMId2ObjectMap<Coord> coordMap = new OSMId2ObjectMap<Coord>();

	protected Map<Long, Node> nodeMap;
	protected Map<Long, Way> wayMap;
	protected Map<Long, Relation> relationMap;

	protected final Map<Long, List<Map.Entry<String,Relation>>> deferredRelationMap = new HashMap<Long, List<Map.Entry<String,Relation>>>();

	// This is an explicitly given bounding box from the input file command line etc.
	private Area boundingBox;

	// This is a calculated bounding box, which is only available if there is
	// no given bounding box.
	private int minLat = Utils.toMapUnit(180.0);
	private int minLon = Utils.toMapUnit(180.0);
	private int maxLat = Utils.toMapUnit(-180.0);
	private int maxLon = Utils.toMapUnit(-180.0);

	// Options
	private final boolean ignoreBuiltinRelations;
	private final boolean ignoreTurnRestrictions;

	/** name of the tag that contains a ;-separated list of tagnames that should be removed after all elements have been processed */
	public static final String MKGMAP_REMOVE_TAG = "mkgmap:removetags";
	/** tagvalue of the {@link ElementSaver#MKGMAP_REMOVE_TAG} if all tags should be removed */
	public static final String MKGMAP_REMOVE_TAG_ALL_KEY = "mkgmap:ALL";

	public ElementSaver(EnhancedProperties args) {
		if (args.getProperty("preserve-element-order", false)) {
			nodeMap = new LinkedHashMap<Long, Node>(5000);
			wayMap = new LinkedHashMap<Long, Way>(5000);
			relationMap = new LinkedHashMap<Long, Relation>();
		} else {
			nodeMap = new HashMap<Long, Node>();
			wayMap = new HashMap<Long, Way>();
			relationMap = new HashMap<Long, Relation>();
		}

		ignoreBuiltinRelations = args.getProperty("ignore-builtin-relations", false);
		ignoreTurnRestrictions = args.getProperty("ignore-turn-restrictions", false);
	}

	/**
	 * Store the {@link Coord} with the associated OSM id.
	 * We use this to calculate a bounding box in the situation where none is
	 * given.  In the usual case where there is a bounding box, then nothing
	 * is done.
	 *
	 * @param id the OSM id
	 * @param co The point.
	 */
	public void addPoint(long id, Coord co) {
		coordMap.put(id, co);
		if (boundingBox == null) {
			if (co.getLatitude() < minLat)
				minLat = co.getLatitude();
			if (co.getLatitude() > maxLat)
				maxLat = co.getLatitude();

			if (co.getLongitude() < minLon)
				minLon = co.getLongitude();
			if (co.getLongitude() > maxLon)
				maxLon = co.getLongitude();
		}
	}

	/**
	 * Add the given node and save it. The node should have tags.
	 *
	 * @param node The osm node.
	 */
	public void addNode(Node node) {
		nodeMap.put(node.getId(), node);
	}

	/**
	 * Add the given way.
	 *
	 * @param way The osm way.
	 */
	public void addWay(Way way) {
		wayMap.put(way.getId(), way);
	}

	/**
	 * Add the given relation.
	 *
	 * @param rel The osm relation.
	 */
	public void addRelation(Relation rel) {
		if (!ignoreBuiltinRelations) {
			String type = rel.getTag("type");
			if (type == null) {
			} else if ("multipolygon".equals(type) || "boundary".equals(type)) {
				rel = createMultiPolyRelation(rel); 
			} else if("restriction".equals(type)) {
				if (ignoreTurnRestrictions)
					rel = null;
				else
					rel = new RestrictionRelation(rel);
			}
		}

		if(rel != null) {
			long id = rel.getId();
			relationMap.put(rel.getId(), rel);
			
			rel.processElements();

			List<Map.Entry<String,Relation>> entries = deferredRelationMap.remove(id);
			if (entries != null)
				for (Map.Entry<String,Relation> entry : entries)
					entry.getValue().addElement(entry.getKey(), rel);
		}
	}

	/**
	 * Create a multipolygon relation.  Has to be here as they use shared maps.
	 * Would like to change how the constructor works so that was not needed.
	 * @param rel The original relation, that the result will replace.
	 * @return A new multi polygon relation, based on the input relation.
	 */
	public Relation createMultiPolyRelation(Relation rel) {
		return new MultiPolygonRelation(rel, wayMap, getBoundingBox());
	}
	
	public SeaPolygonRelation createSeaPolyRelation(Relation rel) {
		return new SeaPolygonRelation(rel, wayMap, getBoundingBox());
	}

	public void setBoundingBox(Area bbox) {
		boundingBox = bbox;
	}

	public Coord getCoord(long id) {
		return coordMap.get(id);
	}

	public Node getNode(long id) {
		return nodeMap.get(id);
	}

	public Way getWay(long id) {
		return wayMap.get(id);
	}

	public Relation getRelation(long id) {
		return relationMap.get(id);
	}
	
	public void finishLoading() {
		coordMap = null;
	}

	/**
	 * After the input file is read, this is called to convert the saved information
	 * into the general intermediate format.
	 *
	 * @param converter The Converter to use.
	 */
	public void convert(OsmConverter converter) {

		// We only do this if an explicit bounding box was given.
		if (boundingBox != null)
			makeBoundaryNodes();

		converter.setBoundingBox(getBoundingBox());

		for (Relation r : relationMap.values())
			converter.convertRelation(r);

		for (Node n : nodeMap.values()){
			converter.convertNode(n);
			if (n.getTag("fixme") != null || n.getTag("FIXME") != null){
				n.getLocation().setFixme(true);
			}
		}

		nodeMap = null;

		Iterator<Way> wayIter = wayMap.values().iterator();
		while (wayIter.hasNext()){
			Way way = wayIter.next();
			converter.convertWay(way);
			wayIter.remove();
		}
		wayMap = null;

		converter.end();

		relationMap = null;
		deferredRelationMap.clear();
	}

	/**
	 *
	 * "soft clip" each way that crosses a boundary by adding a point
	 * at each place where it meets the boundary
	 */
	private void makeBoundaryNodes() {
		log.info("Making boundary nodes");
		int numBoundaryNodesDetected = 0;
		int numBoundaryNodesAdded = 0;
		for(Way way : wayMap.values()) {
			List<Coord> points = way.getPoints();

			// clip each segment in the way against the bounding box
			// to find the positions of the boundary nodes - loop runs
			// backwards so we can safely insert points into way
			for (int i = points.size() - 1; i >= 1; --i) {
				Coord[] pair = { points.get(i - 1), points.get(i) };
				Coord[] clippedPair = LineClipper.clip(getBoundingBox(), pair, true);
				// we're only interested in segments that touch the
				// boundary
				if (clippedPair != null) {
					// the segment touches the boundary or is
					// completely inside the bounding box
					if (clippedPair[1] != points.get(i)) {
						// the second point in the segment is outside
						// of the boundary
						assert clippedPair[1].getOnBoundary();
						// insert boundary point before the second point
						points.add(i, clippedPair[1]);
						++numBoundaryNodesAdded;
					} else if(clippedPair[1].getOnBoundary())
						++numBoundaryNodesDetected;


					if (clippedPair[0] != points.get(i - 1)) {
						// the first point in the segment is outside
						// of the boundary
						assert clippedPair[0].getOnBoundary();
						// insert boundary point after the first point
						points.add(i, clippedPair[0]);
						++numBoundaryNodesAdded;
					} else if (clippedPair[0].getOnBoundary())
						++numBoundaryNodesDetected;
				}
			}
		}

		log.info("Making boundary nodes - finished (" + numBoundaryNodesAdded + " added, " + numBoundaryNodesDetected + " detected)");
	}

	public Map<Long, Node> getNodes() {
		return nodeMap;
	}
	
	public Map<Long, Way> getWays() {
		return wayMap;
	}

	public Map<Long, Relation> getRelations() {
		return relationMap;
	}
	
	/**
	 * Get the bounding box.  This is either the one that was explicitly included in the input
	 * file, or if none was given, the calculated one.
	 */
	public Area getBoundingBox() {
		if (boundingBox != null) {
			return boundingBox;
		} else if (minLat == Utils.toMapUnit(180.0) && maxLat == Utils.toMapUnit(-180.0)) {
			return new Area(0, 0, 0, 0);
		} else {
			return new Area(minLat, minLon, maxLat, maxLon);
		}
	}

	public void deferRelation(long id, Relation rel, String role) {
		// The relation may be defined later in the input.
		// Defer the lookup.
		Map.Entry<String,Relation> entry =
				new AbstractMap.SimpleEntry<String,Relation>(role, rel);

		List<Map.Entry<String,Relation>> entries = deferredRelationMap.get(id);
		if (entries == null) {
			entries = new ArrayList<Map.Entry<String,Relation>>();
			deferredRelationMap.put(id, entries);
		}

		entries.add(entry);
	}
}
