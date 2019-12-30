/*
 * Copyright (C) 2012
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.osmstyle.housenumber.ExtNumbers;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.MultiHashMap;

/**
 * Cuts link ways into parts and adds destination/exit hints to a new part so
 * that the Garmin is able to display a valid destination.
 * @author Gerd Petermann
 * @author WanMil (initial versions)
 */
public class LinkDestinationHook implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(LinkDestinationHook.class);

	private ElementSaver saver;

	/** Maps which ways can be driven from a given Coord */
	private IdentityHashMap<Coord, Set<Way>> adjacentWays = new IdentityHashMap<>();
	/** Contains all _link ways that have to be processed */
	private LinkedHashSet<Way> destinationLinkWays = new LinkedHashSet<>();
	
	private static final Set<String> highwayTypes = new LinkedHashSet<>(Arrays.asList(
			"motorway", "trunk", "primary", "secondary", "tertiary", 
			"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"));
	private static final Set<String> linkTypes = new LinkedHashSet<>(Arrays.asList(
			"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"));

	/** Map way ids to its restriction relations so that the relations can easily be updated when the way is split. */
	private MultiHashMap<Long, RestrictionRelation> restrictions = new MultiHashMap<>();
	
	private NameFinder nameFinder;

	/** Maps which nodes contains to which ways */ 
	private IdentityHashMap<Coord, Set<Way>> wayNodes = new IdentityHashMap<>();
	
	private boolean processDestinations;
	private boolean processExits;
	
	private static final short TAG_KEY_HIGHWAY = TagDict.getInstance().xlate("highway");
	private static final short TAG_KEY_ONEWAY = TagDict.getInstance().xlate("oneway");
	private static final short TAG_KEY_EXIT_TO = TagDict.getInstance().xlate("exit_to");
	private static final short TAG_KEY_DEST_HINT_WORK = TagDict.getInstance().xlate("mkgmap:dest_hint_work");
	
	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props) {
		this.saver = saver;
		nameFinder = new NameFinder(props);
		processDestinations = props.containsKey("process-destination");
		processExits = props.containsKey("process-exits");
		return processDestinations || processExits;
	}

	/**
	 * Fills the internal lists 
	 */
	private void retrieveWaysAndRelations() {
		// collect all ways tagged with highway 
		for (Way w : saver.getWays().values()) {
			if (w.getPoints().size() < 2) {
				// ignore one-node or zero-node ways
				continue;
			}
			String highwayTag = w.getTag(TAG_KEY_HIGHWAY);
			if (highwayTag != null && highwayTypes.contains(highwayTag)) {
				processHighWay(w, highwayTag);
			}
		}
		
		// get all restriction relations
		// eventually they must be modified if one of its ways is split
		for (Relation rel : saver.getRelations().values()) {
			if (rel instanceof RestrictionRelation) {
				RestrictionRelation rrel = (RestrictionRelation) rel;
				for (Long wayId : rrel.getWayIds())
					restrictions.add(wayId, rrel);
			}
		}
	}
	
	private void processHighWay(Way w, String highwayTag) {
		// the points of the way are kept so that it is easy to get
		// the adjacent ways for a given _link way
		List<Coord> points;
		String directionSuffix = null;
		
		if (isOnewayInDirection(w)) {
			// oneway => don't need the last point because the
			// way cannot be driven standing at the last point
			points = w.getPoints().subList(0, w.getPoints().size() - 1);
			directionSuffix = "forward";
		} else if (isOnewayOppositeDirection(w)) {
			// reverse oneway => don't need the first point because the
			// way cannot be driven standing at the first point
			points = w.getPoints().subList(1, w.getPoints().size());
			directionSuffix = "backward";
		} else {
			points = w.getPoints();
		}
		for (Coord c : points) {
			adjacentWays.computeIfAbsent(c, k -> new HashSet<>(4)).add(w);
		}
		registerPointsOfWay(w);
		checkIfUsableLink(w, highwayTag, directionSuffix);
	}

	private void checkIfUsableLink(Way w, String highwayTag, String directionSuffix) {
		// if the way is a link way and has a destination tag
		// put it the list of ways that have to be processed
		if (!linkTypes.contains(highwayTag)) 
			return;
		
		final String destinationLanes = "destination:lanes";
		final String standardTagKey = "destination";
		String destSourceTagKey = standardTagKey; // for log messages
		String destHint = w.getTag(standardTagKey);
		if (destHint == null) {
			// destination is not set 
			// => check if destination:lanes is without any lane specific information (no |)
			destSourceTagKey = destinationLanes;
			String destLanesTag = w.getTag(destSourceTagKey);
			if (destLanesTag == null && directionSuffix != null) {
				destSourceTagKey += ":" + directionSuffix;
				destLanesTag = w.getTag(destSourceTagKey);
			}
			if (destLanesTag != null && !destLanesTag.contains("|")) {
				// the destination:lanes tag contains no | => no lane specific information
				// use this tag as destination tag 
				destHint = destLanesTag;
			}
			
			if (destHint == null && directionSuffix != null) {
				// use the destination:forward or :backward value
				destSourceTagKey = "destination:" + directionSuffix;
				destHint = w.getTag(destSourceTagKey);
 
			}
			if (destHint == null) {
				// try to use the destination:street value
				destSourceTagKey = "destination:street";
				destHint = w.getTag(destSourceTagKey);
			}
		}

		if (destHint != null) {
			w.addTag(TAG_KEY_DEST_HINT_WORK, destHint);
			destinationLinkWays.add(w);

			if (log.isDebugEnabled() && !standardTagKey.equals(destSourceTagKey)) {
				String msg = destSourceTagKey.startsWith(destinationLanes)
						? "as destination tag because there is one lane information only." : "as destination tag.";
				log.debug("Use", destSourceTagKey, msg, "Way ", w.getId(), w.toTagString());
			}
		}
	}

	/**
	 * Registers the points of the given way for the internal data structures.
	 * @param w a new way
	 */
	private void registerPointsOfWay(Way w) {
		for (Coord c : w.getPoints()) {
			wayNodes.computeIfAbsent(c, k-> new HashSet<>()).add(w);
		}			
	}
	
	/**
	 * Removes the points in range from to to from the way and the internal data structures.
	 * @param w way
	 * @param from first point to remove
	 * @param to range end to remove (exclusive)
	 */
	private void removePointsFromWay(Way w, int from, int to) {
		// first remove them from the wayNodes map
		for (Coord c : w.getPoints().subList(from, to)) {
			wayNodes.get(c).remove(w);
		}
		// second remove them from the way
		w.getPoints().subList(from, to).clear();

	}
	
	/**
	 * Check all restriction relations and eventually update the relations to use
	 * the split way if appropriate.
	 * 
	 * @param oldWay the original way
	 * @param newWay the split part of the old way
	 */
	private void changeWayIdInRelations(Way oldWay, Way newWay) {
		List<RestrictionRelation> wayRestrictions = restrictions.get(oldWay.getId());
		if (wayRestrictions.isEmpty()) {
			return;
		}
		if (oldWay.isViaWay()) {
			log.error("internal error: via way is split in", this.getClass().getSimpleName());
		}
		// create a copy because original list may be modified within the loop
		for (RestrictionRelation rr : new ArrayList<>(wayRestrictions)) {
			Coord lastPointNewWay = newWay.getFirstPoint();
			List<Coord> viaCoords = rr.getViaCoords();
			for (Coord via : viaCoords) {
				if (via == lastPointNewWay) {
					if (rr.isToWay(oldWay.getId())) {
						log.debug("Change to-way",oldWay.getId(),"to",newWay.getId(),"for relation",rr.getId(),"at",lastPointNewWay.toOSMURL());
						rr.replaceWay(oldWay.getId(), newWay.getId());
						restrictions.removeMapping(oldWay.getId(), rr);
						restrictions.add(newWay.getId(), rr);
						
					} else if (rr.isFromWay(oldWay.getId())) {
						log.debug("Change from-way",oldWay.getId(),"to",newWay.getId(),"for relation",rr.getId(),"at",lastPointNewWay.toOSMURL());
						rr.replaceWay(oldWay.getId(), newWay.getId());
						restrictions.removeMapping(oldWay.getId(), rr);
						restrictions.add(newWay.getId(), rr);
					} 
				}
			}
		}
	}
	
	/**
	 * Cuts off at least minLength meter of the given way and returns the cut off way tagged
	 * identical to the given way.   
	 * @param w the way to be cut 
	 * @param maxLength the cut off way is no longer than this value
	 * @return the cut off way or <code>null</code> if cutting not possible
	 */
	private Way cutoffWay(Way w, double cutLength, double maxLength) {
		if (w.getPoints().size() < 2) {
			return null;
		}
		
		if (w.getPoints().size() >= 3) {
			// try to use existing points - that does not deform the way
			Coord firstPoint = w.getPoints().get(0);
			Coord cutPoint = w.getPoints().get(1);

			// check if the maxLength is not exceeded
			double dist = firstPoint.distance(cutPoint);
			if (dist <= maxLength) {
				// create a new way with the first two points and identical tags
				Way precedingWay = new Way(w.getOriginalId(), w.getPoints().subList(0, 1 + 1));
				precedingWay.setFakeId();
				precedingWay.copyTags(w);

				saver.addWay(precedingWay);
				// remove the points of the new way from the original way
				removePointsFromWay(w, 0, 1);

				registerPointsOfWay(precedingWay);

				// check and update relations so that they use the new way if appropriate
				changeWayIdInRelations(w, precedingWay);
				
				log.debug("Cut way", w, "at existing point 1. New way:", precedingWay);

				// return the new way
				return precedingWay;
			} else {
				log.debug("Cannot cut way", w, "on existing nodes because the first distance is too big:", dist);
			}
		}
		
		double startSegmentLength = 0;
		
		Coord lastC = w.getFirstPoint();
		for (int i = 1; i < w.getPoints().size(); i++) {
			Coord c = w.getPoints().get(i);
			double segmentLength = lastC.distance(c);
			
			if (startSegmentLength + segmentLength  >= cutLength) {
				double frac = (cutLength - startSegmentLength) / segmentLength;
				// insert a new point at the minimum distance
				Coord cConnection = lastC.makeBetweenPoint(c, frac);
				// check if we find a point which is closer to Garmin point in 24 resolution 
				Coord alternative = ExtNumbers.rasterLineNearPoint(c, lastC, cConnection, false);
				if (alternative != null)
					cConnection = alternative;
				// create the new way with identical tags
				w.getPoints().add(i, cConnection);
				Way precedingWay = new Way(w.getOriginalId(), new ArrayList<Coord>(w.getPoints().subList(0, i + 1)));
				precedingWay.setFakeId();
				precedingWay.copyTags(w);
				
				saver.addWay(precedingWay);
				
				// remove the points of the new way from the old way
				removePointsFromWay(w, 0, i);
				registerPointsOfWay(precedingWay);

				// check and update relations so that they use the new way if appropriate
				changeWayIdInRelations(w, precedingWay);

				// return the split way
				return precedingWay;
			} 		
			lastC = c;
		}
		
		// way too short
		return null;
	}
	
	/**
	 * Retrieves if the given node is tagged as usable exit. So it must contain at least the tags
	 * highway=motorway_junction and one of the tags ref, name or exit_to.
	 * @param node the node to check
	 * @return <code>true</code> if the node is a usable exit, else <code>false</code> 
	 */
	private boolean isTaggedAsExit(Node node) {
		return "motorway_junction".equals(node.getTag(TAG_KEY_HIGHWAY))
				&& (node.getTag("ref") != null || (nameFinder.getName(node) != null) || node.getTag(TAG_KEY_EXIT_TO) != null);
	}
	
	/**
	 * Cuts major roads into three parts to be able to get
	 * a hint on Garmin GPS. This happens if the the option process-exits is set
	 * and the way is connected to an exit node (highway=motorway_junction)
	 * and/or the option process-destination is set and the destination tag is
	 * set. The mid part way is tagged additionally with the following tags:
	 * <ul>
	 * <li>mkgmap:dest_hint=* (for destinations)</li>
	 * <li>mkgmap:exit_hint=true (for exits)</li>
	 * <li>mkgmap:exit_hint_ref: Tagged with the ref tag value of the motorway
	 * junction node</li>
	 * <li>mkgmap:exit_hint_exit_to: Tagged with the exit_to tag value of the
	 * motorway junction node</li>
	 * <li>mkgmap:exit_hint_name: Tagged with the name tag value of the motorway
	 * junction node</li>
	 * </ul>
	 * Style implementors can use the common Garmin code 0x09 for motorway_links
	 * and any other routable id (except 0x08 and 0x09) for the links with
	 * mkgmap:exit_hint=true and/or mkgmap:dest_hint=*. The naming of this
	 * middle way can be typically assigned from destination, ref, destination:ref, 
	 * mkgmap:exit_hint_ref, mkgmap:exit_hint_name and/or mkgmap:exit_hint_exit_to.
	 */
	private void processWays() {
		cleanupLinkDestWays();
		createExitHints();
		createDestinationHints();
	}

	private void cleanupLinkDestWays() {
		// remove the adjacent links from the destinationLinkWays list
		// to avoid duplicate dest_hints
		Queue<Way> linksWithDestination = new ArrayDeque<>(destinationLinkWays);
		log.debug(destinationLinkWays.size(),"links with destination tag");
		while (!linksWithDestination.isEmpty()) {
			Way linkWay = linksWithDestination.poll();
			String destination = linkWay.getTag(TAG_KEY_DEST_HINT_WORK);
			if (log.isDebugEnabled())
				log.debug("Check way", linkWay.getId(), linkWay.toTagString());
			
			// Retrieve all adjacent ways of the current link
			Coord c = isOnewayOppositeDirection(linkWay) ? linkWay.getFirstPoint() : linkWay.getLastPoint();
			
			Set<Way> nextWays = adjacentWays.get(c);
			if (nextWays != null) {
				for (Way connectedWay : nextWays) {
					String nextDest = connectedWay.getTag(TAG_KEY_DEST_HINT_WORK);
					
					if (log.isDebugEnabled())
						log.debug("Followed by",connectedWay.getId(),connectedWay.toTagString());
	
					// remove the way from destination handling only if both ways are connected with start/end points
					// otherwise it is a crossroads and therefore both ways need to be handled
					Coord c2 = isOnewayOppositeDirection(connectedWay) ? connectedWay.getLastPoint()
							: connectedWay.getFirstPoint();

					boolean startEndConnection = c == c2;
					if (startEndConnection && !connectedWay.equals(linkWay)
							&& connectedWay.getTag(TAG_KEY_HIGHWAY).endsWith("_link")
							&& destination.equals(nextDest)) {
						// do not use this way because there is another link before that with the same destination
						boolean removed = destinationLinkWays.remove(connectedWay);
						if (removed && log.isDebugEnabled()) {
							log.debug("Removed", connectedWay.getId(), connectedWay.toTagString());
						}
					}
				}
			}
		}
		log.debug(destinationLinkWays.size(),"links with destination tag after cleanup");
	}

	private void createExitHints() {
		if(!processExits)
			return;
		
		List<String> hwSorted = new ArrayList<>(highwayTypes);
		for (Node exitNode : saver.getNodes().values()) {
			if (isTaggedAsExit(exitNode) && saver.getBoundingBox().contains(exitNode.getLocation())) {
				// node is tagged with highway=motorway_junction and has usable exit info 
				processExitNode(exitNode, hwSorted);
			}
		}
	}

	/**
	 * Check if we can add an exit hint for the given exit node.
	 * @param exitNode
	 * @param hwSorted 
	 * @param highwayCoords
	 */
	private void processExitNode(Node exitNode, List<String> hwSorted) {
		// retrieve all ways with this exit node
		Set<Way> exitWays = adjacentWays.get(exitNode.getLocation());
		if (exitWays == null) {
			log.debug("Exit node", exitNode, "has no connected ways. Skip it.");
			return;
		}
		String exitTo = exitNode.getTag(TAG_KEY_EXIT_TO);
		if (exitTo != null) {
			int countMatches = 0;
			int preferred = Integer.MAX_VALUE;
			for (Way w : exitWays) {
				String hw = w.getTag(TAG_KEY_HIGHWAY);
				int pos = hwSorted.indexOf(hw);
				if (pos < preferred) {
					preferred = pos;
					countMatches = 1;
				} else if (pos == preferred) {
					countMatches++;
				}
			}
			if (countMatches != 1)
				exitTo = null; // don't use exit_to info, it typically contains info for all the ways
		}

		// use link ways only
		for (Way w : exitWays) {
			destinationLinkWays.remove(w);
			if(!canSplit(w))
				continue;
			processExitWay(exitNode, w, exitTo);
		}
	}

	private void processExitWay(Node exitNode, Way w, String exitTo) {
		String highwayLinkTag = w.getTag(TAG_KEY_HIGHWAY);
		if (highwayLinkTag.endsWith("_link")) {
			log.debug("Try to cut", highwayLinkTag, w, "into three parts for giving hint to exit", exitNode);
			Way hintWay = splitWay(w, "exit");
			if (hintWay != null) {
				fixDestHint(hintWay);
				hintWay.addTag("mkgmap:exit_hint", "true");
				if (exitNode.getTag("ref") != null)
					hintWay.addTag("mkgmap:exit_hint_ref", exitNode.getTag("ref"));
				if (exitTo != null) {
					hintWay.addTag("mkgmap:exit_hint_exit_to", exitTo);
				}
				if (nameFinder.getName(exitNode) != null) {
					hintWay.addTag("mkgmap:exit_hint_name", nameFinder.getName(exitNode));
				}

				if (log.isInfoEnabled())
					log.info("Cut off exit hint way", hintWay, hintWay.toTagString());
			}
		}
	}

	private String fixDestHint(Way hintWay) {
		if (processDestinations) {
			String hint = hintWay.getTag(TAG_KEY_DEST_HINT_WORK);
			if (hint != null) {
				hintWay.deleteTag(TAG_KEY_DEST_HINT_WORK);
				hintWay.addTag("mkgmap:dest_hint", hint);
			}
			return hint;
		}
		return null;
	}

	private void createDestinationHints() {
		if (!processDestinations)
			return;
		// use link ways only
		while (!destinationLinkWays.isEmpty()) {
			Way w = destinationLinkWays.iterator().next();
			destinationLinkWays.remove(w);
			String highwayLinkTag = w.getTag(TAG_KEY_HIGHWAY);
			if (!canSplit(w) || !highwayLinkTag.endsWith("_link")) 
				continue;

			log.debug("Try to cut",highwayLinkTag, w, "into three parts for giving hint");

			Way hintWay = splitWay(w, "destination");
			if (hintWay != null) {
				String hint = fixDestHint(hintWay);
				if (hint == null) {
					log.error("Internal error in process_destination with way", hintWay);
				}

				if (log.isInfoEnabled())
					log.info("Cut off exit hint way", hintWay, hintWay.toTagString());
			}
		}
	}

	/**
	 * Try to split the way. If successful, it creates two or three parts.
	 * <ul>
	 * <li>wayPart1: original tags only</li>
	 * <li>hintWay: original tags plus the mkgmap:exit_hint* tags</li>
	 * <li>rest of the original way (if any remains)</li>
	 * </ul>
	 * @param w the way to split
	 * @param hintType the type of hint that is added (exit or destination), use for logging only
	 * @return null if not successful, else the hintWay
	 */
	private Way splitWay(Way w, String  hintType) {
		// calc the way length to decide how to cut the way
		double wayLength = w.calcLengthInMetres();
		double cut1 = Math.min(wayLength / 2, 20.0);
		double cut2 = Math.min(wayLength, 100);
		Way wayPart1 = cutoffWay(w, cut1, cut2);
		if (wayPart1 == null) {
			log.info("Way", w, "is too short to cut at least", cut1, "m from it. Cannot create", hintType, "hint.");
			return null;
		}
		if (log.isDebugEnabled())
			log.debug("Cut off way", wayPart1, wayPart1.toTagString());
		
		Way hintWay = w;
		if (wayLength > 50) {
			hintWay = cutoffWay(w, 10.0, 50.0);
		}
		if (hintWay == null) {
			log.info("Way", w, "is too short to cut at least 20m from it. Cannot create", hintType, "hint.");
		}
		return hintWay;
	}

	private static boolean canSplit(Way w) {
		if (isNotOneway(w)) {
			log.warn("Ignore way", w, "because it is not oneway");
			return false;
		}
		if (w.isViaWay()) {
			log.warn("Ignore way", w, "because it is a via way in a restriction  relation");
			return false;
		}
		return w.calcLengthInMetres() >= 0.3; 
	}

	/**
	 * Cleans all internal data that is no longer used after the hook has been processed.
	 */
	private void cleanup() {
		adjacentWays = null;
		wayNodes = null;
		destinationLinkWays = null;
		saver = null;
		nameFinder = null;
	}

	@Override
	public Set<String> getUsedTags() {
		if (!(processDestinations || processExits))
			return Collections.emptySet();
		// When processing destinations also load the destination:lanes,forward and backward tag 
		// to be able to copy the value to the destination tag
		// Do not load destination because it makes sense only if the tag is
		// referenced in the style file
		Set<String> tags = new HashSet<>();
		tags.add("highway");
		tags.add("oneway");
		tags.add("destination");
		tags.add("destination:lanes");
		tags.add("destination:lanes:forward");
		tags.add("destination:lanes:backward");
		tags.add("destination:forward");
		tags.add("destination:backward");
		tags.add("destination:street");
		if (processExits) {
			tags.add("exit_to");
			tags.add("ref");
		}
		return tags;
	}	

	@Override
	public void end() {
		log.info("LinkDestinationHook started");

		retrieveWaysAndRelations();
		processWays();
		cleanup();

		log.info("LinkDestinationHook finished");
	}

	/**
	 * Retrieves if the given way is tagged as oneway in the direction of the way.
	 * @param w the way
	 * @return <code>true</code> way is oneway
	 */
	private static boolean isOnewayInDirection(Way w) {
		if (w.tagIsLikeYes(TAG_KEY_ONEWAY)) {
			return true;
		}
		
		// check if oneway is set implicitly by the highway type (motorway and motorway_link)
		String onewayTag = w.getTag(TAG_KEY_ONEWAY);
		String highwayTag = w.getTag(TAG_KEY_HIGHWAY);
		return onewayTag == null && highwayTag != null
				&& ("motorway".equals(highwayTag)|| "motorway_link".equals(highwayTag));
	}

	/**
	 * Retrieves if the given way is tagged as oneway but in opposite direction of the way.
	 * @param w the way
	 * @return <code>true</code> way is oneway in opposite direction
	 */
	private static boolean isOnewayOppositeDirection(Way w) {
		return "-1".equals(w.getTag(TAG_KEY_ONEWAY));
	}

	/**
	 * Retrieves if the given way is not oneway.
	 * @param w the way
	 * @return <code>true</code> way is not oneway
	 */
	private static boolean isNotOneway(Way w) {
		return "no".equals(w.getTag(TAG_KEY_ONEWAY)) || (!isOnewayInDirection(w) && !isOnewayOppositeDirection(w));
	}
}
