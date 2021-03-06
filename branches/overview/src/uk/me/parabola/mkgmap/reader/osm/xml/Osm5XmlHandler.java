/*
 * Copyright (C) 2006 Steve Ratcliffe
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
 * Create date: 16-Dec-2006
 */
package uk.me.parabola.mkgmap.reader.osm.xml;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.Exit;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineClipper;
import uk.me.parabola.mkgmap.general.MapCollector;
import uk.me.parabola.mkgmap.general.MapDetails;
import uk.me.parabola.mkgmap.reader.osm.CoordPOI;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GeneralRelation;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmConverter;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.RestrictionRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.EnhancedProperties;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads and parses the OSM XML format.
 *
 * @author Steve Ratcliffe
 */
public class Osm5XmlHandler extends DefaultHandler {
	private static final Logger log = Logger.getLogger(Osm5XmlHandler.class);

	private int mode;

	private Map<Long, Coord> coordMap = new HashMap<Long, Coord>(50000);

	private Map<Coord, Long> nodeIdMap = new IdentityHashMap<Coord, Long>();
	private Map<Long, Node> nodeMap;
	private Map<Long, Way> wayMap;
	private Map<Long, Relation> relationMap;
	private final Map<Long, List<Map.Entry<String,Relation>>> deferredRelationMap =
		new HashMap<Long, List<Map.Entry<String,Relation>>>();
	private final Map<String, Long> fakeIdMap = new HashMap<String, Long>();
	private final List<Node> exits = new ArrayList<Node>();
	private final List<Way> motorways = new ArrayList<Way>();
	private final List<Way> shoreline = new ArrayList<Way>();

	private static final int MODE_NODE = 1;
	private static final int MODE_WAY = 2;
	private static final int MODE_BOUND = 3;
	private static final int MODE_RELATION = 4;
	private static final int MODE_BOUNDS = 5;

	private static final long CYCLEWAY_ID_OFFSET = 0x10000000;

	private Node currentNode;
	private Way currentWay;
	private Node currentNodeInWay;
	private boolean currentWayStartsWithFIXME;
	private Relation currentRelation;
	private long currentElementId;

	private OsmConverter converter;
	private MapCollector collector;
	private Area bbox;
	private Runnable endTask;

	private final boolean reportUndefinedNodes;
	private final boolean makeOppositeCycleways;
	private final boolean makeCycleways;
	private final boolean ignoreBounds;
	private final boolean processBoundaryRelations;
	private final boolean ignoreTurnRestrictions;
	private final boolean linkPOIsToWays;
	private final boolean generateSea;
	private boolean generateSeaUsingMP = true;
	private boolean allowSeaSectors = true;
	private boolean extendSeaSectors;
	private boolean roadsReachBoundary;
	private int maxCoastlineGap;
	private String[] landTag = { "natural", "land" };
	private final Double minimumArcLength;
	private final String frigRoundabouts;

	private HashMap<String,Set<String>> deletedTags;
	private Map<String, String> usedTags;

	public Osm5XmlHandler(EnhancedProperties props) {
		if(props.getProperty("make-all-cycleways", false)) {
			makeOppositeCycleways = makeCycleways = true;
		}
		else {
			makeOppositeCycleways = props.getProperty("make-opposite-cycleways", false);
			makeCycleways = props.getProperty("make-cycleways", false);
		}
		linkPOIsToWays = props.getProperty("link-pois-to-ways", false);
		ignoreBounds = props.getProperty("ignore-osm-bounds", false);
		String gs = props.getProperty("generate-sea", null);
		generateSea = gs != null;
		if(generateSea) {
			for(String o : gs.split(",")) {
				if("no-mp".equals(o) ||
				   "polygon".equals(o) ||
				   "polygons".equals(o))
					generateSeaUsingMP = false;
				else if("multipolygon".equals(o))
					generateSeaUsingMP = true;
				else if(o.startsWith("close-gaps="))
					maxCoastlineGap = (int)Double.parseDouble(o.substring(11));
				else if("no-sea-sectors".equals(o))
					allowSeaSectors = false;
				else if("extend-sea-sectors".equals(o)) {
					allowSeaSectors = false;
					extendSeaSectors = true;
				}
				else if(o.startsWith("land-tag="))
					landTag = o.substring(9).split("=");
				else {
					if(!"help".equals(o))
						System.err.println("Unknown sea generation option '" + o + "'");
					System.err.println("Known sea generation options are:");
					System.err.println("  multipolygon        use a multipolygon (default)");
					System.err.println("  polygons | no-mp    use polygons rather than a multipolygon");
					System.err.println("  no-sea-sectors      disable use of \"sea sectors\"");
					System.err.println("  extend-sea-sectors  extend coastline to reach border");
					System.err.println("  land-tag=TAG=VAL    tag to use for land polygons (default natural=land)");
					System.err.println("  close-gaps=NUM      close gaps in coastline that are less than this distance (metres)");
				}
			}
		}

		String rsa = props.getProperty("remove-short-arcs", null);
		if(rsa != null)
			minimumArcLength = (rsa.length() > 0)? Double.parseDouble(rsa) : 0.0;
		else
			minimumArcLength = null;
		frigRoundabouts = props.getProperty("frig-roundabouts");
		ignoreTurnRestrictions = props.getProperty("ignore-turn-restrictions", false);
		processBoundaryRelations = props.getProperty("process-boundary-relations", false);
		reportUndefinedNodes = props.getProperty("report-undefined-nodes", false);
		String deleteTagsFileName = props.getProperty("delete-tags-file");
		if(deleteTagsFileName != null)
			readDeleteTagsFile(deleteTagsFileName);

		if (props.getProperty("preserve-element-order", false)) {
			nodeMap = new LinkedHashMap<Long, Node>(5000);
			wayMap = new LinkedHashMap<Long, Way>(5000);
			relationMap = new LinkedHashMap<Long, Relation>();
		} else {
			nodeMap = new HashMap<Long, Node>(5000);
			wayMap = new HashMap<Long, Way>(5000);
			relationMap = new HashMap<Long, Relation>();
		}
	}

	private void readDeleteTagsFile(String fileName) {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(new DataInputStream(new FileInputStream(fileName))));
			deletedTags = new HashMap<String,Set<String>>();
			String line;
			while((line = br.readLine()) != null) {
				line = line.trim();
				if(line.length() > 0 && 
				   !line.startsWith("#") &&
				   !line.startsWith(";")) {
					String[] parts = line.split("=");
					if (parts.length == 2) {
						parts[0] = parts[0].trim();
						parts[1] = parts[1].trim();
						if ("*".equals(parts[1])) {
							deletedTags.put(parts[0], new HashSet<String>());
						} else {
							Set<String> vals = deletedTags.get(parts[0]);
							if (vals == null)
								vals = new HashSet<String>();
							vals.add(parts[1]);
							deletedTags.put(parts[0], vals);
						}
					} else {
						log.error("Ignoring bad line in deleted tags file: " + line);
					}
				}
			}
			br.close();
		}
		catch(FileNotFoundException e) {
			log.error("Could not open delete tags file " + fileName);
		}
		catch(IOException e) {
			log.error("Error reading delete tags file " + fileName);
		}

		if(deletedTags != null && deletedTags.isEmpty())
			deletedTags = null;
	}

	private String keepTag(String key, String val) {
		if(deletedTags != null) {
			Set<String> vals = deletedTags.get(key);
			if(vals != null && (vals.isEmpty() || vals.contains(val))) {
				//				System.err.println("Deleting " + key + "=" + val);
				return key;
			}
		}

		if (usedTags != null)
			return usedTags.get(key);
		
		return key;
	}

	public void setUsedTags(Set<String> used) {
		if (used == null || used.isEmpty()) {
			usedTags = null;
			return;
		}
		usedTags = new HashMap<String, String>();
		for (String s : used)
			usedTags.put(s, s);
	}

	/**
	 * Receive notification of the start of an element.
	 *
	 * @param uri		The Namespace URI, or the empty string if the
	 *                   element has no Namespace URI or if Namespace
	 *                   processing is not being performed.
	 * @param localName  The local name (without prefix), or the
	 *                   empty string if Namespace processing is not being
	 *                   performed.
	 * @param qName	  The qualified name (with prefix), or the
	 *                   empty string if qualified names are not available.
	 * @param attributes The attributes attached to the element.  If
	 *                   there are no attributes, it shall be an empty
	 *                   Attributes object.
	 * @throws SAXException Any SAX exception, possibly
	 *                                  wrapping another exception.
	 * @see ContentHandler#startElement
	 */
	public void startElement(String uri, String localName,
	                         String qName, Attributes attributes)
			throws SAXException
	{

		if (mode == 0) {
			if (qName.equals("node")) {
				mode = MODE_NODE;

				addNode(attributes.getValue("id"),
						attributes.getValue("lat"),
						attributes.getValue("lon"));

			} else if (qName.equals("way")) {
				mode = MODE_WAY;
				addWay(attributes.getValue("id"));
			} else if (qName.equals("relation")) {
				mode = MODE_RELATION;
				currentRelation = new GeneralRelation(idVal(attributes.getValue("id")));
			} else if (qName.equals("bound")) {
				mode = MODE_BOUND;
				if(!ignoreBounds) {
					String box = attributes.getValue("box");
					setupBBoxFromBound(box);
				}
			} else if (qName.equals("bounds")) {
				mode = MODE_BOUNDS;
				if(!ignoreBounds)
					setupBBoxFromBounds(attributes);
			}

		} else if (mode == MODE_NODE) {
			startInNode(qName, attributes);
		} else if (mode == MODE_WAY) {
			startInWay(qName, attributes);
		} else if (mode == MODE_RELATION) {
			startInRelation(qName, attributes);
		}
	}

	private void startInRelation(String qName, Attributes attributes) {
		if (qName.equals("member")) {
			long id = idVal(attributes.getValue("ref"));
			Element el;
			String type = attributes.getValue("type");
			if ("way".equals(type)){
				el = wayMap.get(id);
			} else if ("node".equals(type)) {
				el = nodeMap.get(id);
				if(el == null) {
					// we didn't make a node for this point earlier,
					// do it now (if it exists)
					Coord co = coordMap.get(id);
					if(co != null) {
						el = new Node(id, co);
						nodeMap.put(id, (Node)el);
					}
				}
			} else if ("relation".equals(type)) {
				el = relationMap.get(id);
				if (el == null) {
					// The relation may be defined later in the input.
					// Defer the lookup.
					Map.Entry<String,Relation> entry =
						new AbstractMap.SimpleEntry<String,Relation>
						(attributes.getValue("role"), currentRelation);

					List<Map.Entry<String,Relation>> entries =
						deferredRelationMap.get(id);
					if (entries == null) {
						entries = new ArrayList<Map.Entry<String,Relation>>();
						deferredRelationMap.put(id, entries);
					}

					entries.add(entry);
				}
			} else
				el = null;
			if (el != null) // ignore non existing ways caused by splitting files
				currentRelation.addElement(attributes.getValue("role"), el);
		} else if (qName.equals("tag")) {
			String key = attributes.getValue("k");
			String val = attributes.getValue("v");
			key = keepTag(key, val);
			if (key != null)
				currentRelation.addTag(key, val);
		}
	}

	private void startInWay(String qName, Attributes attributes) {
		if (qName.equals("nd")) {
			long id = idVal(attributes.getValue("ref"));
			addNodeToWay(id);
		} else if (qName.equals("tag")) {
			String key = attributes.getValue("k");
			String val = attributes.getValue("v");
			key = keepTag(key, val);
			if (key != null)
				currentWay.addTag(key, val);
		}
	}

	private void startInNode(String qName, Attributes attributes) {
		if (qName.equals("tag")) {
			String key = attributes.getValue("k");
			String val = attributes.getValue("v");

			if("mkgmap:on-boundary".equals(key)) {
				if("1".equals(val) || "true".equals(val) || "yes".equals(val)) {
					Coord co = coordMap.get(currentElementId);
					co.setOnBoundary(true);
					co.incHighwayCount();
				}
				return;
			}

			// We only want to create a full node for nodes that are POI's
			// and not just point of a way.  Only create if it has tags that
			// could be used in a POI.
			key = keepTag(key, val);
			if (key != null) {
				if (currentNode == null) {
					Coord co = coordMap.get(currentElementId);
					currentNode = new Node(currentElementId, co);
					nodeMap.put(currentElementId, currentNode);
				}

				if ((val.equals("motorway_junction") || val.equals("services"))
						&& key.equals("highway")) {
					exits.add(currentNode);
					currentNode.addTag("osm:id", "" + currentElementId);
				}

				currentNode.addTag(key, val);
			}
		}
	}

	/**
	 * Receive notification of the end of an element.
	 *
	 * @param uri	   The Namespace URI, or the empty string if the
	 *                  element has no Namespace URI or if Namespace
	 *                  processing is not being performed.
	 * @param localName The local name (without prefix), or the
	 *                  empty string if Namespace processing is not being
	 *                  performed.
	 * @param qName	 The qualified name (with prefix), or the
	 *                  empty string if qualified names are not available.
	 * @throws SAXException Any SAX exception, possibly
	 *                                  wrapping another exception.
	 * @see ContentHandler#endElement
	 */
	public void endElement(String uri, String localName, String qName)
					throws SAXException
	{
		if (mode == MODE_NODE) {
			if (qName.equals("node"))
				endNode();

		} else if (mode == MODE_WAY) {
			if (qName.equals("way")) {
				mode = 0;
				String highway = currentWay.getTag("highway");
				if(highway != null ||
				   "ferry".equals(currentWay.getTag("route"))) {
					boolean oneway = currentWay.isBoolTag("oneway");
					// if the first or last Node of the Way has a
					// FIXME attribute, disable dead-end-check for
					// oneways
					if (oneway &&
						currentWayStartsWithFIXME ||
						(currentNodeInWay != null &&
						 (currentNodeInWay.getTag("FIXME") != null ||
						  currentNodeInWay.getTag("fixme") != null))) {
						currentWay.addTag("mkgmap:dead-end-check", "false");
					}

					// if the way is a roundabout but isn't already
					// flagged as "oneway", flag it here
					if("roundabout".equals(currentWay.getTag("junction"))) {
						if(currentWay.getTag("oneway") == null) {
							currentWay.addTag("oneway", "yes");
						}
						if(currentWay.getTag("mkgmap:frig_roundabout") == null) {
							if(frigRoundabouts != null)
								currentWay.addTag("mkgmap:frig_roundabout", frigRoundabouts);
						}
					}
					String cycleway = currentWay.getTag("cycleway");
					if(makeOppositeCycleways &&
					   cycleway != null &&
					   !"cycleway".equals(highway) &&
					   oneway &&
					   ("opposite".equals(cycleway) ||
						"opposite_lane".equals(cycleway) ||
						"opposite_track".equals(cycleway))) {
						// what we have here is a oneway street
						// that allows bicycle traffic in both
						// directions -- to enable bicycle routing
						// in the reverse direction, we synthesise
						// a cycleway that has the same points as
						// the original way
						long cycleWayId = currentWay.getId() + CYCLEWAY_ID_OFFSET;
						Way cycleWay = new Way(cycleWayId);
						wayMap.put(cycleWayId, cycleWay);
						// this reverses the direction of the way but
						// that isn't really necessary as the cycleway
						// isn't tagged as oneway
						List<Coord> points = currentWay.getPoints();
						for(int i = points.size() - 1; i >= 0; --i)
							cycleWay.addPoint(points.get(i));
						cycleWay.copyTags(currentWay);
						//cycleWay.addTag("highway", "cycleway");
						String name = currentWay.getTag("name");
						if(name != null)
							name += " (cycleway)";
						else
							name = "cycleway";
						cycleWay.addTag("name", name);
						cycleWay.addTag("oneway", "no");
						cycleWay.addTag("access", "no");
						cycleWay.addTag("bicycle", "yes");
						cycleWay.addTag("foot", "no");
						cycleWay.addTag("mkgmap:synthesised", "yes");
						log.info("Making " + cycleway + " cycleway '" + cycleWay.getTag("name") + "'");
					}
					else if(makeCycleways &&
							cycleway != null &&
							!"cycleway".equals(highway) &&
							("track".equals(cycleway) ||
							 "lane".equals(cycleway) ||
							 "both".equals(cycleway) ||
							 "left".equals(cycleway) ||
							 "right".equals(cycleway))) {
						// what we have here is a highway with a
						// separate track for cycles -- to enable
						// bicycle routing, we synthesise a cycleway
						// that has the same points as the original
						// way
						long cycleWayId = currentWay.getId() + CYCLEWAY_ID_OFFSET;
						Way cycleWay = new Way(cycleWayId);
						wayMap.put(cycleWayId, cycleWay);
						List<Coord> points = currentWay.getPoints();
						for (Coord point : points)
							cycleWay.addPoint(point);
						cycleWay.copyTags(currentWay);
						if(currentWay.getTag("bicycle") == null)
							currentWay.addTag("bicycle", "no");
						//cycleWay.addTag("highway", "cycleway");
						String name = currentWay.getTag("name");
						if(name != null)
							name += " (cycleway)";
						else
							name = "cycleway";
						cycleWay.addTag("name", name);
						cycleWay.addTag("access", "no");
						cycleWay.addTag("bicycle", "yes");
						cycleWay.addTag("foot", "no");
						cycleWay.addTag("mkgmap:synthesised", "yes");
						log.info("Making " + cycleway + " cycleway '" + cycleWay.getTag("name") + "'");
					}
				}
				if("motorway".equals(highway) ||
				   "trunk".equals(highway))
					motorways.add(currentWay);
				if(generateSea) {
					String natural = currentWay.getTag("natural");
					if(natural != null) {
						if("coastline".equals(natural)) {
							currentWay.deleteTag("natural");
							shoreline.add(currentWay);
						}
						else if(natural.contains(";")) {
							// cope with compound tag value
							String others = null;
							boolean foundCoastline = false;
							for(String n : natural.split(";")) {
								if("coastline".equals(n.trim()))
									foundCoastline = true;
								else if(others == null)
									others = n;
								else
									others += ";" + n;
							}
							if(foundCoastline) {
								currentWay.deleteTag("natural");
								if(others != null)
									currentWay.addTag("natural", others);
								shoreline.add(currentWay);
							}
						}
					}
				}
				currentNodeInWay = null;
				currentWayStartsWithFIXME = false;
				currentWay = null;
				// ways are processed at the end of the document,
				// may be changed by a Relation class
			}
		} else if (mode == MODE_BOUND) {
			if (qName.equals("bound"))
				mode = 0;
		} else if (mode == MODE_BOUNDS) {
			if (qName.equals("bounds"))
				mode = 0;
		} else if (mode == MODE_RELATION) {
			if (qName.equals("relation")) {
				mode = 0;
				endRelation();
			}
		}
	}

	private void endNode() {
		mode = 0;

		currentElementId = 0;
		currentNode = null;
	}

	private void endRelation() {
		String type = currentRelation.getTag("type");
		if (type != null) {
			if ("multipolygon".equals(type)) {
				Area mpBbox = (bbox != null ? bbox : ((MapDetails) collector).getBounds());
				currentRelation = new MultiPolygonRelation(currentRelation, wayMap, mpBbox);
			} else if("restriction".equals(type)) {

				if(ignoreTurnRestrictions)
					currentRelation = null;
				else
					currentRelation = new RestrictionRelation(currentRelation);
			}
		}
		if(currentRelation != null) {
			long id = currentRelation.getId();

			relationMap.put(id, currentRelation);
			if (!processBoundaryRelations &&
			     currentRelation instanceof MultiPolygonRelation &&
				 ((MultiPolygonRelation)currentRelation).isBoundaryRelation()) {
				log.info("Ignore boundary multipolygon "+currentRelation.toBrowseURL());
			} else {
				currentRelation.processElements();
			}

			List<Map.Entry<String,Relation>> entries =
				deferredRelationMap.remove(id);
			if (entries != null)
				for (Map.Entry<String,Relation> entry : entries)
					entry.getValue().addElement(entry.getKey(), currentRelation);

			currentRelation = null;
		}
	}

	/**
	 * Receive notification of the end of the document.
	 *
	 * We add the background polygon here.  As this is going to be big it
	 * may be split up further down the chain.
	 *
	 * @throws SAXException Any SAX exception, possibly wrapping
	 * another exception.
	 */
	public void endDocument() throws SAXException {
		
		for (Node e : exits) {
			String refTag = Exit.TAG_ROAD_REF;
			if(e.getTag(refTag) == null) {
				String exitName = e.getTag("name");
				if(exitName == null)
					exitName = e.getTag("ref");

				String ref = null;
				Way motorway = null;
				for (Way w : motorways) {
					if (w.getPoints().contains(e.getLocation())) {
						motorway = w;
						ref = w.getTag("ref");
						if(ref != null)
						    break;
					}
				}
				if(ref != null) {
					log.info("Adding " + refTag + "=" + ref + " to exit " + exitName);
					e.addTag(refTag, ref);
				}
				else if(motorway != null) {
					log.warn("Motorway exit " + exitName + " is positioned on a motorway that doesn't have a 'ref' tag (" + e.getLocation().toOSMURL() + ")");
				}
			}
		}

		coordMap = null;

		if(bbox != null && (generateSea || minimumArcLength != null))
			makeBoundaryNodes();

		if (generateSea)
		    generateSeaPolygon(shoreline);

		for (Relation r : relationMap.values())
			converter.convertRelation(r);

		for (Node n : nodeMap.values())
			converter.convertNode(n);

		nodeMap = null;

		if(minimumArcLength != null)
			removeShortArcsByMergingNodes(minimumArcLength);

		nodeIdMap = null;

		for (Way w: wayMap.values())
			converter.convertWay(w);

		wayMap = null;

		converter.end();

		relationMap = null;

		if(bbox != null) {
			collector.addToBounds(new Coord(bbox.getMinLat(),
						     bbox.getMinLong()));
			collector.addToBounds(new Coord(bbox.getMinLat(),
						     bbox.getMaxLong()));
			collector.addToBounds(new Coord(bbox.getMaxLat(),
						     bbox.getMinLong()));
			collector.addToBounds(new Coord(bbox.getMaxLat(),
						     bbox.getMaxLong()));
		}

		// Run a finishing task.
		endTask.run();
	}

	// "soft clip" each way that crosses a boundary by adding a point
	//  at each place where it meets the boundary
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
				Coord[] clippedPair = LineClipper.clip(bbox, pair);
				// we're only interested in segments that touch the
				// boundary
				if(clippedPair != null) {
					// the segment touches the boundary or is
					// completely inside the bounding box
					if(clippedPair[1] != points.get(i)) {
						// the second point in the segment is outside
						// of the boundary
						assert clippedPair[1].getOnBoundary();
						// insert boundary point before the second point
						points.add(i, clippedPair[1]);
						// it's a newly created point so make its
						// highway count one
						clippedPair[1].incHighwayCount();
						++numBoundaryNodesAdded;
						if(!roadsReachBoundary && way.getTag("highway") != null)
							roadsReachBoundary = true;
					}
					else if(clippedPair[1].getOnBoundary())
						++numBoundaryNodesDetected;

					if(clippedPair[1].getOnBoundary()) {
						// the point is on the boundary so make sure
						// it becomes a node
						clippedPair[1].incHighwayCount();
					}

					if(clippedPair[0] != points.get(i - 1)) {
						// the first point in the segment is outside
						// of the boundary
						assert clippedPair[0].getOnBoundary();
						// insert boundary point after the first point
						points.add(i, clippedPair[0]);
						// it's a newly created point so make its
						// highway count one
						clippedPair[0].incHighwayCount();
						++numBoundaryNodesAdded;
						if(!roadsReachBoundary && way.getTag("highway") != null)
							roadsReachBoundary = true;
					}
					else if(clippedPair[0].getOnBoundary())
						++numBoundaryNodesDetected;

					if(clippedPair[0].getOnBoundary()) {
						// the point is on the boundary so make sure
						// it becomes a node
						clippedPair[0].incHighwayCount();
					}
				}
			}
		}
		log.info("Making boundary nodes - finished (" + numBoundaryNodesAdded + " added, " + numBoundaryNodesDetected + " detected)");
	}

	private void incArcCount(Map<Coord, Integer> map, Coord p, int inc) {
		Integer i = map.get(p);
		if(i != null)
			inc += i;
		map.put(p, inc);
	}

	private void removeShortArcsByMergingNodes(double minArcLength) {
		// keep track of how many arcs reach a given point
		Map<Coord, Integer> arcCounts = new IdentityHashMap<Coord, Integer>();
		log.info("Removing short arcs (min arc length = " + minArcLength + "m)");
		log.info("Removing short arcs - counting arcs");
		for(Way w : wayMap.values()) {
			List<Coord> points = w.getPoints();
			int numPoints = points.size();
			if(numPoints >= 2) {
				// all end points have 1 arc
				incArcCount(arcCounts, points.get(0), 1);
				incArcCount(arcCounts, points.get(numPoints - 1), 1);
				// non-end points have 2 arcs but ignore points that
				// are only in a single way
				for(int i = numPoints - 2; i >= 1; --i) {
					Coord p = points.get(i);
					// if this point is a CoordPOI it may become a
					// node later even if it isn't actually a junction
					// between ways at this time - so for the purposes
					// of short arc removal, consider it to be a node
					if(p.getHighwayCount() > 1 || p instanceof CoordPOI)
						incArcCount(arcCounts, p, 2);
				}
			}
		}
		// replacements maps those nodes that have been replaced to
		// the node that replaces them
		Map<Coord, Coord> replacements = new IdentityHashMap<Coord, Coord>();
		Map<Way, Way> complainedAbout = new IdentityHashMap<Way, Way>();
		boolean anotherPassRequired = true;
		int pass = 0;
		int numWaysDeleted = 0;
		int numNodesMerged = 0;
		while(anotherPassRequired && pass < 10) {
			anotherPassRequired = false;
			log.info("Removing short arcs - PASS " + ++pass);
			Way[] ways = wayMap.values().toArray(new Way[wayMap.size()]);
			for (Way way : ways) {
				List<Coord> points = way.getPoints();
				if (points.size() < 2) {
					log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has less than 2 points - deleting it");
					wayMap.remove(way.getId());
					++numWaysDeleted;
					continue;
				}
				// scan through the way's points looking for nodes and
				// check to see that the nodes are not too close to
				// each other
				int previousNodeIndex = 0; // first point will be a node
				Coord previousPoint = points.get(0);
				double arcLength = 0;
				for(int i = 0; i < points.size(); ++i) {
					Coord p = points.get(i);
					// check if this point is to be replaced because
					// it was previously merged into another point
					Coord replacement = null;
					Coord r = p;
					while ((r = replacements.get(r)) != null) {
						replacement = r;
					}
					if (replacement != null) {
						assert !p.getOnBoundary() : "Boundary node replaced";
						String replacementId = (replacement.getOnBoundary())? "'boundary node'" : "" + nodeIdMap.get(replacement);
						log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has node " + nodeIdMap.get(p) + " replaced with node " + replacementId);
						p = replacement;
						// replace point in way
						points.set(i, p);
						if (i == 0)
							previousPoint = p;
						anotherPassRequired = true;
					}
					if(i == 0) {
						// first point in way is a node so preserve it
						// to ensure it won't be filtered out later
						p.preserved(true);

						// nothing more to do with this point
						continue;
					}

					// this is not the first point in the way

					if (p == previousPoint) {
						log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has consecutive identical points at " + p.toOSMURL() + " - deleting the second point");
						points.remove(i);
						// hack alert! rewind the loop index
						--i;
						anotherPassRequired = true;
						continue;
					}

					arcLength += p.distance(previousPoint);
					previousPoint = p;

					// this point is a node if it has an arc count
					Integer arcCount = arcCounts.get(p);

					if(arcCount == null) {
						// it's not a node so go on to next point
						continue;
					}

					// preserve the point to stop the node being
					// filtered out later
					p.preserved(true);

					Coord previousNode = points.get(previousNodeIndex);
					if(p == previousNode) {
						// this node is the same point object as the
						// previous node - leave it for now and it
						// will be handled later by the road loop
						// splitter
						previousNodeIndex = i;
						arcLength = 0;
						continue;
					}

					boolean mergeNodes = false;

					if(p.equals(previousNode)) {
						// nodes have identical coordinates and are
						// candidates for being merged

						// however, to avoid trashing unclosed loops
						// (e.g. contours) we only want to merge the
						// nodes when the length of the arc between
						// the nodes is small

						if(arcLength == 0 || arcLength < minArcLength)
							mergeNodes = true;
						else if(complainedAbout.get(way) == null) {
							log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has unmerged co-located nodes at " + p.toOSMURL() + " - they are joined by a " + (int)(arcLength * 10) / 10.0 + "m arc");
							complainedAbout.put(way, way);
						}
					}
					else if(minArcLength > 0 && minArcLength > arcLength) {
						// nodes have different coordinates but the
						// arc length is less than minArcLength so
						// they will be merged
						mergeNodes = true;
					}

					if(!mergeNodes) {
						// keep this node and go look at the next point
						previousNodeIndex = i;
						arcLength = 0;
						continue;
					}

					if(previousNode.getOnBoundary() && p.getOnBoundary()) {
						if(p.equals(previousNode)) {
							// the previous node has identical
							// coordinates to the current node so it
							// can be replaced but to avoid the
							// assertion above we need to forget that
							// it is on the boundary
							previousNode.setOnBoundary(false);
						}
						else {
							// both the previous node and this node
							// are on the boundary and they don't have
							// identical coordinates
							if(complainedAbout.get(way) == null) {
								log.warn("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has short arc (" + String.format("%.2f", arcLength) + "m) at " + p.toOSMURL() + " - but it can't be removed because both ends of the arc are boundary nodes!");
								complainedAbout.put(way, way);
							}
							break; // give up with this way
						}
					}
					String thisNodeId = (p.getOnBoundary())? "'boundary node'" : "" + nodeIdMap.get(p);
					String previousNodeId = (previousNode.getOnBoundary())? "'boundary node'" : "" + nodeIdMap.get(previousNode);

					if(p.equals(previousNode))
						log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has consecutive nodes with the same coordinates (" + p.toOSMURL() + ") - merging node " + thisNodeId + " into " + previousNodeId);
					else
						log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has short arc (" + String.format("%.2f", arcLength) + "m) at " + p.toOSMURL() + " - removing it by merging node " + thisNodeId + " into " + previousNodeId);
					// reset arc length
					arcLength = 0;

					// do the merge
					++numNodesMerged;
					if(p.getOnBoundary()) {
						// current point is a boundary node so we need
						// to merge the previous node into this node
						replacements.put(previousNode, p);
						// add the previous node's arc count to this
						// node
						incArcCount(arcCounts, p, arcCounts.get(previousNode) - 1);
						// remove the preceding point(s) back to and
						// including the previous node
						for(int j = i - 1; j >= previousNodeIndex; --j) {
							points.remove(j);
						}
					}
					else {
						// current point is not on a boundary so merge
						// this node into the previous one
						replacements.put(p, previousNode);
						// add this node's arc count to the node that
						// is replacing it
						incArcCount(arcCounts, previousNode, arcCount - 1);
						// reset previous point to be the previous
						// node
						previousPoint = previousNode;
						// remove the point(s) back to the previous
						// node
						for(int j = i; j > previousNodeIndex; --j) {
							points.remove(j);
						}
					}

					// hack alert! rewind the loop index
					i = previousNodeIndex;
					anotherPassRequired = true;
				}
			}
		}

		if(anotherPassRequired)
			log.error("Removing short arcs - didn't finish in " + pass + " passes, giving up!");
		else
			log.info("Removing short arcs - finished in " + pass + " passes (" + numNodesMerged + " nodes merged, " + numWaysDeleted + " ways deleted)");
	}

	private void setupBBoxFromBounds(Attributes xmlattr) {
		try {
			setBBox(Double.parseDouble(xmlattr.getValue("minlat")),
					Double.parseDouble(xmlattr.getValue("minlon")),
					Double.parseDouble(xmlattr.getValue("maxlat")),
					Double.parseDouble(xmlattr.getValue("maxlon")));
		} catch (NumberFormatException e) {
			// just ignore it
			log.warn("NumberformatException: Cannot read bbox");
		}
	}

	private void setupBBoxFromBound(String box) {
		String[] f = box.split(",");
		try {
			setBBox(Double.parseDouble(f[0]), Double.parseDouble(f[1]),
					Double.parseDouble(f[2]), Double.parseDouble(f[3]));
			log.debug("Map bbox: " + bbox);
		} catch (NumberFormatException e) {
			// just ignore it
			log.warn("NumberformatException: Cannot read bbox");
		}
	}

	private void setBBox(double minlat, double minlong,
	                     double maxlat, double maxlong) {

		bbox = new Area(minlat, minlong, maxlat, maxlong);
		converter.setBoundingBox(bbox);
	}

	/**
	 * Save node information.  Consists of a location specified by lat/long.
	 *
	 * @param sid The id as a string.
	 * @param slat The lat as a string.
	 * @param slon The longitude as a string.
	 */
	private void addNode(String sid, String slat, String slon) {
		if (sid == null || slat == null || slon == null)
			return;
		
		try {
			long id = idVal(sid);

			Coord co = new Coord(Double.parseDouble(slat), Double.parseDouble(slon));
			coordMap.put(id, co);
			currentElementId = id;
			if (bbox == null)
				collector.addToBounds(co);
		} catch (NumberFormatException e) {
			// ignore bad numeric data.
		}
	}

	private void addWay(String sid) {
		try {
			long id = idVal(sid);
			currentWay = new Way(id);
			wayMap.put(id, currentWay);
			currentNodeInWay = null;
			currentWayStartsWithFIXME = false;
		} catch (NumberFormatException e) {
			// ignore bad numeric data.
		}
	}

	private void addNodeToWay(long id) {
		Coord co = coordMap.get(id);
		currentNodeInWay = nodeMap.get(id);
		//co.incCount();
		if (co != null) {
			if(linkPOIsToWays) {
				// if this Coord is also a POI, replace it with an
				// equivalent CoordPOI that contains a reference to
				// the POI's Node so we can access the POI's tags
				if(!(co instanceof CoordPOI) && currentNodeInWay != null) {
					// for now, only do this for nodes that have
					// certain tags otherwise we will end up creating
					// a CoordPOI for every node in the way
					final String[] coordPOITags = { "access", "barrier", "highway" };
					for(String cpt : coordPOITags) {
						if(currentNodeInWay.getTag(cpt) != null) {
							// the POI has one of the approved tags so
							// replace the Coord with a CoordPOI
							CoordPOI cp = new CoordPOI(co.getLatitude(), co.getLongitude());
							coordMap.put(id, cp);
							// we also have to jump through hoops to
							// make a new version of Node because we
							// can't replace the Coord that defines
							// its location
							Node newNode = new Node(id, cp);
							newNode.copyTags(currentNodeInWay);
							nodeMap.put(id, newNode);
							// tell the CoordPOI what node it's
							// associated with
							cp.setNode(newNode);
							co = cp;
							// if original node is in exits, replace it
							if(exits.remove(currentNodeInWay))
								exits.add(newNode);
							currentNodeInWay = newNode;
							break;
						}
					}
				}
				if(co instanceof CoordPOI) {
					// flag this Way as having a CoordPOI so it
					// will be processed later
					currentWay.addTag("mkgmap:way-has-pois", "true");
					log.info("Linking POI " + currentNodeInWay.toBrowseURL() + " to way at " + co.toOSMURL());
				}
			}

			// See if the first Node of the Way has a FIXME attribute
			if (currentWay.getPoints().isEmpty()) {
				currentWayStartsWithFIXME = (currentNodeInWay != null &&
											 (currentNodeInWay.getTag("FIXME") != null ||
											  currentNodeInWay.getTag("fixme") != null));
			}

			currentWay.addPoint(co);
			co.incHighwayCount(); // nodes (way joins) will have highwayCount > 1
			if (minimumArcLength != null || generateSea)
				nodeIdMap.put(co, id);
		}
		else if(reportUndefinedNodes && currentWay != null)
			log.warn("Way " + currentWay.toBrowseURL() + " references undefined node " + id);
	}

	public void setConverter(OsmConverter converter) {
		this.converter = converter;
	}

	public void setCollector(MapCollector collector) {
		this.collector = collector;
	}

	public void setEndTask(Runnable endTask) {
		this.endTask = endTask;
	}

	public void fatalError(SAXParseException e) throws SAXException {
		System.err.println("Error at line " + e.getLineNumber() + ", col "
				+ e.getColumnNumber());
		super.fatalError(e);
	}

	private long idVal(String id) {
		try {
			// attempt to parse id as a number
			return Long.parseLong(id);
		}
		catch (NumberFormatException e) {
			// if that fails, fake a (hopefully) unique value
			Long fakeIdVal = fakeIdMap.get(id);
			if(fakeIdVal == null) {
				fakeIdVal = FakeIdGenerator.makeFakeId();
				fakeIdMap.put(id, fakeIdVal);
			}
			//System.out.printf("%s = 0x%016x\n", id, fakeIdVal);
			return fakeIdVal;
		}
	}

	private void generateSeaPolygon(List<Way> shoreline) {
		
		Area seaBounds;
		if (bbox != null)
			seaBounds = bbox;
		else {
			// This should probably be moved somewhere that is supposed to know
			// what the bounding box is.
			if (collector instanceof MapCollector)
				seaBounds = ((MapDetails) collector).getBounds();
			else {
				throw new IllegalArgumentException("Not using MapDetails");
			}
		}

		// clip all shoreline segments
		List<Way> toBeRemoved = new ArrayList<Way>();
		List<Way> toBeAdded = new ArrayList<Way>();
		for (Way segment : shoreline) {
			List<Coord> points = segment.getPoints();
			List<List<Coord>> clipped = LineClipper.clip(seaBounds, points);
			if (clipped != null) {
				log.info("clipping " + segment);
				toBeRemoved.add(segment);
				for (List<Coord> pts : clipped) {
					long id = FakeIdGenerator.makeFakeId();
					Way shore = new Way(id, pts);
					toBeAdded.add(shore);
				}
			}
		}
		log.info("clipping: adding " + toBeAdded.size() + ", removing " + toBeRemoved.size());
		shoreline.removeAll(toBeRemoved);
		shoreline.addAll(toBeAdded);

		log.info("generating sea, seaBounds=", seaBounds);
		int minLat = seaBounds.getMinLat();
		int maxLat = seaBounds.getMaxLat();
		int minLong = seaBounds.getMinLong();
		int maxLong = seaBounds.getMaxLong();
		Coord nw = new Coord(minLat, minLong);
		Coord ne = new Coord(minLat, maxLong);
		Coord sw = new Coord(maxLat, minLong);
		Coord se = new Coord(maxLat, maxLong);

		if(shoreline.isEmpty()) {
			// no sea required
			if(!generateSeaUsingMP) {
				// even though there is no sea, generate a land
				// polygon so that the tile's background colour will
				// match the land colour on the tiles that do contain
				// some sea
				long landId = FakeIdGenerator.makeFakeId();
				Way land = new Way(landId);
				land.addPoint(nw);
				land.addPoint(sw);
				land.addPoint(se);
				land.addPoint(ne);
				land.addPoint(nw);
				land.addTag(landTag[0], landTag[1]);
				wayMap.put(landId, land);
			}
			// nothing more to do
			return;
		}

		long multiId = FakeIdGenerator.makeFakeId();
		Relation seaRelation = null;
		if(generateSeaUsingMP) {
			log.debug("Generate seabounds relation "+multiId);
			seaRelation = new GeneralRelation(multiId);
			seaRelation.addTag("type", "multipolygon");
		}

		List<Way> islands = new ArrayList<Way>();

		// handle islands (closes shoreline components) first (they're easy)
		Iterator<Way> it = shoreline.iterator();
		while (it.hasNext()) {
			Way w = it.next();
			if (w.isClosed()) {
				log.info("adding island " + w);
				islands.add(w);
				it.remove();
			}
		}
		concatenateWays(shoreline, seaBounds);
		// there may be more islands now
		it = shoreline.iterator();
		while (it.hasNext()) {
			Way w = it.next();
			if (w.isClosed()) {
				log.debug("island after concatenating\n");
				islands.add(w);
				it.remove();
			}
		}

		boolean generateSeaBackground = true;

		// the remaining shoreline segments should intersect the boundary
		// find the intersection points and store them in a SortedMap
		SortedMap<EdgeHit, Way> hitMap = new TreeMap<EdgeHit, Way>();
		long seaId;
		Way sea;
		for (Way w : shoreline) {
			List<Coord> points = w.getPoints();
			Coord pStart = points.get(0);
			Coord pEnd = points.get(points.size()-1);

			EdgeHit hStart = getEdgeHit(seaBounds, pStart);
			EdgeHit hEnd = getEdgeHit(seaBounds, pEnd);
			if (hStart == null || hEnd == null) {
				String msg = String.format("Non-closed coastline segment does not hit bounding box: %d (%s) %d (%s) %s\n",
						nodeIdMap.get(pStart),  pStart.toDegreeString(),
						nodeIdMap.get(pEnd),  pEnd.toDegreeString(),
						pStart.toOSMURL());
				log.warn(msg);

				/*
				 * This problem occurs usually when the shoreline is cut by osmosis (e.g. country-extracts from geofabrik)
				 * There are two possibilities to solve this problem:
				 * 1. Close the way and treat it as an island. This is sometimes the best solution (Germany: Usedom at the
				 *    border to Poland)
				 * 2. Create a "sea sector" only for this shoreline segment. This may also be the best solution
				 *    (see German border to the Netherlands where the shoreline continues in the Netherlands)
				 * The first choice may lead to "flooded" areas, the second may lead to "triangles".
				 *
				 * Usually, the first choice is appropriate if the segment is "nearly" closed.
				 */
				double length = 0;
				Coord p0 = pStart;
				for (Coord p1 : points.subList(1, points.size()-1)) {
					length += p0.distance(p1);
					p0 = p1;
				}
				boolean nearlyClosed = pStart.distance(pEnd) < 0.1 * length;

				if (nearlyClosed) {
					// close the way
					points.add(pStart);
					if(generateSeaUsingMP)
						seaRelation.addElement("inner", w);
					else {
						if(!FakeIdGenerator.isFakeId(w.getId())) {
							Way w1 = new Way(FakeIdGenerator.makeFakeId());
							w1.getPoints().addAll(w.getPoints());
							// only copy the name tags
							for(String tag : w)
								if(tag.equals("name") || tag.endsWith(":name"))
									w1.addTag(tag, w.getTag(tag));
							w = w1;
						}
						w.addTag(landTag[0], landTag[1]);
						wayMap.put(w.getId(), w);
					}
				}
				else if(allowSeaSectors) {
					seaId = FakeIdGenerator.makeFakeId();
					sea = new Way(seaId);
					sea.getPoints().addAll(points);
					sea.addPoint(new Coord(pEnd.getLatitude(), pStart.getLongitude()));
					sea.addPoint(pStart);
					sea.addTag("natural", "sea");
					log.info("sea: ", sea);
					wayMap.put(seaId, sea);
					if(generateSeaUsingMP)
						seaRelation.addElement("outer", sea);
					generateSeaBackground = false;
				}
				else if (extendSeaSectors) {
					// create additional points at next border to prevent triangles from point 2
					if (null == hStart) {
						hStart = getNextEdgeHit(seaBounds, pStart);
						w.getPoints().add(0, hStart.getPoint(seaBounds));
					}
					if (null == hEnd) {
						hEnd = getNextEdgeHit(seaBounds, pEnd);
						w.getPoints().add(hEnd.getPoint(seaBounds));
					}
					log.debug("hits (second try): ", hStart, hEnd);
					hitMap.put(hStart, w);
					hitMap.put(hEnd, null);
				}
				else {
					// show the coastline even though we can't produce
					// a polygon for the land
					w.addTag("natural", "coastline");
					wayMap.put(w.getId(), w);
				}
			}
			else {
				log.debug("hits: ", hStart, hEnd);
				hitMap.put(hStart, w);
				hitMap.put(hEnd, null);
			}
		}

		// now construct inner ways from these segments
		NavigableSet<EdgeHit> hits = (NavigableSet<EdgeHit>) hitMap.keySet();
		boolean shorelineReachesBoundary = false;
		while (!hits.isEmpty()) {
			long id = FakeIdGenerator.makeFakeId();
			Way w = new Way(id);
			wayMap.put(id, w);

			EdgeHit hit =  hits.first();
			EdgeHit hFirst = hit;
			do {
				Way segment = hitMap.get(hit);
				log.info("current hit: " + hit);
				EdgeHit hNext;
				if (segment != null) {
					// add the segment and get the "ending hit"
					log.info("adding: ", segment);
					for(Coord p : segment.getPoints())
						w.addPointIfNotEqualToLastPoint(p);
					hNext = getEdgeHit(seaBounds, segment.getPoints().get(segment.getPoints().size()-1));
				}
				else {
					w.addPointIfNotEqualToLastPoint(hit.getPoint(seaBounds));
					hNext = hits.higher(hit);
					if (hNext == null)
						hNext = hFirst;

					Coord p;
					if (hit.compareTo(hNext) < 0) {
						log.info("joining: ", hit, hNext);
						for (int i=hit.edge; i<hNext.edge; i++) {
							EdgeHit corner = new EdgeHit(i, 1.0);
							p = corner.getPoint(seaBounds);
							log.debug("way: ", corner, p);
							w.addPointIfNotEqualToLastPoint(p);
						}
					}
					else if (hit.compareTo(hNext) > 0) {
						log.info("joining: ", hit, hNext);
						for (int i=hit.edge; i<4; i++) {
							EdgeHit corner = new EdgeHit(i, 1.0);
							p = corner.getPoint(seaBounds);
							log.debug("way: ", corner, p);
							w.addPointIfNotEqualToLastPoint(p);
						}
						for (int i=0; i<hNext.edge; i++) {
							EdgeHit corner = new EdgeHit(i, 1.0);
							p = corner.getPoint(seaBounds);
							log.debug("way: ", corner, p);
							w.addPointIfNotEqualToLastPoint(p);
						}
					}
					w.addPointIfNotEqualToLastPoint(hNext.getPoint(seaBounds));
				}
				hits.remove(hit);
				hit = hNext;
			} while (!hits.isEmpty() && !hit.equals(hFirst));

			if (!w.isClosed())
				w.getPoints().add(w.getPoints().get(0));
			log.info("adding non-island landmass, hits.size()=" + hits.size());
			islands.add(w);
			shorelineReachesBoundary = true;
		}

		if(!shorelineReachesBoundary && roadsReachBoundary) {
			// try to avoid tiles being flooded by anti-lakes or other
			// bogus uses of natural=coastline
			generateSeaBackground = false;
		}

		List<Way> antiIslands = new ArrayList<Way>();

		for (Way w : islands) {

			if(!FakeIdGenerator.isFakeId(w.getId())) {
				Way w1 = new Way(FakeIdGenerator.makeFakeId());
				w1.getPoints().addAll(w.getPoints());
				// only copy the name tags
				for(String tag : w)
					if(tag.equals("name") || tag.endsWith(":name"))
						w1.addTag(tag, w.getTag(tag));
				w = w1;
			}

			// determine where the water is
			if(w.clockwise()) {
				// water on the inside of the poly, it's an
				// "anti-island" so tag with natural=water (to
				// make it visible above the land)
				w.addTag("natural", "water");
				antiIslands.add(w);
				wayMap.put(w.getId(), w);
			}
			else {
				// water on the outside of the poly, it's an island
				if(generateSeaUsingMP) {
					// create a "inner" way for each island
					seaRelation.addElement("inner", w);
				}
				else {
					// tag as land
					w.addTag(landTag[0], landTag[1]);
					wayMap.put(w.getId(), w);
				}
			}
		}

		islands.removeAll(antiIslands);

		if(islands.isEmpty()) {
			// the tile doesn't contain any islands so we can assume
			// that it's showing a land mass that contains some
			// enclosed sea areas - in which case, we don't want a sea
			// coloured background
			generateSeaBackground = false;
		}

		if (generateSeaBackground) {

			// the background is sea so all anti-islands should be
			// contained by land otherwise they won't be visible

			for(Way ai : antiIslands) {
				boolean containedByLand = false;
				for(Way i : islands) {
					if(i.containsPointsOf(ai)) {
						containedByLand = true;
						break;
					}
				}
				if(!containedByLand) {
					// found an anti-island that is not contained by
					// land so convert it back into an island
					ai.deleteTag("natural");
					if(generateSeaUsingMP) {
						// create a "inner" way for the island
						seaRelation.addElement("inner", ai);
						wayMap.remove(ai.getId());
					}
					else
						ai.addTag(landTag[0], landTag[1]);
					log.warn("Converting anti-island starting at " + ai.getPoints().get(0).toOSMURL() + " into an island as it is surrounded by water");
				}
			}

			seaId = FakeIdGenerator.makeFakeId();
			sea = new Way(seaId);
			if (generateSeaUsingMP) {
				// the sea background area must be a little bigger than all
				// inner land areas. this is a workaround for a mp shortcoming:
				// mp is not able to combine outer and inner if they intersect
				// or have overlaying lines
				// the added area will be clipped later by the style generator
				sea.addPoint(new Coord(nw.getLatitude() - 1,
						nw.getLongitude() - 1));
				sea.addPoint(new Coord(sw.getLatitude() + 1,
						sw.getLongitude() - 1));
				sea.addPoint(new Coord(se.getLatitude() + 1,
						se.getLongitude() + 1));
				sea.addPoint(new Coord(ne.getLatitude() - 1,
						ne.getLongitude() + 1));
				sea.addPoint(new Coord(nw.getLatitude() - 1,
						nw.getLongitude() - 1));
			} else {
				sea.addPoint(nw);
				sea.addPoint(sw);
				sea.addPoint(se);
				sea.addPoint(ne);
				sea.addPoint(nw);
			}
			sea.addTag("natural", "sea");
			log.info("sea: ", sea);
			wayMap.put(seaId, sea);
			if(generateSeaUsingMP)
				seaRelation.addElement("outer", sea);
		}
		else {
			// background is land
			if(!generateSeaUsingMP) {
				// generate a land polygon so that the tile's
				// background colour will match the land colour on the
				// tiles that do contain some sea
				long landId = FakeIdGenerator.makeFakeId();
				Way land = new Way(landId);
				land.addPoint(nw);
				land.addPoint(sw);
				land.addPoint(se);
				land.addPoint(ne);
				land.addPoint(nw);
				land.addTag(landTag[0], landTag[1]);
				wayMap.put(landId, land);
			}
		}

		if(generateSeaUsingMP) {
			Area mpBbox = (bbox != null ? bbox : ((MapDetails) collector).getBounds());
			seaRelation = new MultiPolygonRelation(seaRelation, wayMap, mpBbox);
			relationMap.put(multiId, seaRelation);
			seaRelation.processElements();
		}
	}

	/**
	 * Specifies where an edge of the bounding box is hit.
	 */
	private static class EdgeHit implements Comparable<EdgeHit>
	{
		private final int edge;
		private final double t;

		EdgeHit(int edge, double t) {
			this.edge = edge;
			this.t = t;
		}

		public int compareTo(EdgeHit o) {
			if (edge < o.edge)
				return -1;
			else if (edge > o.edge)
				return +1;
			else if (t > o.t)
				return +1;
			else if (t < o.t)
				return -1;
			else
				return 0;
		}

		public boolean equals(Object o) {
			if (o instanceof EdgeHit) {
				EdgeHit h = (EdgeHit) o;
				return (h.edge == edge && Double.compare(h.t, t) == 0);
			}
			else
				return false;
		}

		private Coord getPoint(Area a) {
			log.info("getPoint: ", this, a);
			switch (edge) {
			case 0:
				return new Coord(a.getMinLat(), (int) (a.getMinLong() + t * (a.getMaxLong()-a.getMinLong())));

			case 1:
				return new Coord((int)(a.getMinLat() + t * (a.getMaxLat()-a.getMinLat())), a.getMaxLong());

			case 2:
				return new Coord(a.getMaxLat(), (int)(a.getMaxLong() - t * (a.getMaxLong()-a.getMinLong())));

			case 3:
				return new Coord((int)(a.getMaxLat() - t * (a.getMaxLat()-a.getMinLat())), a.getMinLong());

			default:
				throw new MapFailedException("illegal state");
			}
		}

		public String toString() {
			return "EdgeHit " + edge + "@" + t;
		}
	}

	private EdgeHit getEdgeHit(Area a, Coord p)
	{
		return getEdgeHit(a, p, 10);
	}

	private EdgeHit getEdgeHit(Area a, Coord p, int tolerance)
	{
		int lat = p.getLatitude();
		int lon = p.getLongitude();
		int minLat = a.getMinLat();
		int maxLat = a.getMaxLat();
		int minLong = a.getMinLong();
		int maxLong = a.getMaxLong();

		log.info(String.format("getEdgeHit: (%d %d) (%d %d %d %d)", lat, lon, minLat, minLong, maxLat, maxLong));
		if (lat <= minLat+tolerance) {
			return new EdgeHit(0, ((double)(lon - minLong))/(maxLong-minLong));
		}
		else if (lon >= maxLong-tolerance) {
			return new EdgeHit(1, ((double)(lat - minLat))/(maxLat-minLat));
		}
		else if (lat >= maxLat-tolerance) {
			return new EdgeHit(2, ((double)(maxLong - lon))/(maxLong-minLong));
		}
		else if (lon <= minLong+tolerance) {
			return new EdgeHit(3, ((double)(maxLat - lat))/(maxLat-minLat));
		}
		else
			return null;
	}

	/*
	 * Find the nearest edge for supplied Coord p.
	 */
	private EdgeHit getNextEdgeHit(Area a, Coord p)
	{
		int lat = p.getLatitude();
		int lon = p.getLongitude();
		int minLat = a.getMinLat();
		int maxLat = a.getMaxLat();
		int minLong = a.getMinLong();
		int maxLong = a.getMaxLong();

		log.info(String.format("getNextEdgeHit: (%d %d) (%d %d %d %d)", lat, lon, minLat, minLong, maxLat, maxLong));
		// shortest distance to border (init with distance to southern border) 
		int min = lat - minLat;
		// number of edge as used in getEdgeHit. 
		// 0 = southern
		// 1 = eastern
		// 2 = northern
		// 3 = western edge of Area a
		int i = 0;
		// normalized position at border (0..1)
		double l = ((double)(lon - minLong))/(maxLong-minLong);
		// now compare distance to eastern border with already known distance
		if (maxLong - lon < min) {
			// update data if distance is shorter
			min = maxLong - lon;
			i = 1;
			l = ((double)(lat - minLat))/(maxLat-minLat);
		}
		// same for northern border
		if (maxLat - lat < min) {
			min = maxLat - lat;
			i = 2;
			l = ((double)(maxLong - lon))/(maxLong-minLong);
		}
		// same for western border
		if (lon - minLong < min) {
			i = 3;
			l = ((double)(maxLat - lat))/(maxLat-minLat);
		}
		// now created the EdgeHit for found values
		return new EdgeHit(i, l);
	} 
	
	private void concatenateWays(List<Way> ways, Area bounds) {
		Map<Coord, Way> beginMap = new HashMap<Coord, Way>();

		for (Way w : ways) {
			if (!w.isClosed()) {
				List<Coord> points = w.getPoints();
				beginMap.put(points.get(0), w);
			}
		}

		int merged = 1;
		while (merged > 0) {
			merged = 0;
			for (Way w1 : ways) {
				if (w1.isClosed()) continue;

				List<Coord> points1 = w1.getPoints();
				Way w2 = beginMap.get(points1.get(points1.size()-1));
				if (w2 != null) {
					log.info("merging: ", ways.size(), w1.getId(), w2.getId());
					List<Coord> points2 = w2.getPoints();
					Way wm;
					if (FakeIdGenerator.isFakeId(w1.getId())) {
						wm = w1;
					} else {
						wm = new Way(FakeIdGenerator.makeFakeId());
						ways.remove(w1);
						ways.add(wm);
						wm.getPoints().addAll(points1);
						beginMap.put(points1.get(0), wm);
						// only copy the name tags
						for (String tag : w1)
							if (tag.equals("name") || tag.endsWith(":name"))
								wm.addTag(tag, w1.getTag(tag));
					}
					wm.getPoints().addAll(points2);
					ways.remove(w2);
					beginMap.remove(points2.get(0));
					merged++;
					break;
				}
			}
		}

		// join up coastline segments whose end points are less than
		// maxCoastlineGap metres apart
		if(maxCoastlineGap > 0) {
			boolean changed = true;
			while(changed) {
				changed = false;
				for(Way w1 : ways) {
					if(w1.isClosed())
						continue;
					List<Coord> points1 = w1.getPoints();
					Coord w1e = points1.get(points1.size() - 1);
					if(bounds.onBoundary(w1e))
						continue;
					Way nearest = null;
					double smallestGap = Double.MAX_VALUE;
					for(Way w2 : ways) {
						if(w1 == w2 || w2.isClosed())
							continue;
						List<Coord> points2 = w2.getPoints();
						Coord w2s = points2.get(0);
						if(bounds.onBoundary(w2s))
							continue;
						double gap = w1e.distance(w2s);
						if(gap < smallestGap) {
							nearest = w2;
							smallestGap = gap;
						}
					}
					if(nearest != null && smallestGap < maxCoastlineGap) {
						Coord w2s = nearest.getPoints().get(0);
						log.warn("Bridging " + (int)smallestGap + "m gap in coastline from " + w1e.toOSMURL() + " to " + w2s.toOSMURL());
						Way wm;
						if (FakeIdGenerator.isFakeId(w1.getId())) {
							wm = w1;
						} else {
							wm = new Way(FakeIdGenerator.makeFakeId());
							ways.remove(w1);
							ways.add(wm);
							wm.getPoints().addAll(points1);
							wm.copyTags(w1);
						}
						wm.getPoints().addAll(nearest.getPoints());
						ways.remove(nearest);
						// make a line that shows the filled gap
						Way w = new Way(FakeIdGenerator.makeFakeId());
						w.addTag("natural", "mkgmap:coastline-gap");
						w.addPoint(w1e);
						w.addPoint(w2s);
						wayMap.put(w.getId(), w);
						changed = true;
						break;
					}
				}
			}
		}
	}
}
