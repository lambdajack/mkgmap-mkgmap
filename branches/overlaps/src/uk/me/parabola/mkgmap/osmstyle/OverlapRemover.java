/*
 * Copyright (C) 2018.
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
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.RestrictionRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.MultiIdentityHashMap;

/**
 * Class to find and report overlapping road segments. Eventually remove the overlap to reduce possible routing problems.
 * @author GerdP
 *
 */
public class OverlapRemover {
	private static final Logger log = Logger.getLogger(OverlapRemover.class);

	private final Area bbox;
	
	private List<ConvertedWay> roads;
	private int idx;

	private int removedSegments;

	public OverlapRemover(Area bbox) {
		this.bbox = bbox;
	}

	/**
	 * Find and report overlapping road segments. Eventually remove the overlap to reduce possible routing problems. 
	 * @param roads list of roads, elements might be set to null by this method
	 * @param lines list of non-routable ways  
	 * @param modifiedRoads Will be enlarged by all roads modified in this method 
	 * @param restrictions Map with restriction relations 
	 */
	public void processWays(List<ConvertedWay> roads, List<RestrictionRelation> restrictions ) {
		long t1 = System.currentTimeMillis();
		this.roads = roads;
		this.idx = roads.size();
		List<Segment> dups = findDups(roads);
		handleDups(dups);
		long dt = System.currentTimeMillis() - t1;
		log.error("check for overlapping road segments took", dt, "ms");
	}

	private List<Segment> findDups(List<ConvertedWay> roads) {
		MultiIdentityHashMap<Coord, Segment> node2SegmentMap = new MultiIdentityHashMap<>(); 
		List<Segment> dups = new ArrayList<>();
		Way lastWay = null;
		for (int i = 0; i < roads.size(); i++) {
			ConvertedWay cw = roads.get(i);
			if (!cw.isValid() || cw.isOverlay()) 
				continue;
			Way way = cw.getWay();
			if (way.equals(lastWay)) 
				continue;
			lastWay = way;
			List<Coord> points = way.getPoints();
			int last = points.size();
			if (!way.isClosed())
				last--;
			// TODO: add check against bbox to avoid splitting roads outside of it
			for (int j = 0; j < last; j++) {
				Coord p1 = points.get(j);
				if (p1.getHighwayCount() <= 1) 
					continue;
				Coord p2 = points.get(j + 1 < points.size() ? j + 1 : 0);
				if (p2.getHighwayCount() > 1) {
					boolean added = false;
					List<Segment> segments = new ArrayList<>();
					segments.addAll(node2SegmentMap.get(p1));
					segments.addAll(node2SegmentMap.get(p2));
					for (Segment s : segments) {
						if (s.p1 == p1 && s.p2 == p2 || s.p2 == p1 && s.p1 == p2) {
							s.ways.add(way);
							added = true;
							log.debug("found overlapping road segments",s.ways.get(0).toBrowseURL(),way.toBrowseURL());
							dups.add(s);
						}
					}
					if (!added) {
						Segment s = new Segment(p1, p2);
						s.ways.add(way);
						node2SegmentMap.add(p1, s);
					}
				}
			}
		}
		return dups;
	}
	private void handleDups(List<Segment> dups) {
		HashSet<Way> splitWays = new HashSet<>();
		for (Segment s : dups) {
			splitWays.addAll(s.ways);
		}
		splitWaysAtNodes(splitWays);
	}
	
	/**
	 * Split ways which have shared segments. Some segments may be removed later.
	 * Remaining segments are connected by RoadMerger.
	 * @param splitWays
	 */
	private void splitWaysAtNodes(HashSet<Way> splitWays) {
		Map<Way, List<Way>> modWays = new LinkedHashMap<>();
		for (Way w : splitWays) {
			if (w.isViaWay()) {
				log.error("cannot yet handle via way",w);
				continue;
			}
			
			List<Coord> seq = new ArrayList<>();
			List<Way> parts = new ArrayList<>();
			for (int i = 0; i < w.getPoints().size(); i++) {
				Coord p = w.getPoints().get(i);
				seq.add(p);
				if (p.getHighwayCount() > 1) {
					if (seq.size() > 1) {
						parts.add(createWayPart(w, seq));
					}
					seq = new ArrayList<>();
					seq.add(p);
				}
			}
			if (seq.size() > 1) {
				// we get here when the end of an unclosed way is not connected to other roads
				parts.add(createWayPart(w, seq));
			}
			log.debug("Way", w.getId(), "was split into", parts.size(), "parts");
			modWays.put(w, parts);
		}
		Map<Way, List<ConvertedWay>> modRoads= new LinkedHashMap<>();
		for (ConvertedWay cw : roads) {
			List<Way> parts = modWays.get(cw.getWay());
			if (parts != null) {
				List<ConvertedWay> roadParts = new ArrayList<>();
				for (Way part : parts ) {
					ConvertedWay cwPart = new ConvertedWay(idx++,part,cw.getGType());
					// TODO: overlay handling ?
					roadParts.add(cwPart);
				}
				modRoads.put(cw.getWay(), roadParts);
			}
		}
		int origBad = modRoads.size();
		removeOverlaps(modRoads);
		if (modRoads.isEmpty()) {
			log.error("could not change any of the",origBad,"road(s) with overlapping segments");
		} else {
			Iterator<ConvertedWay> iter = roads.iterator();
			List<ConvertedWay> roadParts = new ArrayList<>(); 
			while (iter.hasNext()) {
				ConvertedWay origRoad = iter.next();
				for (Entry<Way, List<ConvertedWay>> entry : modRoads.entrySet()) {
					if (entry.getKey() == origRoad.getWay()) {
						iter.remove();
						log.error("removed part(s) of",origRoad);
						roadParts.addAll(entry.getValue());
					}
				}
			}
			roads.addAll(roadParts);
			log.error("changed",modRoads.size(),"of",origBad,"roads with overlapping segments and remvoed",removedSegments,"segments");
		}
		
	}

	private void removeOverlaps(Map<Way, List<ConvertedWay>> modRoads) {
		List<Way> ways = new ArrayList<>(modRoads.keySet());
		BitSet mod = new BitSet(ways.size());
		for (int i = 0; i+1 < ways.size(); i++) {
			Way w1 = ways.get(i);
			List<ConvertedWay> parts1 = modRoads.get(w1);
			for (int j = i+1; j < ways.size(); j++) {
				Way w2 = ways.get(j);
				List<ConvertedWay> parts2 = modRoads.get(w2);
				Iterator<ConvertedWay> iter1 = parts1.iterator();
				while (iter1.hasNext()) {
					ConvertedWay r1 = iter1.next();
					Map<String, String> r1Labels = r1.getWay().getTagsWithPrefix("mkgmap:label:", false);
					Iterator<ConvertedWay> iter2 = parts2.iterator();
					while (iter2.hasNext()) {
						ConvertedWay r2 = iter2.next();
						int res = checkRemove(r1, r2);
						if (res == 0) {
							Map<String, String> r2Labels = r2.getWay().getTagsWithPrefix("mkgmap:label:", false);
							if (r1Labels.isEmpty() && !r2Labels.isEmpty()) {
								Way temp = r1.getWay().copy();
								for (Entry<String, String> entry : r2Labels.entrySet()) {
									temp.addTag(entry.getKey(), entry.getValue());
								}
								r1 = new ConvertedWay(r1, temp);
								res = checkRemove(r1, r2);
							} else if (!r1Labels.isEmpty() && r2Labels.isEmpty()) {
								Way temp = r2.getWay().copy();
								for (Entry<String, String> entry : r1Labels.entrySet()) {
									temp.addTag(entry.getKey(), entry.getValue());
								}
								r2 = new ConvertedWay(r2, temp);
								res = checkRemove(r1, r2);
							}
							if (res != 0) {
								// we remove the way segment with the labels, copy them to the other segment
								if (res == 1 && !r1Labels.isEmpty()) {
									for (Entry<String, String> entry : r1Labels.entrySet()) {
										r2.getWay().addTag(entry.getKey(), entry.getValue());
									}
								} else if (res == 2 && !r2Labels.isEmpty()) {
									for (Entry<String, String> entry : r2Labels.entrySet()) {
										r1.getWay().addTag(entry.getKey(), entry.getValue());
									}
									r1Labels = r1.getWay().getTagsWithPrefix("mkgmap:label:", false);
								}
							}
						}
						
						if (res == 0) {
							continue;
						}
						removedSegments++;
						if (res == 1) {
							mod.set(i);
							iter1.remove();
							break;
						} else {
							mod.set(j);
							iter2.remove();
						}
					}
				}
			}
		}
		if (mod.isEmpty()) {
			modRoads.clear();
			return;
		}
		// try to recombine parts
		for (int i = 0; i < ways.size(); i++) {
			Way w = ways.get(i);
			if (!mod.get(i)) {
				modRoads.remove(w);
				continue;
			}

			List<ConvertedWay> parts = modRoads.get(w);
//			for (int xx = 0; xx < parts.size(); xx++) {
//				GpxCreator.createGpx("e:/ld/part"+w.getId()+"_"+xx, parts.get(xx).getPoints());
//			}
			ConvertedWay pattern = parts.get(0);
			List<List<Coord>> combinedPoints = new ArrayList<>();
			List<Coord> part = new ArrayList<>();
			for (ConvertedWay cw1 : parts) {
				if (part.isEmpty())
					part.addAll(cw1.getPoints());
				else {
					Coord p1 = part.get(part.size()-1);
					Coord p2 = cw1.getPoints().get(0);
					if (p1 == p2) {
						// re-combine
						part.remove(part.size()-1);
						part.addAll(cw1.getPoints());
					} else {
						combinedPoints.add(part);
						part = new ArrayList<>(cw1.getPoints());
					}
				}
			}
			if (!part.isEmpty()) {
				if (!combinedPoints.isEmpty()) {
					// check if we can combine last with first sequence
					List<Coord> firstPart = combinedPoints.get(0);
					Coord p1 = part.get(part.size() - 1);
					Coord p2 = firstPart.get(0);
					if (p1 == p2) {
						firstPart.remove(0);
						part.addAll(firstPart);
						combinedPoints.set(0, part);
					} else {
						combinedPoints.add(part);
					}
				} else {
					combinedPoints.add(part);
				}
			}
			parts.clear();
			for (List<Coord> seq : combinedPoints) {
				Way w2 = new Way(w.getOriginalId(), seq);
				w2.setFakeId();
				ConvertedWay cw = new ConvertedWay(idx++, w2, pattern.getGType());
				parts.add(cw);
			}
		}
	}

	private Way createWayPart(Way w, List<Coord> points) {
		Way part = new Way(w.getId(), points);
		part.setFakeId();
		part.copyTags(w);
		log.debug("splitting part of way",w.getId(),"new id:",part.getId());
		return part;
	}
	
	/**
	 * 
	 * @param road1
	 * @param road2
	 * @return 0 if no way is removable, 1 if 1st way should be removed, 2 if 2nd way should be removed.
	 */
	private int checkRemove(ConvertedWay road1, ConvertedWay road2) {
		if (road1.getPoints().size() != 2 || road2.getPoints().size() != 2)
			return 0;
		boolean isEqual = RoadMerger.isMergeable(road1, road2, true); // TODO: tolerate different labels?
		if (isEqual) {
			Coord p10 = road1.getPoints().get(0);
			Coord p11 = road1.getPoints().get(1);
			Coord p20 = road2.getPoints().get(0);
			Coord p21 = road2.getPoints().get(1);
			if (p10 == p20 && p11 == p21 || p10 == p21 && p11 == p20) {
				if (road1.isOneway()){
					if (road2.isOneway()) {
						// both are oneways: make sure that the direction matches
						if (p10 == p20 && p11 == p21)
							return 1;
					} else {
						return 2; // remove 2nd
					}
				} else if (road2.isOneway()) {
					return 1;
				} else {
					// both are equal, prefer to remove part of area
					if ("yes".equals(road1.getWay().getTag("area")))
						return 1;
					return 2;   
				}
			}
		}
		return 0;
	}
	
	static class Segment {
		final Coord p1,p2;
		final ArrayList<Way> ways = new ArrayList<>();

		public Segment(Coord p1, Coord p2) {
			this.p1 = p1;
			this.p2 = p2;
		}
		
		@Override
		public String toString() {
			return ways.toString() + " " + p1.toDegreeString() + " " + p2.toDegreeString();
		}
	}
}
