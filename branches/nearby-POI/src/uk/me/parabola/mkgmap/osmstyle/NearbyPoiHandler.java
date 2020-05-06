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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.util.EnhancedProperties;

public class NearbyPoiHandler {
	private static final Logger log = Logger.getLogger(NearbyPoiHandler.class);
	private final HashMap<Integer, Map<String, MapPoint>> pointMap = new HashMap<>();

	private enum NearbyPoiActOn {
		ActOnAll, ActOnNamed, ActOnUnnamed
	}

	private enum NearbyPoiAction {
		DeletePOI, DeleteName, MergeAtMidPoint
	}

	private class NearbyPoiRule {
		int minPoiType = 0;
		int maxPoiType = 0;
		int maxDistance = 0;
		NearbyPoiActOn actOn = NearbyPoiActOn.ActOnAll;
		NearbyPoiAction action = NearbyPoiAction.DeletePOI;
	}

	private ArrayList<NearbyPoiRule> nearbyPoiRules = new ArrayList<>();
	private NearbyPoiRule defaultNamedNearbyPoiRule = new NearbyPoiRule();
	private NearbyPoiRule defaultUnnamedNearbyPoiRule = new NearbyPoiRule();

	public NearbyPoiHandler(EnhancedProperties props) {
		defaultNamedNearbyPoiRule.actOn = NearbyPoiActOn.ActOnNamed;
		defaultUnnamedNearbyPoiRule.actOn = NearbyPoiActOn.ActOnUnnamed;
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
				nearbyPoiRule.actOn = NearbyPoiActOn.ActOnNamed;
				break;
	
			case "unnamed":
				nearbyPoiRule.actOn = NearbyPoiActOn.ActOnUnnamed;
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
					nearbyPoiRule.action = NearbyPoiAction.DeleteName;
					break;
	
				case "merge-at-mid-point":
					nearbyPoiRule.action = NearbyPoiAction.MergeAtMidPoint;
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
					if (nearbyPoiRule.actOn != NearbyPoiActOn.ActOnUnnamed)
						defaultNamedNearbyPoiRule = nearbyPoiRule;
					if (nearbyPoiRule.actOn != NearbyPoiActOn.ActOnNamed)
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

	private static boolean applyRuleAction(Node node, MapPoint mp, MapPoint old, NearbyPoiAction action) {
		int type = mp.getType();
		Coord location = mp.getLocation();
		switch (action) {
		case DeletePOI:
			if (log.isInfoEnabled()) {
				log.info("Removed nearby duplicate POI with type", GType.formatType(type), mp.getName(), getLogInfo(node));
			}
			return true;

		case DeleteName:
			if (log.isInfoEnabled()) {
				log.info("Removed name from nearby duplicate POI with type", GType.formatType(type), mp.getName(), getLogInfo(node));
			}
			mp.setName("");
			return false;

		case MergeAtMidPoint:
			Coord oldLocation = old.getLocation();
			Coord newLocation = location.makeBetweenPoint(oldLocation, 0.5);
			old.setLocation(newLocation);
			if (log.isInfoEnabled()) {
				log.info("Removed nearby duplicate POI with type", GType.formatType(type), mp.getName(),
						getLogInfo(node), "and moved location of mearby POI from", oldLocation, "to", newLocation);
			}
			return true;
		}
		return false; // should not happen
	}
	
	public boolean isDuplicatePOI(Node node, MapPoint mp) {
		int type = mp.getType();
		Map<String, MapPoint> typeMap = pointMap.computeIfAbsent(type, HashMap::new);
		String name = mp.getName();
		MapPoint old = typeMap.get(name);
		if (old == null)
			typeMap.put(mp.getName(), mp);
		else {
			Coord location = mp.getLocation();
			Coord oldLocation = old.getLocation();
			if ((location != null) && (oldLocation != null)) {					
				if (oldLocation.equals(location)) {
					if (log.isInfoEnabled())
						log.info("Removed duplicate POI with type", GType.formatType(type), mp.getName(), getLogInfo(node));
					return true;
				}
				
				boolean ruleApplied = false;
				for (NearbyPoiRule rule : nearbyPoiRules) {
					if (rule.minPoiType > type)
						break; // rules are in ascending order of poiType, so we can stop trying other rules

					if ((rule.minPoiType <= type) && (rule.maxPoiType >= type) &&
						((rule.actOn == NearbyPoiActOn.ActOnAll) ||
						 ((rule.actOn == NearbyPoiActOn.ActOnNamed) && (name != null) && !name.isEmpty()) ||
						 ((rule.actOn == NearbyPoiActOn.ActOnUnnamed) && ((name == null) || name.isEmpty())))) {
						ruleApplied = true;
						if (location.distance(oldLocation) <= rule.maxDistance)
							return applyRuleAction(node, mp, old, rule.action);
					}
				}

				if (!ruleApplied) {
					NearbyPoiRule defaultRule = (name == null) ? defaultUnnamedNearbyPoiRule : defaultNamedNearbyPoiRule;
					if ((location.distance(oldLocation) < defaultRule.maxDistance))
						return applyRuleAction(node, mp, old, defaultRule.action);
				}
			}
		}
		return false;
	}
	
	private static String getLogInfo(Node n) {
		return "for element " + (FakeIdGenerator.isFakeId(n.getId()) ? "generated from " + n.getOriginalId()
				: n.toBrowseURL() + " at " + n.getLocation().toOSMURL());
	}
}
