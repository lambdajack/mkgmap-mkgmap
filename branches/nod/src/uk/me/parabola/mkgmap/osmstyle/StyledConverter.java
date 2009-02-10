/*
 * Copyright (C) 2007 Steve Ratcliffe
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
 * Create date: Feb 17, 2008
 */
package uk.me.parabola.mkgmap.osmstyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.AreaClipper;
import uk.me.parabola.mkgmap.general.Clipper;
import uk.me.parabola.mkgmap.general.LineAdder;
import uk.me.parabola.mkgmap.general.MapCollector;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.general.RoadNetwork;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmConverter;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Rule;
import uk.me.parabola.mkgmap.reader.osm.Style;
import uk.me.parabola.mkgmap.reader.osm.Way;

/**
 * Convert from OSM to the mkgmap intermediate format using a style.
 * A style is a collection of files that describe the mappings to be used
 * when converting.
 *
 * @author Steve Ratcliffe
 */
public class StyledConverter implements OsmConverter {
	private static final Logger log = Logger.getLogger(StyledConverter.class);

	private final String[] nameTagList;

	private final MapCollector collector;

	private Clipper clipper = Clipper.NULL_CLIPPER;

	private int roadId;

	private final int MAX_NODES_IN_WAY = 16;

	// nodeIdMap maps a Coord into a nodeId
	private Map<Coord, Integer> nodeIdMap = new HashMap<Coord, Integer>();
	private int nextNodeId;
	
	private final Rule wayRules;
	private final Rule nodeRules;
	private final Rule relationRules;

	private LineAdder lineAdder = new LineAdder() {
		public void add(MapLine element) {
			if (element instanceof MapRoad)
				collector.addRoad((MapRoad) element);
			else
				collector.addLine(element);
		}
	};

	public StyledConverter(Style style, MapCollector collector) {
		this.collector = collector;

		nameTagList = style.getNameTagList();
		wayRules = style.getWayRules();
		nodeRules = style.getNodeRules();
		relationRules = style.getRelationRules();

		LineAdder overlayAdder = style.getOverlays(lineAdder);
		if (overlayAdder != null)
			lineAdder = overlayAdder;
	}

	/**
	 * This takes the way and works out what kind of map feature it is and makes
	 * the relevant call to the mapper callback.
	 * <p>
	 * As a few examples we might want to check for the 'highway' tag, work out
	 * if it is an area of a park etc.
	 *
	 * @param way The OSM way.
	 */
	public void convertWay(Way way) {
		if (way.getPoints().size() < 2)
			return;

		preConvertRules(way);

		GType foundType = wayRules.resolveType(way);
		if (foundType == null)
			return;

		postConvertRules(way, foundType);

		if (foundType.getFeatureKind() == GType.POLYLINE) {
		    if(foundType.isRoad())
			addRoad(way, foundType);
		    else
			addLine(way, foundType);
		}
		else
			addShape(way, foundType);
	}

	/**
	 * Takes a node (that has its own identity) and converts it from the OSM
	 * type to the Garmin map type.
	 *
	 * @param node The node to convert.
	 */
	public void convertNode(Node node) {
		preConvertRules(node);

		GType foundType = nodeRules.resolveType(node);
		if (foundType == null)
			return;

		// If the node does not have a name, then set the name from this
		// type rule.
		log.debug("node name", node.getName());
		if (node.getName() == null) {
			node.setName(foundType.getDefaultName());
			log.debug("after set", node.getName());
		}

		postConvertRules(node, foundType);

		addPoint(node, foundType);
	}

	/**
	 * Rules to run before converting the element.
	 */
	private void preConvertRules(Element el) {
		if (nameTagList == null)
			return;

		for (String t : nameTagList) {
			String val = el.getTag(t);
			if (val != null) {
				el.addTag("name", val);
				break;
			}
		}
	}

	/**
	 * Built in rules to run after converting the element.
	 */
	private void postConvertRules(Element el, GType type) {
		// Set the name from the 'name' tag or failing that from
		// the default_name.
		el.setName(el.getTag("name"));
		if (el.getName() == null)
			el.setName(type.getDefaultName());
	}

	/**
	 * Set the bounding box for this map.  This should be set before any other
	 * elements are converted if you want to use it. All elements that are added
	 * are clipped to this box, new points are added as needed at the boundry.
	 *
	 * If a node or a way falls completely outside the boundry then it would be
	 * ommited.  This would not normally happen in the way this option is typically
	 * used however.
	 *
	 * @param bbox The bounding area.
	 */
	public void setBoundingBox(Area bbox) {
		this.clipper = new AreaClipper(bbox);
	}

	/**
	 * Run the rules for this relation.  As this is not an end object, then
	 * the only useful rules are action rules that set tags on the contained
	 * ways or nodes.  Every rule should probably start with 'type=".."'.
	 *
	 * @param relation The relation to convert.
	 */
	public void convertRelation(Relation relation) {
		// Relations never resolve to a GType and so we ignore the return
		// value.
		relationRules.resolveType(relation);
	}

	private void addLine(Way way, GType gt) {
		MapLine line = new MapLine();
		elementSetup(line, gt, way);
		line.setPoints(way.getPoints());

		if (way.isBoolTag("oneway"))
			line.setDirection(true);

		clipper.clipLine(line, lineAdder);
	}

	private void addShape(Way way, GType gt) {
		MapShape shape = new MapShape();
		elementSetup(shape, gt, way);
		shape.setPoints(way.getPoints());

		clipper.clipShape(shape, collector);
	}

	private void addPoint(Node node, GType gt) {
		if (!clipper.contains(node.getLocation()))
			return;

		MapPoint mp = new MapPoint();
		elementSetup(mp, gt, node);
		mp.setLocation(node.getLocation());

		collector.addPoint(mp);
	}

	private void elementSetup(MapElement ms, GType gt, Element element) {
		ms.setName(element.getName());
		ms.setType(gt.getType());
		ms.setMinResolution(gt.getMinResolution());
		ms.setMaxResolution(gt.getMaxResolution());
	}

	void addRoad(Way way, GType gt) {
		boolean looped = false;
		List<Coord> wayPoints = way.getPoints();
		int numPointsInWay = wayPoints.size();

		if(numPointsInWay > 1) {
			// check if the last point in the way is the same as
			// any other point in the way - if it is, there's a loop
			Coord wayEndCoord = wayPoints.get(numPointsInWay - 1);
			for(int i = 0; !looped && i < numPointsInWay - 1; ++i) {
				if(wayPoints.get(i) == wayEndCoord)
					looped = true;
			}
		}
	
		if(looped) {
			if(numPointsInWay > 2) {
				// create a new way to replace the last segment
				Way loopTail = splitWayAt(way, numPointsInWay - 2);
				// make roads from the un-looped ways
				addRoadWithoutLoops(way, gt);
				addRoadWithoutLoops(loopTail, gt);
			}
			else {
				System.err.println("Ignoring looped way with only 2 points located at " + wayPoints.get(0).toDegreeString());
			}
		}
		else {
			// make a road from the way (it wasn't looped)
			addRoadWithoutLoops(way, gt);
		}
	}

	void addRoadWithoutLoops(Way way, GType gt) {
		List<Integer> nodeIndices = new ArrayList<Integer>();
		List<Coord> points = way.getPoints();
		Way trailingWay = null;

		// collect the Way's nodes
		for(int i = 0; i < points.size(); ++i) {
			Coord p = points.get(i);
			int highwayCount = p.getHighwayCount();
			if(highwayCount > 1) {
				// this point is a node connecting highways
				Integer nodeId = nodeIdMap.get(p);
				if(nodeId == null) {
					// assign a node id
					nodeId = nextNodeId++;
					nodeIdMap.put(p, nodeId);
				}
				nodeIndices.add(i);
		//		System.err.println("Found node " + nodeId + " at " + p.toDegreeString());

				if((i + 1) < points.size() &&
				   nodeIndices.size() == MAX_NODES_IN_WAY) {
					// this isn't the last point in the way
					// so split it here to avoid exceeding
					// the max nodes in way limit
					trailingWay = splitWayAt(way, i);
					// this will have truncated
					// the current Way's points so
					// the loop will now terminate
					//					System.err.println("Splitting way " + way.getName() + " at " + points.get(i).toDegreeString() + " as it has at least " + MAX_NODES_IN_WAY + " nodes");
				}
			}
		}

		MapLine line = new MapLine();
		elementSetup(line, gt, way);
		line.setPoints(points);

		if(way.isBoolTag("oneway"))
			line.setDirection(true);

		MapRoad road = new MapRoad(roadId++, line);

		// set road parameters.
		road.setRoadClass(gt.getRoadClass());
		road.setSpeed(gt.getRoadSpeed());
		road.setOneway(line.isDirection());

		boolean noAccess[] = new boolean[RoadNetwork.NO_MAX];
		String highwayType = way.getTag("highway");
		String access = way.getTag("access");
		if(access != null &&
		   (access.toUpperCase().equals("NO") ||
		    access.toUpperCase().equals("PRIVATE"))) {
			for(int i = 0; i < noAccess.length; ++i)
				noAccess[i] = true;
		}
		else if(highwayType.equals("footway")) {
			noAccess[RoadNetwork.EMERGENCY] = true;
			noAccess[RoadNetwork.DELIVERY] = true;
			noAccess[RoadNetwork.NO_CAR] = true;
			noAccess[RoadNetwork.NO_BUS] = true;
			noAccess[RoadNetwork.NO_TAXI] = true;
			if(!way.isBoolTag("bicycle"))
				noAccess[RoadNetwork.NO_BIKE] = true;
			noAccess[RoadNetwork.NO_TRUCK] = true;
		}
		else if(highwayType.equals("cycleway") ||
			highwayType.equals("bridleway")) {
			noAccess[RoadNetwork.EMERGENCY] = true;
			noAccess[RoadNetwork.DELIVERY] = true;
			noAccess[RoadNetwork.NO_CAR] = true;
			noAccess[RoadNetwork.NO_BUS] = true;
			noAccess[RoadNetwork.NO_TAXI] = true;
			noAccess[RoadNetwork.NO_TRUCK] = true;
		}

		road.setAccess(noAccess);

		if(way.isBoolTag("toll"))
			road.setToll(true);

		//road.setDirIndicator(dirIndicator); // FIXME

		int numNodes = nodeIndices.size();
		road.setNumNodes(numNodes);

		if(numNodes > 0) {
			// replace Coords that are nodes with CoordNodes
			boolean hasInternalNodes = false;
			for(int i = 0; i < numNodes; ++i) {
				int n = nodeIndices.get(i);
				if(n > 0 && n < points.size() - 1)
					hasInternalNodes = true;
				Coord coord = points.get(n);
				Integer nodeId = nodeIdMap.get(coord);
				boolean boundary = way.isBoolTag("mkg:boundary_node");
				points.set(n, new CoordNode(coord.getLatitude(), coord.getLongitude(), nodeId, boundary));
				//		System.err.println("Road " + road.getRoadId() + " node[" + i + "] " + nodeId + " at " + coord.toDegreeString());
			}

			road.setStartsWithNode(nodeIndices.get(0) == 0);
			road.setInternalNodes(hasInternalNodes);
		}

		clipper.clipLine(road, lineAdder);

		if(trailingWay != null)
		    addRoadWithoutLoops(trailingWay, gt);
	}

	// split a Way at the specified point and return the new Way
        // (the original Way is truncated)

	Way splitWayAt(Way way, int index) {
		Way trailingWay = new Way();
		List<Coord> wayPoints = way.getPoints();
		int numPointsInWay = wayPoints.size();

		for(int i = index; i < numPointsInWay; ++i)
			trailingWay.addPoint(wayPoints.get(i));

		// ensure split point becomes a node
		wayPoints.get(index).incHighwayCount();

		// copy the way's name and tags to the new way
		trailingWay.setName(way.getName());
		trailingWay.copyTags(way);

		// remove the points after the split from the original way
		// it's probably more efficient to remove from the end first
		for(int i = numPointsInWay - 1; i > index; --i)
			wayPoints.remove(i);

		return trailingWay;
	}
}
