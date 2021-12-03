/*
 * Copyright (C) 2015
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
package uk.me.parabola.imgfmt.app.net;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Find sharp angles at junctions. The Garmin routing algorithm doesn't
 * like to route on roads building a sharp angle. It adds a time penalty
 * from 30 to 150 seconds and often prefers small detours instead.
 * The penalty depends on the road speed and the vehicle, for pedestrian
 * mode it is zero, for bicycles it is rather small, for cars it is high.
 * The sharp angles typically don't exist in the real world, they are
 * caused by the simplifications done by mappers.
 * 
 * Maps created for cyclists typically "abuse" the car routing for racing 
 * bikes, but in this scenario the time penalties are much too high,
 * and detours are likely.
 * 
 * This method tries to modify the initial heading values of the arcs 
 * which are used to calculate the angles. Where possible, the values are
 * changed so that angles appear larger. 
 * 
 * @author Gerd Petermann
 *
 * Somehow these penalties are also applied to "shortest" routing,
 * often resulting in something that is definitely not the shortest
 * route.
 *
 * Possibly a more significant problem is the very low cost Garmin
 * gives to a minor road crossing a more major road - much less than
 * the "against driving-side" turn onto the major road.  If there is a
 * small block/triangle of roads on the other side, the routing
 * algorithm might prefer crossing the major road then multiple
 * driving-side turns rather than the correct single turn.
 *
 * I haven't found a way of preventing this.
 *
 * Ticker Berkin
 *
 */
public class AngleChecker {
	private static final Logger log = Logger.getLogger(AngleChecker.class);

	private boolean ignoreSharpAngles;
	private boolean cycleMap;

	// Generally it is safe to use compactDirs when there are no arcs in consecutive
	// 22.5 degree sectors and this is guaranteed if the minumum angle is >= 45 degrees.
	private static final float COMPACT_DIR_DEGREES = 45+1; // +bit for 360>256 conversion rounding
	// If this is >= above, then compactDirs will be used unless other, non-vehicle access angles are sharp
	private static final float SHARP_DEGREES = COMPACT_DIR_DEGREES;
	// Experimentation found no benefit in increasing angle beyond 2 "sectors"
	private static final float MIN_ANGLE = 11.25f; // don't reduce angles to less than this (arbitrary)
	
	// helper class to collect multiple arcs with (nearly) the same initial headings
	private class ArcGroup {
		float initialHeading;
		int isOneWayTrueCount;
		int isForwardTrueCount;
		int maxRoadSpeed;
		int maxRoadClass;
		byte orAccessMask;
		HashSet<RoadDef> roadDefs = new HashSet<>();
		
		List<RouteArc> arcs = new ArrayList<>();
		public void addArc(RouteArc arc) {
			arcs.add(arc);
			if (arc.getRoadDef().isOneway())
				isOneWayTrueCount++;
			if (arc.isForward())
				isForwardTrueCount++;
			if (arc.getRoadDef().getRoadSpeed() > maxRoadSpeed)
				maxRoadSpeed = arc.getRoadDef().getRoadSpeed();
			if (arc.getRoadDef().getRoadClass() > maxRoadClass)
				maxRoadClass = arc.getRoadDef().getRoadClass();
			orAccessMask |= arc.getRoadDef().getAccess();
			roadDefs.add(arc.getRoadDef());
		}

		public float getInitialHeading() {
			return initialHeading;
		}
		
		public boolean isOneway() {
			return isOneWayTrueCount == arcs.size();
		}
		
		public boolean isForward() {
			return isForwardTrueCount == arcs.size();
		}
		
		public void modInitialHeading(float modIH) {
			initialHeading += modIH;
			if (initialHeading >= 180)
				initialHeading -= 360;
			else if (initialHeading < -180)
				initialHeading += 360;

			for (RouteArc arc : arcs) {
				arc.modInitialHeading(modIH);
			}
		}

		public String toString() {
			return arcs.get(0).toString();
		}
	}
	
	public void config(EnhancedProperties props) {
		// undocumented option - usually used for debugging only
		ignoreSharpAngles = props.getProperty("ignore-sharp-angles", false);
		cycleMap = props.getProperty("cycle-map", false);
	}

	public void check(Map<Integer, RouteNode> nodes) {
		if (!ignoreSharpAngles){
			byte sharpAnglesCheckMask = cycleMap ? (byte) (0xff & ~AccessTagsAndBits.FOOT) : AccessTagsAndBits.BIKE;

			for (RouteNode node : nodes.values()){
				fixSharpAngles(node, sharpAnglesCheckMask);				
			}
		}
	}

	public void fixSharpAngles(RouteNode node, byte sharpAnglesCheckMask) {

		List<RouteArc> arcs = node.getArcs();
		if (arcs.size() <= 1) // nothing to do - maybe an edge. Will use 8bit dirs if there is an arc
			return;
		if (arcs.size() == 2) { // common case where two roads join but isn't a junction
			doSimpleJoin(node, arcs);
			return;
		}

		// Combine arcs with nearly the same initial heading.

		// first get direct arcs leaving the node
		List<RouteArc> directArcs = new ArrayList<>();
		for (RouteArc arc : arcs) {
			if (arc.isDirect())
				directArcs.add(arc);
			else
				// AngleChecker runs before addArcsToMajorRoads so there shouldn't be any indirect arcs yet.
				// If this changes, extra care needs to be taken check they are positioned correctly in the list
				// of arcs or that their heading is kept consistent with changes to their direct base arc.
				log.warn("Unexpected indirect arc", arc, "from", node);
		}
		if (directArcs.size() <= 1)
			return; // should not happen
		
		// sort the arcs by initial heading
		directArcs.sort((ra1,ra2) -> {
			int d = Float.compare(ra1.getInitialHeading(), ra2.getInitialHeading());
			if (d != 0)
				return d;
			d = Integer.compare(ra1.getPointsHash(), ra2.getPointsHash());
			if (d != 0)
				return d;
			return Long.compare(ra1.getRoadDef().getId() , ra2.getRoadDef().getId());
		});

		// now combine into groups
		// also calculate minimum angle between arcs for quick decision if more needs to be done
		List<ArcGroup> arcGroups = new ArrayList<>();
		Iterator<RouteArc> iter = directArcs.listIterator();
		RouteArc arc1 = iter.next();
		boolean addArc1 = false;
		float minAngle = 180;
		while (iter.hasNext() || addArc1) {
			ArcGroup ag = new ArcGroup();
			ag.initialHeading = arc1.getInitialHeading();
			ag.addArc(arc1);
			arcGroups.add(ag);
			addArc1 = false;
			while (iter.hasNext()) {
				RouteArc arc2 = iter.next();
				float angleBetween = arc2.getInitialHeading() - ag.initialHeading;
				if (angleBetween < 1) {
					if (arc1.getDest() != arc2.getDest() && arc1.getRoadDef().getId() != arc2.getRoadDef().getId())
						log.warn("sharp angle < 1° at", node.getCoord(), ",maybe duplicated OSM way with bearing", getCompassBearing(arc1.getInitialHeading()));
					ag.addArc(arc2);
				} else {
					if (angleBetween < minAngle)
						minAngle = angleBetween;
					arc1 = arc2;
					if (!iter.hasNext())
						addArc1 = true;
					break;
				}
			}
		}

		int lastInx = arcGroups.size()-1;
		if (lastInx == 0)
			return;
		// handle the last > first groups
		float angleBetween = arcGroups.get(0).initialHeading - arcGroups.get(lastInx).initialHeading + 360;
		if (angleBetween < 1) {
			if (lastInx == 1) // just two that would be merged, so can stop
				return;
			for (RouteArc arc : arcGroups.get(lastInx).arcs)
				arcGroups.get(0).addArc(arc);
			arcGroups.remove(lastInx);
		} else if (angleBetween < minAngle) {
			minAngle = angleBetween;
		}
		
		if (minAngle >= SHARP_DEGREES) {
			if (minAngle >= COMPACT_DIR_DEGREES)
				// RouteNode default is setUseCompactDirs(false);
				node.setUseCompactDirs(true);
			return;
		}

		final int n = arcGroups.size();
		// scan the angles and see what needs attention
		// Note: This algorithm won't spot and fix a sharp angle where there is a 'no-access' arc between

		class AngleAttr {
			float angle;
			float minAngle;
		}

		AngleAttr[] angles = new AngleAttr[n];
		for (int i = 0; i < n; i++){
			ArcGroup ag1 = arcGroups.get(i);
			ArcGroup ag2 = arcGroups.get(i+1 < n ? i+1 : 0);
			AngleAttr aa = new AngleAttr();
			angles[i] = aa;
			aa.angle = ag2.getInitialHeading() - ag1.getInitialHeading();
			if (i+1 >= n)
				aa.angle += 360;
			aa.minAngle = Math.min(MIN_ANGLE, aa.angle);
			float saveMinAngle = aa.minAngle;

			if (ag1.isOneway() && ag2.isOneway() && (ag1.isForward() == ag2.isForward()))
				continue; // two one-ways entry to entry or exit to exit
			byte pathAccessMask = (byte) (ag1.orAccessMask & ag2.orAccessMask);
			if (pathAccessMask == 0)
				continue; // no common vehicle allowed on both arcs
			if (pathAccessMask == AccessTagsAndBits.FOOT)
				continue; // only pedestrians - sharp angle not a problem
			if (Math.min(ag1.maxRoadSpeed, ag2.maxRoadSpeed) == 0)
				continue; // eg service/parking where sharp angle probably indicates shouldn't turn here
			
			aa.minAngle = SHARP_DEGREES;

//			int sumSpeeds = ag1.maxRoadSpeed + ag2.maxRoadSpeed;
			// the Garmin algorithm sees rounded values, so the thresholds are probably 
			// near 22.5 (0x10), 45(0x20), 67.5 (0x30), 90, 112.5 (0x40)

			// the following code doesn't seem to improve anything, I leave it as comment 
			// for further experiments.
//			if (cycleMap){
//				if (sumSpeeds >= 14)
//					maskedMinAngle = 0x80;
//				if (sumSpeeds >= 12)
//					maskedMinAngle = 0x70;
//				if (sumSpeeds >= 10)
//					maskedMinAngle = 0x60;
//				if (sumSpeeds >= 8)
//					maskedMinAngle = 0x50;
//				else if (sumSpeeds >= 6)
//					maskedMinAngle = 0x40;
//				else if (sumSpeeds >= 4)
//					maskedMinAngle = 0x30;
//			}
			// With changes to switch compactDirs and working in degrees rather than masked sectors,
			// the above variables are wrong, but the idea holds
			
			if (aa.angle >= aa.minAngle)
				continue;
			
			String ignoredReason = null;
			if ((pathAccessMask & sharpAnglesCheckMask) == 0)
				ignoredReason = "because it can not be used by bike";
			else if (ag1.isOneway() && ag2.isOneway() && n == 3) {
				// both arcs are one-ways, probably the road splits/joins carriageways here
				ignoredReason = "because it seems to be a flare road";
			}		
			else if (ag1.roadDefs.size() == 1 && ag2.roadDefs.size() == 1 && ag1.roadDefs.containsAll(ag2.roadDefs)){
				ignoredReason = "because both arcs belong to the same road";
			}
			if (ignoredReason != null){
				if (log.isInfoEnabled()){
					log.info("sharp angle", aa.angle, "° at", node.getCoord(),
							 "headings", getCompassBearing(ag1.getInitialHeading()), getCompassBearing(ag2.getInitialHeading()),
							 "speeds", ag1.maxRoadSpeed, ag2.maxRoadSpeed);
					log.info("ignoring", ignoredReason);
				}
				aa.minAngle = saveMinAngle; // restore as don't want to widen
			}
		}
		
		// go through the angles again and try to increase any that are less than minAngle
		for (int i = 0; i < n; i++){
			AngleAttr aa = angles[i];
			float wantedIncrement = aa.minAngle - aa.angle;
			if (wantedIncrement <= 0)
				continue;
			float oldAngle = aa.angle;
			ArcGroup ag1 = arcGroups.get(i);
			ArcGroup ag2 = arcGroups.get(i+1 < n ? i+1 : 0);
			if (log.isInfoEnabled()){
				log.info("sharp angle", aa.angle, "° at", node.getCoord(),
						 "headings", getCompassBearing(ag1.getInitialHeading()), getCompassBearing(ag2.getInitialHeading()),
						 "speeds", ag1.maxRoadSpeed, ag2.maxRoadSpeed,
						 "classes",ag1.maxRoadClass, ag2.maxRoadClass);
			}

			// XXX restrictions ?
			AngleAttr predAA = angles[i == 0 ? n - 1 : i - 1];
			AngleAttr nextAA = angles[i >= n - 1 ? 0 : i + 1];

			// we can increase the angle by changing the heading values of one or both arcs
			// How much we can encroach on the adjacent angles
			float deltaPred = predAA.angle - predAA.minAngle;
			float deltaNext = nextAA.angle - nextAA.minAngle;

			if (deltaNext > 0 && deltaPred > 0) { // can take from both
				if (ag1.maxRoadClass == ag2.maxRoadClass &&
					ag1.maxRoadSpeed == ag2.maxRoadSpeed) { // take from both in ratio to available
					deltaNext = Math.min(deltaNext, wantedIncrement * deltaNext / (deltaNext + deltaPred));
					deltaPred = Math.min(deltaPred, wantedIncrement - deltaNext);
			    } else if (ag1.maxRoadClass > ag2.maxRoadClass ||
						   (ag1.maxRoadClass == ag2.maxRoadClass &&
							ag1.maxRoadSpeed > ag2.maxRoadSpeed)) { // take as much as poss from next
					if (deltaNext >= wantedIncrement) {
						deltaNext = wantedIncrement;
						deltaPred = 0;
					} else {
						deltaPred = Math.min(deltaPred, wantedIncrement - deltaNext);
					}
				} else  { // take as much as possible from pred
					if (deltaPred >= wantedIncrement) {
						deltaPred = wantedIncrement;
						deltaNext = 0;
					} else {
						deltaNext = Math.min(deltaNext, wantedIncrement - deltaPred);
					}
				}
			} else if (deltaNext > 0) {
				if (deltaNext > wantedIncrement)
					deltaNext = wantedIncrement;
			} else {
				if (deltaPred > wantedIncrement)
					deltaPred = wantedIncrement;
			}

			if (deltaNext > 0) { // can take some from the following angle
				log.info("increasing arc with heading", getCompassBearing(ag2.getInitialHeading()), "by", deltaNext);
				ag2.modInitialHeading(deltaNext);
				aa.angle += deltaNext;
				nextAA.angle -= deltaNext;
				wantedIncrement -= deltaNext;
			}
			if (deltaPred > 0) { // and some from the previous angle
				log.info("decreasing arc with heading", getCompassBearing(ag1.getInitialHeading()), "by", deltaPred);
				ag1.modInitialHeading(-deltaPred);
				aa.angle += deltaPred;
				predAA.angle -= deltaPred;
				wantedIncrement -= deltaPred;
			}
	
			if (wantedIncrement > 0) {
				if (aa.angle == oldAngle)
					log.info("don't know how to fix it", wantedIncrement);
				else
					log.info("don't know how to enlarge it further", wantedIncrement);
			}
		}

		// see what the smallest angle is now; might be some that couldn't fix and some that didn't matter
		minAngle = 180;
		for (int i=0; i < n; ++i) {
			if (angles[i].angle < minAngle)
				minAngle = angles[i].angle;
		}
		if (minAngle >= COMPACT_DIR_DEGREES)
			node.setUseCompactDirs(true);
	}


	/**
	 * Called when there are exactly 2 arcs on the routing node.
	 * This is a common case where a road changes speed/name/class.
	 * It just checks for the angle of the 2 roads and increases it if sharp to reduce any potential
	 * excessive cost for the section so it won't be avoided unneccessarily by routing decisions elsewhere.
	 * Could check that both arcs usable by vehicles and do nothing if not, but hardly worth the bother.
	 *
	 * @param node
	 * @param arcs
	 */
	private static void doSimpleJoin(RouteNode node, List<RouteArc> arcs) {
		RouteArc arc1 = arcs.get(0);
		RouteArc arc2 = arcs.get(1);
		float angle = arc1.getInitialHeading() - arc2.getInitialHeading();
		float extra = 0; // signed amount where +ve increases arc1 & decreases arc2
		if (angle > 360 - SHARP_DEGREES) // crosses -180
			extra = 360 - angle - SHARP_DEGREES;
		else if (angle < SHARP_DEGREES - 360) // ditto the other way
			extra = SHARP_DEGREES - angle - 360;
		else if (angle > 0) {
			if (angle < SHARP_DEGREES)
				extra = SHARP_DEGREES - angle;
		} else if (angle < 0) {
			if (angle > -SHARP_DEGREES)
				extra = -angle - SHARP_DEGREES;
		} // else angle == 0 and can't widen as don't know which way around to do it
		if (extra != 0) {
			if (log.isInfoEnabled())
				log.info("join angle", angle, "° at", node.getCoord(), "increased by", extra);
			arc1.modInitialHeading(+extra/2);
			arc2.modInitialHeading(-extra/2);
		}
		node.setUseCompactDirs(true); // this won't cause any problems
	}


	/**
	 * for log messages
	 */
	private static String getCompassBearing (float bearing){
		float cb = (bearing + 360) % 360;
		return Math.round(cb) + "°";
	}

}
