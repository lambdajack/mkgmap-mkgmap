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

import java.util.Map;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;

/**
 * Collects the map elements in their OSM form, that is: with osm nodes and
 * ways with tags.
 *
 * In the early days of mkgmap, the nodes and ways were converted as soon
 * as they were encounted in the input file.  After relations that is not
 * possible, you have to save up all the nodes and ways as they might be
 * needed for relations.
 *
 * We also want access to the other ways/nodes to generate sea polygons,
 * cycle lanes and so on.
 *
 * @author Steve Ratcliffe
 */
public interface OsmCollector {

	/**
	 * Add a new coordinate point to the map. Called for every point in the
	 * map including nodes that have no tags and are only used within ways.
	 * @param co The point.
	 */
	public void addPoint(Coord co);

	/**
	 * Add the given node and save it. The node must be completely formed
	 * with all its tags. If it was read from an XML file, then you would
	 * only call this routine on the closing tags.
	 *
	 * All nodes that are passed to this routine will have some tags.
	 *
	 * @param node The osm node.
	 */
	public void addNode(Node node);

	/**
	 * Add the given way. The way must be complete, call after the end tag
	 * is seen for the XML format.
	 *
	 * @param way The osm way.
	 */
	public void addWay(Way way);

	/**
	 * Add the given relation. The relation must be complete, call after the
	 * end tag is seen for the XML format.
	 *
	 * @param rel The osm relation.
	 */
	public void addRelation(Relation rel);

	public Way getWay(long id);

	public Node getNode(long id);

	public Relation getRelation(long id);

	public void convert(OsmConverter converter);

	/** TEMPORARY, will be removed. */
	@Deprecated Map<Long, Way> getWays();

	public void setBoundingBox(Area bbox);

}
