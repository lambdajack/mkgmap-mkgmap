/*
 * Copyright (C) 2013.
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

package uk.me.parabola.mkgmap.osmstyle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.net.AccessTagsAndBits;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.RestrictionRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.MultiIdentityHashMap;

/**
 * Merges connected roads with identical road relevant tags based on the OSM elements 
 * and the GType class.
 * 
 * @author WanMil
 */
public class RoadMerger {
	private static final Logger log = Logger.getLogger(RoadMerger.class);

	private static final double MAX_MERGE_ANGLE = 130d;
	
	/** maps which coord of a way(id) are restricted - they should not be merged */
	private final MultiIdentityHashMap<Coord, Long> restrictions = new MultiIdentityHashMap<>();

	/** maps the start point of a road to its road definition */
	private final MultiIdentityHashMap<Coord, ConvertedWay> startPoints = new MultiIdentityHashMap<>();
	/** maps the end point of a road to its road definition */
	private final MultiIdentityHashMap<Coord, ConvertedWay> endPoints = new MultiIdentityHashMap<>();

	/** 
	 * For these tags two ways need to have an equal value so that their roads can be merged.
	 */
	private static final Set<String> mergeTagsEqualValue = new HashSet<>(Arrays.asList( 
			"mkgmap:label:1",
			"mkgmap:label:2",
			"mkgmap:label:3",
			"mkgmap:label:4",
			"mkgmap:postal_code",
			"mkgmap:city",
			"mkgmap:region",
			"mkgmap:country",
			"mkgmap:is_in",
			"mkgmap:skipSizeFilter",
			"mkgmap:synthesised",
			"mkgmap:highest-resolution-only",
			"mkgmap:flare-check",
			"mkgmap:numbers"
			));


	/**
	 * Checks if two strings are equal ({@code null} supported).
	 * @param s1 first string ({@code null} allowed)
	 * @param s2 second string ({@code null} allowed)
	 * @return {@code true} both strings are equal or both {@code null}; {@code false} both strings are not equal
	 */
	private static boolean stringEquals(String s1, String s2) {
		if (s1 == null) 
			return s2 == null;
		return s1.equals(s2);
	}


	/**
	 * We must not merge roads at via points of restriction relations
	 * if the way is referenced in the restriction.
	 * @param restrictionRels
	 */
	private void workoutRestrictionRelations(List<RestrictionRelation> restrictionRels) {
		for (RestrictionRelation rel : restrictionRels) {
			Set<Long> restrictionWayIds = rel.getWayIds();
			for (Coord via: rel.getViaCoords()){
				HashSet<ConvertedWay> roadAtVia = new HashSet<>();
				roadAtVia.addAll(startPoints.get(via));
				roadAtVia.addAll(endPoints.get(via));
				for (ConvertedWay r: roadAtVia){
					long wayId = r.getWay().getId();
					if (restrictionWayIds.contains(wayId))
						restrictions.add(via, wayId);
				}
			}
		}
	}
	
	private boolean hasRestriction(Coord c, Way w) {
		if (w.isViaWay())
			return true;
		List<Long> wayRestrictions = restrictions.get(c);
		return wayRestrictions.contains(w.getId());
	}

	/**
	 * Merges {@code road2} into {@code road1}. This means that
	 * only the way id and the tags of {@code road1} is kept.
	 * For the tag it should not matter because all tags used after the
	 * RoadMerger are compared to be the same.
	 * 
	 * @param road1 first road (will keep the merged road)
	 * @param road2 second road
	 */
	private void mergeRoads(ConvertedWay road1, ConvertedWay road2) {
		// Removes the second line,
		// Merges the points in the first one
		List<Coord> points2 = road2.getWay().getPoints();
		Coord mergePoint = points2.get(0);
		Coord endPoint= points2.get(points2.size()-1);
		
		startPoints.removeMapping(mergePoint, road2);
		endPoints.removeMapping(endPoint, road2);
		endPoints.removeMapping(mergePoint, road1);

		road1.getPoints().addAll(points2.subList(1, points2.size()));
		endPoints.add(endPoint, road1);
		
		// merge the POI info
		String wayPOI2 = road2.getWay().getTag(StyledConverter.WAY_POI_NODE_IDS);
		if (wayPOI2 != null){
			String wayPOI1 = road1.getWay().getTag(StyledConverter.WAY_POI_NODE_IDS);
			if (!wayPOI2.equals(wayPOI1)){
				if (wayPOI1 == null)
					wayPOI1 = "";
				// store combination of both ways. This might contain
				// duplicates, but that is not a problem.
				road1.getWay().addTag(StyledConverter.WAY_POI_NODE_IDS, wayPOI1 + wayPOI2);
			}
		}
		
		// the mergePoint is now used by one highway less
		mergePoint.decHighwayCount();
		
		// road2 is removed - it must not be part of a restriction
		assert !restrictions.get(endPoint).contains(road2.getWay().getId());
		
	}

	/**
	 * Merge the roads and copy the results to the given lists.
	 * @param resultingWays list for the merged (and not mergeable) ways
	 * @param resultingGTypes list for the merged (and not mergeable) GTypes
	 */
	public List<ConvertedWay>  merge(List<ConvertedWay> convertedWays,
			List<RestrictionRelation> restrictions) {
		List<ConvertedWay> result = new ArrayList<>();
		List<ConvertedWay> roadsToMerge = new ArrayList<>(convertedWays.size());
		for (int i = 0; i < convertedWays.size(); i++) {
			ConvertedWay cw = convertedWays.get(i);
			if (cw.isValid() && cw.isRoad())
				roadsToMerge.add(cw);
		}

		int noRoadsBeforeMerge = roadsToMerge.size();
		int numMerges = 0;
		
		List<Coord> mergePoints = new ArrayList<>();

		// first add all roads with their start and end points to the
		// start/endpoint lists
		for (ConvertedWay road : roadsToMerge) {
			Coord start = road.getWay().getFirstPoint();
			Coord end = road.getWay().getLastPoint();

			if (start == end) {
				// do not merge closed roads
				result.add(road);
				continue;
			}

			mergePoints.add(start);
			mergePoints.add(end);
			startPoints.add(start, road);
			endPoints.add(end, road);
		}
		workoutRestrictionRelations(restrictions);

		// a set of all points where no more merging is possible
		Set<Coord> mergeCompletedPoints = Collections.newSetFromMap(new IdentityHashMap<Coord, Boolean>());
		
		// go through all start/end points and check if a merge is possible
		for (Coord mergePoint : mergePoints) {
			if (mergeCompletedPoints.contains(mergePoint)) {
				// a previous run did not show any possible merge
				// do not check again
				continue;
			}
			
			// get all road that start with the merge point
			List<ConvertedWay> startRoads = startPoints.get(mergePoint);
			// get all roads that end with the merge point
			List<ConvertedWay> endRoads = endPoints.get(mergePoint);
			
			if (endRoads.isEmpty() || startRoads.isEmpty()) {
				// this might happen if another merge operation changed endPoints and/or startPoints
				mergeCompletedPoints.add(mergePoint);
				continue;
			}
			
			// go through all combinations and test which combination is the best
			double bestAngle = Double.MAX_VALUE;
			ConvertedWay mergeRoad1 = null;
			ConvertedWay mergeRoad2 = null;
			
			for (ConvertedWay road1 : endRoads) {
				// check if the road has a restriction at the merge point
				// which does not allow us to merge the road at this point
				if (hasRestriction(mergePoint, road1.getWay())) {
					continue;
				}
				
				List<Coord> points1 = road1.getPoints();
				
				// go through all candidates to merge
				for (ConvertedWay road2 : startRoads) {
					
					// the second road is merged into the first road
					// so only the id of the first road is kept
					// This also means that the second road must not have a restriction on 
					// both start and end point
					if (hasRestriction(mergePoint, road2.getWay())
							|| hasRestriction(road2.getWay().getLastPoint(), road2.getWay())) {
						continue;
					}
					
					// check if both roads can be merged
					if (isMergeable(mergePoint, road1, road2)){
						// yes they might be merged
						// calculate the angle between them 
						// if there is more then one road to merge the one with the lowest angle is merged 
						double angle = Math.abs(Utils.getAngle(points1.get(points1.size()-2), mergePoint, road2.getPoints().get(1)));
						log.debug("Road",road1.getWay().getId(),"and road",road2.getWay().getId(),"are mergeable with angle",angle);
						if (angle < bestAngle) {
							mergeRoad1 = road1;
							mergeRoad2 = road2;
							bestAngle = angle;
						} 
					}
				}
			}
			
			// is there a pair of roads that can be merged?
			if (mergeRoad1 != null && mergeRoad2 != null) {
				// yes!! => merge them
				log.debug("Merge",mergeRoad1.getWay().getId(),"and",mergeRoad2.getWay().getId(),"with angle",bestAngle);
				mergeRoads(mergeRoad1, mergeRoad2);
				numMerges++;
			} else {
				// no => do not check this point again
				mergeCompletedPoints.add(mergePoint);
			}
		}

		// copy all merged roads to the roads list
		for (List<ConvertedWay> mergedRoads : endPoints.values()) {
			result.addAll(mergedRoads);
		}

		// sort the roads to ensure that the order of roads is constant for two runs
		result.sort((o1, o2) -> Integer.compare(o1.getIndex(), o2.getIndex()));
		
		// print out some statistics
		int noRoadsAfterMerge = result.size();
		log.info("Roads before/after merge:", noRoadsBeforeMerge, "/",
				noRoadsAfterMerge);
		int percentage = (int) Math.round((noRoadsBeforeMerge - noRoadsAfterMerge) * 100.0d
						/ noRoadsBeforeMerge);
		log.info("Road network reduced by", percentage, "%",numMerges,"merges");
		return result;
	}

	/**
	 * Checks if the given {@code otherRoad} can be merged with this road at 
	 * the given {@code mergePoint}.
	 * @param mergePoint the coord where this road and otherRoad might be merged
	 * @param road1 1st road instance
	 * @param road2 2nd road instance
	 * @return {@code true} road1 can be merged with {@code road2};
	 * 	{@code false} the roads cannot be merged at {@code mergePoint}
	 */
	private static boolean isMergeable(Coord mergePoint, ConvertedWay road1, ConvertedWay road2) {
		// check if basic road attributes match
		if (road1.getRoadClass() != road2.getRoadClass() || road1.getRoadSpeed() != road2.getRoadSpeed()) {
			return false;
		}
		Way way1 = road1.getWay();
		Way way2 = road2.getWay();

		if (road1.getAccess() != road2.getAccess()) {
			if (log.isDebugEnabled()) {
				reportFirstDifferentTag(way1, way2, road1.getAccess(),
						road2.getAccess(), AccessTagsAndBits.ACCESS_TAGS);
			}
			return false;
		}
		if (road1.getRouteFlags() != road2.getRouteFlags()) {
			if (log.isDebugEnabled()) {
				reportFirstDifferentTag(way1, way2, road1.getRouteFlags(),
						road2.getRouteFlags(), AccessTagsAndBits.ROUTE_TAGS);
			}
			return false;
		}

		if (!checkGeometry(mergePoint, road1, road2))
			return false;

		if (!isGTypeMergeable(road1.getGType(), road2.getGType())) {
			return false;
		}

		if (!isWayTagsMergeable(way1, way2)) 
			return false;
		
		return isAngleOK(mergePoint, way1, way2); 
	}


	/**
	 * Check if roads meet at the given point and don't form a loop or a bad oneway combination
	 * @param mergePoint the mergePoint
	 * @param road1 first road
	 * @param road2 second road
	 * @return true if roads can be merged, else false
	 */
	private static boolean checkGeometry(Coord mergePoint, ConvertedWay road1, ConvertedWay road2) {
		Way way1 = road1.getWay();
		Way way2 = road2.getWay();
		// now check if this road starts or stops at the mergePoint
		Coord cStart = way1.getFirstPoint();
		Coord cEnd = way1.getLastPoint();
		if (cStart != mergePoint && cEnd != mergePoint) {
			// it doesn't => roads not mergeable at mergePoint
			return false;
		}

		// do the same check for the otherRoad
		Coord cOtherStart = way2.getFirstPoint();
		Coord cOtherEnd = way2.getLastPoint();
		if (cOtherStart != mergePoint && cOtherEnd != mergePoint) {
			// otherRoad does not start or stop at mergePoint =>
			// roads not mergeable at mergePoint
			return false;
		}

		// check if merging would create a closed way - which should not
		// be done (why? WanMil)
		if (cStart == cOtherEnd) {
			return false;
		}

		if (road1.isOneway()){
			assert road2.isOneway();
			// oneway must not only be checked for equality
			// but also for correct direction of both ways
			if ((cStart == mergePoint) == (cOtherStart == mergePoint)) {
				// both ways are oneway but they have a different direction
				log.warn("oneway with different direction", way1.getId(),way2.getId());
				return false;
			}
		}
		return true;
	}


	/**
	 * For logging purposes. Print first tag with different meaning.
	 * @param way1 1st way
	 * @param way2 2nd way
	 * @param flags1 the bit mask for 1st way 
	 * @param flags2 the bit mask for 2nd way
	 * @param tagMaskMap the map that explains the meaning of the bit masks
	 */
	private static void reportFirstDifferentTag(Way way1, Way way2, byte flags1,
			byte flags2, Map<String, Byte> tagMaskMap) {
		for (Entry<String, Byte> entry : tagMaskMap.entrySet()){
			byte mask = entry.getValue();
			if ((flags1 & mask) != (flags2 & mask)){
				String tagKey = entry.getKey();
				log.debug(entry.getKey(), "does not match", way1.getId(), "("
						+ way1.getTag(tagKey) + ")", 
						way2.getId(), "(" + way2.getTag(tagKey) + ")");
				return; // report only first mismatch 
			}
		}
	}


	/**
	 * Checks if two GType objects can be merged. Not all fields are compared.
	 * @param type1 the 1st GType 
	 * @param type2 the 2nd GType 
	 * @return {@code true} both GType objects can be merged; {@code false} GType 
	 *   objects do not match and must not be merged
	 */
	private static boolean isGTypeMergeable(GType type1, GType type2) {
		return type1.getType() == type2.getType() 
				&& type1.getMinResolution() == type2.getMinResolution()
				&& type1.getMaxResolution() == type2.getMaxResolution() 
				&& type1.getMinLevel() == type2.getMinLevel()
				&& type1.getMaxLevel() == type2.getMaxLevel();
		// roadClass and roadSpeed are taken from the ConvertedWay objects 
	}

	/**
	 * Checks if the tag values of the {@link Way} objects of both roads 
	 * match so that both roads can be merged. 
	 * @param way1 1st way
	 * @param way2 2nd way
	 * @return {@code true} tag values match so that both roads might be merged;
	 *  {@code false} tag values differ so that road must not be merged
	 */
	private static boolean isWayTagsMergeable(Way way1, Way way2) {
		// tags that need to have an equal value
		for (String tagname : mergeTagsEqualValue) {
			String tag1 = way1.getTag(tagname);
			String tag2 = way2.getTag(tagname);
			if (!stringEquals(tag1, tag2)) {
				if (log.isDebugEnabled()){
					log.debug(tagname, "does not match", way1.getId(), "("
							+ tag1 + ")", way2.getId(), "(" + tag2
							+ ")");
				}
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if the angle between the two {@link Way} objects of both roads 
	 * is not too sharp so that both roads can be merged. 
	 * @param mergePoint the coord where both roads should be merged
	 * @param way1 1st way
	 * @param way2 2nd way
	 * @return {@code true} angle is okay, roads might be merged;
	 *  {@code false} angle is so sharp that roads must not be merged
	 */
	private static boolean isAngleOK(Coord mergePoint, Way way1, Way way2) {
		// Check the angle of the two ways
		Coord cOnWay1;
		if (way1.getFirstPoint() == mergePoint) {
			cOnWay1 = way1.getPoints().get(1);
		} else {
			cOnWay1 = way1.getPoints().get(way1.getPoints().size() - 2);
		}
		Coord cOnWay2;
		if (way2.getFirstPoint() == mergePoint) {
			cOnWay2 = way2.getPoints().get(1);
		} else {
			cOnWay2 = way2.getPoints().get(way2.getPoints().size() - 2);
		}

		double angle = Math.abs(Utils.getAngle(cOnWay1, mergePoint, cOnWay2));
		if (angle > MAX_MERGE_ANGLE) {
			// The angle exceeds the limit => do not merge
			// Don't know if this is really required or not. 
			// But the number of merges which do not succeed due to this
			// restriction is quite low and there have been requests
			// for this: http://www.mkgmap.org.uk/pipermail/mkgmap-dev/2013q3/018649.html

			log.info("Do not merge ways",way1.getId(),"and",way2.getId(),"because they span a too big angle",angle,"°");
			return false;
		}

		return true;
	}
}
