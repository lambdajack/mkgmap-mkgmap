/*
 * Copyright (C) 2011-2021.
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

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.IsInUtil;
import uk.me.parabola.util.Java2DConverter;
import uk.me.parabola.util.MultiIdentityHashMap;
import uk.me.parabola.util.ShapeSplitter;

/**
 * Representation of an OSM Multipolygon Relation.<br/>
 * The different way of the multipolygon are joined to polygons and inner
 * polygons are cut out from the outer polygons.
 * 
 * @author WanMil
 */
public class MultiPolygonRelation extends Relation {
	private static final Logger log = Logger.getLogger(MultiPolygonRelation.class);

	public static final String STYLE_FILTER_TAG = "mkgmap:stylefilter";
	public static final String STYLE_FILTER_LINE = "polyline";
	public static final String STYLE_FILTER_POLYGON = "polygon";

	/** A tag that is set with value true on each polygon that is created by the mp processing. */
	public static final short TKM_MP_CREATED = TagDict.getInstance().xlate("mkgmap:mp_created");
	private static final short TKM_MP_ROLE = TagDict.getInstance().xlate("mkgmap:mp_role");
	private static final short TKM_CACHE_AREA_SIZEKEY = TagDict.getInstance().xlate("mkgmap:cache_area_size");
	
	public static final String ROLE_OUTER = "outer";  
	public static final String ROLE_INNER = "inner";  

	private static final byte INT_ROLE_NULL = 1; 
	private static final byte INT_ROLE_INNER = 2; 
	private static final byte INT_ROLE_OUTER = 4; 
	private static final byte INT_ROLE_BLANK = 8; 
	private static final byte INT_ROLE_OTHER = 16; 

	/** maps ids to ways, will be extended with joined ways */
	private final Map<Long, Way> tileWayMap; // never clear!
	

	protected List<JoinedWay> polygons;
	private Map<Long, Way> mpPolygons = new LinkedHashMap<>();

	protected JoinedWay largestOuterPolygon;
	private Long2ObjectOpenHashMap<Coord> commonCoordMap = new Long2ObjectOpenHashMap<>();
	protected Set<Way> outerWaysForLineTagging;

	private final uk.me.parabola.imgfmt.app.Area tileBounds;
	private Area tileArea;
	
	private Coord cOfG = null;
	
	// the sum of all outer polygons area size 
	private double mpAreaSize;
	
	private boolean noRecalc;
	
	/**
	 * Create an instance based on an existing relation. We need to do this
	 * because the type of the relation is not known until after all its tags
	 * are read in.
	 * 
	 * @param other
	 *            The relation to base this one on.
	 * @param wayMap
	 *            Map of all ways.
	 * @param bbox
	 *            The bounding box of the tile
	 */
	public MultiPolygonRelation(Relation other, Map<Long, Way> wayMap, uk.me.parabola.imgfmt.app.Area bbox) {
		this.tileWayMap = wayMap;
		this.tileBounds = bbox;
		// create an Area for the bbox to clip the polygons
		tileArea = Java2DConverter.createBoundsArea(tileBounds); 

		setId(other.getId());
		copyTags(other);

		other.getElements().forEach(e -> addElement(e.getKey(), e.getValue()));
		if (log.isDebugEnabled()) {
			log.debug("Constructed multipolygon", toBrowseURL(), toTagString());
		}
	}
	

	/**
	 * Retrieves the center point of this multipolygon. This is set in the 
	 * {@link #processElements()} methods so it returns <code>null</code> 
	 * before that. It can also return <code>null</code> in case the 
	 * multipolygon could not be processed.<br/>
	 * The returned point may lie outside the multipolygon area. It is just
	 * the center point of it.
	 * 
	 * @return the center point of this multipolygon (maybe <code>null</code>)
	 */
	public Coord getCofG() {
		return cOfG;
	}
	
	/**
	 * Retrieves the role of the given element based on the role in the MP.
	 * 
	 * @param jw the element
	 * @return either ROLE_INNER, ROLE_OUTER or null.
	 */
	private static String getRole(JoinedWay jw) {
		if (jw.intRole == INT_ROLE_INNER)
			return ROLE_INNER;
		if (jw.intRole == INT_ROLE_OUTER)
			return ROLE_OUTER;
		return null;
	}

	/**
	 * Combine a list of way segments to a list of maximally joined ways. There are
	 * lots of possible ways to do this but the result should be predictable, so
	 * that multiple runs of mkgmap produce the same polygons.
	 * 
	 * @return a list of maximally joined ways
	 */
	private List<JoinedWay> joinWays() {
		List<JoinedWay> joinedWays = new ArrayList<>();
		List<JoinedWay> unclosedWays = new LinkedList<>();
		
		parseElements(joinedWays, unclosedWays);
		
		if (unclosedWays.isEmpty())
			return joinedWays;
		
		if (unclosedWays.size() > 1) {
			// first try to combine ways in the given order
			joinInGivenOrder(joinedWays, unclosedWays);
		}
		if (unclosedWays.size() == 1) {
			joinedWays.add(unclosedWays.remove(0));
		}
		if (!unclosedWays.isEmpty()) {
			// members are not fully ordered or we have unclosed rings
			joinWithIndex(joinedWays, unclosedWays);
		}
		joinedWays.addAll(unclosedWays);
		if(log.isInfoEnabled()) {
			for (JoinedWay jw : joinedWays) {
				if (Integer.bitCount(jw.intRole) > 1) {
					log.info("Joined polygon ways have different roles", this.toBrowseURL(), jw.toString());
				}
			}
		}
		return joinedWays;
	}

	/**
	 * Go through list of elements, do some basic checks and separate the ways into
	 * closed and unclosed ways. The (last) label node is used to set cOfG.
	 * 
	 * @param closedWays   list to which closed ways are added
	 * @param unclosedWays list to which unclosed ways are added
	 */
	private void parseElements(List<JoinedWay> closedWays, List<JoinedWay> unclosedWays) {
		Map<Long, Way> dupCheck = new HashMap<>();

		for (Map.Entry<String, Element> entry : getElements()) {
			String role = entry.getKey();
			Element el = entry.getValue();
			if (el instanceof Way) {
				Way wayEl = (Way) el;
				if (dupCheck.put(wayEl.getId(), wayEl) != null) {
					log.warn("repeated way member with id", el.getId(), "is ignored in multipolygon relation", toBrowseURL());
				} else if (wayEl.getPoints().size() <= 1) {
					log.warn("Way", wayEl, "has", wayEl.getPoints().size(),
							 "points and cannot be used for the multipolygon", toBrowseURL());
				} else {
					JoinedWay jw = new JoinedWay(wayEl, role);
					if (jw.intRole == INT_ROLE_OTHER) 
						log.warn("Way role invalid", role, el.toBrowseURL(),
								 "in multipolygon", toBrowseURL(), toTagString());
					if (wayEl.isClosedInOSM() && !wayEl.hasIdenticalEndPoints() && !wayEl.isComplete()) {
						// the way is closed in planet but some points are missing in this tile
						// we can close it artificially, it is very likely outside of the tile bounds
						if (log.isDebugEnabled())
							log.debug("Close incomplete but closed polygon:", wayEl);
						jw.closeWayArtificially();
					}
					if (jw.hasIdenticalEndPoints())
						closedWays.add(jw);
					else {
						unclosedWays.add(jw);
					}
				}
			} else if (el instanceof Node) {
				if ("label".equals(role))
					cOfG = ((Node) el).getLocation();
				else if (!"admin_centre".equals(role)) 
					log.warn("Node with unknown role is ignored", role, el.toBrowseURL(),
							 "in multipolygon", toBrowseURL(), toTagString());
			} else {
				log.warn("Non Way/Node member with role is ignored", role, el.toBrowseURL(),
						 "in multipolygon", toBrowseURL(), toTagString());
			}
		}
	}

	/**
	 * Combines ways in the given order. Closed ways are added to closedWays, unclosed ways remain in unclosed.  
	 * Stops when two ways cannot be joined in the given order.
	 * @param closedWays
	 * @param unclosedWays
	 */
	private void joinInGivenOrder(List<JoinedWay> closedWays, List<JoinedWay> unclosedWays) {
		JoinedWay work = null;
		while (unclosedWays.size() > 1) {
			if (work == null)
				work = unclosedWays.get(0);
			if (!work.canJoin(unclosedWays.get(1)))
				break;
			work.joinWith(unclosedWays.get(1));
			unclosedWays.remove(1);
			if (work.hasIdenticalEndPoints()) {
				closedWays.add(work);
				unclosedWays.remove(0);
				work = null;
			}
		}
	}

	private void joinWithIndex(List<JoinedWay> closedWays, List<JoinedWay> unclosedWays) {
		MultiIdentityHashMap<Coord, JoinedWay> index = new MultiIdentityHashMap<>();
		unclosedWays.forEach(jw -> {
			index.add(jw.getFirstPoint(), jw);
			index.add(jw.getLastPoint(), jw);
		});

		List<JoinedWay> finishedUnclosed = new ArrayList<>();
		while (!unclosedWays.isEmpty()) {
			JoinedWay joinWay = unclosedWays.remove(0);

			List<JoinedWay> candidates = index.get(joinWay.getLastPoint());
			if (candidates.size() != 2) {
				candidates = index.get(joinWay.getFirstPoint());
			}
			if (candidates.size() <= 1) {
				// cannot join further
				finishedUnclosed.add(joinWay);
				// no need to maintain index
				continue;
			}
			// we will join
			candidates.remove(joinWay);
			JoinedWay other = candidates.get(0);
			if (candidates.size() > 1) {
				// we have alternatives, prefer one that closes the ring. 
				other = candidates.stream().filter(joinWay::buildsRingWith).findFirst().orElse(other);
			}
			
			// maintain index, we don't know which node is removed by the joining
			index.removeMapping(other.getFirstPoint(), other);
			index.removeMapping(other.getLastPoint(), other);
			index.removeMapping(joinWay.getFirstPoint(), joinWay);
			index.removeMapping(joinWay.getLastPoint(), joinWay);

			unclosedWays.remove(other); // needs sequential search. Could set a flag in JoinedWay instead
			joinWay.joinWith(other);

			if (joinWay.hasIdenticalEndPoints()) {
				closedWays.add(joinWay);
			} else {
				index.add(joinWay.getFirstPoint(), joinWay);
				index.add(joinWay.getLastPoint(), joinWay);
				unclosedWays.add(0, joinWay);
			}
		}
		unclosedWays.addAll(finishedUnclosed);
	}

	/**
	 * Try to close unclosed way.
	 * 
	 * @param way the joined way
	 * 
	 */
	private void tryCloseSingleWays(JoinedWay way) {
		if (way.hasIdenticalEndPoints() || way.getPoints().size() < 3)
			return;
		
		Coord p1 = way.getFirstPoint();
		Coord p2 = way.getLastPoint();

		if ((p1.getLatitude() <= tileBounds.getMinLat() && p2.getLatitude() <= tileBounds.getMinLat())
				|| (p1.getLatitude() >= tileBounds.getMaxLat() && p2.getLatitude() >= tileBounds.getMaxLat())
				|| (p1.getLongitude() <= tileBounds.getMinLong() && p2.getLongitude() <= tileBounds.getMinLong())
				|| (p1.getLongitude() >= tileBounds.getMaxLong() && p2.getLongitude() >= tileBounds.getMaxLong())) {
			// both points lie outside the bbox or on the bbox and 
			// they are on the same side of the bbox
			// so just close them without worrying about if
			// they intersect itself because the intersection also
			// is outside the bbox
			way.closeWayArtificially();
			log.info("Endpoints of way", way, "are both outside the bbox. Closing it directly.");
			return;
		}
		
		// calc the distance to close
		double closeDist = way.getFirstPoint().distance(way.getLastPoint());
		if (closeDist > getMaxCloseDist()) 
			return;
			
		// We may get here with boundary preparer, for example when a country extract doesn't contain the 
		// complete data. It is assumed that the country border is very close to the cutting polygon that was
		// used for the country extract.
		Line2D closingLine = new Line2D.Double(p1.getHighPrecLon(), 
				p1.getHighPrecLat(), p2.getHighPrecLon(), p2.getHighPrecLat());

		boolean intersects = false;
		Coord lastPoint = null;
		// don't use the first and the last point
		// the closing line can intersect only in one point or complete.
		// Both isn't interesting for this check
		for (Coord thisPoint : way.getPoints().subList(1, way.getPoints().size() - 1)) {
			if (lastPoint != null && closingLine.intersectsLine(lastPoint.getHighPrecLon(), lastPoint.getHighPrecLat(),
					thisPoint.getHighPrecLon(), thisPoint.getHighPrecLat())) {
				intersects = true;
				break;
			}
			lastPoint = thisPoint;
		}

		if (!intersects) {
			// close the polygon
			// the new way segment does not intersect the rest of the polygon
			if (log.isInfoEnabled()) {
				log.info("Closing way", way);
				log.info("from", way.getFirstPoint().toOSMURL());
				log.info("to", way.getLastPoint().toOSMURL());
			}
			// mark this ways as artificially closed
			way.closeWayArtificially();
		}
	} 

	private static class ConnectionData {
		Coord c1;
		Coord c2;
		JoinedWay w1;
		JoinedWay w2;
		// sometimes the connection of both points cannot be done directly but with an intermediate point 
		Coord imC;
		double distance;
	}

	/**
	 * Try to connect pairs of ways to closed rings or a single way by adding a
	 * point outside of the tileBounds.
	 * 
	 * @param allWays     list of ways
	 * @param onlyOutside if true, only connect ways outside of the tileBounds
	 * @return true if anything was closed
	 */
	private boolean connectUnclosedWays(List<JoinedWay> allWays, boolean onlyOutside) {
		List<JoinedWay> unclosed = allWays.stream().filter(w->!w.hasEqualEndPoints()).collect(Collectors.toList());
		
		// try to connect ways lying outside or on the bbox
		if (!unclosed.isEmpty()) {
			log.debug("Checking", unclosed.size(), "unclosed ways for connections outside the bbox");
			Map<Coord, JoinedWay> openEnds = new IdentityHashMap<>();
			
			// check all ways for endpoints outside or on the bbox
			for (JoinedWay w : unclosed) {
				for (Coord e : Arrays.asList(w.getFirstPoint(), w.getLastPoint())) { 
					if (!onlyOutside) {
						openEnds.put(e, w);
					} else {
						if (!tileBounds.insideBoundary(e)) {
							log.debug("Point", e, "of way", w.getId(), "outside bbox");
							openEnds.put(e, w);
						}
					}
				}
			}

			if (openEnds.size() < 2) {
				log.debug(openEnds.size(), "point outside the bbox. No connection possible.");
				return false;
			}
			
			List<ConnectionData> coordPairs = new ArrayList<>();
			ArrayList<Coord> coords = new ArrayList<>(openEnds.keySet());
			for (int i = 0; i < coords.size(); i++) {
				for (int j = i + 1; j < coords.size(); j++) {
					ConnectionData cd = new ConnectionData();
					cd.c1 = coords.get(i);
					cd.c2 = coords.get(j);
					cd.w1 = openEnds.get(cd.c1);					
					cd.w2 = openEnds.get(cd.c2);
					
					if (!onlyOutside && cd.w1 == cd.w2) 
						continue; // was already tested in tryCloseSingleWays() 
					
					if (onlyOutside && lineCutsBbox(cd.c1, cd.c2)) {
						// Check if the way can be closed with one additional point
						// outside the bounding box.
						// The additional point is combination of the coords of both endpoints.
						// It works if the lines from the endpoints to the additional point does
						// not cut the bounding box.
						// This can be removed when the splitter guarantees to provide logical complete
						// multi-polygons.
						Coord edgePoint1 = new Coord(cd.c1.getLatitude(), cd.c2.getLongitude());
						Coord edgePoint2 = new Coord(cd.c2.getLatitude(), cd.c1.getLongitude());

						if (!lineCutsBbox(cd.c1, edgePoint1) && !lineCutsBbox(edgePoint1, cd.c2)) {
							cd.imC = edgePoint1;
						} else if (!lineCutsBbox(cd.c1, edgePoint2) && !lineCutsBbox(edgePoint2, cd.c2)) {
							cd.imC = edgePoint2;
						} else {
							// both endpoints are on opposite sides of the bounding box
							// automatically closing such points would create wrong polygons in most cases
							continue;
						}
						cd.distance = cd.c1.distance(cd.imC) + cd.imC.distance(cd.c2);
					} else {
						cd.distance = cd.c1.distance(cd.c2);
					}
					coordPairs.add(cd);
				}
			}

			if (coordPairs.isEmpty()) {
				log.debug("All potential connections cross the bbox. No connection possible.");
				return false;
			}
			// retrieve the connection with the minimum distance
			ConnectionData minCon = Collections.min(coordPairs, (o1, o2) -> Double.compare(o1.distance, o2.distance));

			if (onlyOutside || minCon.distance < getMaxCloseDist()) {
				if (minCon.w1 == minCon.w2) {
					log.debug("Close a gap in way", minCon.w1);
					if (minCon.imC != null)
						minCon.w1.getPoints().add(minCon.imC);
					minCon.w1.closeWayArtificially();
				} else {
					log.debug("Connect", minCon.w1, "with", minCon.w2);

					if (minCon.w1.getFirstPoint() == minCon.c1) {
						Collections.reverse(minCon.w1.getPoints());
					}
					if (minCon.w2.getFirstPoint() != minCon.c2) {
						Collections.reverse(minCon.w2.getPoints());
					}

					minCon.w1.getPoints().addAll(minCon.w2.getPoints());
					minCon.w1.addWay(minCon.w2);
					allWays.remove(minCon.w2);
				}
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Removes all non closed ways from the given list.
	 * <code>!{@link Way#hasIdenticalEndPoints()}</code>)
	 * 
	 * @param wayList
	 *            list of ways
	 */
	private void removeUnclosedWays(List<JoinedWay> wayList) {
		Iterator<JoinedWay> it = wayList.iterator();
		boolean firstWarn = true;
		while (it.hasNext()) {
			JoinedWay jw = it.next();
			if (!jw.hasIdenticalEndPoints()) {
				// warn only if the way bbox intersects the bounding box 
				if (jw.getArea().intersects(tileBounds)) {
					if (firstWarn) {
						log.warn(
							"Cannot join the following ways to closed polygons. Multipolygon",
							toBrowseURL(), toTagString());
						firstWarn = false;
					}
					logWayURLs(Level.WARNING, "- way:", jw);
					logFakeWayDetails(Level.WARNING, jw);
					String role = getRole(jw);
					if (role == null || ROLE_OUTER.equals(role)) {
						// anyhow add the ways to the list for line tagging
						outerWaysForLineTagging.addAll(jw.getOriginalWays());
					}
				}

				it.remove();
			}
		}
	}

	/**
	 * Removes all ways that are completely outside the bounding box. 
	 * This reduces error messages from problems on the tile bounds.
	 * @param wayList list of ways
	 */
	private void removeWaysOutsideBbox(List<JoinedWay> wayList) {
		ListIterator<JoinedWay> wayIter = wayList.listIterator();
		while (wayIter.hasNext()) {
			JoinedWay w = wayIter.next();
			
			if (isFullyOutsideBBox(w)) {
				if (log.isDebugEnabled()) {
					if (w.originalWays.size() == 1) {
						log.debug(this.getId(), ": Ignoring way", w.originalWays.get(0).getId(),
								"because it is completely outside the bounding box.");
					} else {
						log.debug(this.getId(), ": Ignoring joined ways",
								w.originalWays.stream().map(way -> Long.toString(way.getId()))
										.collect(Collectors.joining(",")),
								"because they are completely outside the bounding box.");
					}
				}
				wayIter.remove();
			}
		}
	}

	private boolean isFullyOutsideBBox(JoinedWay w) {
		if (!w.getBounds().intersects(tileArea.getBounds())) {
			return true;
		}
		
		// check if the polygon bbox contains the complete tile bounds
		if (w.getBounds().contains(tileArea.getBounds())) {
			return false;
		}
		
		// check if any point is inside tile bounds
		if (w.getPoints().stream().anyMatch(tileBounds::contains))
			return false;

		// check if any line segment of the polygon crosses the tile bounds
		for (int i = 0; i < w.getPoints().size() - 1; i++) {
			if (lineCutsBbox(w.getPoints().get(i), w.getPoints().get(i + 1))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Process the ways in this relation. Tries to join the ways to closed rings and
	 * detect inner/outer status and calls methods to process them.
	 */
	public final void processElements() {
		log.info("Processing multipolygon", toBrowseURL());
		long t1 = System.currentTimeMillis();
		
		// check if it makes sense to process the mp 
		if (!isUsable()) { 
			log.info("Do not process multipolygon", getId(), "because it has no style relevant tags.");
			return;
		}
		polygons = buildRings();
		if (polygons.isEmpty())
			return;
		
		// trigger setting area before start cutting...
		// do like this to disguise function with side effects
		polygons.forEach(jw -> jw.setFullArea(jw.getFullArea()));
		
		if (polygons.stream().allMatch(jw -> jw.intRole == INT_ROLE_INNER || jw.intRole == INT_ROLE_OTHER)) {
			log.warn("Multipolygon", toBrowseURL(),
				"does not contain any way tagged with role=outer or empty role.");
			cleanup();
			return;
		}
		
		//TODO: trunk uses more complex logic that also takes the inners into account
		largestOuterPolygon = getLargest(polygons);
		
		List<List<JoinedWay>> partitions = new ArrayList<>();
		if ("boundary".equals(getTag("type"))) {
			partitions.add(polygons);
		} else {	 
			divideLargest(polygons, partitions, 0);
		}
		for (List<JoinedWay> some : partitions) {
			processPartition(new Partition(some));
		}

		tagOuterWays();
		
		postProcessing();
		cleanup();
//		long dt = System.currentTimeMillis() - t1;
//		if (dt > 10000)
//			log.diagnostic("processing MP relation " + this + " took " + dt + " ms with " + partitions.size() + " partitions");
		
	}

	private List<JoinedWay> buildRings() {
		List<JoinedWay> polygons = joinWays();

		outerWaysForLineTagging = new HashSet<>();
		
		polygons = filterUnclosed(polygons);
		
		do {
			polygons.forEach(this::tryCloseSingleWays);
		} while (connectUnclosedWays(polygons, assumeDataInBoundsIsComplete()));

		removeUnclosedWays(polygons);

		// now only closed ways are left => polygons only

		// check if we have at least one polygon left
		boolean hasPolygons = !polygons.isEmpty();

		removeWaysOutsideBbox(polygons);

		if (polygons.isEmpty()) {
			// do nothing
			if (log.isInfoEnabled()) {
				log.info("Multipolygon", toBrowseURL(),
						hasPolygons ? "is completely outside the bounding box. It is not processed."
								: "does not contain a closed polygon.");
			}
			tagOuterWays();
			cleanup();
		}
		return polygons;
	}
	
	private static JoinedWay getLargest(List<JoinedWay> polygons) {
		double maxSize = -1;
		int maxPos = -1;
		for (int i = 0; i< polygons.size(); i++) {
			JoinedWay closed = polygons.get(i);
			double size = calcAreaSize(closed.getPoints());
			if (size > maxSize) {
				maxSize = size;
				maxPos = i;
			}
		}
		return polygons.get(maxPos);
	}


	/**
	 * Calculate the bounds of given collection of joined ways
	 * @param polygons list of polygons
	 * @return the bounds
	 */
	private static uk.me.parabola.imgfmt.app.Area calcBounds(Collection<JoinedWay> polygons) {
		int minLat = Integer.MAX_VALUE;
		int minLon = Integer.MAX_VALUE;
		int maxLat = Integer.MIN_VALUE;
		int maxLon = Integer.MIN_VALUE;
		for (JoinedWay jw : polygons) {
			if (jw.minLat < minLat)
				minLat = jw.minLat;
			if (jw.minLon < minLon)
				minLon = jw.minLon;
			if (jw.maxLat > maxLat)
				maxLat = jw.maxLat;
			if (jw.maxLon > maxLon)
				maxLon = jw.maxLon;
		}
		return new uk.me.parabola.imgfmt.app.Area(minLat, minLon, maxLat, maxLon);
	}


	void processPartition(Partition partition) {
		
		assert !partition.outerPolygons.isEmpty(): "no outer way in partition"; 

		Queue<PolygonStatus> polygonWorkingQueue = new LinkedBlockingQueue<>();
		
		polygonWorkingQueue.addAll(partition.getPolygonStatus(null));
		processQueue(partition, polygonWorkingQueue);

		if (doReporting() && log.isLoggable(Level.WARNING)) {
			partition.doReporting();
		}

	}

	protected boolean doReporting() {
		return true;
	}


	protected boolean isUsable() {
		// TODO: add a hook to filter unwanted MP 
		for (Map.Entry<String, String> tagEntry : this.getTagEntryIterator()) {
			String tagName = tagEntry.getKey();
			// all tags are style relevant
			// except: type and mkgmap:* 
			if (!"type".equals(tagName) && !tagName.startsWith("mkgmap:")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Should return true if extra ways with style filter {@code STYLE_FILTER_LINE}
	 * should be added to the {@code tileWayMap}. Overwrite if those ways are not needed. 
	 * @return true if extra ways with style filter {@code STYLE_FILTER_LINE}
	 * should be added to the {@code tileWayMap}
	 */
	protected boolean needsWaysForOutlines() {
		return true;
	}

	protected boolean assumeDataInBoundsIsComplete() {
		// we assume that data inside tile boundaries is complete
		return true; 
	}

	private void tagOuterWays() {
		if (outerWaysForLineTagging.isEmpty())
			return;
		
		final Way patternWayForLineCopies;
		if (needsWaysForOutlines()) {
			// create pattern way with tags for outline of this multipolygon
			patternWayForLineCopies = new Way(0);
			patternWayForLineCopies.copyTags(this);
			patternWayForLineCopies.deleteTag("type");
			patternWayForLineCopies.addTag(STYLE_FILTER_TAG, STYLE_FILTER_LINE);
			patternWayForLineCopies.addTag(TKM_MP_CREATED, "true");
			if (needsAreaSizeTag()) {
				patternWayForLineCopies.addTag(TKM_CACHE_AREA_SIZEKEY, getAreaSizeString());
			}
		} else { 
			patternWayForLineCopies = null;
		}
		
		// Go through all original outer ways, create a copy if wanted, tag them
		// with the mp tags and mark them only to be used for polyline processing
		// This enables the style file to decide if the polygon information or
		// the simple line information should be used.
		
		for (Way orgOuterWay : outerWaysForLineTagging) {
			if (patternWayForLineCopies != null) {
				Way lineTagWay =  new Way(getOriginalId(), orgOuterWay.getPoints());
				lineTagWay.markAsGeneratedFrom(this);
				lineTagWay.copyTags(patternWayForLineCopies);
				if (log.isDebugEnabled())
					log.debug("Add line way", lineTagWay.getId(), lineTagWay.toTagString());
				tileWayMap.put(lineTagWay.getId(), lineTagWay);
			}
			
			for (Entry<String, String> tag : this.getTagEntryIterator()) {
				// mark the tag for removal in the original way if it has the same value
				if (tag.getValue().equals(orgOuterWay.getTag(tag.getKey()))) {
					markTagsForRemovalInOrgWays(orgOuterWay, tag.getKey());
				}
			}
		}
	}

	/**
	 * Filter unclosed ways which have one or both end points outside of the tile bounds.
	 * @param polygons the list of ways to filter
	 * @return the original list if {@link allowCloseOutsideBBox} returns false, else the filtered list
	 */
	private List<JoinedWay> filterUnclosed(List<JoinedWay> polygons) {
		if (assumeDataInBoundsIsComplete())
			return polygons;
		return polygons.stream().filter(w -> {
			Coord first = w.getFirstPoint();
			Coord last = w.getLastPoint();
			return first == last || tileBounds.contains(first) && tileBounds.contains(last);
		}).collect(Collectors.toList());
	}

	/**
	 * The main routine to cut or split the rings of one partition containing a list of polygons
	 * @param partition the partition
	 * @param polygonWorkingQueue the queue that contains the initial outer rings
	 */
	protected void processQueue(Partition partition, Queue<PolygonStatus> polygonWorkingQueue) {
		
		while (!polygonWorkingQueue.isEmpty()) {

			// the polygon is not contained by any other unfinished polygon
			PolygonStatus currentPolygon = polygonWorkingQueue.poll();

			// this polygon is now processed and should not be used by any
			// further step
			partition.markFinished(currentPolygon);

			List<PolygonStatus> holes = partition.getPolygonStatus(currentPolygon);

			// these polygons must all be checked for holes
			polygonWorkingQueue.addAll(holes);

			if (currentPolygon.outer) {
				// add the original ways to the list of ways that get the line tags of the mp
				// the joined ways may be changed by the auto closing algorithm
				outerWaysForLineTagging.addAll(currentPolygon.polygon.getOriginalWays());
			}
			
			// check if the polygon is an outer polygon or 
			// if there are some holes
			boolean processPolygon = currentPolygon.outer || !holes.isEmpty();

			if (processPolygon) {
				List<Way> singularOuterPolygons;
				if (holes.isEmpty()) {
					Way w = new Way(currentPolygon.polygon.getId(), currentPolygon.polygon.getPoints());
					singularOuterPolygons = Collections.singletonList(w);
				} else {
					List<Way> innerWays = new ArrayList<>(holes.size());
					for (PolygonStatus polygonHoleStatus : holes) {
						innerWays.add(polygonHoleStatus.polygon);
					}

					MultiPolygonCutter cutter = new MultiPolygonCutter(this, tileArea, commonCoordMap);
					singularOuterPolygons = cutter.cutOutInnerPolygons(currentPolygon.polygon, innerWays);
					if (currentPolygon.outer) {
						singularOuterPolygons.forEach(s -> s.setMpRel(this));
					}
				}
				
				if (!singularOuterPolygons.isEmpty()) {
					// handle the tagging 
					if (currentPolygon.outer) {
						// use the tags of the multipolygon
						for (Way p : singularOuterPolygons) {
							// overwrite all tags
							p.copyTags(this);
							p.deleteTag("type");
						}
					} else {
						// we have a nested MP with one or more outer rings inside an inner ring 
						// use the tags of the original ways
						currentPolygon.polygon.mergeTagsFromOrgWays();
						for (Way p : singularOuterPolygons) {
							// overwrite all tags
							p.copyTags(currentPolygon.polygon);
						}
						// remove the current polygon tags in its original ways
						markTagsForRemovalInOrgWays(currentPolygon.polygon);
					}
				
					long fullArea = currentPolygon.polygon.getFullArea();
					for (Way mpWay : singularOuterPolygons) {
						// put the cut out polygons to the
						// final way map
						if (log.isDebugEnabled())
							log.debug(mpWay.getId(), mpWay.toTagString());
					
						mpWay.setFullArea(fullArea);
						// mark this polygons so that only polygon style rules are applied
						mpWay.addTag(STYLE_FILTER_TAG, STYLE_FILTER_POLYGON);
						mpWay.addTag(TKM_MP_CREATED, "true");
						
						if (currentPolygon.outer) {
							mpWay.addTag(TKM_MP_ROLE, ROLE_OUTER);
							if (needsAreaSizeTag())
								mpAreaSize += calcAreaSize(mpWay.getPoints());
						} else {
							mpWay.addTag(TKM_MP_ROLE, ROLE_INNER);
						}
						
						mpPolygons.put(mpWay.getId(), mpWay);
					}
				}
			}
		}
	}


	protected double getMaxCloseDist() {
		return -1; // overwritten in BoundaryRelation
	}

	private String getAreaSizeString() {
		return String.format(Locale.US, "%.3f", mpAreaSize); 
	}

	protected void postProcessing() {
		String mpAreaSizeStr = null;
		if (needsAreaSizeTag()) {
			// assign the area size of the whole multipolygon to all outer polygons
			mpAreaSizeStr = getAreaSizeString(); 
			addTag(TKM_CACHE_AREA_SIZEKEY, mpAreaSizeStr);
		}

		for (Way w : mpPolygons.values()) {
			String role = w.deleteTag(TKM_MP_ROLE); 
			if (mpAreaSizeStr != null && ROLE_OUTER.equals(role)) {
				w.addTag(TKM_CACHE_AREA_SIZEKEY, mpAreaSizeStr);
			}
		}
		// copy all polygons created by the multipolygon algorithm to the global way map
		tileWayMap.putAll(mpPolygons);
		
		if (cOfG == null && largestOuterPolygon != null) {
			// use the center of the largest polygon as reference point
			cOfG = largestOuterPolygon.getCofG();
		}
		// TODO: maybe keep the cOfg data from a label node? 
		if (largestOuterPolygon == null) 
			cOfG = null; 
	}
	
	protected void cleanup() {
		mpPolygons = null;
		tileArea = null;
		outerWaysForLineTagging = null;
		commonCoordMap = null;
	}

	/**
	 * Checks if polygon1 contains polygon2 without intersection. 
	 * 
	 * @param expectedOuter
	 *            a closed way
	 * @param expectedInner
	 *            a 2nd closed way
	 * @return true if polygon1 contains polygon2 without intersection.
	 */
	private static boolean calcContains(JoinedWay expectedOuter, JoinedWay expectedInner) {
		if (!expectedOuter.hasIdenticalEndPoints()) {
			return false;
		}
		// check if the bounds of polygon2 are completely inside/enclosed the bounds
		// of polygon1
		if (!expectedOuter.getBounds().contains(expectedInner.getBounds())) {
			return false;
		}
		
//		Coord test = expectedInner.getPointInside();
//		if (test != null) {
//			// we know that point test is inside expectedInner
//			int quick = IsInUtil.isPointInShape(test, expectedOuter.getPoints());
//			// if point is ON we can assume that a part of the inner is OUT
//			return quick == IsInUtil.IN;
//		}
		
		int x = IsInUtil.isLineInShape(expectedInner.getPoints(), expectedOuter.getPoints(), expectedInner.getArea());
		return (x & IsInUtil.OUT) == 0;
	}

	private boolean lineCutsBbox(Coord p1, Coord p2) {
		Coord nw = new Coord(tileBounds.getMaxLat(), tileBounds.getMinLong());
		Coord sw = new Coord(tileBounds.getMinLat(), tileBounds.getMinLong());
		Coord se = new Coord(tileBounds.getMinLat(), tileBounds.getMaxLong());
		Coord ne = new Coord(tileBounds.getMaxLat(), tileBounds.getMaxLong());
		return linesCutEachOther(nw, sw, p1, p2)
				|| linesCutEachOther(sw, se, p1, p2)
				|| linesCutEachOther(se, ne, p1, p2)
				|| linesCutEachOther(ne, nw, p1, p2);
	}

	/**
	 * XXX: This code presumes that certain tests were already done!
	 * Check if the line p1_1 to p1_2 cuts line p2_1 to p2_2 in two pieces and vice versa.
	 * This is a form of intersection check where it is allowed that one line ends on the
	 * other line or that the two lines overlap.
	 * @param p11 first point of line 1
	 * @param p12 second point of line 1
	 * @param p21 first point of line 2
	 * @param p22 second point of line 2
	 * @return true if both lines intersect somewhere in the middle of each other
	 */
	private static boolean linesCutEachOther(Coord p11, Coord p12, Coord p21, Coord p22) {
		long width1 = (long) p12.getHighPrecLon() - p11.getHighPrecLon();
		long width2 = (long) p22.getHighPrecLon() - p21.getHighPrecLon();

		long height1 = (long) p12.getHighPrecLat() - p11.getHighPrecLat();
		long height2 = (long) p22.getHighPrecLat() - p21.getHighPrecLat();

		long denominator = ((height2 * width1) - (width2 * height1));
		if (denominator == 0) {
			// the lines are parallel
			// they might overlap but this is ok for this test
			return false;
		}
		
		long x1Mx3 = (long) p11.getHighPrecLon() - p21.getHighPrecLon();
		long y1My3 = (long) p11.getHighPrecLat() - p21.getHighPrecLat();

		double isx = (double) ((width2 * y1My3) - (height2 * x1Mx3)) / denominator;
		if (isx <= 0 || isx >= 1) {
			return false;
		}
		
		double isy = (double) ((width1 * y1My3) - (height1 * x1Mx3)) / denominator;

		return (isy > 0 &&  isy < 1);
	}

	private static void logWayURLs(Level level, String preMsg, Way way) {
		if (log.isLoggable(level)) {
			if (way instanceof JoinedWay) {
				if (((JoinedWay) way).getOriginalWays().isEmpty()) {
					log.warn("Way", way, "does not contain any original ways");
				}
				for (Way segment : ((JoinedWay) way).getOriginalWays()) {
					if (preMsg == null || preMsg.length() == 0) {
						log.log(level, segment.toBrowseURL());
					} else {
						log.log(level, preMsg, segment.toBrowseURL());
					}
				}
			} else {
				if (preMsg == null || preMsg.length() == 0) {
					log.log(level, way.toBrowseURL());
				} else {
					log.log(level, preMsg, way.toBrowseURL());
				}
			}
		}
	}
	
	/**
	 * Logs the details of the original ways of a way with a fake id. This is
	 * primarily necessary for the sea multipolygon because it consists of 
	 * faked ways only. In this case logging messages can be improved by the
	 * start and end points of the faked ways.
	 * @param logLevel the logging level
	 * @param fakeWay a way composed by other ways with faked ids
	 */
	private void logFakeWayDetails(Level logLevel, JoinedWay fakeWay) {
		if (!log.isLoggable(logLevel)) {
			return;
		}
		
		// only log if this is an artificial multipolygon
		if (!FakeIdGenerator.isFakeId(getId())) {
			return;
		}
		
		if (fakeWay.getOriginalWays().stream().noneMatch(w -> FakeIdGenerator.isFakeId(w.getId())))
			return;
		
		// the fakeWay consists only of other faked ways
		// there should be more information about these ways
		// so that it is possible to retrieve the original
		// OSM ways
		// => log the start and end points
		
		for (Way orgWay : fakeWay.getOriginalWays()) {
			log.log(logLevel, "Way", orgWay.getId(), "is composed of other artificial ways. Details:");
			log.log(logLevel, " Start:", orgWay.getFirstPoint().toOSMURL());
			if (orgWay.hasEqualEndPoints()) {
				// the way is closed so start and end are equal - log the point in the middle of the way
				int mid = orgWay.getPoints().size()/2;
				log.log(logLevel, " Mid:  ", orgWay.getPoints().get(mid).toOSMURL());
			} else {
				log.log(logLevel, " End:  ", orgWay.getLastPoint().toOSMURL());
			}
		}		
	}

	/**
	 * Marks all tags of the original ways of the given JoinedWay for removal.
	 * 
	 * @param way a joined way
	 */
	private static void markTagsForRemovalInOrgWays(JoinedWay way) {
		for (Entry<String, String> tag : way.getTagEntryIterator()) {
			markTagForRemovalInOrgWays(way, tag.getKey(), tag.getValue());
		}
	}

	/**
	 * Mark the given tag for removal in all original ways of the given way.
	 * 
	 * @param way      a joined way
	 * @param tagKey   the tag to be removed
	 * @param tagvalue the value of the tag to be removed
	 */
	private static void markTagForRemovalInOrgWays(JoinedWay way, String tagKey, String tagvalue) {
		for (Way w : way.getOriginalWays()) {
			if (w instanceof JoinedWay) {
				// remove the tag recursively
				markTagForRemovalInOrgWays((JoinedWay) w, tagKey, tagvalue);
			} else if (tagvalue.equals(w.getTag(tagKey))) {
				markTagsForRemovalInOrgWays(w, tagKey);
			}
		}
	}
	
	/**
	 * Add given tag key to the special tag which contains the list of tag keys
	 * which are to be removed in MultiPolygonFinishHook.
	 * 
	 * @param way    the way
	 * @param tagKey the tag key
	 */
	private static void markTagsForRemovalInOrgWays(Way way, String tagKey) {
		if (tagKey == null || tagKey.isEmpty()) {
			return; // should not happen
		}

		String tagsToRemove = way.getTag(ElementSaver.TKM_REMOVETAGS);
		
		if (tagsToRemove == null) {
			tagsToRemove = tagKey;
		} else if (tagKey.equals(tagsToRemove)) {
			return;
		} else {
			String[] keys = tagsToRemove.split(";");
			if (Arrays.asList(keys).contains(tagKey)) {
				return;
			}
			tagsToRemove += ";" + tagKey;
		} 
		if (log.isDebugEnabled()) {
			log.debug("Will remove", tagKey + "=" + way.getTag(tagKey), "from way", way.getId(), way.toTagString());
		}
		way.addTag(ElementSaver.TKM_REMOVETAGS, tagsToRemove);
	}
	
	/**
	 * Flag if the area size of the mp should be calculated and added as tag.
	 * @return {@code true} area size should be calculated; {@code false} area size should not be calculated
	 */
	protected boolean needsAreaSizeTag() {
		return true;
	}

	protected Map<Long, Way> getTileWayMap() {
		return tileWayMap;
	}

	protected Map<Long, Way> getMpPolygons() {
		return mpPolygons;
	}

	protected uk.me.parabola.imgfmt.app.Area getTileBounds() {
		return tileBounds;
	}
	
	/**
	 * Calculates a unitless number that gives a value for the size
	 * of the area. The calculation does not correct to any earth 
	 * coordinate system. It uses the simple rectangular coordinate
	 * system of garmin coordinates. 
	 * 
	 * @param polygon the points of the area
	 * @return the size of the area (unitless)
	 */
	public static double calcAreaSize(List<Coord> polygon) {
		if (polygon.size() < 4 || polygon.get(0) != polygon.get(polygon.size() - 1)) {
			return 0; // line or not closed
		}
		long area = 0;
		Iterator<Coord> polyIter = polygon.iterator();
		Coord c2 = polyIter.next();
		while (polyIter.hasNext()) {
			Coord c1 = c2;
			c2 = polyIter.next();
			area += (long) (c2.getHighPrecLon() + c1.getHighPrecLon())
					* (c1.getHighPrecLat() - c2.getHighPrecLat());
		}
		//  convert from high prec to value in map units
		double areaSize = (double) area / (2 * (1 << Coord.DELTA_SHIFT) * (1 << Coord.DELTA_SHIFT));  
		return Math.abs(areaSize);
	}

	/**
	 * This is a helper class that gives access to the original
	 * segments of a joined way or a string of ways. It may be unclosed.
	 */
	public static final class JoinedWay extends Way {
		private final List<Way> originalWays;
		private byte intRole;  
		private boolean closedArtificially;
		private Coord pointInside;
		private boolean doPointInsideCalcs = true;

		private int minLat;
		private int maxLat;
		private int minLon;
		private int maxLon;
		private Rectangle bounds;
		private uk.me.parabola.imgfmt.app.Area area;

		public JoinedWay(Way originalWay, String givenRole) {
			super(originalWay.getOriginalId(), originalWay.getPoints());
			markAsGeneratedFrom(originalWay);
			originalWays = new ArrayList<>();
			addWay(originalWay, roleToInt(givenRole));

			// we have to initialize the min/max values
			Coord c0 = originalWay.getFirstPoint();
			minLat = maxLat = c0.getLatitude();
			minLon = maxLon = c0.getLongitude();

			updateBounds(originalWay.getPoints());
		}

		public JoinedWay(JoinedWay other, List<Coord> points) {
			super(other.getOriginalId(), points);
			markAsGeneratedFrom(other);
			originalWays = new ArrayList<>(other.getOriginalWays());
			intRole = other.intRole;
			closedArtificially = other.closedArtificially;
			// we have to initialize the min/max values
			Coord c0 = points.get(0);
			minLat = maxLat = c0.getLatitude();
			minLon = maxLon = c0.getLongitude();

			updateBounds(points);
			
		}

		private byte roleToInt(String role) {
			if (role == null)
				return INT_ROLE_NULL;
			switch (role) {
			case ROLE_INNER:
				return INT_ROLE_INNER;
			case ROLE_OUTER:
				return INT_ROLE_OUTER;
			case "":
				return INT_ROLE_BLANK;
			default:
				return INT_ROLE_OTHER;
			}
		}

		public void addPoint(int index, Coord point) {
			getPoints().add(index, point);
			updateBounds(point);
		}

		@Override
		public void addPoint(Coord point) {
			super.addPoint(point);
			updateBounds(point);
		}

		private void updateBounds(List<Coord> pointList) {
			for (Coord c : pointList) {
				updateBounds(c.getLatitude(), c.getLongitude());
			}
		}

		private void updateBounds(JoinedWay other) {
			updateBounds(other.minLat,other.minLon);
			updateBounds(other.maxLat,other.maxLon);
		}

		private void updateBounds(int lat, int lon) {
			if (lat < minLat) {
				minLat = lat;
				bounds = null;
			} else if (lat > maxLat) {
				maxLat = lat;
				bounds = null;
			}

			if (lon < minLon) {
				minLon = lon;
				bounds = null;
			} else if (lon > maxLon) {
				maxLon = lon;
				bounds = null;
			}
		}
		
		private void updateBounds(Coord point) {
			updateBounds(point.getLatitude(), point.getLongitude());
		}
		
		public Rectangle getBounds() {
			if (bounds == null) {
				// note that we increase the rectangle by 1 because intersects
				// checks
				// only the interior
				bounds = new Rectangle(minLon - 1, minLat - 1, maxLon - minLon
						+ 2, maxLat - minLat + 2);
			}

			return bounds;
		}

		public uk.me.parabola.imgfmt.app.Area getArea() {
			if (area == null) {
				area = new uk.me.parabola.imgfmt.app.Area(minLat, minLon, maxLat, maxLon);
			}

			return area;
		}

		public void addWay(Way way, int internalRole) {
			if (way instanceof JoinedWay) {
				originalWays.addAll(((JoinedWay) way).getOriginalWays());
				this.intRole |= ((JoinedWay) way).intRole;
				updateBounds((JoinedWay) way);
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Joined", this.getId(), "with", way.getId());
				}
				this.originalWays.add(way);
				this.intRole |= internalRole;
			}
		}

		public void addWay(JoinedWay way) {
			originalWays.addAll(way.originalWays);
			this.intRole |= way.intRole;
			updateBounds(way);
		}

		public void closeWayArtificially() {
			addPoint(getPoints().get(0));
			closedArtificially = true;
		}

		public boolean isClosedArtificially() {
			return closedArtificially;
		}

		/**
		 * Get common tags of all ways.
		 * @param ways the collection of ways
		 * @return map with common tags, might be empty but will never be null
		 */
		public static Map<String, String> getMergedTags(Collection<Way> ways) {
			Map<String, String> mergedTags = new HashMap<>();
			boolean first = true;
			for (Way way : ways) {
				if (first) {
					// the tags of the first way are copied completely 
					for (Map.Entry<String, String> tag : way.getTagEntryIterator()) {
						mergedTags.put(tag.getKey(), tag.getValue());
					}
					first = false;
				} else {
					if (mergedTags.isEmpty()) {
						break;
					}
					// remove tags with different value
					mergedTags.entrySet().removeIf(tag -> {
						String wayTagValue = way.getTag(tag.getKey());
						return (wayTagValue != null && !tag.getValue().equals(wayTagValue));
					});
				}
			}
			return mergedTags;
		}
		
		/**
		 * Tags this way with a merge of the tags of all original ways.
		 */
		public void mergeTagsFromOrgWays() {
			if (log.isDebugEnabled()) {
				log.debug("Way", getId(), "merge tags from", getOriginalWays().size(), "ways");
			}
			removeAllTags();
			
			Map<String, String> mergedTags = getMergedTags(getOriginalWays());
			mergedTags.forEach(this::addTag);
		}

		public List<Way> getOriginalWays() {
			return originalWays;
		}
		
		@Override
		public String toString() {
			final String prefix = getId() + "(" + getPoints().size() + "P)(";
			return getOriginalWays().stream().map(w -> w.getId() + "[" + w.getPoints().size() + "P]")
					.collect(Collectors.joining(",", prefix, ")"));
		}

		public boolean canJoin(JoinedWay other) {
			return getFirstPoint() == other.getFirstPoint() || getFirstPoint() == other.getLastPoint()
					|| getLastPoint() == other.getFirstPoint() || getLastPoint() == other.getLastPoint();
		}

		public boolean buildsRingWith(JoinedWay other) {
			return getFirstPoint() == other.getFirstPoint() && getLastPoint() == other.getLastPoint()
					|| getFirstPoint() == other.getLastPoint() && getLastPoint() == other.getFirstPoint();
		}
		
		/**
		 * Join the other way.
		 * 
		 * @param other     the way to be added to this 
		 * @throws ExitException if ways cannot be joined 
		 */
		private void joinWith(JoinedWay other) {
			boolean reverseOther = false;
			int insIdx = -1;
			int firstOtherIdx = 1;
			
			if (this.getFirstPoint() == other.getFirstPoint()) {
				insIdx = 0;
				reverseOther = true;
				firstOtherIdx = 1;
			} else if (this.getLastPoint() == other.getFirstPoint()) {
				insIdx = this.getPoints().size();
				firstOtherIdx = 1;
			} else if (this.getFirstPoint() == other.getLastPoint()) {
				insIdx = 0; 
				firstOtherIdx = 0;
			} else if (this.getLastPoint() == other.getLastPoint()) {
				insIdx = this.getPoints().size();
				reverseOther = true;
				firstOtherIdx = 0;
			} else {
				String msg = "Cannot join " + this.getBasicLogInformation() + " with " + other.getBasicLogInformation();
				log.error(msg);
				throw new ExitException(msg);
			}
			
			int lastIdx = other.getPoints().size();
			if (firstOtherIdx == 0) {
				// the last temp point is already contained in the joined way - do not copy it
				lastIdx--;
			}

			List<Coord> tempCoords = other.getPoints().subList(firstOtherIdx,lastIdx);

			if (reverseOther) {
				// the temp coords need to be reversed so copy the list
				tempCoords = new ArrayList<>(tempCoords);
				// and reverse it
				Collections.reverse(tempCoords);
			}

			this.getPoints().addAll(insIdx, tempCoords);
			this.addWay(other);
		}

		/**
		 * Try to find a point that is inside the polygon and store the result.
		 * 
		 * @return null or a point that is inside
		 */
		public Coord getPointInside() {
			if (doPointInsideCalcs) {
				// TODO: other faster alternatives to find point inside shape
				doPointInsideCalcs = false;
				Coord test = super.getCofG();
				if (IsInUtil.isPointInShape(test, getPoints()) == IsInUtil.IN) {
					pointInside = test;
				}
			}
			return pointInside;
		}
	}

	protected static class PolygonStatus {
		public final boolean outer;
		public final int index;
		public final JoinedWay polygon;

		public PolygonStatus(boolean outer, int index, JoinedWay polygon) {
			this.outer = outer;
			this.index = index;
			this.polygon = polygon;
		}
		
		public String toString() {
			return polygon + "_" + outer;
		}
	}

	/**
	 * Helper class to bundle objects related to a list of polygons
	 *
	 */
	protected class Partition {
		/** list of polygons with a fixed order */
		final List<JoinedWay> polygons; 

		final List<BitSet> containsMatrix;
		// Various BitSets which relate to the content of field polygons  
		/** unfinishedPolygons marks which polygons are not yet processed */
		public final BitSet unfinishedPolygons;

		// reporting: BitSets which polygons belong to the outer and to the inner role
		final BitSet innerPolygons;
		final BitSet taggedInnerPolygons;
		final BitSet outerPolygons;
		final BitSet taggedOuterPolygons;
		final BitSet nestedOuterPolygons;
		final BitSet nestedInnerPolygons;
		final BitSet outmostInnerPolygons;
		
		public Partition(List<JoinedWay> list) {
			this.polygons = Collections.unmodifiableList(list);
			innerPolygons = new BitSet(list.size());
			taggedInnerPolygons = new BitSet(list.size());
			outerPolygons = new BitSet(list.size());
			taggedOuterPolygons = new BitSet(list.size());
			analyseRelationRoles();
			// unfinishedPolygons marks which polygons are not yet processed
			unfinishedPolygons = new BitSet(list.size());
			unfinishedPolygons.set(0, list.size());
			// check which polygons lie inside which other polygon
			containsMatrix = createContainsMatrix(list);
			nestedOuterPolygons = new BitSet(list.size());
			nestedInnerPolygons = new BitSet(list.size());
			outmostInnerPolygons = new BitSet(list.size());
		}
		
		public void markFinished(PolygonStatus currentPolygon) {
			unfinishedPolygons.clear(currentPolygon.index);
		}

		/**
		 * Creates a matrix which polygon contains which polygon. A polygon does not
		 * contain itself.
		 * @return 
		 */
		private List<BitSet> createContainsMatrix(List<JoinedWay> polygons) {
			List<BitSet> matrix = new ArrayList<>();
			for (int i = 0; i < polygons.size(); i++) {
				matrix.add(new BitSet());
			}

			long t1 = System.currentTimeMillis();

			if (log.isDebugEnabled())
				log.debug("createContainsMatrix listSize:", polygons.size());

			// use this matrix to check which matrix element has been
			// calculated
			ArrayList<BitSet> finishedMatrix = new ArrayList<>(polygons.size());

			for (int i = 0; i < polygons.size(); i++) {
				BitSet matrixRow = new BitSet();
				// a polygon does not contain itself
				matrixRow.set(i);
				finishedMatrix.add(matrixRow);
			}
			
			for (int rowIndex = 0; rowIndex < polygons.size(); rowIndex++) {
				JoinedWay potentialOuterPolygon = polygons.get(rowIndex);
				BitSet containsColumns = matrix.get(rowIndex);
				BitSet finishedCol = finishedMatrix.get(rowIndex);

				// get all non calculated columns of the matrix
				for (int colIndex = finishedCol.nextClearBit(0); colIndex >= 0
						&& colIndex < polygons.size(); colIndex = finishedCol
						.nextClearBit(colIndex + 1)) {

					JoinedWay innerPolygon = polygons.get(colIndex);

					if (potentialOuterPolygon.getBounds().intersects(innerPolygon.getBounds())) {
						boolean contains = calcContains(potentialOuterPolygon, innerPolygon);
						if (contains) {
							containsColumns.set(colIndex);

							// we also know that the inner polygon does not contain the
							// outer polygon
							// so we can set the finished bit for this matrix
							// element
							finishedMatrix.get(colIndex).set(rowIndex);

							// additionally we know that the outer polygon contains all
							// polygons that are contained by the inner polygon
							containsColumns.or(matrix.get(colIndex));
							finishedCol.or(containsColumns);
						}
					} else {
						// both polygons do not intersect
						// we can flag both matrix elements as finished
						finishedMatrix.get(colIndex).set(rowIndex);
						finishedMatrix.get(rowIndex).set(colIndex);
					}
					// this matrix element is calculated now
					finishedCol.set(colIndex);
				}
			}

			if (log.isDebugEnabled()) {
				long t2 = System.currentTimeMillis();
				log.debug("createMatrix for", polygons.size(), "polygons took", (t2 - t1), "ms");

				log.debug("Containsmatrix:");
				int i = 0;
				boolean noContained = true;
				for (BitSet b : matrix) {
					if (!b.isEmpty()) {
						log.debug(i, "contains", b);
						noContained = false;
					}
					i++;
				}
				if (noContained) {
					log.debug("Matrix is empty");
				}
			}
			return matrix;
		}

		
		/**
		 * 
		 * @return
		 */
		private BitSet getOutmostRingsAndMatchWithRoles() {
			BitSet outmostPolygons;
			boolean outmostInnerFound;
			do {
				outmostInnerFound = false;
				outmostPolygons = findOutmostPolygons(unfinishedPolygons);

				if (outmostPolygons.intersects(taggedInnerPolygons)) {
					// found outmost ring(s) with role inner
					outmostInnerPolygons.or(outmostPolygons);
					outmostInnerPolygons.and(taggedInnerPolygons);

					if (log.isDebugEnabled())
						log.debug("wrong inner polygons: " + outmostInnerPolygons);
					// do not process polygons tagged with role=inner but which are
					// not contained by any other polygon
					unfinishedPolygons.andNot(outmostInnerPolygons);
					outmostPolygons.andNot(outmostInnerPolygons);
					outmostInnerFound = true;
				}
			} while (outmostInnerFound);
			return outmostPolygons;
		}

		/**
		 * Analyse roles in ways and fill corresponding sets.
		 */
		private void analyseRelationRoles() {
			for (int i = 0; i < polygons.size(); i++) {
				JoinedWay jw = polygons.get(i);
				if (jw.intRole == INT_ROLE_INNER) {
					innerPolygons.set(i);
					taggedInnerPolygons.set(i);
				} else if (jw.intRole == INT_ROLE_OUTER) {
					outerPolygons.set(i);
					taggedOuterPolygons.set(i);
				} else {
					// unknown role => it could be both
					innerPolygons.set(i);
					outerPolygons.set(i);
				}
			}
		}

		/**
		 * TODO: either remove this or combine the data for all partitions
		 */
		public void doReporting() {
			if (outmostInnerPolygons.cardinality() + unfinishedPolygons.cardinality()
					+ nestedOuterPolygons.cardinality() + nestedInnerPolygons.cardinality() >= 1) {
				log.warn("Multipolygon", toBrowseURL(), toTagString(), "contains errors.");

				BitSet outerUnusedPolys = new BitSet();
				outerUnusedPolys.or(unfinishedPolygons);
				outerUnusedPolys.or(outmostInnerPolygons);
				outerUnusedPolys.or(nestedOuterPolygons);
				outerUnusedPolys.or(nestedInnerPolygons);
				outerUnusedPolys.or(unfinishedPolygons);
				// use only the outer polygons
				outerUnusedPolys.and(outerPolygons);
				for (JoinedWay w : bitsetToList(outerUnusedPolys)) {
					//TODO: How do we get here?
					outerWaysForLineTagging.addAll(w.getOriginalWays());
				}

				runOutmostInnerPolygonCheck(polygons, outmostInnerPolygons);
				runNestedOuterPolygonCheck(polygons, nestedOuterPolygons);
				runNestedInnerPolygonCheck(polygons, nestedInnerPolygons);
				runWrongInnerPolygonCheck(polygons, unfinishedPolygons, innerPolygons);

				// we have at least one polygon that could not be processed
				// Probably we have intersecting or overlapping polygons
				// one possible reason is if the relation overlaps the tile
				// bounds
				// => issue a warning
				List<JoinedWay> lostWays = bitsetToList(unfinishedPolygons);
				for (JoinedWay w : lostWays) {
					log.warn("Polygon", w, "is not processed due to an unknown reason.");
					logWayURLs(Level.WARNING, "-", w);
				}
			}
		}

		private List<JoinedWay> bitsetToList(BitSet selection) {
			return selection.stream().mapToObj(polygons::get).collect(Collectors.toList());
		}

		private void runNestedOuterPolygonCheck(List<JoinedWay> polygons, BitSet nestedOuterPolygons) {
			// just print out warnings
			// the check has been done before
			nestedOuterPolygons.stream().forEach(idx ->  {
				JoinedWay outerWay = polygons.get(idx);
				log.warn("Polygon",	outerWay, "carries role outer but lies inside an outer polygon. Potentially its role should be inner.");
				logFakeWayDetails(Level.WARNING, outerWay);
			});
		}
		
		private void runNestedInnerPolygonCheck(List<JoinedWay> polygons, BitSet nestedInnerPolygons) {
			// just print out warnings
			// the check has been done before
			nestedInnerPolygons.stream().forEach(idx -> {
				JoinedWay innerWay = polygons.get(idx);
				log.warn("Polygon",	innerWay, "carries role", getRole(innerWay), "but lies inside an inner polygon. Potentially its role should be outer.");
				logFakeWayDetails(Level.WARNING, innerWay);
			});
		}	
		
		private void runOutmostInnerPolygonCheck(List<JoinedWay> polygons, BitSet outmostInnerPolygons) {
			// just print out warnings
			// the check has been done before
			outmostInnerPolygons.stream().forEach(idx -> {
				JoinedWay innerWay = polygons.get(idx);
				log.warn("Polygon",	innerWay, "carries role", getRole(innerWay), "but is not inside any other polygon. Potentially it does not belong to this multipolygon.");
				logFakeWayDetails(Level.WARNING, innerWay);
			});
		}

		private void runWrongInnerPolygonCheck(List<JoinedWay> polygons, BitSet unfinishedPolygons, BitSet innerPolygons) {
			// find all unfinished inner polygons that are not contained by any
			BitSet wrongInnerPolygons = findOutmostPolygons(unfinishedPolygons, innerPolygons);
			if (log.isDebugEnabled()) {
				log.debug("unfinished", unfinishedPolygons);
				log.debug(ROLE_INNER, innerPolygons);
				// other polygon
				log.debug("wrong", wrongInnerPolygons);
			}
			if (!wrongInnerPolygons.isEmpty()) {
				// we have an inner polygon that is not contained by any outer polygon
				// check if
				wrongInnerPolygons.stream().forEach(wiIndex -> {
					BitSet containedPolygons = new BitSet();
					containedPolygons.or(unfinishedPolygons);
					containedPolygons.and(containsMatrix.get(wiIndex));

					JoinedWay innerWay = polygons.get(wiIndex);
					if (containedPolygons.isEmpty()) {
						log.warn("Polygon",	innerWay, "carries role", getRole(innerWay),
							"but is not inside any outer polygon. Potentially it does not belong to this multipolygon.");
						logFakeWayDetails(Level.WARNING, innerWay);
					} else {
						log.warn("Polygon",	innerWay, "carries role", getRole(innerWay),
							"but is not inside any outer polygon. Potentially the roles are interchanged with the following",
							(containedPolygons.cardinality() > 1 ? "ways" : "way"), ".");
						containedPolygons.stream().forEach(wrIndex -> {
							logWayURLs(Level.WARNING, "-", polygons.get(wrIndex));
							unfinishedPolygons.set(wrIndex);
							wrongInnerPolygons.set(wrIndex);
						});
						logFakeWayDetails(Level.WARNING, innerWay);
					}

					unfinishedPolygons.clear(wiIndex);
					wrongInnerPolygons.clear(wiIndex);
				});
			}
		}

		/**
		 * Checks if the polygon with polygonIndex1 contains the polygon with polygonIndex2.
		 * 
		 * @return true if polygon(polygonIndex1) contains polygon(polygonIndex2)
		 */
		private boolean contains(int polygonIndex1, int polygonIndex2) {
			return containsMatrix.get(polygonIndex1).get(polygonIndex2);
		}

		/**
		 * Find all polygons that are not contained by any other polygon.
		 * 
		 * @param candidates
		 *            all polygons that should be checked
		 * @param roleFilter
		 *            an additional filter
		 * @return all polygon indexes that are not contained by any other polygon
		 */
		private BitSet findOutmostPolygons(BitSet candidates, BitSet roleFilter) {
			BitSet realCandidates = ((BitSet) candidates.clone());
			realCandidates.and(roleFilter);
			return findOutmostPolygons(realCandidates);
		}

		/**
		 * Finds all polygons that are not contained by any other polygons and that
		 * match the given role. All polygons with index given by
		 * <var>candidates</var> are tested.
		 * 
		 * @param candidates indexes of the polygons that should be used
		 * @return set of indexes of all outermost polygons 
		 */
		private BitSet findOutmostPolygons(BitSet candidates) {
			BitSet outmostPolygons = new BitSet();

			// go through all candidates and check if they are contained by any
			// other candidate
			candidates.stream().forEach(candidateIndex -> {
				// check if the candidateIndex polygon is not contained by any
				// other candidate polygon
				boolean isOutmost = candidates.stream()
						.noneMatch(otherCandidateIndex -> contains(otherCandidateIndex, candidateIndex));
				if (isOutmost) {
					// this is an outermost polygon
					// put it to the bitset
					outmostPolygons.set(candidateIndex);
				}
			});

			return outmostPolygons;
		}

		public List<PolygonStatus> getPolygonStatus(PolygonStatus currentPolygon) {
			ArrayList<PolygonStatus> polygonStatusList = new ArrayList<>();
			BitSet outmostPolygons;
			final String defaultRole;
			if (currentPolygon == null) {
				outmostPolygons = getOutmostRingsAndMatchWithRoles();
				defaultRole = ROLE_OUTER;
			} else {
				outmostPolygons = checkRoleAgainstGeometry(currentPolygon);
				defaultRole = currentPolygon.outer ? ROLE_INNER : ROLE_OUTER;
			}
			outmostPolygons.stream().forEach(polyIndex -> {
				// polyIndex is the polygon that is not contained by any other
				// polygon
				JoinedWay polygon = polygons.get(polyIndex);
				String role = getRole(polygon);
				// if the role is not explicitly set use the default role
				if (role == null || "".equals(role)) {
					role = defaultRole;
				} 
				polygonStatusList.add(new PolygonStatus(ROLE_OUTER.equals(role), polyIndex, polygon));
			});
			
			// sort by role and then by number of points, this improves performance
			// in the routines which add the polygons to areas
			if (polygonStatusList.size() > 2) {
				polygonStatusList.sort((o1, o2) -> {
					if (o1.outer != o2.outer)
						return (o1.outer) ? -1 : 1;
					return o1.polygon.getPoints().size() - o2.polygon.getPoints().size();
				});
			}
			return polygonStatusList;
		}

		/**
		 * Check the roles of polygons against the actual findings in containsMatrix. Not sure what this does so far.
		 * @param currentPolygon the current polygon
		 * @return set of polygon indexes which are considered as holes of the current polygon  
		 */
		public BitSet checkRoleAgainstGeometry(PolygonStatus currentPolygon) {
			BitSet polygonContains = new BitSet();
			polygonContains.or(containsMatrix.get(currentPolygon.index));
			// use only polygon that are contained by the polygon
			polygonContains.and(unfinishedPolygons);
			// polygonContains is the intersection of the unfinished and
			// the contained polygons

			// get the holes
			// these are all polygons that are in the current polygon
			// and that are not contained by any other polygon
			boolean holesOk;
			BitSet holeIndexes;
			do {
				holeIndexes = findOutmostPolygons(polygonContains);
				holesOk = true;

				if (currentPolygon.outer) {
					// for role=outer only role=inner is allowed
					if (holeIndexes.intersects(taggedOuterPolygons)) {
						BitSet addOuterNestedPolygons = new BitSet();
						addOuterNestedPolygons.or(holeIndexes);
						addOuterNestedPolygons.and(taggedOuterPolygons);
						nestedOuterPolygons.or(addOuterNestedPolygons);
						holeIndexes.andNot(addOuterNestedPolygons);
						// do not process them
						unfinishedPolygons.andNot(addOuterNestedPolygons);
						polygonContains.andNot(addOuterNestedPolygons);
						
						// recalculate the holes again to get all inner polygons 
						// in the nested outer polygons
						holesOk = false;
					}
				} else {
					// for role=inner both role=inner and role=outer is supported
					// although inner in inner is not officially allowed
					if (holeIndexes.intersects(taggedInnerPolygons)) {
						// process inner in inner but issue a warning later
						BitSet addInnerNestedPolygons = new BitSet();
						addInnerNestedPolygons.or(holeIndexes);
						addInnerNestedPolygons.and(taggedInnerPolygons);
						nestedInnerPolygons.or(addInnerNestedPolygons);
					}
				}
			} while (!holesOk);
			return holeIndexes;
		}
	}
	
	private void divideLargest(List<JoinedWay> partition, List<List<JoinedWay>> partitions, int depth) {
		if (partition.isEmpty())
			return;
		//TODO: find out in what case dividing will produce false results in calcContains
		// probably complex polygons with crossing /self intersecting ways will be problematic 
		if (depth >= 10 || partition.size() < 2 || tagIsLikeYes("expect-self-intersection")) {
			partitions.add(partition);
			return;
		}
		JoinedWay mostComplex = partition.get(0);
		for (JoinedWay jw : partition) {
			if (mostComplex.getPoints().size() < jw.getPoints().size())
				mostComplex = jw;
		}
		if (mostComplex.getPoints().size() > 2000) {
			uk.me.parabola.imgfmt.app.Area fullArea = calcBounds(partition);
			uk.me.parabola.imgfmt.app.Area[] areas;
			final int niceSplitShift = 11; 
			if (fullArea.getHeight() > fullArea.getWidth())
				areas = fullArea.split(1, 2, niceSplitShift);
			else 
				areas = fullArea.split(2, 1, niceSplitShift);
			// code to calculate dividingLine taken from MapSplitter
			if (areas != null && areas.length == 2) {
				int dividingLine = 0;
				boolean isLongitude = false;
				boolean commonLine = true;
				if (areas[0].getMaxLat() == areas[1].getMinLat()) {
					dividingLine = areas[0].getMaxLat();
				} else if (areas[0].getMaxLong() == areas[1].getMinLong()) {
					dividingLine = areas[0].getMaxLong();
					isLongitude = true;
				} else {
					commonLine = false;
					log.error("Split into 2 expects shared edge between the areas");
				}
				if (commonLine) {
					List<JoinedWay> dividedLess = new ArrayList<>();
					List<JoinedWay> dividedMore = new ArrayList<>();
					for (int i = 0; i < partition.size(); i++) {
						JoinedWay jw = partition.get(i);
						
						List<List<Coord>> lessList = new ArrayList<>();
						List<List<Coord>> moreList = new ArrayList<>();
						ShapeSplitter.splitShape(jw.getPoints(), dividingLine << Coord.DELTA_SHIFT, isLongitude,
								lessList, moreList, commonCoordMap);
						
						lessList.forEach(part -> dividedLess.add(new JoinedWay(jw, part)));
						moreList.forEach(part -> dividedMore.add(new JoinedWay(jw, part)));
					}
					divideLargest(dividedLess, partitions, depth + 1);
					divideLargest(dividedMore, partitions, depth + 1);
					return;
				}
			}
		} 
		partitions.add(partition);
	}


	public Way getLargestOuterRing() {
		return largestOuterPolygon;
	}
	
	public List<JoinedWay> getRings() {
		if (polygons == null) {
			polygons = buildRings();
			cleanup();
		}
		return polygons;
	}


	public void setNoRecalc(boolean b) {
		this.noRecalc = b;
	}

	public boolean isNoRecalc() {
		return noRecalc;
	}


}
