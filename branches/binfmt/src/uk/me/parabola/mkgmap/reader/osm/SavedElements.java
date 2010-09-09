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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.util.EnhancedProperties;

/**
 * This is where we save the elements read from any of the file formats that
 * are in OSM format.  OSM format means that there are nodes, ways and relations
 * and they have tags.
 *
 * Both the XML format and the binary format use this.
 *
 * @author Steve Ratcliffe
 */
public class SavedElements implements OsmCollector {
	private Map<Long, Node> nodeMap;
	private Map<Long, Way> wayMap;
	private Map<Long, Relation> relationMap;

	public SavedElements(EnhancedProperties args) {
		if (args.getProperty("preserve-element-order", false)) {
			nodeMap = new LinkedHashMap<Long, Node>(5000);
			wayMap = new LinkedHashMap<Long, Way>(5000);
			relationMap = new LinkedHashMap<Long, Relation>();
		} else {
			nodeMap = new HashMap<Long, Node>();
			wayMap = new HashMap<Long, Way>();
			relationMap = new HashMap<Long, Relation>();
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
		relationMap.put(rel.getId(), rel);
	}

	public Way getWay(long id) {
		return wayMap.get(id);
	}

	public Node getNode(long id) {
		return nodeMap.get(id);
	}

	public Relation getRelation(long id) {
		return relationMap.get(id);
	}

	public void finish(OsmConverter converter) {
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

	public Map<Long, Way> getWays() {
		return wayMap;
	}
}
