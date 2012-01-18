package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Tags;

/**
 * A simple quadtree implementation that stores the areas formed by boundaries. 
 * @author GerdP
 *
 */
public class BoundaryQuadTree {
	private static final Logger log = Logger.getLogger(BoundaryQuadTree.class);
	private Node root;
	/** An array of tag names used in the LocationHook*/
	private final String [] locationTagNames;
	private final uk.me.parabola.imgfmt.app.Area bbox;
	
	private long cntEmpty;
	private long cntNodes;
	private long cntTreeNode;
	private long cntSimpleContains;
	private long cntComplexContains;
	private long cntSubtract;
	private long cntIntersect;
	private long cntIntersects;
	private long cntAreaEquals;
	private long cntIsRectangular;
	/**
	 * Create a quadtree for a given bounding box and a list of boundaries.
	 * @param bBox	The bounding box for the quadTree, only data within this box is used 
	 * @param boundaries A list of boundaries. For better performance, the list should be sorted so that small areas come first.
	 * @param locationTagNames An array of location tag names that should be returned 
	 */
	public BoundaryQuadTree (uk.me.parabola.imgfmt.app.Area bBox, List<Boundary> boundaries, final String[] locationTagNames ){
		this.locationTagNames = locationTagNames;
		this.bbox = bBox;
		root = new Node(bBox);
		for (int i = 0;i < boundaries.size(); i++){
			Boundary b = boundaries.get(i);
			root.add (b.getArea(), b.getLocTags(), b.getId());
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
		Tags res = root.get(co);
		if (res == null && bbox.contains(co)){
			// we did not find the point, probably it lies on a boundary and
			// the clauses regarding insideness of areas make it "invisible"
			// try again a few other nearby points 
			Coord neighbour1 = new Coord(co.getLatitude()-1, co.getLongitude());
			Coord neighbour2 = new Coord(co.getLatitude()  , co.getLongitude()-1);
			Coord neighbour3 = new Coord(co.getLatitude()+1, co.getLongitude());
			Coord neighbour4 = new Coord(co.getLatitude()  , co.getLongitude()+1);
			res = root.get(neighbour1);
			if (res == null)
				res = root.get(neighbour2);
			if (res == null)
				res = root.get(neighbour3);
			if (res == null)
				res = root.get(neighbour4);
		}
		return res;
	}
	
	public void stats(){
		System.out.println("qt: tree nodes              : " + cntTreeNode);
		System.out.println("qt: Area nodes              : " + cntNodes);
		System.out.println("qt: emptied area nodes      : " + cntEmpty);
		System.out.println("qt: simple contains() calls : " + cntSimpleContains);
		System.out.println("qt: simple intersects calls : " + cntIntersects);
		System.out.println("qt: complex contains() calls: " + cntComplexContains);
		System.out.println("qt: area intersect calls    : " + cntIntersect);
		System.out.println("qt: area subtract calls     : " + cntSubtract);
		System.out.println("qt: area equals   calls     : " + cntAreaEquals);
		System.out.println("qt: area isRectangular calls: " + cntIsRectangular);
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
		 * Create a string with location relevant tags ordered by admin_level
		 * @param elem
		 * @return A new String object
		 */
		private void printNodes(String prefix){
			int n = 0;
			for (NodeElem nodeElem: nodes){
				String res = new String();
				for (int i = locationTagNames.length-1; i >= 0 ; --i){
					String tagVal = nodeElem.tags.get(locationTagNames[i] );
					if (tagVal != null)
						res += i+1 + "=" + tagVal + ";";
				}
				Rectangle2D r = nodeElem.area.getBounds2D();
				res += "  " + r;
				System.out.println(prefix + " " +  n + ":" + nodeElem.boundaryId + " " + calcLocationTagsMask(nodeElem.tags) + " " + res );
				++n;
			}
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
		private void add(Area area, Tags bTags, String id){
			// only add areas that intersect with this part of the tree
			++cntIntersects;
			if (area.intersects(this.bbox) == false)
				return;
			Area a;
			if (area.contains(bbox))
				a = new Area(bboxArea); // quadtree bbox lies entirely in area
			else {
				a = new Area(area);
				// check if area lies entirely in quadtree bbox
				if (bboxArea.contains(area.getBounds()) == false){
					// worst case: area and bbox partly intersect
					a.intersect(this.bboxArea); 
					++cntIntersect;
				}
			}
			if (a.isEmpty() == false){
				if (numNodes == 0){
					nodes = new ArrayList<NodeElem>();
				}
				else {
					++cntIsRectangular;
					if (a.isRectangular()){
						NodeElem lastNode = nodes.get(numNodes-1);
						++cntIsRectangular;
						if (lastNode.area.isRectangular()){
							// two areas are rectangles, it is likely that they are equal to the bounding box
							// In this case we add the tags to the existing area instead of creating a new one
							Rectangle2D rlast = lastNode.area.getBounds2D();
							++cntAreaEquals;
							if (a.equals(lastNode.area)){
								lastNode.tagMask |= calcLocationTagsMask(bTags);
								addMissingTags(lastNode.tags, bTags);
								return;
							}
						}
					}
				}
				NodeElem nodeElem = new NodeElem();
				nodeElem.area = a;
				nodeElem.tags = bTags.copy();
				nodeElem.tagMask = calcLocationTagsMask(nodeElem.tags);
				nodeElem.boundaryId = id;
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
			if (isLeaf == false || numNodes <= 1)
				return;
			int startSize = nodes.size();
				
			/*
			if (startSize > 30){
				System.out.println("merge: =============================");
				printNodes("start");
			}
			*/
			
			long t1 = System.nanoTime();
			
			/*
			NodeElem lastNode = nodes.get(nodes.size()-1);
			++cntIsRectangular;
			if (lastNode.area.isRectangular()){
				++cntAreaEquals;
				if (bboxArea.equals(lastNode.area)){
					// lastNode covers full quadtree bbox, copy the tags to all other nodes
					for (int i = 0; i < nodes.size()-1; i++){
						nodes.get(i).tagMask |= lastNode.tagMask;
						addMissingTags(nodes.get(i).tags,lastNode.tags);
					}
				}
			}
			 */
			
			// detect intersection of areas, merge tag info
			for (int i=0; i < numNodes-1; i++){
				
				if (numNodes == startSize*3){
					log.error("merge seems to loop ");
				}
				
				NodeElem toMerge = nodes.get(i);
				//short neededTags = (short)(availLocTagsMask & ~toMerge.tagMask);

				int jStart = Math.max(i+1, toMerge.srcPos);
				for (int j=jStart; j < numNodes; j++){
					short neededTags = (short)(availLocTagsMask & ~toMerge.tagMask );
					if (neededTags == 0)
						break;
					NodeElem srcElem = nodes.get(j);
					if ((neededTags & srcElem.tagMask) == 0 )
						continue;
					// srcElem might add info to toMerge element
					Rectangle2D r = srcElem.area.getBounds2D();
					++cntIntersects;
					Rectangle2D ri = toMerge.area.getBounds2D().createIntersection(r);
					if (ri.getHeight() < 1.0d && ri.getWidth() <= 1.0d)
						continue;
					// the bounding boxes intersect, so we have to find out if the areas also intersect
					Area a1 = new Area(toMerge.area);
					++cntSubtract;
					a1.subtract(srcElem.area);

					if (a1.isEmpty()){
						// toMerge area is fully covered by srcElem area
						if ((neededTags & srcElem.tagMask) != 0 ){
							toMerge.tagMask |= srcElem.tagMask;
							addMissingTags(toMerge.tags,srcElem.tags);
						}
						++cntSubtract;
						srcElem.area.subtract(toMerge.area);
						continue;
					}

					// if the two areas intersect partly, create a new nodeElem with the intersection 
					// and subtract the intersection from the others
					
					NodeElem intersect = new NodeElem();
					intersect.area  = new Area(toMerge.area);

					// create intersection part
					++cntIntersect;
					intersect.area.intersect(srcElem.area);

					// don't save intersection that is too small
					if (isTooSmallArea(intersect.area))
						continue;

					toMerge.area = a1;
					intersect.tags = toMerge.tags.copy();
					intersect.boundaryId = toMerge.boundaryId;
					addMissingTags(intersect.tags, srcElem.tags);
					intersect.tagMask = (short) (toMerge.tagMask | srcElem.tagMask);
					intersect.srcPos = j;

					++numNodes;
					++cntNodes;
					nodes.add(i+1, intersect);
					++cntSubtract;
					srcElem.area.subtract(intersect.area);
					if (isTooSmallArea(srcElem.area)){
						nodes.remove(j+1);
						--cntNodes;
						--numNodes;
						++cntEmpty;
					}

					

				}
				// free memory for nodes with empty or too small areas
				for (int j = numNodes-1; j > i; --j){
					if (isTooSmallArea(nodes.get(j).area)){
						nodes.remove(j);
						--cntNodes;
						++cntEmpty;
					}
				}
				numNodes = (short) nodes.size();
			}
			// move the largest area to the first position
			long t2 = System.nanoTime()-t1;
			if (t2  > 100000000)
				System.out.println("merge required long time: " + t2/1000000 + " ms");
			//printNodes("end");
		}

		/**
		 * Detect very small area
		 * @param a the area
 		 * @return true if area is very small
		 */
		private boolean isTooSmallArea(Area a){
			if (a.isEmpty())
				return true;
			Rectangle2D r = a.getBounds2D();
			if (r.getHeight() < 1.0d && r.getWidth() < 1.0d)
				return true;
			return false;
			
		}
		

		/**
		 * Add src tags to dest if they are missing in dest
		 * @param dest
		 * @param src
		 */
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
					childs[i].add(nodeElem.area, nodeElem.tags, nodeElem.boundaryId);
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
	
	private class NodeElem{
		// location relevant tags of boundaries that intersect with the bounding box of this node
		Tags tags;
		// the intersections of the boundaries with the bounding box of this node
		Area area;
		// a bit mask that helps comparing tags
		short tagMask;
		// boundary id that was initially used 
		String boundaryId;
		int srcPos;
		
		NodeElem (){
			srcPos = -1;
		}
	}
}