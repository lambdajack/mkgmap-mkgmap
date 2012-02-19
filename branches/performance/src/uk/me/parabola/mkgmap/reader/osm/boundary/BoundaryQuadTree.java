package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Tags;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.GpxCreator;
import uk.me.parabola.util.Java2DConverter;
import uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryLocationPreparer;

/**
 * A quadtree implementation to handle areas formed by boundaries. 
 * @author GerdP
 *
 */
public class BoundaryQuadTree {
	private static final Logger log = Logger.getLogger(BoundaryQuadTree.class);
	private static final boolean DEBUG = false;
	private static final String DEBUG_TREEPATH = "?";
	private static final boolean DO_ALL_TESTS = false;

	// the "normal" tags of the boundaries that are saved in this tree
	private final HashMap<String, Tags> boundaryTags = new LinkedHashMap<String,Tags>();
	// the location relevant info 
	private final HashMap<String, BoundaryLocationInfo> preparedLocationInfo;
	// property controlled preparer
	private final BoundaryLocationPreparer preparer;
	
	private Node root;
	// the bounding box of the tree
	private final uk.me.parabola.imgfmt.app.Area bbox;
	boolean srcWasQuadTree; 		// to be removed

	// performance counters
	private long cntEmptied;
	private long cntNodes;
	private long cntTreeNode;
	private long cntBboxContains;
	private long cntComplexContains;
	private long cntSubtract;
	private long cntIntersect;
	private long cntBboxIntersects;
	private long cntAreaEquals;
	private long cntIsRectangular;
	
	// tags that can be returned in the get method
	public final static String[] mkgmapTagsArray =  {
		"mkgmap:admin_level1",
		"mkgmap:admin_level2",
		"mkgmap:admin_level3",
		"mkgmap:admin_level4",
		"mkgmap:admin_level5",
		"mkgmap:admin_level6",
		"mkgmap:admin_level7",
		"mkgmap:admin_level8",
		"mkgmap:admin_level9",
		"mkgmap:admin_level10",
		"mkgmap:admin_level11",
		"mkgmap:postcode"
	};
	
	/**
	 * Create a quadtree for a given bounding box and a list of boundaries.
	 * @param fileBbox	The bounding box for the quadTree, only data within this box is used 
	 * @param searchBbox	The bounding box for the quadTree, only data within this box is used 
	 * @param props if not null, use it to set location names
	 */
	public BoundaryQuadTree(DataInputStream inpStream,
			uk.me.parabola.imgfmt.app.Area fileBbox,
			uk.me.parabola.imgfmt.app.Area searchBbox, EnhancedProperties props)
			throws IOException {
		preparedLocationInfo = new LinkedHashMap<String, BoundaryLocationInfo> ();
		preparer = new BoundaryLocationPreparer(props);
		srcWasQuadTree = true;
		this.bbox = fileBbox;
		root = new Node(this.bbox);
		
		assert fileBbox != null: "parameter fileBbox must not be null";
		readStreamQuadTreeFormat(inpStream,searchBbox);
	}
	
	
	/**
	 * Create a quadtree for a given bounding box and a list of boundaries.
	 * @param givenBbox	The bounding box for the quadTree, only data within this box is used 
	 * @param boundaries A list of boundaries. For better performance, the list should be sorted so that small areas come first.
	 * @param props if not null, use it to set location names
	 */
	public BoundaryQuadTree (uk.me.parabola.imgfmt.app.Area givenBbox, List<Boundary> boundaries, EnhancedProperties props){
		preparer = new BoundaryLocationPreparer(props);
		srcWasQuadTree = false;
		this.bbox = givenBbox;
		root = new Node(this.bbox);
		List<Boundary>preparedList = preparer.prepareBoundaryList(boundaries);
		preparedLocationInfo = preparer.getPreparedLocationInfo(preparedList);
		if (boundaries == null || boundaries.size() == 0)
			return;
		
		assert givenBbox != null: "parameter givenBbox must not be null";
		for (Boundary b: preparedList){
			boundaryTags.put(b.getId(), b.getTags());
			root.add (b.getArea(), b.getId());
		}
		root.split("_");
	}

	/**
	 * Return location relevant Tags for the point defined by Coord 
	 * @param co the point
	 * @return a reference to the internal Tags or null if the point was not found. 
	 * The returned Tags must not be modified by the caller.   
	 */
	public Tags get(Coord co){
		Tags res = root.get(co, "_");
		if (res == null && bbox.contains(co)){
			// we did not find the point, probably it lies on a boundary and
			// the clauses regarding insideness of areas make it "invisible"
			// try again a few other nearby points 
			Coord neighbour1 = new Coord(co.getLatitude()-1, co.getLongitude());
			Coord neighbour2 = new Coord(co.getLatitude()  , co.getLongitude()-1);
			Coord neighbour3 = new Coord(co.getLatitude()+1, co.getLongitude());
			Coord neighbour4 = new Coord(co.getLatitude()  , co.getLongitude()+1);
			res = root.get(neighbour1, "_");
			if (res == null)
				res = root.get(neighbour2, "_");
			if (res == null)
				res = root.get(neighbour3, "_");
			if (res == null)
				res = root.get(neighbour4, "_");
		}
		return res;
	}

	public void stats(){
		System.out.println("qt: tree nodes              : " + cntTreeNode);
		System.out.println("qt: Area nodes              : " + cntNodes);
		System.out.println("qt: emptied area nodes      : " + cntEmptied);
		System.out.println("qt: bbox contains() calls   : " + cntBboxContains);
		System.out.println("qt: bbox intersects calls   : " + cntBboxIntersects);
		System.out.println("qt: area contains() calls: " + cntComplexContains);
		System.out.println("qt: area intersect calls    : " + cntIntersect);
		System.out.println("qt: area subtract calls     : " + cntSubtract);
		System.out.println("qt: area equals   calls     : " + cntAreaEquals);
		System.out.println("qt: area isRectangular calls: " + cntIsRectangular);
	}

	public Map<String, Tags> getTagsMap() {
		return new LinkedHashMap<String, Tags>(boundaryTags);
	}
	
	public Map<String, List<Area>> getAreas(){
		Map<String, List<Area>> areas = new HashMap<String, List<Area>>();
		root.combine(areas, "_", null);
		return areas;
	}
	
	/**
	 * For BoundaryMerger: Add the data of another tree into this tree. 
	 * @param other the other instance of BoundaryQuadTree
	 */
	public void merge(BoundaryQuadTree other){
		if (bbox.equals(other.bbox) == false){
			log.error("Cannot merge tree with different boundaing box");
			return;
		}
		for (Entry <String, BoundaryLocationInfo> entry : other.preparedLocationInfo.entrySet()){
			if (this.preparedLocationInfo.containsKey(entry.getKey()) == false){
				this.preparedLocationInfo.put(entry.getKey(),entry.getValue());
			}
		}
		// add the others tags
		for (Entry <String, Tags> entry : other.boundaryTags.entrySet()){
			if (this.boundaryTags.containsKey(entry.getKey()) == false){
				this.boundaryTags.put(entry.getKey(),entry.getValue());
			}
		}
		// make sure that the merged LinkedHashMap is sorted as mergeBoundaries needs it
		ArrayList<String> ids = new ArrayList<String>(boundaryTags.keySet());
		Collections.sort(ids, new AdminLevelCollator());
		Collections.reverse(ids);
		HashMap<String,Tags> tmp = new LinkedHashMap<String,Tags>(boundaryTags);
		boundaryTags.clear();
		for (String id: ids){
			boundaryTags.put(id,tmp.get(id));
		}
		root.mergeNodes(other.root, "_");
	}
	
	public Area getCoveredArea (Integer admLevel){
		return root.getCoveredArea(admLevel, "_");
	}
	
	public void save(OutputStream stream)throws IOException{
		// save the tag infos of all boundaries first
		for (Entry<String,Tags> entry : boundaryTags.entrySet()){
			writeBoundaryTags(stream, entry.getValue(), entry.getKey());
		}
		// now write the area info for those boundaries that have positions in the quadtree
		root.save(stream, "_");
	}

	/**
	 * Return boundary names relevant for the point defined by Coord 
	 * @param co the point
	 * @return A string with a boundary Id, optionally followed by pairs of admlevel:boundary Id.
	 * Sample  
	 */
	public String getBoundaryNames(Coord co){
		return root.getBoundaryNames(co);
	}
	
	private void writeBoundaryTags(OutputStream stream, Tags tags, String id) throws IOException{
		DataOutputStream dOutStream = new DataOutputStream(stream);
		dOutStream.writeUTF("TAGS");
		dOutStream.writeUTF(id);
		// write the tags
		int noOfTags = tags.size();
	
		dOutStream.writeInt(noOfTags);
	
		Iterator<Entry<String, String>> tagIter = tags.entryIterator();
		while (tagIter.hasNext()) {
			Entry<String, String> tag = tagIter.next();
			dOutStream.writeUTF(tag.getKey());
			dOutStream.writeUTF(tag.getValue());
			noOfTags--;
		}
	
		assert noOfTags == 0 : "Remaining tags: " + noOfTags + " size: "
				+ tags.size() + " " + tags.toString();
	
		dOutStream.flush();
	}


	private void readStreamQuadTreeFormat(DataInputStream inpStream,
			uk.me.parabola.imgfmt.app.Area bbox) throws IOException{
		boolean isFirstArea = true;
		try {
			while (true) {
				String type = inpStream.readUTF();
				if (type.equals("TAGS")){
					String id = inpStream.readUTF();
					Tags tags = new Tags();
					int noOfTags = inpStream.readInt();
					for (int i = 0; i < noOfTags; i++) {
						String name = inpStream.readUTF();
						String value = inpStream.readUTF();
						tags.put(name, value.intern());
					}
					boundaryTags.put(id, tags);
				}
				else if (type.equals("AREA")){
					if (isFirstArea){
						isFirstArea = false;
						prepare();
					}
					int minLat = inpStream.readInt();
					int minLong = inpStream.readInt();
					int maxLat = inpStream.readInt();
					int maxLong = inpStream.readInt();
					log.debug("Next boundary. Lat min:",minLat,"max:",maxLat,"Long min:",minLong,"max:",maxLong);
					uk.me.parabola.imgfmt.app.Area rBbox = new uk.me.parabola.imgfmt.app.Area(
							minLat, minLong, maxLat, maxLong);
					int bSize = inpStream.readInt();
					log.debug("Size:",bSize);

					if ( bbox == null || bbox.intersects(rBbox)) {
						log.debug("Bbox intersects. Load the boundary");
						String treePath = inpStream.readUTF();
						String id = inpStream.readUTF();
						String refs = inpStream.readUTF();
						Area area = BoundaryUtil.readArea(inpStream);
						if (area.isEmpty() == false){
							root.add(area, refs, id, treePath);
						}
					} else {
						log.debug("Bbox does not intersect. Skip",bSize);
						inpStream.skipBytes(bSize);
					}
				}
				else{
					log.error("unknown type field " + type );
				}
			}
		} catch (EOFException exp) {
			// it's always thrown at the end of the file
			//				log.error("Got EOF at the end of the file");
		}
	}

	
	private void prepare() {
		for (Entry<String, Tags> entry : boundaryTags.entrySet()) {
			BoundaryLocationInfo info = preparer.parseTags(entry.getValue());
			preparedLocationInfo.put(entry.getKey(), info);
		}
	}

	
	
	/*
	private void testRandom(){
		Tags tags;
		for (int i = 0; i< 1000000; i++){
			int lat = bbox.getMinLat() + (int)(Math.random() * BoundaryUtil.RASTER); 
			int lon = bbox.getMinLong() + (int)(Math.random() * BoundaryUtil.RASTER);
			if (i % 100000 == 0)
				System.out.println(i);
			String errMsg = null;
			tags = root.get(new Coord(lat,lon), "_");
			if (tags == null)
				errMsg = "qt returns null ";
			else if (tags.get("mkgmap:admin_level2") == null)
				errMsg = "qt says no level2 ";
			else if (tags.get("mkgmap:admin_level4") == null)
				errMsg = "qt says no level4 ";
			else if (tags.get("mkgmap:admin_level6") == null)
				errMsg = "qt says no level6 ";
			if (errMsg != null){
				log.error(errMsg + lat + " " + lon);
				tags = root.get(new Coord(lat,lon), "_");
				return;
			}
		}
	}
	*/
	
	private boolean isUsableArea (java.awt.geom.Area area, String id){
		if (area == null || area.isEmpty())
			return false;
		return true;
	}

	/**
	 * Parse the special tag that contains info about other referenced boundaries 
	 * Problem: If the tree is created by  BoundaryPreparer, we do not know how to calculate 
	 * the name because we don't know which tag to use for this.   
	 * @param boundary
	 */
	private Tags calcLocTags(String id, String refInfo){
		Tags locTags = new Tags();
		BoundaryLocationInfo bInfo  = preparedLocationInfo.get(id);
		if (bInfo.getZip() != null){
			locTags.put("mkgmap:postcode",bInfo.getZip());
		}
		
		if (bInfo.getAdmLevel() != BoundaryLocationPreparer.UNSET_ADMIN_LEVEL){
			locTags.put(BoundaryQuadTree.mkgmapTagsArray[bInfo.getAdmLevel()-1], bInfo.getName());
		}
		if (refInfo == null || refInfo.isEmpty())
			return locTags;
		// the common format of refInfo is either :
		// mkgmap:lies_in=2:r19884;4:r20039;6:r998818   (obsolete) or 
		// mkgmap:intersects_with=2:r19884;4:r20039;6:r998818
		String[] relBounds = refInfo.split(Pattern.quote(";"));
		for (String relBound : relBounds) {
			String[] relParts = relBound.split(Pattern.quote(":"));
			if (relParts.length != 2) {
				log.error("Wrong mkgmap:intersects_with format. Value: " + refInfo);
				continue;
			}
			BoundaryLocationInfo addInfo = preparedLocationInfo.get(relParts[1]);
			if (addInfo == null) {
				log.warn("Referenced boundary not known: " + relParts[1]);
				continue;
			}
			
			int addAdmLevel = addInfo.getAdmLevel();
			String addAdmName = null;
			if (addAdmLevel != BoundaryLocationPreparer.UNSET_ADMIN_LEVEL){
				addAdmName = addInfo.getName();
			}
			String addZip = addInfo.getZip();
	
			if (addAdmName != null){
				if (locTags.get(BoundaryQuadTree.mkgmapTagsArray[addAdmLevel-1]) == null)
					locTags.put(BoundaryQuadTree.mkgmapTagsArray[addAdmLevel-1], addAdmName);
			}
			if (addZip != null){
				if (locTags.get("mkgmap:postcode") == null)
					locTags.put("mkgmap:postcode", addZip);
			}
		}
		return locTags;
	}

	private class Node {
		private Node [] childs;
		private List<NodeElem> nodes;

		// bounding box of this part of the tree
		private final Rectangle bbox;
		private final Area bboxArea;
		private final uk.me.parabola.imgfmt.app.Area bounds;

		private short depth;
		private boolean isLeaf;

		private void save(OutputStream stream, String treePath )throws IOException{
			if (isLeaf){
				if (nodes != null){
					for (NodeElem nodeElem :nodes){
						if (nodeElem.tagMask == 0 || nodeElem.area.isEmpty())
							continue;
						saveNode(stream, nodeElem, treePath);
					}
				}
			}
			else {
				for (int i = 0; i < 4; i++){
					childs[i].save(stream, treePath + i);
				}
			}
		}

		/**
		 * Return boundary names relevant for the point defined by Coord 
		 * @param co the point
		 * @return A string with a boundary Id, optionally followed by pairs of admlevel:boundary Id.
		 * Sample  
		 */
		private String getBoundaryNames(Coord co) {
			++cntBboxContains;
			if (this.bounds.contains(co) == false)
				return null;
			if (isLeaf){
				if (nodes == null || nodes.size() == 0)
					return null;
				int lon = co.getLongitude();
				int lat = co.getLatitude();
				for (NodeElem nodeElem: nodes){
					if (nodeElem.tagMask > 0){	
						++cntComplexContains;
						if (nodeElem.area.contains(lon,lat)){
							String res = new String (nodeElem.boundaryId);
							if (nodeElem.locationDataSrc != null)
								res += ";" + nodeElem.locationDataSrc;
							return res;
						}
					}
				}
			}
			else {
				for (int i = 0; i < 4; i++){
					String res = childs[i].getBoundaryNames(co);
					if (res != null) 
						return res; 
				}
			}
			return null;
		}


		private void saveNode(OutputStream stream, NodeElem nodeElem, String treePath) throws IOException{
			ByteArrayOutputStream oneItemStream = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(oneItemStream);
			String id = nodeElem.boundaryId;
			dos.writeUTF(treePath.substring(1));
			dos.writeUTF(id);
			if (nodeElem.locationDataSrc == null)
				dos.writeUTF("");
			else 
				dos.writeUTF(nodeElem.locationDataSrc);
			BoundarySaver.writeArea(dos, nodeElem.area);
			dos.close();

			// now start to write into the real stream

			// first write the bounding box so that is possible to skip the
			// complete
			// entry
			uk.me.parabola.imgfmt.app.Area bbox = Java2DConverter.createBbox(nodeElem.area);
			DataOutputStream dOutStream = new DataOutputStream(stream);
			dOutStream.writeUTF("AREA");
			dOutStream.writeInt(bbox.getMinLat());
			dOutStream.writeInt(bbox.getMinLong());
			dOutStream.writeInt(bbox.getMaxLat());
			dOutStream.writeInt(bbox.getMaxLong());

			// write the size of the boundary block so that it is possible to
			// skip it
			byte[] data = oneItemStream.toByteArray();
			assert data.length > 0 : "bSize is not > 0 : " + data.length;
			dOutStream.writeInt(data.length);

			// write the boundary block
			dOutStream.write(data);
			dOutStream.flush();
		}

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
		private Tags get(Coord co, String treePath){
			++cntBboxContains;
			if (this.bounds.contains(co) == false)
				return null;
			if (isLeaf){
				if (nodes == null || nodes.size() == 0)
					return null;
				int lon = co.getLongitude();
				int lat = co.getLatitude();
				for (NodeElem nodeElem: nodes){
					if (nodeElem.tagMask > 0){	
						++cntComplexContains;
						if (nodeElem.area.contains(lon,lat)){
							return nodeElem.locTags;
						}
					}
				}
			}
			else {
				for (int i = 0; i < 4; i++){
					Tags res = childs[i].get(co, treePath+i);
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
			for (int i = 0; i < mkgmapTagsArray.length; i++){
				if (tags.get(mkgmapTagsArray[i] ) != null)
					res |= (1 << i);
			}
			return res;
		}

		
		private void testRandom(String treePath){
			Tags tags;
			for (int i = 0; i< 100000; i++){
				int lat = bbox.y + (int)(Math.random() * bbox.height); 
				int lon = bbox.x + (int)(Math.random() * bbox.width);
				if (i % 10000 == 0)
					System.out.println(treePath + " " + i);
				String errMsg = null;
				tags = get(new Coord(lat,lon), treePath);
				if (tags == null)
					errMsg = "qt returns null ";
				else if (tags.get("mkgmap:admin_level2") == null)
					errMsg = "qt says no level2 ";
				else if (tags.get("mkgmap:admin_level4") == null)
					errMsg = "qt says no level4 ";
				else if (tags.get("mkgmap:admin_level6") == null)
					errMsg = "qt says no level6 ";
				if (errMsg != null){
					log.error(errMsg + treePath + " " + lat + " " + lon);
					for (NodeElem nodeElem: nodes){
						List<Area> alist = Java2DConverter.areaToSingularAreas(nodeElem.area);
						int cntArea = 0;
						for (Area a:alist){
							cntArea++;
							List <Coord> coList = Java2DConverter.singularAreaToPoints(a);
							String fname = ".\\bnd_gpx_notOk\\" +  treePath + "_" + nodeElem.boundaryId + "_" + cntArea;
							GpxCreator.createGpx(fname, coList);
						}
					}			
					return;
				}
			}
		}

		/**
		 * Create a string with location relevant tags ordered by admin_level
		 * @param elem
		 * @return A new String object
		 */
		private void printNodes(String prefix, String treePath){
			int n = 0;
			//boolean ok = true;
			for (NodeElem nodeElem: nodes){
				// TODO: remove debug code
				if (DEBUG){
					if (treePath.equals(DEBUG_TREEPATH)){
						List<Area> alist = Java2DConverter.areaToSingularAreas(nodeElem.area);
						int cntArea = 0;
						for (Area a:alist){
							cntArea++;
							List <Coord> coList = Java2DConverter.singularAreaToPoints(a);
							String fname = ".\\bnd_gpx\\" + prefix + "_" + treePath + "_" + n + "_" + cntArea;
							GpxCreator.createGpx(fname, coList);
						}
					}
				}
				String res = new String();
				for (int i = mkgmapTagsArray.length-1; i >= 0 ; --i){
					String tagVal = nodeElem.locTags.get(mkgmapTagsArray[i] );
					if (tagVal != null){
						res += i+1 + "=" + tagVal + ";";
					}
				}
				System.out.println(prefix + " " +  n + ":" + nodeElem.boundaryId + " " + calcLocationTagsMask(nodeElem.locTags) + " " + res );
				++n;
			}
		}

		/**
		 * Test if all areas in one node are distinct areas
		 * @param treePath Position in the quadtree. Used for GPX.
		 * @return false if any area intersects with another and the 
		 * intersection has a dimension.
		 */
		private boolean testIfDistinct(String treePath){
			boolean ok = true;
			for (int i=0; i< nodes.size()-1; i++){
				for (int j=i+1; j < nodes.size(); j++){
					Area a = new Area (nodes.get(i).area);
					a.intersect(nodes.get(j).area);
					if (a.isEmpty() == false){
						Path2D.Float path = new Path2D.Float(a);
						a = new Area(path);
					}
					if (isUsableArea(a, nodes.get(i).boundaryId)){
						ok = false;
						log.error("boundaries still intersect " + i + " " + j);
					}
				}
			}
			if (DEBUG){
				if (!ok){
					for (NodeElem nodeElem: nodes){
						List<Area> alist = Java2DConverter.areaToSingularAreas(nodeElem.area);
						int cntArea = 0;
						for (Area a:alist){
							cntArea++;
							List <Coord> coList = Java2DConverter.singularAreaToPoints(a);
							String fname = ".\\bnd_gpx_not_distinct\\" +  treePath + "_" + nodeElem.boundaryId + "_" + cntArea;
							GpxCreator.createGpx(fname, coList);
						}
					}				
				}
			}
			return ok;
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
		}

		/**
		 * Add an area and the related tags to the tree. The position in the tree is known.
		 * @param area the part of the boundary area that should be added to the tree.    
		 * @param locTags the location relevant tags from the boundary 
		 * @param boundaryId id of the originating boundary
		 * @param treePath empty string: calculate position, else the first character is used as index of the child
		 */
		private void add(Area area, String refs, String boundaryId, String treePath){
			// only add areas that intersect with this part of the tree
			if (treePath.isEmpty() == false){
				int idx = Integer.valueOf(treePath.substring(0, 1));
				if (childs == null)
					allocChilds();
				childs[idx].add(area, refs, boundaryId, treePath.substring(1));
			}
			else {
				if (nodes == null){
					nodes = new ArrayList<NodeElem>();
				}
				NodeElem nodeElem = new NodeElem(boundaryId);
				if (refs.isEmpty() == false)
					nodeElem.locationDataSrc = refs;
				nodeElem.area = area;
				nodeElem.locTags = calcLocTags(boundaryId, refs);
				nodeElem.tagMask = calcLocationTagsMask(nodeElem.locTags);
				if (!this.bbox.intersects(area.getBounds2D())){
					long x = 4;
				}
				assert this.bbox.intersects(area.getBounds2D()) : "boundary bbox doesn't fit into quadtree "
						+ bbox + " " + area.getBounds2D(); 
				

				nodes.add(nodeElem);
				++cntNodes;
			}
		}

		/**
		 * Add an area and the related tags to the tree. 
		 * @param area the part of the boundary area that should be added to the tree.    
		 * @param locTags the location relevant tags from the boundary 
		 * @param boundaryId id of the originating boundary
		 */
		private void add(Area area, String boundaryId){
			if (!isLeaf){
				for (int i = 0; i < 4; i++){
					childs[i].add(area, boundaryId);
				}
				return;
			}
			// only add areas that intersect with this part of the tree
			++cntBboxIntersects;
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
				if (nodes == null)
					nodes = new ArrayList<NodeElem>();
				NodeElem nodeElem = new NodeElem(boundaryId);
				nodeElem.area = a;
				nodeElem.locTags = calcLocTags(boundaryId, null);
				nodeElem.tagMask = calcLocationTagsMask(nodeElem.locTags);
				nodes.add(nodeElem);
				++cntNodes;
			}
		}

		
		private void mergeNodes(Node other, String treePath){
			if (!this.isLeaf && !other.isLeaf){
				for (int i = 0; i < 4; i++){
					childs[i].mergeNodes(other.childs[i], treePath+i);
				}
			}
			else{
				// (sub) tree is different, rebuild it as combination of 
				// both trees.
				HashMap<String,List<Area>> areas = new HashMap<String, List<Area>>();
				other.combine(areas, treePath,null);
				this.combine(areas, treePath,null);
				isLeaf = true;
				nodes = null;
				childs = null;
				
				for (String id: boundaryTags.keySet()){
					List<Area> aList = areas.get(id);
					if (aList != null){
						for (Area area: aList){
							add(area, id);
						}
					}
				}
				split(treePath);
			}
		}
		
		private Area getCoveredArea(Integer admLevel, String treePath){
			long t1 = System.currentTimeMillis();
			HashMap<String,List<Area>> areas = new HashMap<String, List<Area>>();
			this.combine(areas, treePath, admLevel);
			Path2D.Float path = new Path2D.Float();
			for (Entry <String, List<Area>> entry : areas.entrySet()){
				for (Area area: entry.getValue()){
					BoundaryUtil.addToPath(path,area);
				}
			}
			Area combinedArea = new Area(path);
			return combinedArea;
		}
		
		
		private void combine(Map<String, List<Area>> areas, String treePath, Integer admLevel){
			if (!this.isLeaf ){
				for (int i = 0; i < 4; i++){
					childs[i].combine(areas, treePath+i, admLevel);
				}
				return;
			}
			if (nodes == null || nodes.size() == 0)
				return;
			
			Short testMask = null;
			if (admLevel != null)
				testMask = (short) (1<<(admLevel-1));
			for (NodeElem nodeElem : nodes){
				String id = nodeElem.boundaryId;
				if (testMask != null && (nodeElem.tagMask & testMask) == 0)
					continue;
				List<Area> aList = areas.get(id);
				Area a = new Area(nodeElem.area);
				if (aList == null){
					aList = new ArrayList<Area>(4);
					areas.put(id, aList);
				}
				aList.add(a);
				if (testMask != null)
					continue;
				
				String refInfo = nodeElem.locationDataSrc;
				if (refInfo != null) {
					String[] relBounds = refInfo.split(Pattern.quote(";"));
					for (String relBound : relBounds) {
						String[] relParts = relBound.split(Pattern.quote(":"));
						if (relParts.length != 2) {
							log.error("Wrong format in locationDataSrc. Value: " + refInfo);
							continue;
						}
						id = relParts[1];
						aList = areas.get(id);
						a = new Area(nodeElem.area);
						if (aList == null){
							aList = new ArrayList<Area>(4);
							areas.put(id, aList);
						}
						aList.add(a);
					}
				}
			}
		}
		
		/***
		 * Merge information from the boundaries saved by BoundarySaver.
		 * This method is used when bnd file is in raw format.
		 * For intersections, create new areas with the merged 
		 * location tags, and subtract the parts from the source 
		 * areas.
		 */
		private void mergeBoundaries(String treePath){
			if (isLeaf == false || nodes == null || nodes.size() <= 1)
				return;
			if (DEBUG)
				printNodes("start", treePath);
			long t1 = System.currentTimeMillis();
			
			mergeEqualIds();
			mergeRectangles();

			if (DEBUG)
				printNodes("prep", treePath);

			List<NodeElem> reworked = new ArrayList<NodeElem>();

			// detect intersection of areas, merge tag info
			for (int i=0; i < nodes.size(); i++){
				NodeElem toAdd = nodes.get(i);
				if (DEBUG){
					if (treePath.equals(DEBUG_TREEPATH)){
						int cntNode  = 0;
						for (NodeElem nodeElem: reworked){
							++cntNode;
							List<Area> alist = Java2DConverter.areaToSingularAreas(nodeElem.area);
							int cntArea = 0;
							for (Area a:alist){
								cntArea++;
								List <Coord> coList = Java2DConverter.singularAreaToPoints(a);
								String fname = ".\\bnd_gpx_debug" + treePath + "_"+i+"\\" +  treePath + "_" + nodeElem.boundaryId + "_" + cntNode + "_" + cntArea;
								GpxCreator.createGpx(fname, coList);
							}
						}			
					}
				}
				for (int j=0; j < reworked.size(); j++){
					if (toAdd.mustVerify && isUsableArea(toAdd.area, toAdd.boundaryId) == false)
						break;
					toAdd.mustVerify = false;
					NodeElem currElem = reworked.get(j);
					if (currElem.srcPos == i)
						continue;
					if (currElem.mustVerify && isUsableArea(currElem.area, currElem.boundaryId) == false)
						continue;
					currElem.mustVerify = false;
					// srcElem might add info to toAdd element

					Rectangle2D rCurr = currElem.area.getBounds2D();
					++cntBboxIntersects;

					Rectangle2D rAdd = rCurr.createIntersection(toAdd.area.getBounds2D());
					if (rAdd.isEmpty()){
						//case a) 
						continue; 
					}
					// the bounding boxes intersect, so we have to find out if the areas also intersect
					Area toAddxCurr = new Area(currElem.area);
					++cntIntersect;
					toAddxCurr.intersect(toAdd.area);
					
					if (! isUsableArea(toAddxCurr, currElem.boundaryId)){
						// no usable intersection: nothing to do
						continue;
					}

					// test if toAdd contains usable tag(s)
					String chkMsg = checkTags(toAdd, currElem);
					
					Area toAddMinusCurr = new Area(toAdd.area);
					++cntSubtract;
					toAddMinusCurr.subtract(currElem.area);
					if (! isUsableArea(toAddMinusCurr, toAdd.boundaryId)){
						toAddMinusCurr.reset();
						// toadd is fully covered by curr
						if (toAdd.tagMask == BoundaryLocationPreparer.POSTCODE_ONLY){
							// if we get here, toAdd has only zip code that is already known 
							// in larger or equal area of currElem
							toAdd.area = toAddMinusCurr; // ignore this
							break;
						}
					}

					Area currMinusToAdd = new Area(currElem.area);
					++cntSubtract;
					currMinusToAdd.subtract(toAdd.area);
					if (! isUsableArea(currMinusToAdd, currElem.boundaryId)){
					    // curr is fully covered by toAdd 
						if (DEBUG){
							if (chkMsg != null){
								createGPX(toAdd, "toAdd",treePath);
								createGPX(currElem, "curr",treePath);
							}
						}
						toAdd.area = toAddMinusCurr;
						toAdd.mustVerify = true;
						if (chkMsg != null){
							log.warn(chkMsg);
							continue;
						}

						if (toAdd.tagMask != BoundaryLocationPreparer.POSTCODE_ONLY){
							currElem.addLocationDataString(toAdd);
							addMissingTags(currElem.locTags,toAdd.locTags); 
							currElem.tagMask |= toAdd.tagMask;
						}
						continue;
					}

					NodeElem intersect = new NodeElem(currElem.boundaryId);
					intersect.area  = toAddxCurr;

					if (chkMsg != null){
						// if we get here we have  a partial overlapping of two 
						// boundaries that have the same level
						if (DEBUG){
							createGPX(toAdd, "toAdd", treePath);
							createGPX(currElem, "curr", treePath);
							createGPX(intersect, "inter", treePath);
						}
						log.warn(chkMsg);
						// just remove overlap from toAdd 
						toAdd.area = toAddMinusCurr;
						toAdd.mustVerify = true;
						continue;
					}

					// remove intersection part from the source areas
					currElem.area = currMinusToAdd;
					currElem.mustVerify = true;
					toAdd.area = toAddMinusCurr;
					toAdd.mustVerify = true;
					
					if (toAdd.tagMask != BoundaryLocationPreparer.POSTCODE_ONLY){
						// combine tag info in intersection
						intersect.locTags = currElem.locTags.copy();
						addMissingTags(intersect.locTags, toAdd.locTags);
						intersect.tagMask = (short) (currElem.tagMask | toAdd.tagMask);
						intersect.srcPos = i;
						intersect.mustVerify = false;
						intersect.locationDataSrc = currElem.locationDataSrc;
						intersect.addLocationDataString(toAdd);
						reworked.add(intersect);
					}
				}

				if (toAdd.area.isEmpty() == false)
					reworked.add(toAdd);
			}
			nodes = reworked;
			// free memory for nodes with empty or too small areas
			removeEmptyAreas(treePath);

			long t2 = System.currentTimeMillis()-t1;
			if (t2  > 1000)
				System.out.println("merge required long time: " + t2 + " ms");
			if (DEBUG)
				printNodes("end", treePath);

			//double check ?
			if (DO_ALL_TESTS){
				testIfDistinct(treePath);
				if (treePath.equals(DEBUG_TREEPATH))
					testRandom(treePath);
			}
		}
			
		/**
		 * Combine the areas with equal boundary IDs. 
		 * We can assume that equal IDs are paired when add is 
		 * called with sorted input.
		 */
		private void mergeEqualIds(){
			int start = nodes.size()-1;
			for (int i = start; i > 0; i--){
				if (nodes.get(i).boundaryId == nodes.get(i-1).boundaryId){
					nodes.get(i-1).area.add(nodes.get(i).area);
					nodes.remove(i);
				}
			}
		}
		/**
		 * The last nodes are likely to fully cover the quadtree bbox. 
		 * Merge the tag infos for them. 
		 */
		private void mergeRectangles(){
			boolean done;
			//step1: merge nodes that fully cover the quadtree area
			do{
				done = true;
				if (nodes.size()<= 1)
					break;
				NodeElem lastNode = nodes.get(nodes.size()-1);
				NodeElem prevNode = nodes.get(nodes.size()-2);
				cntIsRectangular += 2;
				// don't merge admin_level tags into zip-code only boundary
				if (prevNode.tagMask != BoundaryLocationPreparer.POSTCODE_ONLY && lastNode.area.isRectangular() && prevNode.area.isRectangular()){
					// two areas are rectangles, it is likely that they are equal to the bounding box
					// In this case we add the tags to the existing area instead of creating a new one
					++cntAreaEquals;
					if (prevNode.area.equals(lastNode.area)){
						prevNode.addLocationDataString(lastNode);
						prevNode.tagMask |= calcLocationTagsMask(lastNode.locTags);
						addMissingTags(prevNode.locTags, lastNode.locTags);
						nodes.remove(nodes.size()-1);
						done = false;
					}
				}
				else
					done = true;
			} while (!done);
		}

		private void createGPX(NodeElem nodeElem, String desc, String treePath){
			if (DEBUG){
				List<Area> alist = Java2DConverter.areaToSingularAreas(nodeElem.area);
				int cntArea = 0;
				for (Area a:alist){
					cntArea++;
					List <Coord> coList = Java2DConverter.singularAreaToPoints(a);
					String fname = ".\\bnd_is\\" +  nodeElem.area.getBounds().x + "_" + nodeElem.area.getBounds().y + "_" + nodeElem.boundaryId+ "_" + cntArea + "_" + desc + "_" + treePath;
					GpxCreator.createGpx(fname, coList);
				}
			}
		}
		/**
		 * Handle errors in OSM data. Two boundaries with equal levels should not intersect.
		 * Special case: zip-code boundaries with same zip code. 
		 * @param newElem 
		 * @param oldElem
		 * @return null if no error, else a String with a error message
		 */
		private String checkTags(NodeElem newElem, NodeElem oldElem){
			String errMsg = null;
			int errAdmLevel = 0;
			// case c) toAdd area is fully covered by currElem area
			for (int k = 0; k < mkgmapTagsArray.length; k++){
				int testMask = 1 << k;
				if ((testMask & newElem.tagMask) != 0 && (oldElem.tagMask & testMask) != 0){
					if (k != mkgmapTagsArray.length-1){
						errAdmLevel = k+1;
						errMsg = new String ("same admin_level (" + errAdmLevel + ")");
						break;
					}
					else {
						String zipKey = mkgmapTagsArray[k];
						if (newElem.locTags.get(zipKey).equals(oldElem.locTags.get(zipKey)) == false){
							errMsg = "different " + zipKey;
							break;
						}
					}
				}
			}
			if (errMsg != null){
				String url = bounds.getCenter().toOSMURL() + "&";
				url += (newElem.boundaryId.startsWith("w")) ? "way" : "relation";
				url += "=" + newElem.boundaryId.substring(1);
				//http://www.openstreetmap.org/?lat=49.394988&lon=6.551425&zoom=18&layers=M&relation=122907
				errMsg= "incorrect data: " + url + " intersection of boundaries with " + errMsg + " " + newElem.boundaryId + " " + oldElem.boundaryId + " " ;
				if (errAdmLevel != 0 && oldElem.locationDataSrc != null)
					errMsg += oldElem.locationDataSrc;
			}
			
			return errMsg;
		}
		private void removeEmptyAreas(String treePath){
			for (int j = nodes.size()-1; j >= 0 ; j--){
				boolean removeThis = false;
				NodeElem chkRemove = nodes.get(j);
				if (chkRemove.tagMask == 0)
					removeThis = true;
				else if (chkRemove.area.isEmpty())
					removeThis = true;
				else if (chkRemove.mustVerify){
					Path2D.Float path = new Path2D.Float(chkRemove.area);
					Area tmpArea = new Area(path);
					if (tmpArea.isEmpty())
						removeThis = true;
				}
				if (removeThis){
					nodes.remove(j);
					--cntNodes;
					++cntEmptied;
				}
			}			
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

		private void allocChilds(){
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
			}
			isLeaf = false;
		}

		/**
		 * Split the tree into 4 equally sized parts.
		 */
		private void split(String treePath){
			if (isLeaf == true){
				if  (nodes == null)
					return;

				// subject to tuning
				if (depth >= 5 || nodes.size() <= 7){
					mergeBoundaries(treePath);
					return ;
				}

				allocChilds();
				for (int i = 0; i < 4; i++){
					for (NodeElem nodeElem: nodes){
						childs[i].add(nodeElem.area, nodeElem.boundaryId);
					}
				}
				// return memory to GC
				nodes = null;
			}
			// finally try splitting the sub trees
			for (int i = 0; i < 4; i++){
				childs[i].split(treePath+i);
			}
		}
	}

	private class NodeElem{
		// the intersections of the boundaries with the bounding box of this node
		Area area;
		// location relevant tags of boundaries that intersect with the bounding box of this node
		Tags locTags;
		// a bit mask that helps comparing tags
		short tagMask;
		// boundary that was initially used 
		String boundaryId;
		// data for the intersects_with tag
		String locationDataSrc;
		int srcPos;
		boolean mustVerify;

		NodeElem (String boundaryId){
			srcPos = -1;
			locationDataSrc = null;
			this.boundaryId = boundaryId;
			this.mustVerify = true;
		}

		private void addLocationDataString (NodeElem toAdd){
			BoundaryLocationInfo info = preparedLocationInfo.get(toAdd.boundaryId);
			assert info.getAdmLevel() > 0 : "cannot use admLevel";
			
			String admLevel = info.getAdmLevel() + ":" + toAdd.boundaryId;
			if (this.locationDataSrc == null)
				this.locationDataSrc =  admLevel;
			else 
				this.locationDataSrc +=  ";" + admLevel;
			if (toAdd.locationDataSrc != null){
				this.locationDataSrc += ";" + toAdd.locationDataSrc;
			}

		}
	}

	public class AdminLevelCollator implements Comparator<String> {

		public int compare(String o1, String o2) {
			if (o1 == o2) {
				return 0;
			}

			BoundaryLocationInfo i1 = preparedLocationInfo.get(o1);
			BoundaryLocationInfo i2 = preparedLocationInfo.get(o2);
			
			int adminLevel1 = i1.getAdmLevel();
			int adminLevel2 = i2.getAdmLevel();

			if (i1.getName() == null || i1.getName() == "?") {
				// admin_level tag is set but no valid name available
				adminLevel1= BoundaryLocationPreparer.UNSET_ADMIN_LEVEL;
			}
			if (i2.getName() == null || i2.getName() == "?") {
				// admin_level tag is set but no valid name available
				adminLevel2= BoundaryLocationPreparer.UNSET_ADMIN_LEVEL;
			}
			
			if (adminLevel1 > adminLevel2)
				return 1;
			if (adminLevel1 < adminLevel2)
				return -1;
			
			if (i1.getAdmLevel() == 2){
				// prefer countries that are known by the Locator
				if (i1.isISOName() == true && i2.isISOName() == false)
					return 1;
				if (i1.isISOName() == false && i2.isISOName() == true)
					return -1;
			}
			boolean post1set = i1.getZip() != null;
			boolean post2set = i2.getZip() != null;
			
			if (post1set) {
				return (post2set ? 0 : 1);
			} else {
				return (post2set ? -1 : 0);
			}
			
		}
	}
}
