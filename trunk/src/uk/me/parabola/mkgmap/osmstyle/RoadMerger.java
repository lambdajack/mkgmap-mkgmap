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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

	private static final double WARN_MERGE_ANGLE = 130.0;

	private final boolean reverseAllowed;
	
	/** maps which coord of a way(id) are restricted - they should not be merged */
	private final MultiIdentityHashMap<Coord, Long> restrictions = new MultiIdentityHashMap<>();

	/** maps the start and end points of a road to its road definition */
	private final MultiIdentityHashMap<Coord, ConvertedWay> sharedPoints = new MultiIdentityHashMap<>();

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
	 * Create new road merger.
	 * 
	 * @param reverseAllowed set to true if ways can be reversed for merging. Not
	 *                       that the reversed flag will not be set for them.
	 */
	public RoadMerger(boolean reverseAllowed) {
		this.reverseAllowed = reverseAllowed;
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
				HashSet<ConvertedWay> roadAtVia = new HashSet<>(sharedPoints.get(via));
				for (ConvertedWay r : roadAtVia) {
					long wayId = r.getWay().getId();
					if (restrictionWayIds.contains(wayId))
						restrictions.add(via, wayId);
				}
			}
		}
	}
	
	private boolean hasRestriction(Coord c, Way w) {
		return w.isViaWay() || restrictions.get(c).contains(w.getId());
	}

	/**
	 * Merges {@code road2} into {@code road1}. This means that
	 * only the way id and the tags of {@code road1} is kept.
	 * For the tag it should not matter because all tags used after the
	 * RoadMerger are compared to be the same.
	 * 
	 * @param mergePoint the end point where the two roads are connected
	 * @param road1 first road (will keep the merged road)
	 * @param road2 second road
	 */
	private void mergeRoads(Coord mergePoint, ConvertedWay road1, ConvertedWay road2) {
		// Removes the second line,
		// Merges the points in the first one
		Way way1 = road1.getWay();
		Way way2 = road2.getWay();
		if (way1.getFirstPoint() == mergePoint) {
			assert reverseAllowed : "bad reverse at " + mergePoint.toDegreeString();
			Collections.reverse(way1.getPoints());
			
		}
		assert way1.getLastPoint() == mergePoint;
		
		List<Coord> points2 = way2.getPoints();
		sharedPoints.removeMapping(way2.getFirstPoint(), road2);
		sharedPoints.removeMapping(way2.getLastPoint(), road2);
		sharedPoints.removeMapping(mergePoint, road1);
		Coord endPoint= way2.getLastPoint();
		if (endPoint == mergePoint) {
			assert reverseAllowed;
			Collections.reverse(points2);
			endPoint = way2.getLastPoint();
		}

		road1.getPoints().addAll(points2.subList(1, points2.size()));
		
		points2.clear(); // makes road2 invalid
		sharedPoints.add(endPoint, road1);
		
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

	}

	/**
	 * Merge the roads and copy the results to the given lists. 
	 * Note that the points of roads are modified!
	 * @param roads list of roads  
	 * @param restrictions list of restriction relations
	 */
	public List<ConvertedWay> merge(List<ConvertedWay> roads, List<RestrictionRelation> restrictions) {
		
		List<ConvertedWay> roadsToMerge = roads.stream()
				.filter(cw -> cw.isValid() && cw.isRoad())
				.collect(Collectors.toList());
		int noRoadsBeforeMerge = roadsToMerge.size();
		int numMerges = 0;
		
		List<Coord> mergePoints = new ArrayList<>();
		// first add all roads with their start and end points to the sharedPoints map
		for (ConvertedWay road : roadsToMerge) {
			Coord start = road.getWay().getFirstPoint();
			Coord end = road.getWay().getLastPoint();

			if (start == end) {
				// do not merge closed roads 
				continue;
			}
			for (Coord p : Arrays.asList(start, end)) {
				if (p.getHighwayCount() >= 2) {
					mergePoints.add(p);
					sharedPoints.add(p, road);
				}
			}
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
			
			List<ConvertedWay> roadsAtPoint = sharedPoints.get(mergePoint);
			// go through all combinations and test which combination is the best
			double bestAngle = Double.MAX_VALUE;
			ConvertedWay mergeRoad1 = null;
			ConvertedWay mergeRoad2 = null;
			
			// go through all possible combinations of the roads at this point
			for (ConvertedWay road1 : roadsAtPoint) {
				// check if we can only merge at road end and  
				// check if the road has a restriction at the merge point
				// which does not allow us to merge the road at this point
				if ((!reverseAllowed && road1.getWay().getLastPoint() != mergePoint)
						|| (hasRestriction(mergePoint, road1.getWay()))) {
					continue;
				}
				for (ConvertedWay road2 : roadsAtPoint) {
					// make sure roads are different and meat reverse criteria
					// The second road is merged into the first road
					// so only the id of the first road is kept
					// This also means that the second road must not have a restriction on 
					// both start and end point
					if ((road1 == road2 || !reverseAllowed && road2.getWay().getFirstPoint() != mergePoint)
							|| (hasRestriction(road2.getWay().getFirstPoint(), road2.getWay()))
							|| hasRestriction(road2.getWay().getLastPoint(), road2.getWay())) {
						continue;
					}

					if (isMergeable(mergePoint, road1, road2, reverseAllowed)) {
						// calculate the angle between the roads 
						// if there is more then one road to merge the pair with the lowest angle is preferred 
						double angle = getAngle(mergePoint, road1.getWay(), road2.getWay());
						if (log.isDebugEnabled()) {
							log.debug("Road", road1.getWay().getId(), "and road", road2.getWay().getId(),
									"are mergeable with angle", angle, "at", mergePoint.toDegreeString());
						}
						if (angle < bestAngle) {
							mergeRoad1 = road1;
							mergeRoad2 = road2;
							bestAngle = angle;
						}
					}
				}
			}
			boolean merged = false;
			// is there a pair of roads that can be merged?
			if (mergeRoad1 != null && mergeRoad2 != null) {
				if (bestAngle > WARN_MERGE_ANGLE) {
					log.info("Merging ways", mergeRoad1.getWay().getId(), "and", mergeRoad2.getWay().getId(), "at",
							mergePoint, "with sharp angle", bestAngle);
				} 
				// yes!! => merge them
				if (log.isDebugEnabled()) {
					log.debug("Merging", mergeRoad1.getWay().getId(), "and", mergeRoad2.getWay().getId(), "at",
							mergePoint.toDegreeString(), "with angle", bestAngle);
				}
				mergeRoads(mergePoint, mergeRoad1, mergeRoad2);
				numMerges++;
				merged = true;
			}
			if (!merged) {
				// no => do not check this point again
				mergeCompletedPoints.add(mergePoint);
			}
		}

		List<ConvertedWay> result = roadsToMerge.stream()
				.filter(ConvertedWay::isValid)
				.collect(Collectors.toList());
		
		// print out some statistics
		int noRoadsAfterMerge = result.size();
		log.info("Roads before/after merge:", noRoadsBeforeMerge, "/", noRoadsAfterMerge);
		int percentage = (int) Math.round((noRoadsBeforeMerge-noRoadsAfterMerge) * 100.0d / noRoadsBeforeMerge);
		log.info("Road network reduced by", percentage, "%", numMerges, "merges");
		return result;
	}

	/**
	 * Checks if the first road can be merged with the 2nd road at 
	 * the given {@code mergePoint}.
	 * @param mergePoint the coord where this road and otherRoad might be merged
	 * @param road1 1st road instance
	 * @param road2 2nd road instance
	 * @param reverseAllowed set to true if it is allowed to revert the order of points in the roads
	 * @return {@code true} road1 can be merged with {@code road2};
	 * 	{@code false} the roads cannot be merged at {@code mergePoint}
	 */
	public static boolean isMergeable(Coord mergePoint, ConvertedWay road1, ConvertedWay road2, boolean reverseAllowed) {
		if(road1 == road2)
			return false;
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

		if (!checkGeometry(mergePoint, road1, road2, reverseAllowed))
			return false;

		if (!isGTypeMergeable(road1.getGType(), road2.getGType())) {
			return false;
		}

		return isWayTagsMergeable(way1, way2);
	}


	/**
	 * Check if roads meet at the given point and don't form a loop or a bad oneway combination
	 * @param mergePoint the mergePoint
	 * @param road1 first road
	 * @param road2 second road
	 * @param reverseAllowed set to true if it is allowed to revert the order of points in the roads
	 * @return true if roads can be merged, else false
	 */
	private static boolean checkGeometry(Coord mergePoint, ConvertedWay road1, ConvertedWay road2,
			boolean reverseAllowed) {
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
		if (!reverseAllowed && (mergePoint != cEnd || mergePoint != cOtherStart)) {
			 return false;
		}

		// check if merging would create a closed way - which should not
		// be done (why? WanMil)
		if (cStart == cOtherEnd) {
			return false;
		}

		boolean differentDir = (cStart == mergePoint) == (cOtherStart == mergePoint);
		if (!differentDir)
			return true;
		if (road1.isOneway()) {
			assert road2.isOneway();
			// oneway must not only be checked for equality
			// but also for correct direction of both ways
			// both ways are oneway but they have a different direction
			log.warn("oneway with different direction", way1.getId(), way2.getId());
			return false;
		}
		return reverseAllowed && !(road1.hasDirection() || road2.hasDirection()); 
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
			if (!Objects.equals(tag1, tag2)) {
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
	 * Calculate the angle between the two {@link Way} objects of both roads 
	 * @param mergePoint the coord where both roads should be merged
	 * @param way1 1st way
	 * @param way2 2nd way
	 * 
	 */
	private static double getAngle(Coord mergePoint, Way way1, Way way2) {
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
		
		return Math.abs(Utils.getAngle(cOnWay1, mergePoint, cOnWay2));
	}
}
