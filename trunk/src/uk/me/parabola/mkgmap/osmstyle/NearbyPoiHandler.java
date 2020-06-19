/*
 * Copyright.
 *
 * Created by Mike Baggaley 2020
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.KdTree;

public class NearbyPoiHandler {
	private static final Logger log = Logger.getLogger(NearbyPoiHandler.class);
	private final Map<MapPoint, Node> data = new LinkedHashMap<>();

	private enum NearbyPoiActOn {
		ACT_ON_ALL, ACT_ON_NAMED, ACT_ON_UNNAMED
	}

	private enum NearbyPoiAction {
		DELETE_POI, DELETE_NAME
	}

	private class NearbyPoiRule {
		int minPoiType = 0;
		int maxPoiType = 0;
		int maxDistance = 0;
		NearbyPoiActOn actOn = NearbyPoiActOn.ACT_ON_ALL;
		NearbyPoiAction action = NearbyPoiAction.DELETE_POI;
		
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(GType.formatType(minPoiType));
			if (minPoiType != maxPoiType) {
				sb.append('-').append(GType.formatType(maxPoiType));
			}
			if (actOn == NearbyPoiActOn.ACT_ON_NAMED)
				sb.append("/named");
			if (actOn == NearbyPoiActOn.ACT_ON_UNNAMED)
				sb.append("/unnamed");
			sb.append(':').append(maxDistance);
			if (action == NearbyPoiAction.DELETE_NAME) {
				sb.append(":delete-name");
			}
			return sb.toString();
		}
	}

	private final ArrayList<NearbyPoiRule> nearbyPoiRules = new ArrayList<>();
	private NearbyPoiRule defaultNamedNearbyPoiRule;
	private NearbyPoiRule defaultUnnamedNearbyPoiRule;

	public NearbyPoiHandler(EnhancedProperties props) {
		resetRules();
		String[] rules = props.getProperty("nearby-poi-rules", "").split(",");
		String rulesFileName = props.getProperty("nearby-poi-rules-config", "");
		if (!rulesFileName.isEmpty()) {
			File file = new File(rulesFileName);
			try {
				List<String> fileRules = Files.readAllLines(file.toPath());
				for (int i = 0; i < fileRules.size(); i++) {
					String rule = fileRules.get(i);
					int hashPos = rule.indexOf('#');
					if (hashPos >= 0)
						fileRules.set(i, rule.substring(0, hashPos));
				}
				fileRules.addAll(Arrays.asList(rules));
				rules = fileRules.toArray(rules);
			} catch (IOException ex) {
				log.error("Error reading nearby POI rules file", rulesFileName);
			}
		}
		for (int i = 0; i < rules.length; i++) {
			String rule = rules[i].replaceAll("\\s+", "");
			if (!rule.isEmpty()) {
				parseRule(rule, i);
			}
		}
		if (!checkRules()) {
			resetRules();
		}
	}
	
	private void resetRules() {
		nearbyPoiRules.clear();
		defaultNamedNearbyPoiRule = new NearbyPoiRule();
		defaultUnnamedNearbyPoiRule = new NearbyPoiRule();
		defaultNamedNearbyPoiRule.actOn = NearbyPoiActOn.ACT_ON_NAMED;
		defaultUnnamedNearbyPoiRule.actOn = NearbyPoiActOn.ACT_ON_UNNAMED;
	}
	
	private boolean checkRules() {
		// check for conflicting overlaps in intervals
		Map<Integer,NearbyPoiRule> test = new HashMap<>();
		for (NearbyPoiRule r : nearbyPoiRules) {
			int i = r.minPoiType;
			while (i <= r.maxPoiType) {
				if ((i & 0xff) > 0x1f)
					i = ((i >> 8) + 1) << 8; 
				NearbyPoiRule old = test.put(i, r);
				if (old != null
						&& (r.maxDistance != old.maxDistance || r.action != old.action || r.actOn != old.actOn)) {
					log.error("Different rules match for", GType.formatType(i));
					log.error(old);
					log.error(r);
					log.error("NearbyPoiHandler is disabled, only identical POI will be removed");
					return false;
				}
				i++;
			} 		
			
		}
		return true;
	}
	
	private void parseRule(String rule, int i) {
		NearbyPoiRule nearbyPoiRule = new NearbyPoiRule();
		boolean valid = true;
		String[] ruleParts = rule.split(":", 4);
		String part1 = ruleParts[0];
		int slashPos = part1.indexOf('/');
		if (slashPos > 0) {
			String part1Suffix = part1.substring(slashPos + 1);
			part1 = part1.substring(0, slashPos);
			switch (part1Suffix.toLowerCase()) {
			case "named":
				nearbyPoiRule.actOn = NearbyPoiActOn.ACT_ON_NAMED;
				break;
	
			case "unnamed":
				nearbyPoiRule.actOn = NearbyPoiActOn.ACT_ON_UNNAMED;
				break;
	
			case "all":
				break;
	
			default:
				valid = false;
				log.error("Invalid Act On value", part1Suffix, "in nearby poi rule", i + 1, rule, "- 'all', 'named' or 'unnamed' expected.");
				break;
			}
		}
		if (!"*".equals(part1)) {
			String[] poiRange = part1.split("-", 2);
			try {
				nearbyPoiRule.minPoiType = Integer.decode(poiRange[0]);
				if (poiRange.length == 1)
					nearbyPoiRule.maxPoiType = nearbyPoiRule.minPoiType;
				else {
					nearbyPoiRule.maxPoiType = Integer.decode(poiRange[1]);
					if (nearbyPoiRule.maxPoiType < nearbyPoiRule.minPoiType) {
						valid = false;
						log.error("Invalid POI range", part1, "in nearby poi rule", i + 1, rule);
					}
				}
			} catch (Exception ex) {
				valid = false;
				log.error("Invalid POI type", part1, "in nearby poi rule", i + 1, rule);
			}
		}
		if (ruleParts.length > 1) {
			try {
				nearbyPoiRule.maxDistance = Integer.parseInt(ruleParts[1]);
			} catch (Exception ex) {
				valid = false;
				log.error("Invalid maximum distance", ruleParts[1], "in nearby poi rule", i + 1, rule);
			}
			if (ruleParts.length > 2) {
				switch (ruleParts[2].toLowerCase()) {
				case "delete-poi":
					break;
	
				case "delete-name":
					nearbyPoiRule.action = NearbyPoiAction.DELETE_NAME;
					break;
	
				default:
					valid = false;
					log.error("Invalid Action value", ruleParts[2], "in nearby poi rule", i + 1, rule,
							"- 'delete-poi', or 'delete-name' expected.");
					break;
				}
			}
			if (ruleParts.length > 3)
				log.warn("Unexpected text", ruleParts[3], "in nearby poi rule", i + 1, rule);
			if (valid) {
				if ("*".equals(part1)) {
					if (nearbyPoiRule.actOn != NearbyPoiActOn.ACT_ON_UNNAMED)
						defaultNamedNearbyPoiRule = nearbyPoiRule;
					if (nearbyPoiRule.actOn != NearbyPoiActOn.ACT_ON_NAMED)
						defaultUnnamedNearbyPoiRule = nearbyPoiRule;
				} else {
					int index;
					int count = nearbyPoiRules.size();
					for (index = 0; index < count; index++) {
						NearbyPoiRule ruleAtIndex = nearbyPoiRules.get(index);
						if ((nearbyPoiRule.minPoiType < ruleAtIndex.minPoiType) ||
							((nearbyPoiRule.minPoiType == ruleAtIndex.minPoiType) &&
							 (nearbyPoiRule.maxDistance < ruleAtIndex.maxDistance))){
							nearbyPoiRules.add(index, nearbyPoiRule);
							break;
						}
					}
					if (index == count)
						nearbyPoiRules.add(nearbyPoiRule);
				}
			}
		} else {
			log.error("Maximum distance expected for POI type", part1, "in nearby poi rule", i + 1, rule);
		}
	}

	/**
	 * Add POI and corresponding node for later processing.
	 * @param mp the POI
	 * @param node the node
	 */
	public void add(final MapPoint mp, final Node node) {
		data.put(mp, node);
	}

	/**
	 * 
	 * @return unmodifiable Set of all POI
	 */
	public Set<MapPoint> getAllPOI() {
		return Collections.unmodifiableSet(data.keySet());
	}

	/**
	 * @return Collection of deduplicated POI
	 */
	public Collection<MapPoint> deDuplicate() {
		Set<MapPoint> allPOI = data.keySet();
		if (allPOI.size() < 2)
			return allPOI; // nothing to do
		Comparator<MapPoint> typeAndName = Comparator.comparingInt(MapPoint::getType)
				.thenComparing(mp -> mp.getName() == null ? "" : mp.getName());
		List<MapPoint> sorted = allPOI.stream().sorted(typeAndName).collect(Collectors.toList());
		Set<MapPoint> toKeep = new HashSet<>();

		int first = 0;
		int last = 0;
		int n = sorted.size();
		while (first < n) {
			while (last < n && (0 == typeAndName.compare(sorted.get(first), sorted.get(last)))) {
				last++;
			}
			toKeep.addAll(reduce(sorted.subList(first, last)));
			first = last;
		}
		if (toKeep.size() < allPOI.size())
			return allPOI.stream().filter(toKeep::contains).collect(Collectors.toList());
		else
			return allPOI;
	}
	
	/**
	 * @param points list of points with equal type and name
	 * @return possibly reduced list
	 */
	private Collection<MapPoint> reduce(List<MapPoint> points) {
		if (points.size() == 1)
			return points;
		int type = points.get(0).getType();
		String name = points.get(0).getName();
		boolean isNamed = name != null && !name.isEmpty();
		for (NearbyPoiRule rule : nearbyPoiRules) {
			if (rule.minPoiType > type)
				break; // rules are in ascending order of poiType, so we can
						// stop trying other rules

			if ((rule.minPoiType <= type) && (rule.maxPoiType >= type)
					&& ((rule.actOn == NearbyPoiActOn.ACT_ON_ALL)
							|| ((rule.actOn == NearbyPoiActOn.ACT_ON_NAMED) && isNamed)
							|| ((rule.actOn == NearbyPoiActOn.ACT_ON_UNNAMED) && !isNamed))) {
				return applyRule(points, rule);
			}
		}

		NearbyPoiRule defaultRule = isNamed ? defaultNamedNearbyPoiRule : defaultUnnamedNearbyPoiRule;
		return applyRule(points, defaultRule);
	}

	private Collection<MapPoint> applyRule(List<MapPoint> points, NearbyPoiRule rule) {
		List<MapPoint> toKeep = new ArrayList<>();
		Map<MapPoint, Set<MapPoint>> groupsMap = buildGroups(points, rule.maxDistance, toKeep);

		while (!groupsMap.isEmpty()) {
			
			// find  cloud with the highest number of POI
			Set<MapPoint> biggestCloud = groupsMap.values().stream().max(Comparator.comparingInt(Set::size)).orElse(null);
			if (biggestCloud == null || biggestCloud.isEmpty())
				break;
			
			final Coord middle = calcMiddle(biggestCloud).getDisplayedCoord();
			final Set<MapPoint> done = new HashSet<>(biggestCloud);
			removeSimpleDuplicates(biggestCloud);
			
			// select point that is closest to the middle
			MapPoint bestPoint = biggestCloud.stream()
					.min(Comparator.comparingDouble(mp -> middle.distance(mp.getLocation().getDisplayedCoord())))
					.orElse(biggestCloud.iterator().next()); // should not happen, stream is not empty
			
			performAction(rule.action, bestPoint, biggestCloud, toKeep);
			
			// remove the processed points, they may also appear in other clouds
			groupsMap.entrySet().forEach(e -> e.getValue().removeAll(done));
			groupsMap.entrySet().removeIf(e -> e.getValue().isEmpty());
		}
		
		return rule.action != NearbyPoiAction.DELETE_NAME ? toKeep : points;
	}

	private void removeSimpleDuplicates(Set<MapPoint> biggestCloud) {
		Long2ObjectOpenHashMap<MapPoint> locations = new Long2ObjectOpenHashMap<>();
		Iterator<MapPoint> iter = biggestCloud.iterator();
		while (iter.hasNext()) {
			MapPoint mp = iter.next();
			if (locations.put(Utils.coord2Long(mp.getLocation()), mp) != null) {
				if (log.isInfoEnabled()) {
					log.info("Removed duplicate", getLogInfo(mp));
				}
				iter.remove();
			}
		}
	}

	private static Map<MapPoint, Set<MapPoint>> buildGroups(List<MapPoint> points, int maxDistance, List<MapPoint> toKeep) {
		final KdTree<MapPoint> kdTree = new KdTree<>();
		points.forEach(kdTree::add); // should better use getDisplayedCoord()
		Map<MapPoint, Set<MapPoint>> groupsMap = new LinkedHashMap<>();
		for (MapPoint mp : points) {
			Set<MapPoint> set = kdTree.findClosePoints(mp, maxDistance);
			if (set.size() <= 1) {
				toKeep.add(mp); // no other point is close
			} else {
				// collect cloud
				groupsMap.put(mp, set);
			}
		}
		return groupsMap;
	}

	private void performAction(NearbyPoiAction action, MapPoint bestPoint, Set<MapPoint> biggestCloud,
			List<MapPoint> toKeep) {
		if (action == NearbyPoiAction.DELETE_NAME) {
			final MapPoint midPoint = bestPoint;
			biggestCloud.stream().filter(mp -> mp != midPoint).forEach(mp -> {
				if (log.isInfoEnabled()) {
					double dist = mp.getLocation().getDisplayedCoord().distance(bestPoint.getLocation().getDisplayedCoord());
					log.info(String.format("Removed name from nearby(<= %d m)", (long) Math.ceil(dist)), getLogInfo(mp));
				}
				mp.setName(null);
			});
			return;
		}
		// this is the point that will be displayed
		toKeep.add(bestPoint);
		if (log.isInfoEnabled()) {
			logRemoval(biggestCloud, bestPoint);
		}
	}

	private void logRemoval(Set<MapPoint> biggestCloud, MapPoint bestPoint) {
		for (MapPoint mp : biggestCloud) {
			if (mp != bestPoint) {
				double dist = mp.getLocation().getDisplayedCoord().distance(bestPoint.getLocation().getDisplayedCoord());
				log.info(String.format("Removed nearby (<= %d m)", (long) Math.ceil(dist)), getLogInfo(mp));
			}
		}
	}

	private static Coord calcMiddle(final Set<MapPoint> points) {
		// calculate centre of cloud
		double lat = 0;
		double lon = 0;
		final int n = points.size();
		for (MapPoint mp : points) {
			Coord p = mp.getLocation().getDisplayedCoord();
			lat += (double) p.getHighPrecLat() / n;
			lon += (double) p.getHighPrecLon() / n;
		}
		return Coord.makeHighPrecCoord((int) Math.round(lat), (int) Math.round(lon));
	}

	private String getLogInfo(final MapPoint mp) {
		StringBuilder sb = new StringBuilder();
		String name = mp.getName();
		sb.append("POI with type " + GType.formatType(mp.getType()));
		sb.append(" \"");
		if (name != null && !name.isEmpty())
			sb.append(mp.getName());
		sb.append('"');
		Node n = data.get(mp);
		if (n != null) {
			sb.append(" for element ");
			if (FakeIdGenerator.isFakeId(n.getId())) {
				sb.append("generated from ").append(n.getOrigElement()).append(' ').append(n.getOriginalId());
			} else {
				sb.append(n.toBrowseURL()).append(" at ").append(n.getLocation().toOSMURL());
			}
		}
		return sb.toString();
	}
	
}
