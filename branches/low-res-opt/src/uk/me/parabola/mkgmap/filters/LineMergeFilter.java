package uk.me.parabola.mkgmap.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.util.MultiHashMap;



public class LineMergeFilter{
	private static final Logger log = Logger.getLogger(LineMergeFilter.class);

	private List<MapLine> linesMerged;
	private final MultiHashMap<Coord, MapLine> startPoints = new MultiHashMap<>();
	private final MultiHashMap<Coord, MapLine> endPoints = new MultiHashMap<>();

	private void addLine(MapLine line) {
		linesMerged.add(line);
		List<Coord> points = line.getPoints();
		startPoints.add(points.get(0), line);
		endPoints.add(points.get(points.size()-1), line);
	}
	
	/**
	 * Removes the first line, merges the points in the second one.
	 * @param line1 1st line
	 * @param line2 2nd line
	 */
	private void mergeLines(MapLine line1, MapLine line2) {
		List<Coord> points1 = line1.getPoints();
		List<Coord> points2 = line2.getPoints();
		startPoints.removeMapping(points1.get(0), line1);
		endPoints.removeMapping(points1.get(points1.size() - 1), line1);
		startPoints.removeMapping(points2.get(0), line2);
		startPoints.add(points1.get(0), line2);
		line2.insertPointsAtStart(points1);
		linesMerged.remove(line1);
	}

	private void addPointsAtStart(MapLine line, List<Coord> additionalPoints) {
		log.info("merged lines before " + line.getName());
		List<Coord> points = line.getPoints();
		startPoints.removeMapping(points.get(0), line);
		line.insertPointsAtStart(additionalPoints);
		startPoints.add(points.get(0), line);
	}
	
	private void addPointsAtEnd(MapLine line, List<Coord> additionalPoints) {
		log.info("merged lines after " + line.getName());
		List<Coord> points = line.getPoints();
		endPoints.removeMapping(points.get(points.size() - 1), line);
		line.insertPointsAtEnd(additionalPoints);
		endPoints.add(points.get(points.size()-1), line);
	}

	/**
	 * Filter lines which are not visible at the given resolution, merge them if type and name are equal. 
	 * @param lines the lines to merge 
	 * @param res the resolution
	 * @param mergeRoads set to true if roads can be merged, too
	 * @return list of visible lines, merged as much as possible 
	 * TODO: Could merge more lines if allowed to reverse directions
	 */
	public List<MapLine> merge(List<MapLine> lines, int res, boolean mergeRoads) {
		linesMerged = new ArrayList<>();
		for (MapLine line : lines) {
			if (line.getMinResolution() > res || line.getMaxResolution() < res)
				continue;
			if (line.isRoad() && !mergeRoads) {
				linesMerged.add(line);
				continue;
			}
			
			boolean isMerged = false;
			List<Coord> points = line.getPoints();
			Coord start = points.get(0); 
			Coord end = points.get(points.size()-1); 

			// Search for start point in hashlist 
			// (can the end of current line be connected to an existing line?)
			for (MapLine line2 : startPoints.get(end)) {
				if (isSimilar(line, line2)) {
					addPointsAtStart(line2, points);
					// Search for endpoint in hashlist
					// (if the other end (=start of line =start of line2) could be connected to an existing line,
					//  both lines has to be merged and one of them dropped)
					for (MapLine line1 : endPoints.get(start)) {
						if (isSimilar(line2, line1)
						 && !line2.equals(line1)) // don't make a closed loop a double loop
						{
							mergeLines(line1, line2);
							break;
						}
					}						
					isMerged = true;
					break;
				}
			}
			if (isMerged)
				continue;

			// Search for endpoint in hashlist
			// (can the start of current line be connected to an existing line?)
			for (MapLine line2 : endPoints.get(start)) {
				if (isSimilar(line, line2)) {
					addPointsAtEnd(line2, points);
					isMerged = true;
					break;
				}
			}
			if (isMerged)
				continue;

			// No matching, create a copy of line
			MapLine l = line.copy();
			l.setPoints(new ArrayList<>(line.getPoints()));				
			addLine(l);
		}
		return linesMerged;
	}
	
	private static boolean isSimilar(MapLine l1, MapLine l2) {
		return l1.getType() == l2.getType() && l1.isDirection() == l2.isDirection()
				&& Objects.equals(l1.getName(), l2.getName());
	}
}

