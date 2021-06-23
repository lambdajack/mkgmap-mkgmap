package uk.me.parabola.mkgmap.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.util.MultiHashMap;



public class LineMergeFilter{
	private static final Logger log = Logger.getLogger(LineMergeFilter.class);

	private List<MapLine> linesMerged;
	private final MultiHashMap<Coord, MapLine> sharedPoints = new MultiHashMap<>();

	private void addLine(MapLine line) {
		linesMerged.add(line);
		List<Coord> points = line.getPoints();
		Coord start = points.get(0);
		Coord end = points.get(points.size() - 1);
		sharedPoints.add(start, line);
		sharedPoints.add(end, line);
	}

	private void addPointsAtStart(MapLine line, List<Coord> additionalPoints) {
		log.info("merged lines before " + line.getName());
		List<Coord> points = line.getPoints();
		sharedPoints.removeMapping(points.get(0), line);
		line.insertPointsAtStart(additionalPoints);
		sharedPoints.add(points.get(0), line);
	}

	private void addPointsAtEnd(MapLine line, List<Coord> additionalPoints) {
		log.info("merged lines after " + line.getName());
		List<Coord> points = line.getPoints();
		sharedPoints.removeMapping(points.get(points.size() - 1), line);
		line.insertPointsAtEnd(additionalPoints);
		sharedPoints.add(points.get(points.size() - 1), line);
	}

	/**
	 * Filter lines which are not visible at the given resolution, merge them if type and name are equal. 
	 * @param lines the lines to merge 
	 * @param res the resolution
	 * @param mergeRoads set to true if roads can be merged, too
	 * @param reverseAllowed set to true if lines without direction can be reversed
	 * @return list of visible lines, merged as much as possible 
	 */
	public List<MapLine> merge(List<MapLine> lines, int res, boolean mergeRoads, boolean reverseAllowed) {
		linesMerged = new ArrayList<>();
		
		for (MapLine line : lines) {
			if (line.getMinResolution() > res || line.getMaxResolution() < res)
				continue;
			if (line.isRoad() && !mergeRoads) {
				linesMerged.add(line);
				continue;
			}
			
			if (!tryMerge(line, reverseAllowed)) {
				// No matching, create a copy of line
				MapLine l = line.copy();
				l.setPoints(new ArrayList<>(line.getPoints()));				
				addLine(l);
			}
		}
		return linesMerged;
	}

	/**
	 * Try to find previously processed lines to which the points of this line can be added.
	 * @param line the new line to add
	 * @param reverseAllowed set to true if lines without direction can be reversed
	 * @return true if points were merged
	 */
	private boolean tryMerge(MapLine line, boolean reverseAllowed) {
		Coord start = line.getPoints().get(0);
		Coord end = line.getPoints().get(line.getPoints().size() - 1);
		
		Set<MapLine> candidates = new LinkedHashSet<>(sharedPoints.get(start));
		candidates.addAll(sharedPoints.get(end));
		Coord mergePoint = null;
		MapLine mergedLine = null;
		for (MapLine line2 : candidates) {
			mergePoint = tryCandidate(line, line2, reverseAllowed);
			if (mergePoint != null) {
				mergedLine = line2;
				break;
			}
		}
		if (mergePoint == null)
			return false;

		// check if the other end of the new line could also be merged
		Coord otherEnd = mergePoint.equals(end) ? start : end;
		candidates.clear();
		candidates.addAll(sharedPoints.get(otherEnd));
		for (MapLine line2 : candidates) {
			mergePoint = tryCandidate(line2, mergedLine, reverseAllowed);
			if (mergePoint != null) {
				sharedPoints.removeMapping(line2.getPoints().get(0), line2);
				sharedPoints.removeMapping(line2.getPoints().get(line2.getPoints().size()-1), line2);
				linesMerged.remove(line2);
				break;
			}
		}
		return true;
	}

	/**
	 * Merge if lines are similar and have exactly one common end node. May create loops.
	 * 
	 * @param line1  line to merge
	 * @param line2 line to keep
	 * @param reverseAllowed set to true if lines without direction can be reversed
	 * @return the common point at which the lines were merged, taken from line1
	 */
	private Coord tryCandidate(MapLine line1, MapLine line2, boolean reverseAllowed) {
		if (line1 == line2 || !isSimilar(line1, line2))
			return null;
		List<Coord> points1 = line1.getPoints();
		Coord start1 = points1.get(0);
		Coord end1 = points1.get(points1.size() - 1);
		List<Coord> points2 = line2.getPoints();
		Coord start2 = points2.get(0);
		Coord end2 = points2.get(points2.size() - 1);
		
		if (end2.equals(start1)) {
			addPointsAtEnd(line2, line1.getPoints());
			return start1;
		} else if (start2.equals(end1)) {
			addPointsAtStart(line2, line1.getPoints());
			return end1;
		} else if (reverseAllowed && end2.equals(end1) && !line1.isDirection()) {
			ArrayList<Coord> reversed = new ArrayList<>(line1.getPoints());
			Collections.reverse(reversed);
			addPointsAtEnd(line2, reversed);
			return end1;
		} else if (reverseAllowed && start2.equals(start1) && !line1.isDirection()) {
			ArrayList<Coord> reversed = new ArrayList<>(line1.getPoints());
			Collections.reverse(reversed);
			addPointsAtStart(line2, reversed);
			return start1;
		}
		return null;
	}

	private static boolean isSimilar(MapLine l1, MapLine l2) {
		return l1.getType() == l2.getType() && l1.isDirection() == l2.isDirection()
				&& Objects.equals(l1.getName(), l2.getName());
	}
}

