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
package uk.me.parabola.mkgmap.general;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.net.NOD1Part;
import uk.me.parabola.imgfmt.app.net.RoadDef;
import uk.me.parabola.imgfmt.app.net.RouteArc;
import uk.me.parabola.imgfmt.app.net.RouteCenter;
import uk.me.parabola.imgfmt.app.net.RouteNode;
import uk.me.parabola.imgfmt.app.net.RouteRestriction;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * This holds the road network.  That is all the roads and the nodes
 * that connect them together.
 * 
 * @see <a href="http://www.movable-type.co.uk/scripts/latlong.html">Distance / bearing calculations</a>
 * @author Steve Ratcliffe
 */
public class RoadNetwork {
	private static final Logger log = Logger.getLogger(RoadNetwork.class);

	public static final int NO_EMERGENCY = 0;
	public static final int NO_DELIVERY = 1;
	public static final int NO_CAR = 2;
	public static final int NO_BUS = 3;
	public static final int NO_TAXI = 4;
	public static final int NO_FOOT = 5;
	public static final int NO_BIKE = 6;
	public static final int NO_TRUCK = 7;
	public static final int NO_MAX = 8;

	private final Map<Long, RouteNode> nodes = new LinkedHashMap<Long, RouteNode>();

	// boundary nodes
	// a node should be in here iff the nodes boundary flag is set
	private final List<RouteNode> boundary = new ArrayList<RouteNode>();
	//private final List<MapRoad> mapRoads = new ArrayList<MapRoad>();

	private final List<RoadDef> roadDefs = new ArrayList<RoadDef>();
	private List<RouteCenter> centers = new ArrayList<RouteCenter>();
	private int adjustTurnHeadings ;
	private boolean checkRoundabouts;
	private boolean checkRoundaboutFlares;
	private int maxFlareLengthRatio ;
	private boolean reportSimilarArcs;
	private boolean outputCurveData;

	public void config(EnhancedProperties props) {
		String ath = props.getProperty("adjust-turn-headings");
		if(ath != null) {
			if(ath.length() > 0)
				adjustTurnHeadings = Integer.decode(ath);
			else
				adjustTurnHeadings = RouteNode.ATH_DEFAULT_MASK;
		}
		checkRoundabouts = props.getProperty("check-roundabouts", false);
		checkRoundaboutFlares = props.getProperty("check-roundabout-flares", false);
		maxFlareLengthRatio = props.getProperty("max-flare-length-ratio", 0);

		reportSimilarArcs = props.getProperty("report-similar-arcs", false);

		outputCurveData = !props.getProperty("no-arc-curves", false);
	}

	public void addRoad(MapRoad road) {
		//mapRoads.add(road);
		roadDefs.add(road.getRoadDef()); //XXX

		CoordNode lastCoord = null;
		int lastIndex = 0;
		double roadLength = 0;
		double arcLength = 0;
		int pointsHash = 0;

		List<Coord> coordList = road.getPoints();
		int npoints = coordList.size();
		for (int index = 0; index < npoints; index++) {
			Coord co = coordList.get(index);

			if (index > 0) {
				double d = co.distance(coordList.get(index-1));
				arcLength += d;
				roadLength += d;
			}

			long id = co.getId();

			pointsHash += co.hashCode();

			if (id == 0)
				// not a routing node
				continue;

			// The next coord determines the heading
			// If this is the not the first node, then create an arc from
			// the previous node to this one (and back again).
			if (lastCoord != null) {
				long lastId = lastCoord.getId();
				if(log.isDebugEnabled()) {
					log.debug("lastId = " + lastId + " curId = " + id);
					log.debug("from " + lastCoord.toDegreeString() 
							  + " to " + co.toDegreeString());
					log.debug("arclength=" + arcLength + " roadlength=" + roadLength);
				}

				RouteNode node1 = getNode(lastId, lastCoord);
				RouteNode node2 = getNode(id, co);

				if(node1 == node2)
					log.error("Road " + road.getRoadDef() + " contains consecutive identical nodes at " + co.toOSMURL() + " - routing will be broken");
				else if(arcLength == 0)
					log.warn("Road " + road.getRoadDef() + " contains zero length arc at " + co.toOSMURL());


				Coord forwardBearingPoint = coordList.get(lastIndex + 1);
				if(lastCoord.equals(forwardBearingPoint)) {
					// bearing point is too close to last node to be
					// useful - try some more points
					for(int bi = lastIndex + 2; bi <= index; ++bi) {
						if(!lastCoord.equals(coordList.get(bi))) {
							forwardBearingPoint = coordList.get(bi);
							break;
						}
					}
				}
				Coord reverseBearingPoint = coordList.get(index - 1);
				if(co.equals(reverseBearingPoint)) {
					// bearing point is too close to this node to be
					// useful - try some more points
					for(int bi = index - 2; bi > lastIndex; --bi) {
						if(!co.equals(coordList.get(bi))) {
							reverseBearingPoint = coordList.get(bi);
							break;
						}
					}
				}
				
				double forwardInitialBearing = lastCoord.bearingTo(forwardBearingPoint);
				double forwardDirectBearing = (co == forwardBearingPoint) ? forwardInitialBearing: lastCoord.bearingTo(co); 

				double reverseInitialBearing = co.bearingTo(reverseBearingPoint);
				double reverseDirectBearing = (lastCoord == reverseBearingPoint) ? reverseInitialBearing: co.bearingTo(lastCoord); 

				// TODO: maybe detect cases where bearing was already calculated above 
				double forwardFinalBearing = reverseBearingPoint.bearingTo(co); 
				double reverseFinalBearing = forwardBearingPoint.bearingTo(lastCoord);

				double directLength = (lastIndex + 1 == index) ? arcLength : lastCoord.distance(co);
				// Create forward arc from node1 to node2
				RouteArc arc = new RouteArc(road.getRoadDef(),
											node1,
											node2,
											forwardInitialBearing,
											forwardFinalBearing,
											forwardDirectBearing,
											arcLength,
											directLength,
											outputCurveData,
											pointsHash);
				arc.setForward();
				node1.addArc(arc);
				node2.addIncomingArc(arc);

				// Create the reverse arc
				arc = new RouteArc(road.getRoadDef(),
								   node2, node1,
								   reverseInitialBearing,
								   reverseFinalBearing,
								   reverseDirectBearing,
								   arcLength,
								   directLength,
								   outputCurveData,
								   pointsHash);
				node2.addArc(arc);
				node1.addIncomingArc(arc);
			} else {
				// This is the first node in the road
				road.getRoadDef().setNode(getNode(id, co));
			}

			lastCoord = (CoordNode) co;
			lastIndex = index;
			arcLength = 0;
			pointsHash = co.hashCode();
		}
		road.getRoadDef().setLength(roadLength);
	}

	private RouteNode getNode(long id, Coord coord) {
		RouteNode node = nodes.get(id);
		if (node == null) {
			node = new RouteNode(coord);
			nodes.put(id, node);
			if (node.isBoundary())
				boundary.add(node);
		}
		return node;
	}

	public List<RoadDef> getRoadDefs() {
		return roadDefs;
	}

	/**
	 * Split the network into RouteCenters.
	 *
	 * The resulting centers must satisfy several constraints,
	 * documented in NOD1Part.
	 */
	private void splitCenters() {
		if (nodes.isEmpty())
			return;
		assert centers.isEmpty() : "already subdivided into centers";

		NOD1Part nod1 = new NOD1Part();

		for (RouteNode node : nodes.values()) {
			if(!node.isBoundary()) {
				if(checkRoundabouts)
					node.checkRoundabouts();
				if(checkRoundaboutFlares)
					node.checkRoundaboutFlares(maxFlareLengthRatio);
				if(reportSimilarArcs)
					node.reportSimilarArcs();
			}
			if(adjustTurnHeadings != 0)
				node.tweezeArcs(adjustTurnHeadings);
			nod1.addNode(node);
		}
		centers = nod1.subdivide();
	}

	public List<RouteCenter> getCenters() {
		if (centers.isEmpty())
			splitCenters();
		return centers;
	}

	/**
	 * Get the list of nodes on the boundary of the network.
	 *
	 * Currently empty.
	 */
	public List<RouteNode> getBoundary() {
		return boundary;
	}

	public void addRestriction(CoordNode fromNode, CoordNode toNode, CoordNode viaNode, byte exceptMask) {
		RouteNode fn = nodes.get(fromNode.getId());
		RouteNode tn = nodes.get(toNode.getId());
		RouteNode vn = nodes.get(viaNode.getId());
		if (fn == null  || tn == null || vn == null){
			if (fn == null)
				log.error("can't locate 'from' RouteNode with id", fromNode.getId());
			if (tn == null)
				log.error("can't locate 'to' RouteNode with id", toNode.getId());
			if (vn == null)
				log.error("can't locate 'via' RouteNode with id", viaNode.getId());
			return;
		}
		RouteArc fa = vn.getArcTo(fn); // inverse arc gets used
		RouteArc ta = vn.getArcTo(tn);
		if (fa == null || ta == null){
			if (fa == null)
				log.error("can't locate arc from 'via' node ",viaNode.getId(),"to 'from' node",fromNode.getId());
			if (ta == null)
				log.error("can't locate arc from 'via' node ",viaNode.getId(),"to 'to' node",toNode.getId());
			return;
		}
		if(!ta.isForward() && ta.getRoadDef().isOneway()) {
			// the route restriction connects to the "wrong" end of a oneway
			if ((exceptMask & RouteRestriction.EXCEPT_FOOT) != 0){
				// pedestrians are allowed
				log.info("restriction via "+viaNode.getId() + " to " + fromNode.getId() + " ignored because to-arc is wrong direction in oneway ");
				return;
			} else {
				log.info("restriction via "+viaNode.getId() + " to " + fromNode.getId() + " added although to-arc is wrong direction in oneway, but restriction also excludes pedestrians.");
			}
		}
		vn.addRestriction(new RouteRestriction(fa, ta, exceptMask));
	}

	public void addThroughRoute(long junctionNodeId, long roadIdA, long roadIdB) {
		RouteNode node = nodes.get(junctionNodeId);
		assert node != null :  "Can't find node with id " + junctionNodeId;

		node.addThroughRoute(roadIdA, roadIdB);
	}
}
