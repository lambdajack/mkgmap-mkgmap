/*
 * Copyright (C) 2014 Gerd Petermann
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
package uk.me.parabola.util;


import java.util.LinkedHashSet;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;


/**
 * A kd-tree (2D) implementation to solve the nearest neighbor problem.
 * The tree is not explicitly balanced.
 * 
 * @author Gerd Petermann
 *
 */
public class KdTree <T extends Locatable> {
	private static final boolean ROOT_NODE_USES_LONGITUDE = false;
	
	private class KdNode {
		T point;
		KdNode left;
		KdNode right;

		KdNode(T p) {
			point = p;
		}
	}
	// the tree root
    private KdNode root;
    // number of saved objects  
    private int size;

    // helpers 
    private T nextPoint ;
    private double minDist;
    private double maxDist;
    private Set<T> set;

    /**
     *  create an empty tree
     */
	public KdTree() {
		root = null;
	}

	public long size()
	{
		return size;
	}

	
	/**
	 * Add a point to the tree.
	 * @param toAdd
	 */
	public void add(T toAdd) {
		size++;
		root = add(toAdd, root, ROOT_NODE_USES_LONGITUDE);
	}

	/**
	 * Compares the given axis of both points. 
	 * @param longitude <code>true</code>: compare longitude; <code>false</code> compare latitude
	 * @param c1 a point
	 * @param c2 another point
	 * @return <code>true</code> the axis value of c1 is smaller than c2; 
	 * 		<code>false</code> the axis value of c1 is equal or larger than c2
	 */
	private boolean isSmaller(boolean longitude, Coord c1, Coord c2) {
		if (longitude) {
			return c1.getLongitude() < c2.getLongitude();
		} else {
			return c1.getLatitude() < c2.getLatitude();
		}
	}
	
	/**
	 * Recursive routine to find the right place for inserting a point into the tree.  
	 * @param toAdd the point to add
	 * @param tree the subtree root node where to add (maybe <code>null</code>)
	 * @param useLongitude <code>true</code> the tree node uses longitude for comparison; 
	 * 		<code>false</code> the tree node uses latitude for comparison
	 * @return the subtree root node after insertion
	 */
	private KdNode add(T toAdd, KdNode tree, boolean useLongitude) {
		if (tree == null) {
			tree = new KdNode(toAdd);
		} else {
			if (isSmaller(useLongitude, toAdd.getLocation(), tree.point.getLocation())) {
				tree.left = add(toAdd, tree.left, !useLongitude);
			} else {
				tree.right = add(toAdd, tree.right, !useLongitude);
			}
		}
		return tree;
	}
    
	/**
	 * Searches for the point that has smallest distance to the given point.
	 * @param p the given point
	 * @return the point with shortest distance to <var>p</var>
	 */
	public T findNextPoint(Locatable p) {
		// reset 
		minDist = Double.MAX_VALUE;
		maxDist = -1; 
		set = null;
		nextPoint = null;
		
		findNextPoint(p.getLocation(), root, ROOT_NODE_USES_LONGITUDE);
		return nextPoint;
	}

	/**
	 * Searches for the points that have <var>maxDist</var> distance to the given point.  
	 * @param p the given point
	 * @param maxDist the allowed distance
	 * @return the points within distance <var>maxDist</var> to <var>p</var>
	 */
	public Set<T> findClosePoints(Locatable p, double maxDist) {
		// reset 
		minDist = Double.MAX_VALUE;
		this.maxDist = Math.pow(maxDist * 360 / Coord.U, 2); // convert maxDist in meter to distanceInDegreesSquared
		nextPoint = null;
		set = new LinkedHashSet<>();
		findNextPoint(p.getLocation(), root, ROOT_NODE_USES_LONGITUDE);
		return set;
	}

	/**
	 * Recursive routine to find the closest point. If set is not null, all
	 * elements within the range given by maxDist are collected. 
	 * Closest point is in field nextPoint.
	 * 
	 * @param p the location of the given point
	 * @param tree the sub tree
	 * @param useLongitude gives the dimension to search in
	 */
	private void findNextPoint(Coord p, KdNode tree, boolean useLongitude) {
		if (tree == null)
			return;
		
		if (tree.left == null && tree.right == null) {
			processNode(tree, p);
			return;
		}
		boolean smaller = isSmaller(useLongitude, p, tree.point.getLocation());
		findNextPoint(p, smaller ? tree.left : tree.right, !useLongitude);

		processNode(tree, p);
		// do we have to search the other part of the tree?
		int testLat = useLongitude ? p.getHighPrecLat() : tree.point.getLocation().getHighPrecLat();
		int testLon =  useLongitude ? tree.point.getLocation().getHighPrecLon() : p.getHighPrecLon();
		Coord test = Coord.makeHighPrecCoord(testLat, testLon);
		if (test.distanceInDegreesSquared(p) < minDist) {
			findNextPoint(p, smaller ? tree.right : tree.left, !useLongitude);
		}
	}
	
	private void processNode(KdNode node, Coord p) {
		double dist = node.point.getLocation().distanceInDegreesSquared(p);
		if (dist <= maxDist && set != null) {
			// node is within wanted range
			set.add(node.point);
		}
		if (dist < minDist) {
			nextPoint = node.point;
			minDist = dist < maxDist ? maxDist : dist;
		}
	}
} 
