/*
 * Copyright (C) 2014.
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
package uk.me.parabola.mkgmap.filters;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.osmstyle.WrongAngleFixer;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.util.Java2DConverter;


/**
 * Merge shapes with same Garmin type and similar attributes if they have common 
 * points. This reduces the number of shapes as well as the number of points.
 * @author GerdP
 *
 */
public class ShapeMergeFilter{
	private static final Logger log = Logger.getLogger(ShapeMergeFilter.class);
	private final int resolution;
	private static final ShapeHelper DUP_SHAPE = new ShapeHelper(new ArrayList<>(0)); 
	private final boolean orderByDecreasingArea;
	private final int maxPoints;
	private final boolean ignoreRes;

	/**
	 * Create the shape filter with the given attributes. It will ignore shapes
	 * which are not displayed at the given resolution.
	 * 
	 * @param resolution            the resolution
	 * @param orderByDecreasingArea if true, only certain shapes are merged
	 */
	public ShapeMergeFilter(int resolution, boolean orderByDecreasingArea) {
		ignoreRes = resolution < 0;
		this.resolution = resolution;
		this.orderByDecreasingArea = orderByDecreasingArea;
		if (ignoreRes)
			maxPoints = Integer.MAX_VALUE;
		else 
			maxPoints = PolygonSplitterFilter.MAX_POINT_IN_ELEMENT;
	}

	/**
	 * Merge shapes that are similar and have identical points.
	 * @param shapes list of shapes
	 * @return list of merged shapes (might be identical to original list)
	 */
	public List<MapShape> merge(List<MapShape> shapes) {
		if (shapes.size() <= 1)
			return shapes;
		List<MapShape> mergedShapes = new ArrayList<>();
		List<MapShape> usableShapes = new ArrayList<>();
		for (MapShape shape: shapes) {
			if (!ignoreRes && (shape.getMinResolution() > resolution || shape.getMaxResolution() < resolution))
				continue;
			if (shape.getPoints().get(0) != shape.getPoints().get(shape.getPoints().size()-1)){
				// should not happen here
				log.error("shape is not closed with identical points", shape.getOsmid(),
					  shape.getPoints().get(0).toOSMURL());
				mergedShapes.add(shape);
				continue;
			}
			usableShapes.add(shape);
		}
		if (usableShapes.size() < 2) {
			mergedShapes.addAll(usableShapes);
			return mergedShapes;
		}
		
		Comparator<MapShape> comparator = new MapShapeComparator(orderByDecreasingArea);
		usableShapes.sort(comparator);
		int p1 = 0;
		MapShape s1 = usableShapes.get(0);
		for (int i = 1; i < usableShapes.size(); i++) {
			if (comparator.compare(s1, usableShapes.get(i)) == 0)
				continue;
			mergeSimilar(usableShapes.subList(p1, i), mergedShapes);
			s1 = usableShapes.get(i);
			p1 = i;
		}
		if (p1 < usableShapes.size())
			mergeSimilar(usableShapes.subList(p1, usableShapes.size()), mergedShapes);
		return mergedShapes;
	}
	
	/**
	 * Merge similar shapes.
	 * @param similar list of similar shapes
	 * @param mergedShapes list to which the shapes are added 
	 */
	private void mergeSimilar(List<MapShape> similar, List<MapShape> mergedShapes) {
		if (similar.size() == 1) {
			mergedShapes.addAll(similar);
			return;
		}
		
		final int partSize = 8192;
		// sorting is meant to reduce the self intersections created by merging
		similar.sort((o1,o2) -> o1.getBounds().getCenter().compareTo(o2.getBounds().getCenter()));
		List<ShapeHelper> list = new ArrayList<>();
		MapShape pattern = similar.get(0);
		for (MapShape ms : similar) {
			ShapeHelper sh = new ShapeHelper(ms.getPoints());
			sh.id = ms.getOsmid();
			list.add(sh);
		}
		
		// if list is very large use partitions to reduce BitSet sizes in called methods 
		while (list.size() > partSize) {
			int oldSize = list.size();
			List<ShapeHelper> nextList = new ArrayList<>();
			for (int part = 0; part < list.size(); part += partSize) {
				List<ShapeHelper> lower = new ArrayList<>(list.subList(part, Math.min(part+partSize, list.size())));
				tryMerge(pattern, lower);
				nextList.addAll(lower);
			}
			list.clear();
			list.addAll(nextList);
			if(list.size() == oldSize)
				break;
		}
		tryMerge(pattern, list);
		for (ShapeHelper sh : list) {
			MapShape newShape = pattern.copy();
			if (sh.getPoints().get(0) != sh.getPoints().get(sh.getPoints().size() - 1)) {
				throw new MapFailedException("shape is no longer closed");
			}
			if (sh.id == 0) {
				// this shape is the result of a merge
				List<Coord> optimizedPoints = sh.getPoints();
				if (!ignoreRes)  {
					optimizedPoints = WrongAngleFixer.fixAnglesInShape(optimizedPoints);
				}
				if (optimizedPoints.isEmpty())
					continue;
				newShape.setPoints(optimizedPoints);
				newShape.setOsmid(FakeIdGenerator.makeFakeId());
			} else {
				newShape.setPoints(sh.getPoints());
				newShape.setOsmid(sh.id);
			}
			mergedShapes.add(newShape);
		}
	}

	/**
	 * Merge ShapeHelpers. Calls itself recursively.
	 * @param pattern a MapShape
	 * @param similarShapes {@link ShapeHelper} instances created from similar {@link MapShape}.
	 * This list is modified if shapes were merged.
	 */
	private void tryMerge(MapShape pattern, List<ShapeHelper> similarShapes) {
		if (similarShapes.size() <= 1)
			return;
		int numCandidates = similarShapes.size();
		List<BitSet> candidates = createMatrix(similarShapes);
		List<ShapeHelper> next = new ArrayList<>();
		boolean merged = false;
		BitSet done = new BitSet();
		BitSet delayed = new BitSet();
		
		List<ShapeHelper> noMerge = new ArrayList<>();
		class SortHelper {
			final int pos;
			final BitSet all;
			public SortHelper(int index, BitSet set) {
				pos = index;
				all = set;
			}
		}
		List<SortHelper> byNum = new ArrayList<>();
		for (int i = 0; i < numCandidates; i++) {
			byNum.add(new SortHelper(i, candidates.get(i)));
		}
		// sort so that shapes with more neighbours come 
		byNum.sort((o1, o2) -> o1.all.cardinality() - o2.all.cardinality());
		for (SortHelper helper : byNum) {
			final int pos = helper.pos;
			BitSet all = helper.all;
			if (all.isEmpty()) {
				noMerge.add(similarShapes.get(pos));
				done.set(pos);
			}
			if (done.get(pos))
				continue;
			if (all.cardinality() == 1) {
				delayed.set(pos);
				continue;
			}
			all.andNot(done);
			
			if (all.isEmpty())
				continue;
			
			IntArrayList byCenter = new IntArrayList();
			byCenter.add(pos);
			all.stream().filter(k -> k != pos).forEach(byCenter::add);

			// try to process the connected shapes so that parts are added to the one in the middle
			// this should allow to remove spikes as early as possible
			if (byCenter.size() > 2) {
				Map<Integer, Coord> centers = new HashMap<>();
				for (int j : byCenter) {
					centers.put(j, Area.getBBox(similarShapes.get(j).points).getCenter());	
				}
				Coord mid = Area.getBBox(new ArrayList<>(centers.values())).getCenter();
				byCenter.sort((o1, o2) -> Double.compare(centers.get(o1).distance(mid), centers.get(o2).distance(mid)));
			} 			
			List<ShapeHelper> result = Collections.emptyList();
			for (int j : byCenter) {
				ShapeHelper sh = similarShapes.get(j);
				int oldSize = result.size();
				result = addWithConnectedHoles(result, sh, pattern.getType());
				if (result.size() < oldSize + 1) {
					merged = true;
					if (log.isDebugEnabled()) {
						log.debug("shape with id", sh.id, "was merged", (oldSize + 1 - result.size()),
								" time(s) at resolution", resolution);
					}
				}
			}
			// XXX : not exact, there may be other combinations of shapes which can be merged
			// e.g. merge of shape 1 + 2 may not work but 2 and 3 could still be candidates.
			done.or(all);
			next.addAll(result);
		}
		
		delayed.andNot(done);
		if (!delayed.isEmpty()) {
			delayed.stream().forEach(i -> noMerge.add(similarShapes.get(i)));
		}
		delayed.clear();
		similarShapes.clear();
		similarShapes.addAll(noMerge);
		candidates.clear();
		if (merged) {
			tryMerge(pattern, next);
		}
		
		// Maybe add final step which calls addWithConnectedHoles for all remaining shapes
		// this will find a few more merges but is still slow for maps with lots of islands 
		similarShapes.addAll(next);
	}

	/**
	 * Calculate matrix of shapes which share node
	 * @param similarShapes
	 * @return list of sets with indexes of shared nodes, empty for shapes which cannot be merged
	 */
	private List<BitSet> createMatrix(List<ShapeHelper> similarShapes) {
		// abuse highway count to find identical points in different shapes
		similarShapes.forEach(sh -> sh.getPoints().forEach(Coord::resetHighwayCount));
		similarShapes.forEach(sh -> sh.getPoints().forEach(Coord::incHighwayCount));
		// decrement counter for duplicated start/end node
		similarShapes.forEach(sh -> sh.getPoints().get(0).decHighwayCount());
		
		// points with count > 1 are probably shared by different shapes, collect the shapes
		IdentityHashMap<Coord, BitSet> coord2Shape = new IdentityHashMap<>();
		final List<BitSet>candidates = new ArrayList<>(similarShapes.size());
		for (int i = 0; i < similarShapes.size(); i++) {
			candidates.add(new BitSet());
		}
		for (int i = 0; i < similarShapes.size(); i++) {
			ShapeHelper sh0 = similarShapes.get(i);
			List<Coord> sharedPoints = sh0.getPoints().stream().filter(p -> p.getHighwayCount() > 1)
					.collect(Collectors.toList());
			if (sh0.getPoints().get(0).getHighwayCount() > 1)
				sharedPoints.remove(0);
			if (sharedPoints.isEmpty() || (sh0.getPoints().size() - sharedPoints.size() > maxPoints)) {
				// merge will not work
				continue;
			}

			BitSet curr = candidates.get(i);
			curr.set(i);

			for (Coord c : sharedPoints) {
				BitSet set = coord2Shape.get(c);
				if (set == null) {
					set = new BitSet();
					coord2Shape.put(c, set);
				} else {
					final int row = i;
					set.stream().forEach(j -> candidates.get(j).set(row));
					curr.or(set);
				}
				set.set(i);
			}
		}
		return candidates;
	}

	/**
	 * Try to merge a shape with one or more of the shapes in the list.
	 *  If it cannot be merged, it is added to the list.
	 *  Holes in shapes are connected with the outer lines,
	 *  so no following routine must use {@link Java2DConverter}
	 *  to process these shapes.
	 * @param list list of shapes with equal type
	 * @param toAdd new shape
	 * @param type garmin type of pattern MapShape
	 * @return new list of shapes, this might contain fewer (merged) elements
	 */
	private List<ShapeHelper> addWithConnectedHoles(List<ShapeHelper> list,
			final ShapeHelper toAdd, final int type) {
		assert toAdd.getPoints().size() > 3;
		List<ShapeHelper> result = new ArrayList<>(list.size() + 1);
		ShapeHelper shNew = new ShapeHelper(toAdd);
		for (ShapeHelper shOld : list) {
			ShapeHelper mergeRes = tryMerge(shOld, shNew);
			if (mergeRes == shOld){
				result.add(shOld);
				continue;
			} else {
				shNew = mergeRes;
			}
			if (shNew == DUP_SHAPE){
				log.warn("ignoring duplicate shape with id", toAdd.id, "at",  toAdd.getPoints().get(0).toOSMURL(), "with type", GType.formatType(type), "for resolution", resolution);
				return list; // nothing to do
			}
		}
		if (shNew != DUP_SHAPE)
			result.add(shNew);
		if (result.size() > list.size() + 1)
			log.error("result list size is wrong", list.size(), "->", result.size());
		return result;
	}

	/**
	 * Find out if two shapes have common points. If yes, merge them.
	 * @param sh1 1st shape1
	 * @param sh2 2st shape2
	 * @return merged shape or 1st shape if no common point found or {@code dupShape} 
	 * if both shapes describe the same area. 
	 */
	private ShapeHelper tryMerge(ShapeHelper sh1, ShapeHelper sh2) {
		
		// both clockwise or both ccw ?
		boolean sameDir = sh1.areaTestVal > 0 && sh2.areaTestVal > 0 || sh1.areaTestVal < 0 && sh2.areaTestVal < 0;
		
		List<Coord> points1, points2;
		if (sh2.getPoints().size()> sh1.getPoints().size()){
			points1 = sh2.getPoints();
			points2 = sh1.getPoints();
		} else {
			points1 = sh1.getPoints();
			points2 = sh2.getPoints();
		}
		// find all coords that are common in the two shapes 
		IntArrayList sh1PositionsToCheck = new IntArrayList();
		IntArrayList sh2PositionsToCheck = new IntArrayList();

		findCommonCoords(points1, points2, sh1PositionsToCheck, sh2PositionsToCheck); 		
		if (sh1PositionsToCheck.isEmpty()){
			return sh1;
		}
		if (sh2PositionsToCheck.size() + 1 >= points2.size()){
			// all points are identical, might be a duplicate
			// or a piece that fills a hole 
			if (points1.size() == points2.size() && Math.abs(sh1.areaTestVal) == Math.abs(sh2.areaTestVal)){ 
				// it is a duplicate, we can ignore it
				// XXX this might fail if one of the shapes is self intersecting
				return DUP_SHAPE;
			}
		}
		List<Coord> merged = null;
		if (points1.size() + points2.size() - 2 * sh1PositionsToCheck.size() < maxPoints) {
			merged = findBestMerge(points1, points2, sh1PositionsToCheck, sh2PositionsToCheck, sameDir);
			if (merged == null)
				return sh1;
			if (merged.isEmpty())
				return DUP_SHAPE;
			if (merged.get(0) != merged.get(merged.size() - 1))
				merged = null;
			else if (merged.size() > maxPoints) {
				// don't merge because merged polygon would probably be split again
				log.info("merge rejected: merged shape has too many points " + merged.size());
				merged = null;
			}
		}
		if (merged == null)
			return sh1;
		
		ShapeHelper shm = new ShapeHelper(merged);
		if (Math.abs(shm.areaTestVal) != Math.abs(sh1.areaTestVal) + Math.abs(sh2.areaTestVal)){
			log.warn("merging shapes skipped for shapes near", points1.get(sh1PositionsToCheck.getInt(0)).toOSMURL(), 
					"(maybe overlapping shapes?)");
			return sh1;
		} else {
			if (log.isInfoEnabled()){
				log.info("merge of shapes near",points1.get(sh1PositionsToCheck.getInt(0)).toOSMURL(), 
						"reduces number of points from",(points1.size()+points2.size()),
						"to",merged.size());

			}
		}
		return shm;
	}

	/**
	 * Find the common Coord instances and save their positions for both shapes.
	 * @param s1 shape 1
	 * @param s2 shape 2
	 * @param s1PositionsToCheck will contain common positions in shape 1   
	 * @param s2PositionsToCheck will contain common positions in shape 2
	 */
	private static void findCommonCoords(List<Coord> s1, List<Coord> s2,
			IntArrayList s1PositionsToCheck,
			IntArrayList s2PositionsToCheck) {
		Map<Coord, Integer> s2PosMap = new IdentityHashMap<>(s2.size() - 1);
		
		for (int i = 0; i+1 < s1.size(); i++){
		    Coord co = s1.get(i);
		    co.setPartOfShape2(false);
		}
		for (int i = 0; i+1 < s2.size(); i++){
		    Coord co = s2.get(i);
		    co.setPartOfShape2(true);
		    s2PosMap.put(co, i); 
		}
		
		int start = 0;
		while(start < s1.size()){
			Coord co = s1.get(start);
			if (!co.isPartOfShape2())
				break;
			start++;
		}
		int pos = start+1;
		int tested = 0;
		while(true){
			if (pos+1 >= s1.size())
				pos = 0;
			Coord co = s1.get(pos);
			if (++tested >= s1.size())
				break;
			if (co.isPartOfShape2()){
				s1PositionsToCheck.add(pos);
				Integer posInSh2 = s2PosMap.get(co);
				assert posInSh2 != null;
				s2PositionsToCheck.add(posInSh2.intValue());
			}
			pos++;
		}
	} 	
	
	/**
	 * Merges two shapes if possible and removes the longest common sequence of points
	 * @param points1 list of Coord instances that describes the 1st shape 
	 * @param points2 list of Coord instances that describes the 2nd shape
	 * @param sh1PositionsToCheck positions in the 1st shape that are common
	 * @param sh2PositionsToCheck positions in the 2nd shape that are common
	 * @param sameDir set to true if both shapes are clockwise or both are counter-clockwise
	 * @return the shape with the shortest overall length
	 */
	private static List<Coord> findBestMerge(List<Coord> points1, List<Coord> points2, IntArrayList sh1PositionsToCheck,
			IntArrayList sh2PositionsToCheck, boolean sameDir) {
		if (sh1PositionsToCheck.isEmpty())
			throw new IllegalArgumentException("nothing to merge");
		int s1Size = points1.size(); 
		int s2Size = points2.size();
		int longestSequence = 0;
		int length = 0;
		int start = -1;
		int n1 = sh1PositionsToCheck.size();
		List<Map.Entry<Integer, Integer>> possibleCombinations = new ArrayList<>();
		assert sh2PositionsToCheck.size() == n1;
		boolean inSequence = false;
		for (int i = 0; i + 1 < n1; i++) {
			int pred1 = sh1PositionsToCheck.getInt(i);
			int succ1 = sh1PositionsToCheck.getInt(i + 1);
			if (Math.abs(succ1 - pred1) == 1 || pred1 + 2 == s1Size && succ1 == 0 || succ1 + 2 == s1Size && pred1 == 0) {
				// found sequence in s1
				int pred2 = sh2PositionsToCheck.getInt(i);
				int succ2 = sh2PositionsToCheck.getInt(i + 1);
				if (Math.abs(succ2 - pred2) == 1 || pred2 + 2 == s2Size && succ2 == 0 || succ2 + 2 == s2Size && pred2 == 0) {
					// found common sequence
					if (start < 0)
						start = i;
					inSequence = true;
					length++;
				} else {
					inSequence = false;
				}
			} else {
				inSequence = false;
			}
			if (!inSequence) {
				if (length > longestSequence) 
					longestSequence = length;
				possibleCombinations.add(new AbstractMap.SimpleEntry<>(start < 0 ? i : start, length));
				length = 0;
				start = -1;
			}
		}
		if (length > longestSequence) 
			longestSequence = length;
		possibleCombinations.add(new AbstractMap.SimpleEntry<>(start < 0 ? n1 - 1 : start, length));

		// now collect the alternative merge results,
		List<List<Coord>> results = new ArrayList<>();
		for (Map.Entry<Integer, Integer> combi : possibleCombinations) {
			int len = combi.getValue();
			if (len < longestSequence)
				continue;
			int pos = combi.getKey();
			List<Coord> merged = combine(points1, points2, sh1PositionsToCheck.getInt(pos + len),
					sh2PositionsToCheck.getInt(pos), sameDir, len);
			if (merged.size() >= 4 && merged.get(0) == merged.get(merged.size() - 1))
				results.add(merged);
		}

		return filterBest(results);
	}
 	
	private static List<Coord> filterBest(List<List<Coord>> allMerged) {
		// if we have more than one result, use the one that produced the shortest line
		// so that connection lines to holes are shortest. 
		List<Coord> best = null;
		double bestLen = Double.MAX_VALUE;
		for (List<Coord> merged : allMerged) {
			if (best == null)
				best = merged;
			else {
				if (bestLen == Double.MAX_VALUE) {
					bestLen = getSumLengthSquared(best);
				} 
				double len = getSumLengthSquared(merged);
				if (len < bestLen) {
					best = merged;
					bestLen = len;
				}
			}
		}
		return best;
	}
	
	private static List<Coord> combine(List<Coord> points1, List<Coord> points2, final int s1PosInit,
			final int s2PosInit, boolean sameDir, int lengthOfSequence) {
		int s1Size = points1.size();
		int s2Size = points2.size();
		// merge the shapes with the parts given
		// The remaining points are connected in the direction of the 1st shape.
		int remaining = s1Size + s2Size - 2 * lengthOfSequence - 1;
		if (remaining < 3) {
			return Collections.emptyList(); // may happen with self-intersecting duplicated shapes
		}
		List<Coord> merged = new ArrayList<>(remaining);
		int s1Pos = s1PosInit;
		for (int i = 0; i < s1Size - lengthOfSequence - 1; i++) {
			merged.add(points1.get(s1Pos));
			s1Pos++;
			if (s1Pos + 1 >= s1Size)
				s1Pos = 0;
		}
		int s2Pos = s2PosInit;
		int s2Step = sameDir ? 1 : -1;
		for (int i = 0; i < s2Size - lengthOfSequence; i++) {
			merged.add(points2.get(s2Pos));
			s2Pos += s2Step;
			if (s2Pos < 0)
				s2Pos = s2Size - 2;
			else if (s2Pos + 1 >= s2Size)
				s2Pos = 0;
		}
		if (merged.get(0) == merged.get(merged.size()-1))
			merged = WrongAngleFixer.removeSpikeInShape(merged);
		return merged;
	}
	
	public static double getSumLengthSquared(final List<Coord> points) {
		double length = 0;
		Iterator<Coord> iter = points.iterator();
		Coord p0 = null;
		while (iter.hasNext()) {
			final Coord p1 = iter.next();
			if (p0 != null) {
				length += p0.distanceInHighPrecSquared(p1);
			}
			p0 = p1;
		}
		return length;
	}

	private static class ShapeHelper {
		private final List<Coord> points;
		long id;
		long areaTestVal;

		public ShapeHelper(List<Coord> merged) {
			this.points = merged;
			areaTestVal = calcAreaSizeTestVal(points);
		}

		public ShapeHelper(ShapeHelper other) {
			this.points = other.points;
			this.areaTestVal = other.areaTestVal;
			this.id = other.id;
		}

		public List<Coord> getPoints() {
			return points;
		}
	}
	
	public static final long SINGLE_POINT_AREA = 1L << Coord.DELTA_SHIFT * 1L << Coord.DELTA_SHIFT;
	
	/**
	 * Calculate the high precision area size test value.  
	 * @param points
	 * @return area size in high precision map units * 2.
	 * The value is >= 0 if the shape is clockwise, else < 0   
	 */
	public static long calcAreaSizeTestVal(List<Coord> points){
		if (points.size() < 4)
			return 0; // straight line cannot enclose an area
		if (!points.get(0).highPrecEquals(points.get(points.size()-1))){
			log.error("shape is not closed");
			return 0;
		}
		Iterator<Coord> polyIter = points.iterator();
		Coord c2 = polyIter.next();
		long signedAreaSize = 0;
		while (polyIter.hasNext()) {
			Coord c1 = c2;
			c2 = polyIter.next();
			signedAreaSize += (long) (c2.getHighPrecLon() + c1.getHighPrecLon())
					* (c1.getHighPrecLat() - c2.getHighPrecLat());
		}
		if (Math.abs(signedAreaSize) < SINGLE_POINT_AREA && log.isDebugEnabled()) {
			log.debug("very small shape near", points.get(0).toOSMURL(), "signed area in high prec map units:", signedAreaSize );
		}
		return signedAreaSize;
	}
	
	public static class MapShapeComparator implements Comparator<MapShape> {
		
		private boolean orderByDecreasingArea;
		

		public MapShapeComparator(boolean orderByDecreasingArea) {
			this.orderByDecreasingArea = orderByDecreasingArea;
		}


		@Override
		public int compare(MapShape o1, MapShape o2) {
			int d = Integer.compare(o1.getType(), o2.getType());
			if (d != 0) 
				return d;
			d = Boolean.compare(o1.isSkipSizeFilter(), o2.isSkipSizeFilter());
			if (d != 0)
				return d;
			// XXX wasClipped() is ignored here, might be needed if later filters need it  
			if (this.orderByDecreasingArea) {
				d = Long.compare(o1.getFullArea(), o2.getFullArea());
				if (d != 0)
					return d;
			}
			String n1 = o1.getName();
			String n2 = o2.getName();

			if (n1 == null) {
				return (n2 == null) ? 0 : 1;
			}
			if (n2 == null)
				return -1;
			
			return n1.compareTo(n2);
		}
	}
}

