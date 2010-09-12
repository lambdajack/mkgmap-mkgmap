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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineClipper;
import uk.me.parabola.mkgmap.reader.osm.CoordPOI;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.ElementSaver;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GeneralRelation;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmReadingHooks;
import uk.me.parabola.mkgmap.reader.osm.Relation;
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
 * Creates the nodes/ways and relations that are read from the file and passes
 * them to the OsmCollector.
 *
 * It should not examine tags, or do anything else.
 *
 * @author Steve Ratcliffe
 */
public class Osm5XmlHandler extends DefaultHandler {
	private static final Logger log = Logger.getLogger(Osm5XmlHandler.class);

	private int mode;

	private ElementSaver saver;
	private OsmReadingHooks hooks;

	private final Map<String, Long> fakeIdMap = new HashMap<String, Long>();

	private static final int MODE_NODE = 1;
	private static final int MODE_WAY = 2;
	private static final int MODE_BOUND = 3;
	private static final int MODE_RELATION = 4;
	private static final int MODE_BOUNDS = 5;

	private Node currentNode;
	private Way currentWay;
	private Relation currentRelation;
	private long currentElementId;

	private Area bbox;

	private final boolean reportUndefinedNodes;

	private final boolean ignoreBounds;
	private boolean roadsReachBoundary;
	private final Double minimumArcLength;

	private Map<String,Set<String>> deletedTags;
	private Map<String, String> usedTags;
	

	public Osm5XmlHandler(EnhancedProperties props) {

		ignoreBounds = props.getProperty("ignore-osm-bounds", false);

		String rsa = props.getProperty("remove-short-arcs", null);
		if(rsa != null)
			minimumArcLength = (rsa.length() > 0)? Double.parseDouble(rsa) : 0.0;
		else
			minimumArcLength = null;
		reportUndefinedNodes = props.getProperty("report-undefined-nodes", false);
	}

	public void setDeletedTags(Map<String, Set<String>> deletedTags) {
		this.deletedTags = deletedTags;
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
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		if (mode == 0) {
			if (qName.equals("node")) {
				mode = MODE_NODE;
				startNode(attributes.getValue("id"),
						attributes.getValue("lat"),
						attributes.getValue("lon"));

			} else if (qName.equals("way")) {
				mode = MODE_WAY;
				startWay(attributes.getValue("id"));

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

	private void startInNode(String qName, Attributes attributes) {
		if (qName.equals("tag")) {
			String key = attributes.getValue("k");
			String val = attributes.getValue("v");

			if("mkgmap:on-boundary".equals(key)) { // FIXME checking tag value
				if("1".equals(val) || "true".equals(val) || "yes".equals(val)) {
					Coord co = saver.getCoord(currentElementId);
					co.setOnBoundary(true);
					co.incHighwayCount();
				}
				return;
			}

			// We only want to create a full node for nodes that are POI's
			// and not just one point of a way.  Only create if it has tags that
			// could be used in a POI.
			key = keepTag(key, val);
			if (key != null) {
				if (currentNode == null) {
					Coord co = saver.getCoord(currentElementId);
					currentNode = new Node(currentElementId, co);
					saver.addNode(currentNode);// TODO call on end tag
				}

				currentNode.addTag(key, val);
			}
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

	private void startInRelation(String qName, Attributes attributes) {
		if (qName.equals("member")) {
			long id = idVal(attributes.getValue("ref"));
			Element el;
			String type = attributes.getValue("type");
			if ("way".equals(type)){
				el = saver.getWay(id);
			} else if ("node".equals(type)) {
				el = saver.getNode(id);
				if(el == null) {
					// we didn't make a node for this point earlier,
					// do it now (if it exists)
					Coord co = saver.getCoord(id);
					if(co != null) {
						el = new Node(id, co);
						saver.addNode((Node)el);
					}
				}
			} else if ("relation".equals(type)) {
				el = saver.getRelation(id);
				if (el == null) {
					saver.deferRelation(id, currentRelation, attributes.getValue("role"));
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
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (mode == MODE_NODE) {
			if (qName.equals("node")) {
				mode = 0;
				endNode();
			}

		} else if (mode == MODE_WAY) {
			if (qName.equals("way")) {
				mode = 0;
				endWay();
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
		if (currentNode != null) {
			saver.addNode(currentNode);
			hooks.addNode(currentNode);
		}
		currentElementId = 0;
		currentNode = null;
	}

	private void endWay() {
		saver.addWay(currentWay);
		hooks.addWay(currentWay);
		currentWay = null;
	}

	private void endRelation() {
		saver.addRelation(currentRelation);
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

		if (bbox != null && (minimumArcLength != null))
			makeBoundaryNodes(); // TODO only on explicity bounding box

		if(minimumArcLength != null)
			removeShortArcsByMergingNodes(minimumArcLength);
	}

	// "soft clip" each way that crosses a boundary by adding a point
	//  at each place where it meets the boundary
	private void makeBoundaryNodes() {
		log.info("Making boundary nodes");
		int numBoundaryNodesDetected = 0;
		int numBoundaryNodesAdded = 0;
		for(Way way : saver.getWays().values()) {
			List<Coord> points = way.getPoints();

			// clip each segment in the way against the bounding box
			// to find the positions of the boundary nodes - loop runs
			// backwards so we can safely insert points into way
			for (int i = points.size() - 1; i >= 1; --i) {
				Coord[] pair = { points.get(i - 1), points.get(i) };
				Coord[] clippedPair = LineClipper.clip(bbox, pair);
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
						// it's a newly created point so make its
						// highway count one
						clippedPair[1].incHighwayCount();
						++numBoundaryNodesAdded;
						if(!roadsReachBoundary && way.getTag("highway") != null)
							roadsReachBoundary = true;
					} else if(clippedPair[1].getOnBoundary())
						++numBoundaryNodesDetected;

					if (clippedPair[1].getOnBoundary()) {
						// the point is on the boundary so make sure
						// it becomes a node
						clippedPair[1].incHighwayCount();
					}

					if (clippedPair[0] != points.get(i - 1)) {
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
					} else if (clippedPair[0].getOnBoundary())
						++numBoundaryNodesDetected;

					if (clippedPair[0].getOnBoundary()) {
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
		for (Way w : saver.getWays().values()) {
			List<Coord> points = w.getPoints();
			int numPoints = points.size();
			if (numPoints >= 2) {
				// all end points have 1 arc
				incArcCount(arcCounts, points.get(0), 1);
				incArcCount(arcCounts, points.get(numPoints - 1), 1);
				// non-end points have 2 arcs but ignore points that
				// are only in a single way
				for (int i = numPoints - 2; i >= 1; --i) {
					Coord p = points.get(i);
					// if this point is a CoordPOI it may become a
					// node later even if it isn't actually a junction
					// between ways at this time - so for the purposes
					// of short arc removal, consider it to be a node
					if (p.getHighwayCount() > 1 || p instanceof CoordPOI)
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

		while (anotherPassRequired && pass < 10) {
			anotherPassRequired = false;
			log.info("Removing short arcs - PASS " + ++pass);
			Way[] ways = saver.getWays().values().toArray(new Way[saver.getWays().size()]);
			for (Way way : ways) {
				List<Coord> points = way.getPoints();
				if (points.size() < 2) {
					log.info("  Way " + way.getTag("name") + " (" + way.toBrowseURL() + ") has less than 2 points - deleting it");
					saver.getWays().remove(way.getId());
					++numWaysDeleted;
					continue;
				}
				// scan through the way's points looking for nodes and
				// check to see that the nodes are not too close to
				// each other
				int previousNodeIndex = 0; // first point will be a node
				Coord previousPoint = points.get(0);
				double arcLength = 0;
				for (int i = 0; i < points.size(); ++i) {
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
						p = replacement;
						// replace point in way
						points.set(i, p);
						if (i == 0)
							previousPoint = p;
						anotherPassRequired = true;
					}

					if (i == 0) {
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

					if (arcCount == null) {
						// it's not a node so go on to next point
						continue;
					}

					// preserve the point to stop the node being
					// filtered out later
					p.preserved(true);

					Coord previousNode = points.get(previousNodeIndex);
					if (p == previousNode) {
						// this node is the same point object as the
						// previous node - leave it for now and it
						// will be handled later by the road loop
						// splitter
						previousNodeIndex = i;
						arcLength = 0;
						continue;
					}

					boolean mergeNodes = false;

					if (p.equals(previousNode)) {
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

					if (!mergeNodes) {
						// keep this node and go look at the next point
						previousNodeIndex = i;
						arcLength = 0;
						continue;
					}

					if (previousNode.getOnBoundary() && p.getOnBoundary()) {
						if (p.equals(previousNode)) {
							// the previous node has identical
							// coordinates to the current node so it
							// can be replaced but to avoid the
							// assertion above we need to forget that
							// it is on the boundary
							previousNode.setOnBoundary(false);
						} else {
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

					// reset arc length
					arcLength = 0;

					// do the merge
					++numNodesMerged;
					if (p.getOnBoundary()) {
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
					} else {
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
						for (int j = i; j > previousNodeIndex; --j) {
							points.remove(j);
						}
					}

					// hack alert! rewind the loop index
					i = previousNodeIndex;
					anotherPassRequired = true;
				}
			}
		}

		if (anotherPassRequired)
			log.error("Removing short arcs - didn't finish in " + pass + " passes, giving up!");
		else
			log.info("Removing short arcs - finished in", pass, "passes (", numNodesMerged, "nodes merged,", numWaysDeleted, "ways deleted)");
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

	private void setBBox(double minlat, double minlong, double maxlat, double maxlong) {
		bbox = new Area(minlat, minlong, maxlat, maxlong);
		saver.setBoundingBox(bbox);
	}

	/**
	 * Save node information.  Consists of a location specified by lat/long.
	 *
	 * @param sid The id as a string.
	 * @param slat The lat as a string.
	 * @param slon The longitude as a string.
	 */
	private void startNode(String sid, String slat, String slon) {
		if (sid == null || slat == null || slon == null)
			return;
		
		try {
			long id = idVal(sid);

			Coord co = new Coord(Double.parseDouble(slat), Double.parseDouble(slon));
			saver.addPoint(id, co);
			currentElementId = id;
			saver.addPoint(id, co);
		} catch (NumberFormatException e) {
			// ignore bad numeric data.
		}
	}

	private void startWay(String sid) {
		try {
			long id = idVal(sid);
			currentWay = new Way(id);
			saver.addWay(currentWay);
		} catch (NumberFormatException e) {
			// ignore bad numeric data.
		}
	}

	private void addNodeToWay(long id) {
		Coord co = saver.getCoord(id);

		if (co != null) {
			hooks.nodeAddedToWay(currentWay, id, co);
			currentWay.addPoint(co);

			// nodes (way joins) will have highwayCount > 1
			co.incHighwayCount();
		} else if(reportUndefinedNodes && currentWay != null) {
			log.warn("Way", currentWay.toBrowseURL(), "references undefined node", id);
		}
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
		} catch (NumberFormatException e) {
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

	public void setOsmCollector(ElementSaver saver) {
		this.saver = saver;
	}

	public void setElementSaver(ElementSaver elementSaver) {
		this.saver = elementSaver;
	}

	public void setHooks(OsmReadingHooks plugin) {
		this.hooks = plugin;
	}
}
