package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Tags;

/**
 * A simple quadtree implementation that stores the areas formed by boundaries. 
 * @author GerdP
 *
 */
public class BoundaryQuadTree {
	Node root;
	/** An array of tag names used in the LocationHook*/
	final String [] locationTagNames;
	
	long cntEmpty;
	long cntNodes;
	long cntTreeNode;
	long cntSimpleContains;
	long cntComplexContains;
	long cntSubtract;
	long cntIntersect;
	long cntIntersects;
	/**
	 * Create a quadtree for a given bounding box and a list of boundaries.
	 * @param bbox	The bounding box for the quadTree, only data within this box is used 
	 * @param boundaries A list of boundaries. For better performance, the list should be sorted so that small areas come first.
	 * @param locationTagNames An array of location tag names that should be returned 
	 */
	public BoundaryQuadTree (uk.me.parabola.imgfmt.app.Area bbox, List<Boundary> boundaries, final String[] locationTagNames ){
		this.locationTagNames = locationTagNames;
		root = new Node(bbox);
		for (int i = 0;i < boundaries.size(); i++){
			Boundary b = boundaries.get(i);
			root.add (b.getArea(), b.getLocTags());
		}
		
		root.split();
		
	}	
	/**
	 * Return location relevant Tags for the point defined by Coord 
	 * @param co the point
	 * @return a reference to the internal Tags or null if the point was not found. 
	 * The returned Tags must not be modified by the caller.   
	 */
	public Tags get(Coord co){
		return root.get(co);
	}
	
	public void stats(){
		System.out.println("qt: tree nodes              : " + cntTreeNode);
		System.out.println("qt: Area nodes              : " + cntNodes);
		System.out.println("qt: emptied nodes           : " + cntEmpty);
		System.out.println("qt: simple contains() calls : " + cntSimpleContains);
		System.out.println("qt: simple intersects calls : " + cntIntersects);
		System.out.println("qt: complex contains() calls: " + cntComplexContains);
		System.out.println("qt: area intersect calls    : " + cntIntersect);
		System.out.println("qt: area subtract calls     : " + cntSubtract);
	}
	
	private class Node {
		private Node [] childs;
		private List<NodeElem> nodes;

		// bounding box of this part of the tree
		private final Rectangle bbox;
		private final Area bboxArea;
		private final uk.me.parabola.imgfmt.app.Area bounds;

		// a bit mask that represents all relevant tags in this node and its sub-nodes 
		private short availLocTagsMask;

		private short depth;
		private short numNodes;
		private boolean isLeaf;

		/**
		 * Create an empty node 
		 * @param bbox
		 */
		private Node (uk.me.parabola.imgfmt.app.Area bbox){
			this.bounds = bbox;
			this.bbox = new Rectangle(bbox.getMinLong(), bbox.getMinLat(),
					bbox.getMaxLong() - bbox.getMinLong(), bbox.getMaxLat()
					- bbox.getMinLat());
			this.bboxArea = new Area (this.bbox);
			isLeaf = true;
		}	
		/**
		 * Return location relevant Tags for the point defined by Coord 
		 * @param co the point
		 * @return a reference to the internal Tags or null if the point was not found. 
		 * The returned Tags must not be modified by the caller.   
		 */
		private Tags get(Coord co){
			++cntSimpleContains;
			if (this.bounds.contains(co) == false)
				return null;
			if (isLeaf){
				if (numNodes == 0)
					return null;
				for (NodeElem nodeElem: nodes){
					if (nodeElem.tagMask > 0){	
						++cntComplexContains;
						if (nodeElem.area.contains(co.getLongitude(), co.getLatitude())) 
							return nodeElem.tags;
					}
				}
			}
			else {
				for (int i = 0; i < 4; i++){
					Tags res = childs[i].get(co);
					if (res != null) 
						return res; 
				}
			}
			return null;
		}


		/**
		 * calculate a handy short value that represents the available location tags
		 * @param tags 
		 * @return a bit mask, a bit with value 1 means the corresponding entry in {@link locationTagNames } 
		 * is available
		 */
		private short calcLocationTagsMask(Tags tags){
			short res = 0;
			for (int i = 0; i < locationTagNames.length; i++){
				if (tags.get(locationTagNames[i] ) != null)
					res |= (1 << i);
			}
			return res;
		}

		/**
		 * Constructor that is used by the split method. The parameters give the corners of a bounding box.
		 * @param minLat
		 * @param minLong
		 * @param maxLat
		 * @param maxLong
		 */
		private Node (int minLat, int minLong, int maxLat, int maxLong){
			this.bounds = new uk.me.parabola.imgfmt.app.Area (minLat, minLong, maxLat, maxLong);
			this.bbox = new Rectangle(minLong, minLat, maxLong - minLong, maxLat - minLat);
			this.bboxArea = new Area(this.bbox);
			this.isLeaf = true;
			numNodes = 0;
		}

		/**
		 * Add an area and the related tags to the tree. 
		 * @param area the part of the boundary area that should be added to the tree.    
		 * @param bTags the location relevant tags from the boundary 
		 */
		private void add(Area area, Tags bTags){
			++cntIntersects;
			if (area.intersects(this.bbox) == false)
				return;
			Area a = new Area(area);
			if (bboxArea.contains(a.getBounds()) == false){
				a.intersect(this.bboxArea);
				++cntIntersect;
			}
			if (a.isEmpty() == false){
				if (numNodes == 0){
					nodes = new ArrayList<NodeElem>();
				}
				NodeElem nodeElem = new NodeElem();
				nodeElem.area = a;
				nodeElem.tags = bTags.copy();
				nodeElem.tagMask = calcLocationTagsMask(nodeElem.tags);
				availLocTagsMask |= nodeElem.tagMask ;
				nodes.add(nodeElem);
				++numNodes;
				++cntNodes;
				
			}
		}

		/***
		 * try to merge information from the boundaries 
		 */
		private void merge(){
			if (isLeaf == false || numNodes <= 0)
				return;
			// detect intersection of areas, merge tag info
			for (int i=0; i < numNodes-1; i++){
				NodeElem toMerge = nodes.get(i);
				short neededTags = (short)(availLocTagsMask & ~toMerge.tagMask);
				if (neededTags == 0) 
					continue;

				for (int j=i+1; j < numNodes; j++){
					NodeElem srcElem = nodes.get(j);
					if ((neededTags & srcElem.tagMask) != 0 ){
						Rectangle r = srcElem.area.getBounds();
						++cntIntersects;
						if (toMerge.area.intersects(r) == false)
							continue;
						// area might add info to toMerge element
						Area a1 = new Area(toMerge.area);
						a1.subtract(srcElem.area);
						++cntSubtract;

						if (a1.equals(toMerge.area)) {
							// the two areas do not intersect
							continue;  
						}

						if (a1.isEmpty()){
							// toMerge area is fully covered by srcElem area 
							toMerge.tagMask |= srcElem.tagMask;
							addMissingTags(toMerge.tags,srcElem.tags);

							srcElem.area.subtract(toMerge.area);
							++cntSubtract;
							if (srcElem.area.isEmpty()){
								nodes.remove(j);
								--numNodes;
								--cntNodes;
								++cntEmpty; 

							}
						}
						else {
							// the two areas intersect partly, create a new nodeElem with the intersection 
							// and subtact the intersection from the others

							++numNodes;
							++cntNodes;
							NodeElem intersect = new NodeElem();

							intersect.area  = new Area(toMerge.area);
							// create intersection part
							toMerge.area = a1;
							intersect.area.intersect(srcElem.area);
							++cntIntersect;
							intersect.tags = toMerge.tags.copy();
							addMissingTags(intersect.tags, srcElem.tags);
							intersect.tagMask = (short) (toMerge.tagMask | srcElem.tagMask);
							nodes.add(i, intersect);
							srcElem.area.subtract(intersect.area);
							++cntSubtract;

							// maintain loop control variables
							toMerge = intersect;
							neededTags = (short)(availLocTagsMask & ~toMerge.tagMask );
						}
					}
					if (neededTags == 0)
						break;
				}
			}
		}

		private void addMissingTags(Tags dest, Tags src){
			Iterator<Entry<String,String>> tagIter = src.entryIterator();
			while (tagIter.hasNext()) {
				Entry<String,String> tag = tagIter.next();
				if (dest.get(tag.getKey()) == null){
					dest.put(tag.getKey(),tag.getValue());
				}
			}
		}

		/**
		 * Split the tree into 4 equally sized parts.
		 */
		private void split(){
			if  (isLeaf == false)
				return;
			// subject to tuning
			if (depth >= 5 || numNodes <= 7){
				merge();
				return ;
			}
			

			childs = new Node[4];
			cntTreeNode+=4;

			int halfLat = (bounds.getMinLat() + bounds.getMaxLat()) / 2;
			int halfLong = (bounds.getMinLong() + bounds.getMaxLong()) / 2;
			if (bounds.getHeight() < 10 || bounds.getWidth() < 10 ){ 
				// area would be too small 
				return;
			}

			childs[0] = new Node(bounds.getMinLat(), bounds.getMinLong(),
					halfLat, halfLong);
			childs[1] = new Node(halfLat, bounds.getMinLong(),
					bounds.getMaxLat(), halfLong);
			childs[2] = new Node(bounds.getMinLat(), halfLong, halfLat,
					bounds.getMaxLong());
			childs[3] = new Node(halfLat, halfLong, bounds.getMaxLat(),
					bounds.getMaxLong());
			for (int i = 0; i < 4; i++){
				childs[i].depth = (short) (this.depth + 1);
				for (NodeElem nodeElem: nodes){
					childs[i].add(nodeElem.area, nodeElem.tags);
				}
			}
			numNodes = 0;
			isLeaf = false;
			// return memory to GC
			nodes = null;
			// finally try splitting the sub trees
			for (int i = 0; i < 4; i++){
				childs[i].split();
			}
		}
	}
	
	class NodeElem{
		// location relevant tags of boundaries that intersect with the bounding box of this node
		Tags tags;
		// the intersections of the boundaries with the bounding box of this node
		Area area;
		// a bit mask that helps comparing tags
		short tagMask;
	}
}