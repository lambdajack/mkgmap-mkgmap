/*
 * Copyright (C) 2010 - 2012.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.Exit;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.ElementQuadTree;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Operations mostly on highways that have to be performed during reading
 * the OSM input file.
 *
 * Some of this would be much better done in a style file or by extending the style system.
 */
public class HighwayHooks implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(HighwayHooks.class);

	private final List<Element> motorways = new ArrayList<>(); // will all be Ways
	private final List<Element> exits = new ArrayList<>();

	private boolean makeOppositeCycleways;
	private ElementSaver saver;
	private boolean linkPOIsToWays;

	private Node currentNodeInWay;

	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props, Style style) {
		this.saver = saver;
		if(props.getProperty("make-all-cycleways", false)) {
			log.error("option make-all-cycleways is deprecated, please use make-opposite-cycleways");
			makeOppositeCycleways = true;
		}
		else {
			makeOppositeCycleways = props.getProperty("make-opposite-cycleways", false);
		}
		
		linkPOIsToWays = props.getProperty("link-pois-to-ways", false);
		currentNodeInWay = null;

		return true;
	}

	@Override
	public Set<String> getUsedTags() {
		Set<String> usedTags = new HashSet<>(Arrays.asList("highway", "access", "barrier", "oneway", "junction", "name",
				Exit.TAG_ROAD_REF, "ref", "motorroad"));
		if (makeOppositeCycleways) {
			// need the additional tags
			usedTags.add("cycleway");
			usedTags.add("bicycle");
			usedTags.add("oneway:bicycle");
			usedTags.add("bicycle:oneway");
			usedTags.add("cycleway:left");
			usedTags.add("cycleway:right");
		}
		return usedTags;
	}
	
	@Override
	public void onAddNode(Node node) {
		String highway = node.getTag("highway");
		if (highway != null && ("motorway_junction".equals(highway) || "services".equals(highway) || "rest_area".equals(highway))) {
			exits.add(node);
			node.addTag("mkgmap:osmid", String.valueOf(node.getId()));
		}
	}

	@Override
	public void onNodeAddedToWay(Way way, long id) {
		if (!linkPOIsToWays)
			return;
			
		currentNodeInWay = saver.getNode(id);
		if (currentNodeInWay == null)
			return;
		Coord co = currentNodeInWay.getLocation();
		
		// if this Coord is also a POI, replace it with an
		// equivalent CoordPOI that contains a reference to
		// the POI's Node so we can access the POI's tags
		if (!(co instanceof CoordPOI)) {
			// for now, only do this for nodes that have
			// certain tags otherwise we will end up creating
			// a CoordPOI for every node in the way
			final String[] coordPOITags = { "barrier", "highway" };
			for (String cpt : coordPOITags) {
				if (currentNodeInWay.getTag(cpt) != null) {
					// the POI has one of the approved tags so
					// replace the Coord with a CoordPOI
					CoordPOI cp = new CoordPOI(co);
					saver.addPoint(id, cp);

					// we also have to jump through hoops to
					// make a new version of Node because we
					// can't replace the Coord that defines
					// its location
					Node newNode = new Node(id, cp);
					newNode.copyTags(currentNodeInWay);
					saver.addNode(newNode);
					// tell the CoordPOI what node it's
					// associated with
					cp.setNode(newNode);
					co = cp;
					// if original node is in exits, replace it
					if (exits.remove(currentNodeInWay))
						exits.add(newNode);
					currentNodeInWay = newNode;
					break;
				}
			}
		}

		if (co instanceof CoordPOI) {
			// flag this Way as having a CoordPOI so it
			// will be processed later
			way.addTag("mkgmap:way-has-pois", "true");
			if (log.isInfoEnabled())
				log.info("Linking POI", currentNodeInWay.toBrowseURL(), "to way at", co.toOSMURL());
		}
	}

	@Override
	public void onAddWay(Way way) {
		String highway = way.getTag("highway");
		if (highway != null) {
			// if the way is a roundabout but isn't already
			// flagged as "oneway", flag it here
			if ("roundabout".equals(way.getTag("junction")) && way.getTag("oneway") == null) {
				way.addTag("oneway", "yes");
			}

			if (makeOppositeCycleways && !"cycleway".equals(highway)){
				String onewayTag = way.getTag("oneway");
				boolean oneway = way.tagIsLikeYes("oneway");
				if (!oneway && onewayTag != null && ("-1".equals(onewayTag) || "reverse".equals(onewayTag)))
					oneway = true;
				if (oneway){
					String cycleway = way.getTag("cycleway");
					// we have a oneway street, check if it allows bicycles to travel in opposite direction
					if ("no".equals(way.getTag("oneway:bicycle")) 
							|| "no".equals(way.getTag("bicycle:oneway"))
							|| "opposite".equals(cycleway) || "opposite_lane".equals(cycleway)
							|| "opposite_track".equals(cycleway) 
							|| "opposite_lane".equals(way.getTag("cycleway:left"))
							|| "opposite_lane".equals(way.getTag("cycleway:right"))
							|| "opposite_track".equals(way.getTag("cycleway:left"))
							|| "opposite_track".equals(way.getTag("cycleway:right"))) {
						way.addTag("mkgmap:make-cycle-way", "yes");
					}
				} 
			}
		}

		if ("motorway".equals(highway) || "trunk".equals(highway) || "primary".equals(highway) || way.tagIsLikeYes("motorroad"))
			motorways.add(way);
		else if (linkPOIsToWays && ("services".equals(highway) || "rest_area".equals(highway))) {
			exits.add(way);
			way.addTag("mkgmap:osmid", String.valueOf(way.getId()));
			}
	}

	@Override
	public void end() {
		finishExits();
		exits.clear();
		motorways.clear();
	}

	private static final int XTRA = 150; // very approx 300m
	private void finishExits() {
		if (exits.isEmpty() || motorways.isEmpty())
			return;
		ElementQuadTree majorRoads = new ElementQuadTree(saver.getBoundingBox(), motorways);
		for (Element e : exits) {
			String refTag = Exit.TAG_ROAD_REF;
			if (e.getTag(refTag) == null) {
				String exitName = e.getTag("name");
				if (exitName == null)
					exitName = e.getTag("ref");

				String ref = null;
				Way motorway = null;
				Area bBox;
				if (e instanceof Node)
					bBox = Area.getBBox(Collections.singletonList(((Node) e).getLocation()));
				else
					bBox = Area.getBBox(((Way) e).getPoints());
				String highway = e.getTag("highway");
				final boolean isServices = "services".equals(highway) || "rest_area".equals(highway);
				if (isServices) // services will be just off the road, so increase size
					bBox = new Area(bBox.getMinLat() - XTRA, bBox.getMinLong() - XTRA, bBox.getMaxLat() + XTRA, bBox.getMaxLong() + XTRA);
				List<Way> possibleRoads = new ArrayList<>();
				for (Element w : majorRoads.get(bBox)) {
					motorway = (Way) w;
					ref = motorway.getTag("ref");
					if (ref != null) {
						if (isServices) {
							possibleRoads.add(motorway);  // save all possibilities
						} else { // probably on 2+ roads, save possibilities to find the more major road (doesn't have to be motorway)
							// uses an implicit call of Coord.equals()
							if (motorway.getPoints().contains(((Node) e).getLocation()))
								possibleRoads.add(motorway);
						}
					}
				}
				
				if (possibleRoads.size() > 1) {
					if (isServices) { // pick the closest road
						Coord serviceCoord;
						if (e instanceof Node)
							serviceCoord = ((Node) e).getLocation();
						else
							// Simple-minded logic to see if a Way that probably defines [part of] a
							// services area, hence might become a POI if option --link-pois-to-ways,
							// is near the specified road.
							// Just pick an arbitary Coord on the services boundary and find the nearest
							// any Coord in the roads.
							// No need to check if it is near the line between far-apart Coords because
							// there also needs to be a junction so that can get off the road to the
							// services.
							serviceCoord = ((Way)e).getFirstPoint();
						long closestRoad = Long.MAX_VALUE;
						for (Way road : possibleRoads) {
							long closestCoord = Long.MAX_VALUE;
							for (Coord pointOnRoad : road.getPoints()) {
								long dist = pointOnRoad.distanceInHighPrecSquared(serviceCoord);
								if (dist < closestCoord)
									closestCoord = dist;
							}
							if (closestCoord < closestRoad) {
								closestRoad = closestCoord;
								motorway = road;
								ref = motorway.getTag("ref");
							}
						}
					} else { // pick the most major road
						int bestRoad = Integer.MAX_VALUE;
						for (Way road : possibleRoads) {
							String roadType = road.getTag("highway");
							int thisRoad = 4;
							if ("motorway".equals(roadType))
								thisRoad = 0;
							else if (road.tagIsLikeYes("motorroad"))
								thisRoad = 1;
							else if ("trunk".equals(roadType))
								thisRoad = 2;
							else if ("primary".equals(roadType))
								thisRoad = 3;
							if (thisRoad < bestRoad) {
								bestRoad = thisRoad;
								motorway = road;
								ref = motorway.getTag("ref");
							}
						}
					}
					//log.info("Exit", exit, possibleRoads.size(), "options, chosen:", motorway, ref);
				} // else 0 or 1 road; ref/motorway null or set correctly
				if (ref != null) {
					log.info("Adding", refTag + "=" + ref, "to exit", exitName);
					e.addTag(refTag, ref);
				} else if(motorway != null) {
					log.warn("Motorway exit", exitName, "is positioned on a motorway that doesn't have a 'ref' tag", e);
				}
			}
		}
	}
}
