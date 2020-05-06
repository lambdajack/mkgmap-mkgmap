/*
 * Copyright (C) 2017.
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

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation.JoinedWay;
import uk.me.parabola.util.Java2DConverter;

/**
 * Methods to cut an MP-relation so that holes are connected with the outer way(s).
 * Extracted from {@link MultiPolygonRelation}.
 * 
 * @author WanMil
 * @author Gerd Petermann
 *
 */
public class MultiPolygonCutter {
	private static final Logger log = Logger.getLogger(MultiPolygonCutter.class);
	private final MultiPolygonRelation rel;
	private final Area tileArea;

	/**
	 * Create cutter for a given MP-relation and tile
	 * @param multiPolygonRelation the MP-relation
	 * @param tileArea the java area of the tile
	 */
	public MultiPolygonCutter(MultiPolygonRelation multiPolygonRelation, Area tileArea) {
		rel = multiPolygonRelation;
		this.tileArea = tileArea;
	}

	/**
	 * Cut out all inner polygons from the outer polygon. This will divide the outer
	 * polygon in several polygons.
	 * @param multiPolygonRelation 
	 * 
	 * @param outerPolygon
	 *            the outer polygon
	 * @param innerPolygons
	 *            a list of inner polygons
	 * @return a list of polygons that make the outer polygon cut by the inner
	 *         polygons
	 */
	public List<Way> cutOutInnerPolygons(Way outerPolygon, List<Way> innerPolygons) {
		if (innerPolygons.isEmpty()) {
			Way outerWay = new JoinedWay(outerPolygon);
			if (log.isDebugEnabled()) {
				log.debug("Way", outerPolygon.getId(), "splitted to way", outerWay.getId());
			}
			return Collections.singletonList(outerWay);
		}

		// use the java.awt.geom.Area class because it's a quick
		// implementation of what's needed

		// this list contains all non overlapping and singular areas
		// of the outerPolygon
		Queue<AreaCutData> areasToCut = new LinkedList<>();
		Collection<Area> finishedAreas = new ArrayList<>(innerPolygons.size());
		
		// create a list of Area objects from the outerPolygon (clipped to the bounding box)
		List<Area> outerAreas = createAreas(outerPolygon, true);
		
		// create the inner areas
		List<Area> innerAreas = new ArrayList<>(innerPolygons.size()+2);
		for (Way innerPolygon : innerPolygons) {
			// don't need to clip to the bounding box because 
			// these polygons are just used to cut out holes
			innerAreas.addAll(createAreas(innerPolygon, false));
		}

		// initialize the cut data queue
		if (innerAreas.isEmpty()) {
			// this is a multipolygon without any inner areas
			// nothing to cut
			finishedAreas.addAll(outerAreas);
		} else if (outerAreas.size() == 1) {
			// there is one outer area only
			// it is checked before that all inner areas are inside this outer area
			AreaCutData initialCutData = new AreaCutData();
			initialCutData.outerArea = outerAreas.get(0);
			initialCutData.innerAreas = innerAreas;
			areasToCut.add(initialCutData);
		} else {
			// multiple outer areas
			for (Area outerArea : outerAreas) {
				AreaCutData initialCutData = new AreaCutData();
				initialCutData.outerArea = outerArea;
				initialCutData.innerAreas = new ArrayList<>(innerAreas
						.size());
				for (Area innerArea : innerAreas) {
					if (outerArea.getBounds2D().intersects(
						innerArea.getBounds2D())) {
						initialCutData.innerAreas.add(innerArea);
					}
				}
				
				if (initialCutData.innerAreas.isEmpty()) {
					// this is either an error
					// or the outer area has been cut into pieces on the tile bounds
					finishedAreas.add(outerArea);
				} else {
					areasToCut.add(initialCutData);
				}
			}
		}

		while (!areasToCut.isEmpty()) {
			AreaCutData areaCutData = areasToCut.poll();
			CutPoint cutPoint = calcNextCutPoint(areaCutData);
			
			if (cutPoint == null) {
				finishedAreas.add(areaCutData.outerArea);
				continue;
			}
			
			assert cutPoint.getNumberOfAreas() > 0 : "Number of cut areas == 0 in mp " + rel.getId();
			
			// cut out the holes
			if (cutPoint.getAreas().size() == 1)
				areaCutData.outerArea.subtract(cutPoint.getAreas().get(0));
			else {
				// first combine the areas that should be subtracted
				Path2D.Double path = new Path2D.Double();
				for (Area cutArea : cutPoint.getAreas()) {
					path.append(cutArea, false);
				}
				Area combinedCutAreas = new Area(path);
				areaCutData.outerArea.subtract(combinedCutAreas);
			}
				
			if (areaCutData.outerArea.isEmpty()) {
				// this outer area space can be abandoned
				continue;
			} 
			
			// the inner areas of the cut point have been processed
			// they are no longer needed
			
			for (Area cutArea : cutPoint.getAreas()) {
				ListIterator<Area> areaIter = areaCutData.innerAreas.listIterator();
				while (areaIter.hasNext()) {
					Area a = areaIter.next();
					if (a == cutArea) {
						areaIter.remove();
						break;
					}
				}
			}

			if (areaCutData.outerArea.isSingular()) {
				// the area is singular
				// => no further splits necessary
				if (areaCutData.innerAreas.isEmpty()) {
					// this area is finished and needs no further cutting
					finishedAreas.add(areaCutData.outerArea);
				} else {
					// read this area to further processing
					areasToCut.add(areaCutData);
				}
			} else {
				// we need to cut the area into two halves to get singular areas
				Rectangle2D r1 = cutPoint.getCutRectangleForArea(areaCutData.outerArea, true);
				Rectangle2D r2 = cutPoint.getCutRectangleForArea(areaCutData.outerArea, false);

				// Now find the intersection of these two boxes with the
				// original polygon. This will make two new areas, and each
				// area will be one (or more) polygons.
				Area a1 = new Area(r1); 
				Area a2 = new Area(r2);
				a1.intersect(areaCutData.outerArea);
				a2.intersect(areaCutData.outerArea);
				if (areaCutData.innerAreas.isEmpty()) {
					finishedAreas.addAll(Java2DConverter.areaToSingularAreas(a1));
					finishedAreas.addAll(Java2DConverter.areaToSingularAreas(a2));
				} else {
					ArrayList<Area> cuttedAreas = new ArrayList<>();
					cuttedAreas.addAll(Java2DConverter.areaToSingularAreas(a1));
					cuttedAreas.addAll(Java2DConverter.areaToSingularAreas(a2));
					
					for (Area nextOuterArea : cuttedAreas) {
						ArrayList<Area> nextInnerAreas = null;
						// go through all remaining inner areas and check if they
						// must be further processed with the nextOuterArea 
						for (Area nonProcessedInner : areaCutData.innerAreas) {
							if (nextOuterArea.intersects(nonProcessedInner.getBounds2D())) {
								if (nextInnerAreas == null) {
									nextInnerAreas = new ArrayList<>();
								}
								nextInnerAreas.add(nonProcessedInner);
							}
						}
						
						if (nextInnerAreas == null || nextInnerAreas.isEmpty()) {
							finishedAreas.add(nextOuterArea);
						} else {
							AreaCutData outCutData = new AreaCutData();
							outCutData.outerArea = nextOuterArea;
							outCutData.innerAreas= nextInnerAreas;
							areasToCut.add(outCutData);
						}
					}
				}
			}
			
		}
		
		// convert the java.awt.geom.Area back to the mkgmap way
		List<Way> cuttedOuterPolygon = new ArrayList<>(finishedAreas.size());
		Long2ObjectOpenHashMap<Coord> commonCoordMap = new Long2ObjectOpenHashMap<>();
		for (Area area : finishedAreas) {
			Way w = singularAreaToWay(area, rel.getOriginalId());
			if (w != null) {
				w.setFakeId();
				// make sure that equal coords are changed to identical coord instances
				// this allows merging in the ShapeMerger
				int n = w.getPoints().size();
				for (int i = 0; i < n; i++){
					Coord p = w.getPoints().get(i);
					long key = Utils.coord2Long(p);
					Coord replacement = commonCoordMap.get(key);
					if (replacement == null)
						commonCoordMap.put(key, p);
					else {
						assert p.highPrecEquals(replacement);
						w.getPoints().set(i, replacement);
					}
				}
				w.copyTags(outerPolygon);
				cuttedOuterPolygon.add(w);
				if (log.isDebugEnabled()) {
					log.debug("Way", outerPolygon.getId(), "splitted to way", w.getId());
				}
			}
		}

		return cuttedOuterPolygon;
	}
	
	private static CutPoint calcNextCutPoint(AreaCutData areaData) {
		if (areaData.innerAreas == null || areaData.innerAreas.isEmpty()) {
			return null;
		}
		
		Rectangle2D outerBounds = areaData.outerArea.getBounds2D();
		
		if (areaData.innerAreas.size() == 1) {
			// make it short if there is only one inner area
			CutPoint cutPoint1 = new CutPoint(CoordinateAxis.LATITUDE, outerBounds);
			cutPoint1.addArea(areaData.innerAreas.get(0));
			CutPoint cutPoint2 = new CutPoint(CoordinateAxis.LONGITUDE, outerBounds);
			cutPoint2.addArea(areaData.innerAreas.get(0));
			if (cutPoint1.compareTo(cutPoint2) > 0) {
				return cutPoint1;
			} else {
				return cutPoint2;
			}
			
		}
		
		ArrayList<Area> innerStart = new ArrayList<>(areaData.innerAreas);
		
		// first try to cut out all polygons that intersect the boundaries of the outer polygon
		// this has the advantage that the outer polygon need not be split into two halves
		for (CoordinateAxis axis : CoordinateAxis.values()) {
			CutPoint edgeCutPoint = new CutPoint(axis, outerBounds);

			// go through the inner polygon list and use all polygons that intersect the outer polygons bbox at the start
			innerStart.sort(axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START: COMP_LAT_START);
			for (Area anInnerStart : innerStart) {
				if (axis.getStartHighPrec(anInnerStart) <= axis.getStartHighPrec(outerBounds)) {
					// found a touching area
					edgeCutPoint.addArea(anInnerStart);
				} else {
					break;
				}
			}
			if (edgeCutPoint.getNumberOfAreas() > 0) {
				// there at least one intersecting inner polygon
				return edgeCutPoint;
			}
			
			innerStart.sort(axis == CoordinateAxis.LONGITUDE ? COMP_LONG_STOP: COMP_LAT_STOP);
			// go through the inner polygon list and use all polygons that intersect the outer polygons bbox at the stop
			for (Area anInnerStart : innerStart) {
				if (axis.getStopHighPrec(anInnerStart) >= axis.getStopHighPrec(outerBounds)) {
					// found a touching area
					edgeCutPoint.addArea(anInnerStart);
				} else {
					break;
				}
			}
			if (edgeCutPoint.getNumberOfAreas() > 0) {
				// there at least one intersecting inner polygon
				return edgeCutPoint;
			}
		}
		
		
		ArrayList<CutPoint> bestCutPoints = new ArrayList<>(CoordinateAxis.values().length);
		for (CoordinateAxis axis : CoordinateAxis.values()) {
			CutPoint bestCutPoint = new CutPoint(axis, outerBounds);
			CutPoint currentCutPoint = new CutPoint(axis, outerBounds);

			innerStart.sort(axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START: COMP_LAT_START);

			for (Area anInnerStart : innerStart) {
				currentCutPoint.addArea(anInnerStart);

				if (currentCutPoint.compareTo(bestCutPoint) > 0) {
					bestCutPoint = currentCutPoint.duplicate();
				}
			}
			bestCutPoints.add(bestCutPoint);
		}

		return Collections.max(bestCutPoints);
		
	}

	/**
	 * Create the areas that are enclosed by the way. Usually the result should
	 * only be one area but some ways contain intersecting lines. To handle these
	 * erroneous cases properly the method might return a list of areas.
	 * 
	 * @param w a closed way
	 * @param clipBbox true if the areas should be clipped to the bounding box; false else
	 * @return a list of enclosed ares
	 */
	private List<Area> createAreas(Way w, boolean clipBbox) {
		Area area = Java2DConverter.createArea(w.getPoints());
		if (clipBbox && !tileArea.contains(area.getBounds2D())) {
			// the area intersects the bounding box => clip it
			area.intersect(tileArea);
		}
		List<Area> areaList = Java2DConverter.areaToSingularAreas(area);
		if (log.isDebugEnabled()) {
			log.debug("Bbox clipped way",w.getId()+"=>",areaList.size(),"distinct area(s).");
		}
		return areaList;
	}

	/**
	 * Convert an area to an mkgmap way. The caller must ensure that the area is singular.
	 * Otherwise only the first part of the area is converted.
	 * 
	 * @param area
	 *            the area
	 * @param wayId
	 *            the wayid for the new way
	 * @return a new mkgmap way
	 */
	private Way singularAreaToWay(Area area, long wayId) {
		List<Coord> points = Java2DConverter.singularAreaToPoints(area);
		if (points == null || points.isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("Empty area", wayId + ".", rel.toBrowseURL());
			}
			return null;
		}

		return new Way(wayId, points);
	}
	private static class AreaCutData {
		Area outerArea;
		List<Area> innerAreas;
	}

	private static final int CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD = 1<<(11 + Coord.DELTA_SHIFT);
	private static final int CUT_POINT_CLASSIFICATION_BAD_THRESHOLD = 1<< (8 + Coord.DELTA_SHIFT);
	private static class CutPoint implements Comparable<CutPoint>{
		private int startPoinHp = Integer.MAX_VALUE; // high precision map units
		private int stopPointHp = Integer.MIN_VALUE;  // high precision map units
		private Integer cutPointHp = null; // high precision map units
		private final LinkedList<Area> areas;
		private final Comparator<Area> comparator;
		private final CoordinateAxis axis;
		private Rectangle2D bounds;
		private final Rectangle2D outerBounds;
		private Double minAspectRatio;

		public CutPoint(CoordinateAxis axis, Rectangle2D outerBounds) {
			this.axis = axis;
			this.outerBounds = outerBounds;
			this.areas = new LinkedList<>();
			this.comparator = (axis == CoordinateAxis.LONGITUDE ? COMP_LONG_STOP : COMP_LAT_STOP);
		}
		
		public CutPoint duplicate() {
			CutPoint newCutPoint = new CutPoint(this.axis, this.outerBounds);
			newCutPoint.areas.addAll(areas);
			newCutPoint.startPoinHp = startPoinHp;
			newCutPoint.stopPointHp = stopPointHp;
			return newCutPoint;
		}

		private boolean isGoodCutPoint() {
			// It is better if the cutting line is on a multiple of 2048. 
			// Otherwise MapSource and QLandkarteGT paints gaps between the cuts
			return getCutPointHighPrec() % CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD == 0;
		}
		
		private boolean isBadCutPoint() {
			int d1 = getCutPointHighPrec() - startPoinHp;
			int d2 = stopPointHp - getCutPointHighPrec();
			return Math.min(d1, d2) < CUT_POINT_CLASSIFICATION_BAD_THRESHOLD;
		}
		
		private boolean isStartCut() {
			return (startPoinHp <= axis.getStartHighPrec(outerBounds));
		}
		
		private boolean isStopCut() {
			return (stopPointHp >= axis.getStopHighPrec(outerBounds));
		}
		
		/**
		 * Calculates the point where the cut should be applied.
		 * @return the point of cut
		 */
		private int getCutPointHighPrec() {
			if (cutPointHp != null) {
				// already calculated => just return it
				return cutPointHp;
			}
			
			if (startPoinHp == stopPointHp) {
				// there is no choice => return the one possible point 
				cutPointHp = startPoinHp;
				return cutPointHp;
			}
			
			if (isStartCut()) {
				// the polygons can be cut out at the start of the sector
				// thats good because the big polygon need not to be cut into two halves
				cutPointHp = startPoinHp;
				return cutPointHp;
			}
			
			if (isStopCut()) {
				// the polygons can be cut out at the end of the sector
				// thats good because the big polygon need not to be cut into two halves
				cutPointHp = startPoinHp;
				return cutPointHp;
			}
			
			// try to cut with a good aspect ratio so try the middle of the polygon to be cut
			int midOuterHp = axis.getStartHighPrec(outerBounds)+(axis.getStopHighPrec(outerBounds) - axis.getStartHighPrec(outerBounds)) / 2;
			cutPointHp = midOuterHp;

			if (midOuterHp < startPoinHp) {
				// not possible => the start point is greater than the middle so correct to the startPoint
				cutPointHp = startPoinHp;
				
				if (((cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1)) + CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD) <= stopPointHp) {
					cutPointHp = ((cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1)) + CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD);
				}
				
			} else if (midOuterHp > stopPointHp) {
				// not possible => the stop point is smaller than the middle so correct to the stopPoint
				cutPointHp = stopPointHp;

				if ((cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1))  >= startPoinHp) {
					cutPointHp = (cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1));
				}
			}
			
			
			// try to find a cut point that is a multiple of 2048 to 
			// avoid that gaps are painted by MapSource and QLandkarteGT
			// between the cutting lines
			int cutMod = cutPointHp % CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD;
			if (cutMod == 0) {
				return cutPointHp;
			}
			
			int cut1 = (cutMod > 0 ? cutPointHp-cutMod : cutPointHp  - CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD- cutMod);
			if (cut1 >= startPoinHp && cut1 <= stopPointHp) {
				cutPointHp = cut1;
				return cutPointHp;
			}
			
			int cut2 = (cutMod > 0 ? cutPointHp + CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD -cutMod : cutPointHp - cutMod);
			if (cut2 >= startPoinHp && cut2 <= stopPointHp) {
				cutPointHp = cut2;
				return cutPointHp;
			}
			
			return cutPointHp;
		}

		public Rectangle2D getCutRectangleForArea(Area toCut, boolean firstRect) {
			return getCutRectangleForArea(toCut.getBounds2D(), firstRect);
		}
		
		public Rectangle2D getCutRectangleForArea(Rectangle2D areaRect, boolean firstRect) {
			double cp = (double)  getCutPointHighPrec() / (1<<Coord.DELTA_SHIFT);
			if (axis == CoordinateAxis.LONGITUDE) {
				double newWidth = cp-areaRect.getX();
				if (firstRect) {
					return new Rectangle2D.Double(areaRect.getX(), areaRect.getY(), newWidth, areaRect.getHeight()); 
				} else {
					return new Rectangle2D.Double(areaRect.getX()+newWidth, areaRect.getY(), areaRect.getWidth()-newWidth, areaRect.getHeight()); 
				}
			} else {
				double newHeight = cp-areaRect.getY();
				if (firstRect) {
					return new Rectangle2D.Double(areaRect.getX(), areaRect.getY(), areaRect.getWidth(), newHeight); 
				} else {
					return new Rectangle2D.Double(areaRect.getX(), areaRect.getY()+newHeight, areaRect.getWidth(), areaRect.getHeight()-newHeight); 
				}
			}
		}
		
		public List<Area> getAreas() {
			return areas;
		}

		public void addArea(Area area) {
			// remove all areas that do not overlap with the new area
			while (!areas.isEmpty() && axis.getStopHighPrec(areas.getFirst()) < axis.getStartHighPrec(area)) {
				// remove the first area
				areas.removeFirst();
			}

			areas.add(area);
			areas.sort(comparator);
			startPoinHp = axis.getStartHighPrec(Collections.max(areas,
				(axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START
						: COMP_LAT_START)));
			stopPointHp = axis.getStopHighPrec(areas.getFirst());
			
			// reset the cached value => need to be recalculated the next time they are needed
			bounds = null;
			cutPointHp = null;
			minAspectRatio = null;
		}

		public int getNumberOfAreas() {
			return this.areas.size();
		}

		/**
		 * Retrieves the minimum aspect ratio of the outer bounds after cutting.
		 * 
		 * @return minimum aspect ratio of outer bound after cutting
		 */
		public double getMinAspectRatio() {
			if (minAspectRatio == null) {
				// first get the left/upper cut
				Rectangle2D r1 = getCutRectangleForArea(outerBounds, true);
				double s11 = CoordinateAxis.LATITUDE.getSizeOfSide(r1);
				double s12 = CoordinateAxis.LONGITUDE.getSizeOfSide(r1);
				double ar1 = Math.min(s11, s12) / Math.max(s11, s12);

				// second get the right/lower cut
				Rectangle2D r2 = getCutRectangleForArea(outerBounds, false);
				double s21 = CoordinateAxis.LATITUDE.getSizeOfSide(r2);
				double s22 = CoordinateAxis.LONGITUDE.getSizeOfSide(r2);
				double ar2 = Math.min(s21, s22) / Math.max(s21, s22);

				// get the minimum
				minAspectRatio = Math.min(ar1, ar2);
			}
			return minAspectRatio;
		}
		
		
		public int compareTo(CutPoint o) {
			if (this == o) {
				return 0;
			}
			// prefer a cut at the boundaries
			int d = Boolean.compare(isStartCut(), o.isStartCut());
			if (d != 0)
				return d;
			d = Boolean.compare(isStopCut(), o.isStopCut());
			if (d != 0)
				return d;
			// handle the special case that a cut has no area
			if (getNumberOfAreas() == 0) {
				return o.getNumberOfAreas() == 0 ? 0 : -1;
			} else if (o.getNumberOfAreas() == 0) {
				return 1;
			}

			d = Boolean.compare(o.isBadCutPoint(), isBadCutPoint()); // exchanged order!
			if (d != 0)
				return d;
			double dAR = getMinAspectRatio() - o.getMinAspectRatio();
			if (dAR != 0) {
				return (dAR > 0 ? 1 : -1);
			}
			d = Boolean.compare(isGoodCutPoint(), o.isGoodCutPoint());
			if (d != 0)
				return d;
			// prefer the larger area that is split
			d = Double.compare(axis.getSizeOfSide(getBounds2D()), o.axis.getSizeOfSide(o.getBounds2D()));
			if (d != 0)
				return d;

			return getNumberOfAreas() - o.getNumberOfAreas();

		}

		private Rectangle2D getBounds2D() {
			if (bounds == null) {
				// lazy init
				bounds = new Rectangle2D.Double();
				for (Area a : areas)
					bounds.add(a.getBounds2D());
			}
			return bounds;
		}

		public String toString() {
			return axis +" "+getNumberOfAreas()+" "+startPoinHp+" "+stopPointHp+" "+getCutPointHighPrec();
		}
	}

	private enum CoordinateAxis {
		LATITUDE(false), LONGITUDE(true);

		private CoordinateAxis(boolean useX) {
			this.useX = useX;
		}

		private final boolean useX;

		public int getStartHighPrec(Area area) {
			return getStartHighPrec(area.getBounds2D());
		}

		public int getStartHighPrec(Rectangle2D rect) {
			double val = (useX ? rect.getX() : rect.getY());
			return (int)Math.round(val * (1<<Coord.DELTA_SHIFT));
		}

		public int getStopHighPrec(Area area) {
			return getStopHighPrec(area.getBounds2D());
		}

		public int getStopHighPrec(Rectangle2D rect) {
			double val = (useX ? rect.getMaxX() : rect.getMaxY());
			return (int)Math.round(val * (1<<Coord.DELTA_SHIFT));
		}
		
		public double getSizeOfSide(Rectangle2D rect) {
			if (useX) {
				int latHp = (int)Math.round(rect.getY() * (1<<Coord.DELTA_SHIFT));
				Coord c1 = Coord.makeHighPrecCoord(latHp, getStartHighPrec(rect));
				Coord c2 = Coord.makeHighPrecCoord(latHp, getStopHighPrec(rect));
				return c1.distance(c2);
			} else {
				int lonHp = (int)Math.round(rect.getX() * (1<<Coord.DELTA_SHIFT));
				Coord c1 = Coord.makeHighPrecCoord(getStartHighPrec(rect), lonHp);
				Coord c2 = Coord.makeHighPrecCoord(getStopHighPrec(rect), lonHp);
				return c1.distance(c2);
			}
		}
	}
	
	private static final AreaComparator COMP_LONG_START = new AreaComparator(
			true, CoordinateAxis.LONGITUDE);
	private static final AreaComparator COMP_LONG_STOP = new AreaComparator(
			false, CoordinateAxis.LONGITUDE);
	private static final AreaComparator COMP_LAT_START = new AreaComparator(
			true, CoordinateAxis.LATITUDE);
	private static final AreaComparator COMP_LAT_STOP = new AreaComparator(
			false, CoordinateAxis.LATITUDE);

	private static class AreaComparator implements Comparator<Area> {

		private final CoordinateAxis axis;
		private final boolean startPoint;

		public AreaComparator(boolean startPoint, CoordinateAxis axis) {
			this.startPoint = startPoint;
			this.axis = axis;
		}

		public int compare(Area o1, Area o2) {
			if (o1 == o2) {
				return 0;
			}

			if (startPoint) {
				int cmp = axis.getStartHighPrec(o1) - axis.getStartHighPrec(o2);
				if (cmp == 0) {
					return axis.getStopHighPrec(o1) - axis.getStopHighPrec(o2);
				} else {
					return cmp;
				}
			} else {
				int cmp = axis.getStopHighPrec(o1) - axis.getStopHighPrec(o2);
				if (cmp == 0) {
					return axis.getStartHighPrec(o1) - axis.getStartHighPrec(o2);
				} else {
					return cmp;
				}
			}
		}

	}
}
