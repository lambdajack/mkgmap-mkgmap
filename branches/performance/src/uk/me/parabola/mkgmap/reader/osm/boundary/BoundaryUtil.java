/*
 * Copyright (C) 2006, 2011.
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
package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Tags;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.Java2DConverter;

public class BoundaryUtil {
	private static final Logger log = Logger.getLogger(BoundaryUtil.class);

	
	/**
	 * Calculate the polygons that describe the area.
	 * @param area the Area instance
	 * @param id an id that is used to create meaningful messages, typically a boundary Id
	 * @return A list of BoundaryElements (can be empty)
	 */
	public static List<BoundaryElement> splitToElements(Area area, String id) {
		if (area.isEmpty()) {
			return Collections.emptyList();
		}

		Area testArea = area;
		boolean tryAgain = true;
		while (true){
			List<List<Coord>> areaElements = Java2DConverter.areaToShapes(testArea);

			if (areaElements.isEmpty()) {
				// this may happen if a boundary overlaps a raster tile in a very small area
				// so that it is has no dimension
				log.debug("Area has no dimension. Area:",area.getBounds());
				return Collections.emptyList();
			}

			List<BoundaryElement> bElements = new ArrayList<BoundaryElement>();
			for (List<Coord> singleElement : areaElements) {
				if (singleElement.size() <= 3) {
					// need at least 4 items to describe a polygon
					continue;
				}
				Way w = new Way(0, singleElement);
				boolean outer = w.clockwise();
				bElements.add(new BoundaryElement(outer, singleElement));
			}

			if (bElements.isEmpty()) {
				// should not happen because empty polygons should be removed by
				// the Java2DConverter
				log.error("Empty boundary elements list after conversion. Area: "+area.getBounds());
				return Collections.emptyList();
			}


			// reverse the list because it starts with the inner elements first and
			// we need the other way round
			Collections.reverse(bElements);

			if (bElements.get(0).isOuter())
				return bElements;

			// result is not usable if first element is not outer 
			if (tryAgain == false){
				// cannot convert this area
				log.error(" first element is not outer. "+ bElements.get(0));
				
				//createJavaCodeSnippet(area);
				//String fname = "bnd_gpx/first_not_outer" + id ;
				//GpxCreator.createGpx(fname, bElements.get(0).getPoints());
				return Collections.emptyList();
			}
			// try converting the area with rounded float values
			Path2D.Float path = new Path2D.Float(area);
			testArea = new Area(path);
			tryAgain = false;
		}
	}

	/**
	 * Wrapper for {@link #loadQuadTree(String, String, uk.me.parabola.imgfmt.app.Area, EnhancedProperties)}
	 * @param boundaryDirName a directory name or zip file containing the *.bnd file
	 * @param boundaryFileName the *.bnd file name
	 * @return
	 */
	public static BoundaryQuadTree loadQuadTree (String boundaryDirName, 
			String boundaryFileName){ 
		return (loadQuadTree (boundaryDirName, boundaryFileName, null, null));
	}
	
	/**
	 * create a BoundaryQuadTree with the data in the given file. 
	 * The routine opens the file and reads only the header 
	 * to decide what format was used to write it
	 * and calls the appropriate method to read the rest.
	 * @param boundaryDirName a directory name or zip file containing the *.bnd file
	 * @param boundaryFileName the *.bnd file name
	 * @param searchBbox null or a bounding box. Data outside of this box is ignored.
	 * @param props null or the properties to be used for the locator
	 * @return
	 */
	public static BoundaryQuadTree loadQuadTree (String boundaryDirName, 
			String boundaryFileName, 
			uk.me.parabola.imgfmt.app.Area searchBbox, EnhancedProperties props){
		BoundaryQuadTree bqt = null;
		File boundaryDir = new File(boundaryDirName);
		try {
			if (boundaryDir.isDirectory()) {
				// no support for nested directories
				File boundaryFile = new File(boundaryDir, boundaryFileName);
				if (boundaryFile.exists()) {
					InputStream stream = new FileInputStream(boundaryFile);
					bqt = BoundaryUtil.loadQuadTreeFromStream(stream, boundaryFileName, searchBbox, props);
				}
			} else if (boundaryDirName.endsWith(".zip")) {
				// a zip file can contain a directory structure, so we 
				// parse the complete directory until we find a matching entry
				Enumeration<? extends ZipEntry> entries;
				ZipFile zipFile;
				zipFile = new ZipFile(boundaryDirName);
				entries = zipFile.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = (ZipEntry) entries
							.nextElement();
					if (entry.getName().endsWith(boundaryFileName)) {
						bqt = BoundaryUtil.loadQuadTreeFromStream(zipFile.getInputStream(entry), 
								boundaryFileName, searchBbox, props);
						break;
					}
				}
				zipFile.close();
			} else{ 
				log.error("Cannot read " + boundaryDir);
			}
		} catch (IOException exp) {
			log.warn("Cannot load boundary file", boundaryFileName+ ".", exp);
		}
		return bqt;
	}
	
	
	/**
	 * read path iterator info from stream and create Area. 
	 * Data is stored with float precision.  
	 * @param inpStream the already opened DataInputStream 
	 * @return a new Area object or null if not successful 
	 * @throws IOException
	 */
	public static Area readArea(DataInputStream inpStream) throws IOException{
		Path2D.Float path = new Path2D.Float();

		int windingRule = inpStream.readInt();
		path.setWindingRule(windingRule);
		int type = inpStream.readInt(); 
		while (type >= 0) {
			switch (type) {
			case PathIterator.SEG_LINETO:
				int len = inpStream.readInt();
				while(len > 0){
					float x = inpStream.readFloat();
					float y = inpStream.readFloat();
					path.lineTo(x, y);
					--len;
				}
				break;
			case PathIterator.SEG_MOVETO:
				float x = inpStream.readFloat();
				float y = inpStream.readFloat();
				path.moveTo(x, y);
				break;
			case PathIterator.SEG_CLOSE:
				path.closePath();
				break;
			default:
				log.error("Unsupported path iterator type " + type
						+ ". This is an mkgmap error.");
				return null;
			}

			type = inpStream.readInt();
		}
		if (type != -1){
			log.error("Final type value != -1: " + type);
		}
		else{
			return new Area(path);
		}
		return null;
	}
	
	/**
	 * Read boundary info saved in RAW_DATA_FORMAT. 
	 * @param inpStream the already opened DataInputStream
	 * @param fname the related file name of the *.bnd file
	 * @param bbox a bounding box. Data outside of this box is ignored.
	 * @return
	 * @throws IOException
	 */
	private static List<Boundary> readStreamRawFormat(
			DataInputStream inpStream, String fname,
			uk.me.parabola.imgfmt.app.Area bbox) throws IOException			{
		List<Boundary> boundaryList = new ArrayList<Boundary>();


		try {
			// 1st read the mkgmap release the boundary file is created by
			String mkgmapRel = inpStream.readUTF();
			long createTime = inpStream.readLong();
			
			if (log.isDebugEnabled()) {
				log.debug("File created by mkgmap release",mkgmapRel,"at",new Date(createTime));
			}
			
			while (true) {
				int minLat = inpStream.readInt();
				int minLong = inpStream.readInt();
				int maxLat = inpStream.readInt();
				int maxLong = inpStream.readInt();
				log.debug("Next boundary. Lat min:",minLat,"max:",maxLat,"Long min:",minLong,"max:",maxLong);
				uk.me.parabola.imgfmt.app.Area rBbox = new uk.me.parabola.imgfmt.app.Area(
						minLat, minLong, maxLat, maxLong);
				int bSize = inpStream.readInt();
				log.debug("Size:",bSize);

				if (bbox == null || bbox.intersects(rBbox)) {
					log.debug("Bbox intersects. Load the boundary");
					Tags tags = new Tags();
					int noOfTags = inpStream.readInt();
					String id = "?"; 
					for (int i = 0; i < noOfTags; i++) {
						String name = inpStream.readUTF();
						String value = inpStream.readUTF();
						// boundary.id was always saved together with the other tags
						if (name.equals("mkgmap:boundaryid")){  
						    id = value;								
						    continue; 
						}
						if (name.equals("mkgmap:lies_in") == false) // ignore info from older preparer version 
							tags.put(name, value.intern()); 					
					}

					int noBElems = inpStream.readInt();
					assert noBElems > 0;
					
					// the first area is always an outer area and will be assigned to the variable
					Area area = null;
					
					for (int i = 0; i < noBElems; i++) {
						boolean outer = inpStream.readBoolean();
						int noCoords = inpStream.readInt();
						log.debug("No of coords",noCoords);
						List<Coord> points = new ArrayList<Coord>(noCoords);
						for (int c = 0; c < noCoords; c++) {
							int lat = inpStream.readInt();
							int lon = inpStream.readInt();
							points.add(new Coord(lat, lon));
						}

						Area elemArea = Java2DConverter.createArea(points);
						if (outer) {
							if (area == null) {
								area = elemArea;
							} else {
								area.add(elemArea);
							}
						} else {
							if (area == null) {
								log.warn("Boundary: "+tags);
								log.warn("Outer way is tagged incosistently as inner way. Ignoring it.");
								log.warn("Points: "+points);
							} else {
								area.subtract(elemArea);
							}
						}
					}

					if (area != null) {
						Boundary boundary = new Boundary(area, tags,id);
						boundaryList.add(boundary);
					} else {
						log.warn("Boundary "+tags+" does not contain any valid area in file " + fname);
					}

				} else {
					log.debug("Bbox does not intersect. Skip",bSize);
					inpStream.skipBytes(bSize);
				}
			}
		} catch (EOFException exp) {
			// it's always thrown at the end of the file
			// log.error("Got EOF at the end of the file");
		}  		
		return boundaryList;
	}

	/**
	 * For a given bounding box, calculate the list of file names that have to be read 
	 * @param bbox the bounding box
	 * @return a List with the names
	 */
	public static List<String> getRequiredBoundaryFileNames(uk.me.parabola.imgfmt.app.Area bbox) {
		List<String> names = new ArrayList<String>();
		for (int latSplit = BoundaryUtil.getSplitBegin(bbox.getMinLat()); latSplit <= BoundaryUtil
				.getSplitBegin(bbox.getMaxLat()); latSplit += BoundaryUtil.RASTER) {
			for (int lonSplit = BoundaryUtil.getSplitBegin(bbox.getMinLong()); lonSplit <= BoundaryUtil
					.getSplitBegin(bbox.getMaxLong()); lonSplit += BoundaryUtil.RASTER) {
				names.add("bounds_"+ getKey(latSplit, lonSplit) + ".bnd");
			}
		}
		return names;
	}

	/** 
	 * return the available *.bnd files in dirName, either dirName has to a directory or a zip file.   
	 * @param dirName : path to a directory or a zip file containing the *.bnd files
	 * @return
	 */
	public static List<String> getBoundaryDirContent(String dirName) {
		List<String> names = new ArrayList<String>();
		File boundaryDir = new File(dirName);
		if (!boundaryDir.exists())
			log.error("boundary directory/zip does not exist. " + dirName);
		else{		
			if (boundaryDir.isDirectory()){
				String[] allNames = boundaryDir.list();
				for (String name: allNames){
					if (name.endsWith(".bnd"))
						names.add(name);
				}
			}
			else if (boundaryDir.getName().endsWith(".zip")){
				Enumeration<? extends ZipEntry> entries;
				ZipFile zipFile;
				try {
					zipFile = new ZipFile(dirName);
					entries = zipFile.entries();
					while(entries.hasMoreElements()) {
						ZipEntry entry = (ZipEntry)entries.nextElement();
						if (entry.getName().endsWith(".bnd"))
							names.add(entry.getName());
					}
					zipFile.close();
				} catch (IOException ioe) {
					System.err.println("Unhandled exception:");
					ioe.printStackTrace();
				}
			}
		}
		return names;
	}
	
	public static final int RASTER = 50000;

	public static int getSplitBegin(int value) {
		int rem = value % RASTER;
		if (rem == 0) {
			return value;
		} else if (value >= 0) {
			return value - rem;
		} else {
			return value - RASTER - rem;
		}
	}

	public static int getSplitEnd(int value) {
		int rem = value % RASTER;
		if (rem == 0) {
			return value;
		} else if (value >= 0) {
			return value + RASTER - rem;
		} else {
			return value - rem;
		}
	}

	public static String getKey(int lat, int lon) {
		return lat + "_" + lon;
	}
	
	/**
	 * Retrieve the bounding box of the given boundary file.
	 * @param boundaryFile the boundary file
	 * @return the bounding box
	 */
	public static uk.me.parabola.imgfmt.app.Area getBbox(String boundaryFileName) {
		String filename = new String(boundaryFileName);
		// cut off the extension
		filename = filename.substring(0,filename.length()-4);
		String[] fParts = filename.split(Pattern.quote("_"));
		
		int lat = Integer.valueOf(fParts[1]);
		int lon = Integer.valueOf(fParts[2]);
		
		return new uk.me.parabola.imgfmt.app.Area(lat, lon, lat+RASTER, lon+RASTER);
	}

	/**
	 * Create and fill a BoundaryQuadTree. Two formats are supported: RAW_DATA_FORMAT and
	 * QUADTREE_DATA_FORMAT. 
	 * @param stream an already opened InputStream
	 * @param fname the file name of the corresponding *.bnd file
	 * @param searchBbox a bounding box or null. If not null, area info outside of this
	 * bounding box is ignored. 
	 * @param props properties to be used or null 
	 * @return on success it returns a new BoundaryQuadTree, else null 
	 * @throws IOException
	 */
	private static BoundaryQuadTree loadQuadTreeFromStream(InputStream stream, 
			String fname,
			uk.me.parabola.imgfmt.app.Area searchBbox, 
			EnhancedProperties props)throws IOException{
		BoundaryQuadTree bqt = null;
		uk.me.parabola.imgfmt.app.Area qtBbox = BoundaryUtil.getBbox(fname);
		try {
			DataInputStream inpStream = new DataInputStream(
					new BufferedInputStream(stream, 1024 * 1024));

			try {
				// 1st read the mkgmap release the boundary file is created by
				String mkgmapRel = inpStream.readUTF();
				long createTime = inpStream.readLong();

				if (log.isDebugEnabled()) {
					log.debug("File created by mkgmap release",mkgmapRel,"at",new Date(createTime));
				}
				if (mkgmapRel.endsWith(BoundarySaver.QUADTREE_DATA_FORMAT)){
					bqt = new BoundaryQuadTree(inpStream, qtBbox, searchBbox, props);
					
				}
				else{
					List<Boundary> boundaryList = readStreamRawFormat(inpStream, fname, searchBbox);
					if (boundaryList == null || boundaryList.isEmpty())
						return null;
					bqt = new BoundaryQuadTree(qtBbox, boundaryList, props);
				}
			} catch (EOFException exp) {
				// it's always thrown at the end of the file
				//				log.error("Got EOF at the end of the file");
			} 
			inpStream.close();
		} finally {
			if (stream != null)
				stream.close();
		}
		return bqt;
	}
	
	public static class BoundsComparator implements Comparator<Boundary>{

		public int compare(Boundary o1, Boundary o2) {
			if (o1 == o2) {
				return 0;
			}
			String id1 = o1.getId();
			String id2 = o2.getId();
			return id1.compareTo(id2);
		}
	}
	/**
	 * Helper to ease the reporting of errors. Creates a java code snippet 
	 * that can be compiled to have the same area.
	 * @param area the area for which the code should be produced
	 */
	public static void createJavaCodeSnippet(Area area) {
		double[] res = new double[6];
		PathIterator pit = area.getPathIterator(null);
		System.out.println("Path2D.Double path = new Path2D.Double();");
		System.out.println("path.setWindingRule(" + pit.getWindingRule() + ");");
		while (!pit.isDone()) {
			int type = pit.currentSegment(res);
			switch (type) {
			case PathIterator.SEG_LINETO:
				System.out.println("path.lineTo(" + res[0] + "d, " + res[1] + "d);");
				break;
			case PathIterator.SEG_MOVETO: 
				System.out.println("path.moveTo(" + res[0] + "d, " + res[1] + "d);");
				break;
			case PathIterator.SEG_CLOSE:
				System.out.println("path.closePath();");
				break;
			default:
				log.error("Unsupported path iterator type " + type
						+ ". This is an mkgmap error.");
			}

			pit.next();
		}
		System.out.println("Area area = new Area(path);");
		
	}

	/**
	 * Add the path of an area to an existing path. 
	 * @param path
	 * @param area
	 */
	public static void addToPath (Path2D.Float path, Area area){
		PathIterator pit = area.getPathIterator(null);
		float [] res = new float[6];
		path.setWindingRule(pit.getWindingRule());
		while (!pit.isDone()) {
			int type = pit.currentSegment(res);
			switch (type) {
			case PathIterator.SEG_LINETO:
				path.lineTo(res[0],res[1]);
				break;
			case PathIterator.SEG_MOVETO: 
				path.moveTo(res[0],res[1]);
				break;
			case PathIterator.SEG_CLOSE:
				path.closePath();
				break;
			default:
				log.error("Unsupported path iterator type " + type
						+ ". This is an mkgmap error.");
			}

			pit.next();
		}
	}
	
}
