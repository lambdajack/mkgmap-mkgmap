/*
 * Copyright (C) 2011.
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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.NameFinder;
import uk.me.parabola.mkgmap.osmstyle.function.AreaSizeFunction;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Adds a POI for each area and multipolygon with the same tags in case the add-pois-to-areas option
 * is set. Adds multiple POIs to each line if the add-pois-to-lines option is set.<br/>
 * <br/>
 * <code>add-pois-to-areas</code><br/>
 * Artificial areas created by 
 * multipolygon relation processing are not used for POI creation. The location of the POI 
 * is determined in different ways.<br/>
 * Element is of type {@link Way}:
 * <ul>
 * <li>the first node tagged with building=entrance</li>
 * <li>the center point of the area</li>
 * </ul>
 * Element is of type {@link MultiPolygonRelation}:
 * <ul>
 * <li>the node with role=label</li>
 * <li>the center point of the biggest area</li>
 * </ul>
 * Each node created is tagged with mkgmap:area2poi=true.<br/>
 * <br/>
 * <code>add-pois-to-lines</code><br/>
 * Adds POIs to lines. Each POI is tagged with mkgmap:line2poi=true.<br/>
 * The following POIs are created for each line:
 * <ul>
 * <li>mkgmap:line2poitype=start: The first point of the line</li>
 * <li>mkgmap:line2poitype=end: The last point of the line</li>
 * <li>mkgmap:line2poitype=inner: Each inner point of the line</li>
 * <li>mkgmap:line2poitype=mid: POI at the middle distance of the line</li>
 * </ul>
 * @author WanMil
 */
public class POIGeneratorHook implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(POIGeneratorHook.class);

	private List<Entry<String,String>> poiPlacementTags; 
	/**
	 * maps only those locations which are used in nodes with tags which are used in
	 * the points rules with the {@code FROM_NODE_PREFIX} and which are not already {@link CoordPOI} instances.
	 * The mapping is only needed to create the POIs, thus we don't create {@link CoordPOI} instances for them.
	 */
	private IdentityHashMap<Coord, Node> coordToNodeMap;	
	
	private ElementSaver saver;
	
	private boolean poisToAreas = false;
	private boolean poisToLines = false;
	private boolean poisToLinesStart = false; 
	private boolean poisToLinesEnd = false; 
	private boolean poisToLinesMid = false; 
	private boolean poisToLinesOther = false; 
	private NameFinder nameFinder;
	private AreaSizeFunction areaSizeFunction = new AreaSizeFunction();

	private Set<String> usedTagsPOI;
	
	/** Name of the bool tag that is set to true if a POI is created from an area */
	public static final short TKM_AREA2POI = TagDict.getInstance().xlate("mkgmap:area2poi");
	public static final short TKM_LINE2POI = TagDict.getInstance().xlate("mkgmap:line2poi");
	public static final short TKM_LINE2POI_TYPE = TagDict.getInstance().xlate("mkgmap:line2poitype");
	public static final short TKM_WAY_LENGTH = TagDict.getInstance().xlate("mkgmap:way-length");
	
	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props, Style style) {
		poisToAreas = props.containsKey("add-pois-to-areas");
		poisToLines = props.containsKey("add-pois-to-lines");
		if (poisToLines) {
			String[] opts = {"all"};
			if (!props.getProperty("add-pois-to-lines").isEmpty()) {
				opts = props.getProperty("add-pois-to-lines").split(",");
			}
			
			for (String opt : opts) {
				switch (opt.trim()) {
				case "start":
					poisToLinesStart = true;
					break;
				case "end":
					poisToLinesEnd = true;
					break;
				case "mid":
					poisToLinesMid = true;
					break;
				case "other":
					poisToLinesOther= true;
					break;
				case "all":
					poisToLinesStart= true;
					poisToLinesEnd= true;
					poisToLinesMid = true;
					poisToLinesOther= true;
					break;

				default:
					throw new IllegalArgumentException("Invalied argument '"+opt+"' for add-pois-to-lines");
				}
			}
			
		}
		
		if (!(poisToAreas || poisToLines)) {
			log.info("Disable Areas2POIHook because add-pois-to-areas and add-pois-to-lines option is not set.");
			return false;
		}
		nameFinder = new NameFinder(props);

		this.poiPlacementTags = getPoiPlacementTags(props);
		
		this.saver = saver;
		if (style != null && style.getUsedTagsPOI() != null) {
			// extract special tags used in the points file
			usedTagsPOI = style.getUsedTagsPOI().stream()
					.filter(s -> s.startsWith(FROM_NODE_PREFIX))
					.map(s -> s.substring(POIGeneratorHook.FROM_NODE_PREFIX.length()))
					.collect(Collectors.toSet());
		} else {
			usedTagsPOI = Collections.emptySet();
		}
		return true;
	}
	
	/**
	 * Reads the tag definitions of the option poi2area-placement-tags from the given properties.
	 * @param props mkgmap options
	 * @return the parsed tag definition list
	 */
	public static List<Entry<String,String>> getPoiPlacementTags(EnhancedProperties props) {
		if (!props.containsKey("add-pois-to-areas")) {
			return Collections.emptyList();
		}
		
		List<Entry<String,String>> tagList = new ArrayList<>();
		
		String placementDefs = props.getProperty("pois-to-areas-placement", "entrance=main;entrance=yes;building=entrance");
		placementDefs = placementDefs.trim();
		
		if (placementDefs.length() == 0) {
			// the POIs should be placed in the center only
			// => return an empty list
			return tagList;
		}
		
		String[] placementDefsParts = placementDefs.split(";");
		for (String placementDef : placementDefsParts) {
			int ind = placementDef.indexOf('=');
			String tagName = null;
			String tagValue = null;
			if (ind < 0) {
				// only the tag is defined => interpret it as tag=*
				tagName = placementDef;
				tagValue = null;
			} else if (ind > 0) {
				tagName = placementDef.substring(0,ind);
				tagValue = placementDef.substring(ind+1);
			} else {
				log.error("Option pois-to-areas-placement contains a tag that starts with '='. This is not allowed. Ignoring it.");
				continue;
			}
			tagName = tagName.trim();
			if (tagName.length() == 0) {
				log.error("Option pois-to-areas-placement contains a whitespace tag  '='. This is not allowed. Ignoring it.");
				continue;
			}
			if (tagValue != null) {
				tagValue = tagValue.trim();
				if (tagValue.length() == 0 || "*".equals(tagValue)) {
					tagValue = null;
				} 
			}
			Entry<String,String> tag = new AbstractMap.SimpleImmutableEntry<>(tagName, tagValue);
			tagList.add(tag);
		}
		return tagList;
	}
	
	@Override
	public Set<String> getUsedTags() {
		return poiPlacementTags.stream().map(Map.Entry::getKey).collect(Collectors.toSet());
	}
	
	@Override
	public void end() {
		log.info(getClass().getSimpleName(), "started");
		coordToNodeMap = new IdentityHashMap<>();
		if (!usedTagsPOI.isEmpty()) {
			for (Node n : saver.getNodes().values()) {
				if (n.getLocation() instanceof CoordPOI)
					continue;
				for (String key : usedTagsPOI) {
					if (n.getTag(key) != null) {
						coordToNodeMap.put(n.getLocation(), n);
						break;
					}
				}
			}
		}
		addPOIsForWays();
		addPOIsForMPs();
		coordToNodeMap.clear();
		log.info(getClass().getSimpleName(), "finished");
	}
	
	private int getPlacementOrder(Element elem) {
		for (int order = 0; order < poiPlacementTags.size(); order++) {
			Entry<String,String> poiTagDef = poiPlacementTags.get(order);
			String tagValue = elem.getTag(poiTagDef.getKey());
			if (tagValue != null && poiTagDef.getValue() == null || poiTagDef.getValue().equals(tagValue)) {
				return order;
			}
		}
		// no poi tag match
		return -1;
	}
	
	private void addPOIsForWays() {
		Map<Coord, Integer> labelCoords = new IdentityHashMap<>(); 
		
		// save all coords with one of the placement tags to a map
		// so that ways use this coord as its labeling point
		if (!poiPlacementTags.isEmpty() && poisToAreas) {
			for (Node n : saver.getNodes().values()) {
				int order = getPlacementOrder(n);
				if (order >= 0) {
					Integer prevOrder = labelCoords.get(n.getLocation());
					if (prevOrder == null || order < prevOrder.intValue())
						labelCoords.put(n.getLocation(), order);
				}
			}
		}
		
		log.debug("Found", labelCoords.size(), "label coords");
		
		int ways2POI = 0;
		int lines2POI = 0;
		
		for (Way w : saver.getWays().values()) {
			// check if way has any tags
			if (w.getTagCount() == 0) {
				continue;
			}

			// do not add POIs for polygons created by multipolygon processing
			if (w.tagIsLikeYes(MultiPolygonRelation.TKM_MP_CREATED)) {
				if (log.isDebugEnabled())
					log.debug("MP processed: Do not create POI for", w.toTagString());
				continue;
			}
			
			
			// check if it is an area
			if (w.hasIdenticalEndPoints()) {
				if (poisToAreas) {
					addPOItoPolygon(w, labelCoords);
					ways2POI++;
				}
			} else {
				if (poisToLines) {
					lines2POI += addPOItoLine(w);
				}
			}
		}
		
		if (poisToAreas)
			log.info(ways2POI, "POIs from single areas created");
		if (poisToLines)
			log.info(lines2POI, "POIs from lines created");
	}
	
	private void addPOItoPolygon(Way polygon, Map<Coord, Integer> labelCoords) {
		if (!poisToAreas) {
			return;
		}
		
		// get the coord where the poi is placed
		Coord poiCoord = null;
		// do we have some labeling coords?
		if (!labelCoords.isEmpty()) {
			int poiOrder = Integer.MAX_VALUE;
			// go through all points of the way and check if one of the coords
			// is a labeling coord
			for (Coord c : polygon.getPoints()) {
				Integer cOrder = labelCoords.get(c);
				if (cOrder != null && cOrder.intValue() < poiOrder) {
					// this coord is a labelling coord
					// use it for the current way
					poiCoord = c;
					poiOrder = cOrder;
					if (poiOrder == 0) {
						// there is no higher order
						break;
					}
				}
			}
		}
		if (poiCoord == null) {
			// did not find any label coord
			// use the common center point of the area
			poiCoord = polygon.getCofG();
		}
		// add tag mkgmap:cache_area_size to the original polygon so that it is copied to the POI
		areaSizeFunction.value(polygon);
		addPOI(polygon, poiCoord, TKM_AREA2POI, 0); 
	}
	
	
	private int addPOItoLine(Way line) {
		// calculate the middle of the line
		Coord prevC = null;
		double sumDist = 0.0;
		ArrayList<Double> dists = new ArrayList<>(line.getPoints().size()-1);
		for (Coord c : line.getPoints()) {
			if (prevC != null) {
				double dist = prevC.distance(c);
				dists.add(dist);
				sumDist+=dist;
			}
			prevC = c;
		}
		
		int countPOIs = 0;
		if (poisToLinesStart) {
			Node startNode = addPOI(line, line.getFirstPoint(), TKM_LINE2POI, sumDist);
			startNode.addTag(TKM_LINE2POI_TYPE, "start");
			countPOIs++;
		}

		if (poisToLinesEnd) {
			Node endNode = addPOI(line, line.getLastPoint(), TKM_LINE2POI, sumDist);
			endNode.addTag(TKM_LINE2POI_TYPE, "end");
			countPOIs++;
		}
		
		if (poisToLinesOther && line.getPoints().size() > 2) {
			Coord lastPoint = line.getFirstPoint();
			for (Coord inPoint : line.getPoints().subList(1, line.getPoints().size() - 1)) {
				if (inPoint.equals(lastPoint)) {
					continue;
				}
				lastPoint = inPoint;
				Node innerNode = addPOI(line, inPoint, TKM_LINE2POI, sumDist);
				innerNode.addTag(TKM_LINE2POI_TYPE, "inner");
				countPOIs++;
			}
		}
		if (poisToLinesMid) {
			Coord midPoint = null;
			double remMidDist = sumDist / 2;
			for (int midPos = 0; midPos < dists.size(); midPos++) {
				double nextDist = dists.get(midPos);
				if (remMidDist <= nextDist) {
					double frac = remMidDist / nextDist;
					midPoint = line.getPoints().get(midPos).makeBetweenPoint(line.getPoints().get(midPos + 1), frac);
					break;
				}
				remMidDist -= nextDist;
			}

			if (midPoint != null) {
				Node midNode = addPOI(line, midPoint, TKM_LINE2POI, sumDist);
				midNode.addTag(TKM_LINE2POI_TYPE, "mid");
				countPOIs++;
			}
		}
		return countPOIs;

	}
	
	/** Prefix that is added to tags which are copied from the original node. */
	public static final String FROM_NODE_PREFIX = "mkgmap:from-node:";

	private Node addPOI(Element source, Coord poiCoord, short poiTypeTagKey, double wayLength) {
		Node poi = new Node(source.getOriginalId(), poiCoord);
		poi.markAsGeneratedFrom(source);
		poi.copyTags(source);
		poi.deleteTag(MultiPolygonRelation.STYLE_FILTER_TAG);
		poi.addTag(poiTypeTagKey, "true");
		if (poiTypeTagKey == TKM_LINE2POI) {
			poi.addTag(TKM_WAY_LENGTH, String.valueOf(Math.round(wayLength)));
		}
		
		Node node = null;
		if (poiCoord instanceof CoordPOI) {
			node = ((CoordPOI) poiCoord).getNode();
		} else {
			node = coordToNodeMap.get(poiCoord);
		}
		if (node != null) {
			// add the original tags of the node with the prefix mkgmap:from-node:
			for (Entry<String, String> entry : node.getTagEntryIterator()) {
				if (!entry.getKey().startsWith("mkgmap:")) {
					poi.addTag(FROM_NODE_PREFIX + entry.getKey(), entry.getValue());
				}
			}
		} 

		if (log.isDebugEnabled()) {
			log.debug("Create POI",poi.toTagString(),"from",source.getId(),source.toTagString());
		}
		saver.addNode(poi);
		return poi;
		
	}

	private void addPOIsForMPs() {
		int mps2POI = 0;
		for (Relation r : saver.getRelations().values()) {
			
			// create POIs for multipolygon relations only
			if (!(r instanceof MultiPolygonRelation)) {
				continue;
			}
			Node adminCentre = null;
			Node labelPOI = null;
			String relName = nameFinder.getName(r);
			if (relName != null){
				for (Entry<String, Element> pair : r.getElements()){
					String role = pair.getKey();
					Element el = pair.getValue();
					if (el instanceof Node){
						if ("admin_centre".equals(role)){
							if ("boundary".equals(r.getTag("type")) && "administrative".equals(r.getTag("boundary"))){
								// boundary relations may have a node with role admin_centre, if yes, use the 
								// location of it
								String pName = nameFinder.getName(el);
								if (relName.equals(pName)){
									adminCentre = (Node) el;
									if (log.isDebugEnabled())
										log.debug("using admin_centre node as location for POI for rel",r.getId(),relName,"at",((Node) el).getLocation());
								}
							}
						} else if ("label".equals(role)){
							String label = nameFinder.getName(el);
							if (relName.equals(label)){
								labelPOI = (Node) el;
								log.debug("using label node as location for POI for rel", r.getId(), relName, "at", ((Node) el).getLocation());
								break;
							} else {
								log.warn("rel",r.toBrowseURL(),",node with role label is ignored because it has a different name");
							}
						}
					}
				}
			}
			Coord point = null;
			if (adminCentre == null && labelPOI == null)
				point = ((MultiPolygonRelation)r).getCofG();
			else {
				if (labelPOI != null)
					point = labelPOI.getLocation();
				else 
					point = adminCentre.getLocation();
			}

			if (point != null) {
				Node poi = addPOI(r, point, TKM_AREA2POI, 0);
				// remove the type tag which makes only sense for relations
				poi.deleteTag("type");
				mps2POI++;
			}
		}
		log.info(mps2POI,"POIs from multipolygons created");
	}


}
