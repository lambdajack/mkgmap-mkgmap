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
import java.util.List;
import java.util.Map;
import java.util.Set;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * This is where we save the elements read from any of the file formats that
 * are in OSM format.  OSM format means that there are nodes, ways and relations
 * and they have tags.
 *
 * Both the XML format and the binary format use this.
 *
 * In the early days of mkgmap, the nodes and ways were converted as soon
 * as they were encounted in the input file.  After relations that is not
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

	private Map<Long, Coord> coordMap = new HashMap<Long, Coord>(50000);

	private Map<Long, Node> nodeMap;
	private Map<Long, Way> wayMap;
	private Map<Long, Relation> relationMap;

	private final Map<Long, Set<String>> mpWayRemoveTags = new HashMap<Long,Set<String>>();

	private final Map<Long, List<Map.Entry<String,Relation>>> deferredRelationMap = new HashMap<Long, List<Map.Entry<String,Relation>>>();

	// This is an explicitly given bounding box from the input file command line etc.
	private Area boundingBox;

	// This is a calculated bounding box, which is only available if there is
	// no given bounding box.
	private int minLat = Utils.toMapUnit(180.0);
	private int minLon = Utils.toMapUnit(180.0);
	private int maxLat = Utils.toMapUnit(-180.0);
	private int maxLon = Utils.toMapUnit(-180.0);

	private final boolean ignoreTurnRestrictions;
	private final boolean processBoundaryRelations;

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

		ignoreTurnRestrictions = args.getProperty("ignore-turn-restrictions", false);
		processBoundaryRelations = args.getProperty("process-boundary-relations", false);

	}

	/**
	 * {@inheritDoc}
	 *
	 * We use this to calculate a bounding box in the situation where none is
	 * given.  In the usual case where there is a bounding box, then nothing
	 * is done.
	 *
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
	 * Add the given node and save it.
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
		String type = rel.getTag("type");
		if (type != null) {
			if ("multipolygon".equals(type)) {
				
				Area mpBbox = getBoundingBox();
				rel = new MultiPolygonRelation(rel, getWays(), mpWayRemoveTags, mpBbox);
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

			if (!processBoundaryRelations &&
			     rel instanceof MultiPolygonRelation &&
				 ((MultiPolygonRelation)rel).isBoundaryRelation()) {
				log.info("Ignore boundary multipolygon "+rel.toBrowseURL());
			} else {
				rel.processElements();
			}

			List<Map.Entry<String,Relation>> entries = deferredRelationMap.remove(id);
			if (entries != null)
				for (Map.Entry<String,Relation> entry : entries)
					entry.getValue().addElement(entry.getKey(), rel);
		}
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

	public void convert(OsmConverter converter) {
		finishMultiPolygons();

		converter.setBoundingBox(boundingBox);
		coordMap = null;

		for (Relation r : relationMap.values())
			converter.convertRelation(r);

		for (Node n : nodeMap.values())
			converter.convertNode(n);

		nodeMap = null;

		for (Way w: wayMap.values())
			converter.convertWay(w);

		wayMap = null;

		converter.end();

		relationMap = null;
	}


	private void finishMultiPolygons() {
		for (Map.Entry<Long,Set<String>> wayTagsRemove : mpWayRemoveTags.entrySet()) {
			Way w = getWay(wayTagsRemove.getKey());
			if (w == null) {
				log.debug("Cannot find way",wayTagsRemove.getKey(), "to remove tags by multipolygon processing.");
				continue;
			}

			log.debug("Remove tags",wayTagsRemove.getValue(),"from way",w.getId(), w.toTagString());
			for (String tagname : wayTagsRemove.getValue()) {
				w.deleteTag(tagname);
			}
			log.debug("After removal",w.getId(), w.toTagString());
		}
		mpWayRemoveTags.clear();
	}

	public Map<Long, Way> getWays() {
		return wayMap;
	}

	/**
	 * Get the bounding box.  This is either the one that was explicitly included in the input
	 * file, or if none was given, the calculated one.
	 */
	public Area getBoundingBox() {
		if (boundingBox != null) {
			return boundingBox;
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
