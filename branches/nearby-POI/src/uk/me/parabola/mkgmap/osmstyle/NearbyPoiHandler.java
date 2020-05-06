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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	final Map<MapPoint, Node> data = new LinkedHashMap<>();

	private enum NearbyPoiActOn {
		ACT_ON_ALL, ACT_ON_NAMED, ACT_ON_UNNAMED
	}

	private enum NearbyPoiAction {
		DELETE_POI, DELETE_NAME, MERGE_AT_MID_POINT
	}

	private class NearbyPoiRule {
		int minPoiType = 0;
		int maxPoiType = 0;
		int maxDistance = 0;
		NearbyPoiActOn actOn = NearbyPoiActOn.ACT_ON_ALL;
		NearbyPoiAction action = NearbyPoiAction.DELETE_POI;
	}

	private ArrayList<NearbyPoiRule> nearbyPoiRules = new ArrayList<>();
	private NearbyPoiRule defaultNamedNearbyPoiRule = new NearbyPoiRule();
	private NearbyPoiRule defaultUnnamedNearbyPoiRule = new NearbyPoiRule();

	public NearbyPoiHandler(EnhancedProperties props) {
		defaultNamedNearbyPoiRule.actOn = NearbyPoiActOn.ACT_ON_NAMED;
		defaultUnnamedNearbyPoiRule.actOn = NearbyPoiActOn.ACT_ON_UNNAMED;
		String[] rules = props.getProperty("nearby-poi-rules", "").split(",");
		String rulesFileName = props.getProperty("nearby-poi-rules-config", "");
		if (!rulesFileName.isEmpty()) {
			File file = new File(rulesFileName);
			try {
				List<String> fileRules = Files.readAllLines(file.toPath());
				for (int i = 0; i < fileRules.size(); i++) {
					String rule = fileRules.get(i);
					int hashPos = rule.indexOf('#');
					if (hashPos >=0)
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
	}
	
	private void parseRule(String rule, int i) {
		NearbyPoiRule nearbyPoiRule = new NearbyPoiRule();
		boolean valid = true;
		String [] ruleParts = rule.split(":", 4);
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
	
				case "merge-at-mid-point":
					nearbyPoiRule.action = NearbyPoiAction.MERGE_AT_MID_POINT;
					break;
	
				default:
					valid = false;
					log.error("Invalid Action value", ruleParts[2], "in nearby poi rule", i + 1, rule,
							"- 'delete-poi', 'delete-name' or'merge-at-mid-point' expected.");
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

	public void add(MapPoint mp, Node node) {
		data.put(mp, node);
	}

	public List<MapPoint> getPOI() {
		return new ArrayList<>(data.keySet());
	}

	public Stream<MapPoint> deDuplicate() {
		Set<MapPoint> allPOI = data.keySet();
		Set<MapPoint> toKeep = new HashSet<>();
		Map<Integer, Map<String, List<MapPoint>>> byTypeAndName = allPOI.stream()
                .collect(Collectors.groupingBy(MapPoint::getType,
                		Collectors.groupingBy(mp -> mp.getName() == null ? "" : mp.getName(),
                				Collectors.toList())));
		for (Entry<Integer, Map<String, List<MapPoint>>> e : byTypeAndName.entrySet()) {
			for (Entry<String, List<MapPoint>> e2 : e.getValue().entrySet()) {
				toKeep.addAll(reduce(e2.getValue()));
			}
		}
		if (toKeep.size() < allPOI.size())
			return allPOI.stream().filter(toKeep::contains);
		else 
			return allPOI.stream();
	}

	private Collection<MapPoint> reduce(List<MapPoint> points) {
		if (points.size() == 1)
			return points;
		int type = points.get(0).getType();
		String name = points.get(0).getName();
		for (NearbyPoiRule rule : nearbyPoiRules) {
			if (rule.minPoiType > type)
				break; // rules are in ascending order of poiType, so we can
						// stop trying other rules

			if ((rule.minPoiType <= type) && (rule.maxPoiType >= type)
					&& ((rule.actOn == NearbyPoiActOn.ACT_ON_ALL)
							|| ((rule.actOn == NearbyPoiActOn.ACT_ON_NAMED) && (name != null) && !name.isEmpty())
							|| ((rule.actOn == NearbyPoiActOn.ACT_ON_UNNAMED) && ((name == null) || name.isEmpty())))) {
				return applyRule(points, rule);
			}
		}

		NearbyPoiRule defaultRule = (name == null) ? defaultUnnamedNearbyPoiRule : defaultNamedNearbyPoiRule;
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
			
			final Coord middle = calcMiddle(biggestCloud);
			
			// select point that is closest to the middle
			MapPoint bestPoint = biggestCloud.stream()
					.min(Comparator.comparingDouble(mp -> middle.distance(mp.getLocation())))
					.orElse(biggestCloud.iterator().next()); // should not happen, stream is not empty
			
			performAction(rule.action, bestPoint, middle, biggestCloud, toKeep);
			
			// remove the processed points, they may also appear in other clouds
			final Set<MapPoint> done = new HashSet<>(biggestCloud);
			groupsMap.entrySet().removeIf(e -> e.getValue().removeAll(done));
			groupsMap.entrySet().removeIf(e -> e.getValue().isEmpty());
		}
		
		return rule.action != NearbyPoiAction.DELETE_NAME ? toKeep : points;
	}

	private static Map<MapPoint, Set<MapPoint>> buildGroups(List<MapPoint> points, int maxDistance, List<MapPoint> toKeep) {
		final KdTree<MapPoint> kdTree = new KdTree<>();
		points.forEach(kdTree::add);
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

	private void performAction(NearbyPoiAction action, MapPoint bestPoint, Coord middle,
			Set<MapPoint> biggestCloud, List<MapPoint> toKeep) {
		if (action == NearbyPoiAction.DELETE_NAME) {
			final MapPoint midPoint = bestPoint;
			biggestCloud.stream().filter(mp -> mp != midPoint).forEach(mp -> {
				if (log.isInfoEnabled()) {
					log.info("Removed name from nearby", getLogInfo(mp));
				}
				mp.setName(null);
			});
			return;
		}
		// this is the point that will be displayed
		toKeep.add(bestPoint);
		boolean doMove = action == NearbyPoiAction.MERGE_AT_MID_POINT;
		if (log.isInfoEnabled()) {
			logRemove(biggestCloud, bestPoint, middle, doMove);
		}
		if (doMove) {
			bestPoint.setLocation(middle);
		}
	}

	private void logRemove(Set<MapPoint> biggestCloud, MapPoint bestPoint, Coord middle, boolean doMove) {
		for (MapPoint mp : biggestCloud) {
			if (mp != bestPoint) {
				if (mp.getLocation().equals(bestPoint.getLocation())) {
					log.info("Removed", getLogInfo(mp));
				} else {
					log.info("Removed nearby", getLogInfo(mp),
							doMove ? "and moved location of nearby POI from " + bestPoint.getLocation()
									+ " to " + middle : "");
				}
			}
		}
	}

	private static Coord calcMiddle(Set<MapPoint> points) {
		// calculate centre of cloud
		double lat = 0;
		double lon = 0;
		final int n = points.size();
		for (MapPoint mp : points) {
			Coord p = mp.getLocation();
			lat += (double) p.getHighPrecLat() / n;
			lon += (double) p.getHighPrecLon() / n;
		}
		return Coord.makeHighPrecCoord((int) Math.round(lat), (int) Math.round(lon));
	}

	private String getLogInfo(MapPoint mp) {
		StringBuilder sb = new StringBuilder("duplicate ");
		String name = mp.getName();
		if (name == null || name.isEmpty())
			sb.append("unnamed ");
		sb.append("POI with type " + GType.formatType(mp.getType()));
		if (name != null && !name.isEmpty())
			sb.append(" " + mp.getName());
		Node n = data.get(mp);
		if (n != null) {
			sb.append(" for element " + (FakeIdGenerator.isFakeId(n.getId()) ? "generated from " + n.getOriginalId()
					: n.toBrowseURL() + " at " + n.getLocation().toOSMURL()));
		}
		return sb.toString();
	}
	
}
