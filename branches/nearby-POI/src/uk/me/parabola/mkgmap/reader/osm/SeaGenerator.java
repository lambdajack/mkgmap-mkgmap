/*
 * Copyright (C) 2010-2012.
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

import java.awt.Rectangle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineClipper;
import uk.me.parabola.mkgmap.osmstyle.StyleImpl;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.Java2DConverter;

/**
 * Code to generate sea polygons from the coastline ways.
 *
 * Currently there are a number of different options.
 * Should pick one that works well and make it the default.
 *
 */
public class SeaGenerator implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(SeaGenerator.class);

	private String precompSea; 
	private boolean generateSeaUsingMP = true;
	private int maxCoastlineGap;
	private boolean allowSeaSectors = true;
	private boolean extendSeaSectors;
	private String[] landTag = { "natural", "land" };
	private boolean floodblocker;
	private int fbGap = 40;
	private double fbRatio = 0.5d;
	private int fbThreshold = 20;
	private boolean fbDebug;

	private ElementSaver saver;

	private List<Way> shoreline = new ArrayList<>();
	private	List<Way> islands = new ArrayList<>();
	private	List<Way> antiIslands = new ArrayList<>();
	private Area tileBounds;
	private boolean generateSeaBackground = true;

	private String[] coastlineFilenames;
	private StyleImpl fbRules;
	
	/** The size (lat and long) of the precompiled sea tiles */
	public static final int PRECOMP_RASTER = 1 << 15;
	
	// flags used in the index 
	private static final byte SEA_TILE = 's';
	private static final byte LAND_TILE = 'l';
	private static final byte MIXED_TILE = 'm';
	
	private static ThreadLocal<PrecompData> precompIndex = new ThreadLocal<>();
	
	// useful constants defining the min/max map units of the precompiled sea tiles
	private static final int MIN_LAT = Utils.toMapUnit(-90.0);
	private static final int MAX_LAT = Utils.toMapUnit(90.0);
	private static final int MIN_LON = Utils.toMapUnit(-180.0);
	private static final int MAX_LON = Utils.toMapUnit(180.0);
	private static final Pattern KEY_SPLITTER = Pattern.compile(Pattern.quote("_"));
	private static final Pattern SEMICOLON_SPLITTER = Pattern.compile(Pattern.quote(";"));

	/**
	 * When order-by-decreasing-area we need all bit of sea to be output consistently.
	 * Unless _draworder changes things, having SEA_SIZE as BIG causes polygons beyond the
	 * coastline to be shown. To hide these and have the sea show up to the high-tide
	 * coastline, can set this to be very small instead (or use _draworder).
	 * <p>
	 * mkgmap:drawLevel can be used to override this value in the style - the default style has:
	 * natural=sea { add mkgmap:skipSizeFilter=true; set mkgmap:drawLevel=2 } [0x32 resolution 10]
	 * which is equivalent to Long.MAX_VALUE-2.
	 */
	private static final long SEA_SIZE = Long.MAX_VALUE-2; // sea is BIG

	/**
	 * Sort out options from the command line.
	 * Returns true only if the option to generate the sea is active, so that
	 * the whole thing is omitted if not used.
	 */
	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props) {
		this.saver = saver;
		
		precompSea = props.getProperty("precomp-sea", null);
		if (precompSea != null) {
			initPrecompSeaIndex(precompSea);
		}
		String gs = props.getProperty("generate-sea", null);
		if (gs != null) {
			parseGenerateSeaOption(gs, precompSea != null);
			
			// init floodblocker and coastlinefile loader only 
			// if precompSea is not set
			if (precompSea == null) {
				if (floodblocker) {
					loadFloodblockerStyle();
				}

				String coastlineFileOpt = props.getProperty("coastlinefile", null);
				if (coastlineFileOpt != null) {
					coastlineFilenames = coastlineFileOpt.split(",");
					CoastlineFileLoader.getCoastlineLoader().setCoastlineFiles(coastlineFilenames);
					CoastlineFileLoader.getCoastlineLoader().loadCoastlines();
					log.info("Coastlines loaded");
				} else {
					coastlineFilenames = null;
				}
			}
		}

		return gs != null || precompSea != null;
	}
	
	private void loadFloodblockerStyle() {
		try {
			fbRules = new StyleImpl(null, "floodblocker");
		} catch (FileNotFoundException e) {
			log.error("Cannot load file floodblocker rules. Continue floodblocking disabled.");
			floodblocker = false;
		}
	}

	private void parseGenerateSeaOption(String gs, boolean forPrecompSea) {
		for (String option : gs.split(",")) {
			if ("no-mp".equals(option) || "polygon".equals(option) || "polygons".equals(option))
				generateSeaUsingMP = false;
			else if ("multipolygon".equals(option))
				generateSeaUsingMP = true;
			else if (option.startsWith("land-tag="))
				landTag = option.substring(9).split("=");
			else if (!forPrecompSea) {
				// the other options are only valid if precompiled sea is not used
				if (option.startsWith("close-gaps="))
					maxCoastlineGap = (int) Double.parseDouble(option.substring(11));
				else if ("no-sea-sectors".equals(option))
					allowSeaSectors = false;
				else if ("extend-sea-sectors".equals(option)) {
					allowSeaSectors = false;
					extendSeaSectors = true;
				} else if ("floodblocker".equals(option)) {
					floodblocker = true;
				} else if (option.startsWith("fbgap=")) {
					fbGap = (int) Double.parseDouble(option.substring("fbgap=".length()));
				} else if (option.startsWith("fbratio=")) {
					fbRatio = Double.parseDouble(option.substring("fbratio=".length()));
				} else if (option.startsWith("fbthres=")) {
					fbThreshold = (int) Double.parseDouble(option.substring("fbthres=".length()));
				} else if ("fbdebug".equals(option)) {
					fbDebug = true;
				} else {
					printOptionHelpMsg(forPrecompSea, option);
				}
			} else if (option.isEmpty()) {
				// nothing to do
			} else {
				printOptionHelpMsg(forPrecompSea, option);
			}
		}
	}

	private static void initPrecompSeaIndex(String precompSea) {
		if (precompIndex.get() != null) {
			return;
		}
		/**
		 * The directory of the precompiled sea tiles or <code>null</code> if
		 * precompiled sea should not be used.
		 */
		File precompSeaDir = new File(precompSea);  
		if (!precompSeaDir.exists()) {
			log.error("Directory or zip file with precompiled sea does not exist: " + precompSea);
			return;
		}

		String internalPath = null;    	
		String indexFileName = "index.txt.gz";
		ZipFile zipFile = null;
		PrecompData precompData = null;
		try {
			if (precompSeaDir.isDirectory()) {
				File indexFile = new File(precompSeaDir, indexFileName);
				if (!indexFile.exists()) {
					// check if the unzipped index file exists
					indexFileName = "index.txt";
					indexFile = new File(precompSeaDir, indexFileName);
				}
				if (indexFile.exists()) {
					precompData = readIndexStream(indexFileName, new FileInputStream(indexFile));
				}
			} else if (precompSea.endsWith(".zip")) {
				zipFile = new ZipFile(precompSeaDir); // don't close here! 
				internalPath = "sea/";
				ZipEntry entry = zipFile.getEntry(internalPath + indexFileName);
				if (entry == null) {
					indexFileName = "index.txt";
					entry = zipFile.getEntry(internalPath + indexFileName);
				}
				if (entry == null) {
					internalPath = "";
					indexFileName = "index.txt.gz";
					entry = zipFile.getEntry(internalPath + indexFileName);
				}
				if (entry != null) {
					precompData = readIndexStream(indexFileName, zipFile.getInputStream(entry));
				} else {
					log.error("Don't know how to read " + precompSeaDir);
				}
			} else {
				log.error("Don't know how to read " + precompSeaDir);
			}
			if (precompData != null) {
				precompData.dirFile = precompSeaDir;
				if (zipFile != null) {
					precompData.precompZipFileInternalPath = internalPath;
					precompData.zipFile = zipFile;
				}
				precompIndex.set(precompData);
			}
		} catch (IOException exp) {
			log.error("Cannot read index file", indexFileName, "in", precompSea, exp);
			throw new ExitException("Failed to read required index file in " + precompSeaDir);
		}
	}
	
	private static PrecompData readIndexStream(String indexFileName, InputStream indexStream) throws IOException {
		if (indexFileName.endsWith(".gz")) {
			indexStream = new GZIPInputStream(indexStream);
		}
		PrecompData precompData = loadIndex(indexStream);
		indexStream.close();
		return precompData;
	}
	
	/**
	 * Show valid generate-sea options
	 * @param forPrecompSea  set to true if --precomp-sea is used 
	 * @param option either "help" or an option that was not recognized
	 */
	private static void printOptionHelpMsg(boolean forPrecompSea, String option) {
		if(!"help".equals(option))
			System.err.println("Unknown sea generation option '" + option + "'");
		
		System.err.println("Known sea generation options " + (forPrecompSea ? "with" : "without") + " --precomp-sea  are:");
		System.err.println("  multipolygon        use a multipolygon (default)");
		System.err.println("  polygons | no-mp    use polygons rather than a multipolygon");
		System.err.println("  land-tag=TAG=VAL    tag to use for land polygons (default natural=land)");
		if (forPrecompSea)
			return;
		System.err.println("  no-sea-sectors      disable use of \"sea sectors\"");
		System.err.println("  extend-sea-sectors  extend coastline to reach border");
		System.err.println("  close-gaps=NUM      close gaps in coastline that are less than this distance (metres)");
		System.err.println("  floodblocker        enable the floodblocker (for multipolgon only)");
		System.err.println("  fbgap=NUM           points closer to the coastline are ignored for flood blocking (default 40)");
		System.err.println("  fbthres=NUM         min points contained in a polygon to be flood blocked (default 20)");
		System.err.println("  fbratio=NUM         min ratio (points/area size) for flood blocking (default 0.5)");
	}
	
    /**
     * Read the index from stream and populate the index grid. 
     * @param fileStream already opened stream
     */
    private static PrecompData loadIndex(InputStream fileStream) throws IOException{
		int indexWidth = (getPrecompTileStart(MAX_LON) - getPrecompTileStart(MIN_LON)) / PRECOMP_RASTER;
		int indexHeight = (getPrecompTileStart(MAX_LAT) - getPrecompTileStart(MIN_LAT)) / PRECOMP_RASTER;
		PrecompData pi = null;
		LineNumberReader indexReader = new LineNumberReader(new InputStreamReader(fileStream));
		String indexLine = null;

		byte[][] indexGrid = new byte[indexWidth + 1][indexHeight + 1];
		boolean detectExt = true; 
		String prefix = null;
		String ext = null;

		while ((indexLine = indexReader.readLine()) != null) {
			if (indexLine.startsWith("#")) {
				// comment
				continue;
			}
			String[] items = SEMICOLON_SPLITTER.split(indexLine);
			if (items.length != 2) {
				log.warn("Invalid format in index file name:", indexLine);
				continue;
			}
			String precompKey = items[0];
			byte type = updatePrecompSeaTileIndex(precompKey, items[1], indexGrid);
			if (type == '?') {
				log.warn("Invalid format in index file name:", indexLine);
				continue;
			}
			if (type == MIXED_TILE) {
				// make sure that all file names are using the same name scheme
				int prePos = items[1].indexOf(items[0]);
				if (prePos >= 0) {
					if (detectExt) {
						prefix = items[1].substring(0, prePos);
						ext = items[1].substring(prePos + items[0].length());
						detectExt = false;
					} else {
						String fname = prefix + precompKey + ext;
						if (!items[1].equals(fname)) {
							log.warn("Unexpected file name in index file:", indexLine);
						}
					}
				}
			}

		}
		// 
		pi = new PrecompData();
		pi.precompIndex = indexGrid;
		pi.precompSeaPrefix = prefix;
		pi.precompSeaExt = ext;
		return pi;
    }

	/**
     * Retrieves the start value of the precompiled tile.
     * @param value the value for which the start value is calculated
     * @return the tile start value
     */
    public static int getPrecompTileStart(int value) {
    	int rem = value % PRECOMP_RASTER;
    	if (rem == 0) {
    		return value;
    	} else if (value >= 0) {
    		return value - rem;
    	} else {
    		return value - PRECOMP_RASTER - rem;
    	}
    }

	/**
	 * Retrieves the end value of the precompiled tile.
	 * @param value the value for which the end value is calculated
	 * @return the tile end value
	 */
	public static int getPrecompTileEnd(int value) {
		int rem = value % PRECOMP_RASTER;
		if (rem == 0) {
			return value;
		} else if (value >= 0) {
			return value + PRECOMP_RASTER - rem;
		} else {
			return value - rem;
		}
	}	
	
	@Override
	public Set<String> getUsedTags() {
		HashSet<String> usedTags = new HashSet<>();
		if (coastlineFilenames == null) {
			usedTags.add("natural");
		}
		if (floodblocker) {
			usedTags.addAll(fbRules.getUsedTags());
		}

		if (log.isDebugEnabled())
			log.debug("Sea generator used tags: " + usedTags);

		return usedTags;
	}

	/**
	 * Test to see if the way is part of the shoreline and if it is
	 * we save it.
	 * @param way The way to test.
	 */
	@Override
	public void onAddWay(Way way) {
		String natural = way.getTag("natural");
		if (natural == null)
			return;
	
		// cope with compound tag value
		StringBuilder others = null;
		boolean foundCoastline = false;
		for (String n : SEMICOLON_SPLITTER.split(natural)) {
			if ("coastline".equals(n.trim()))
				foundCoastline = true;
			else if (others == null)
				others = new StringBuilder(n);
			else
				others.append(';').append(n);
		}
		if (!foundCoastline)
			return;
		if (precompSea != null)
			splitCoastLineToLineAndShape(way, natural);
		else if (coastlineFilenames == null) {
			/* RWB ???
			 *
			 * I'd have thought it better to leave the original way, which has been saved,
			 * untouched. The copy doesn't need any tags at this point. Later it might
			 * be made into a polygon and tagged as land or sea.
			 *
			 * Could do a couple of quick check here to save effort later:
			 * 1/ if no part in tile then stop, don't change anything or save.
			 * 2/ if closed(), add to island list instead of shoreline. Any single closed
			 *    way will be a small island, not a sea! Later, after shoreline
			 *    has been merged/clipped etc, check these again for clipping and add clippings
			 *    to shoreline and unclipped back into islands
			 */
			// create copy of way that has only the natural=coastline tag
			Way shore = new Way(way.getOriginalId(), way.getPoints());
			shore.setFakeId();
			shore.addTag("natural", "coastline");
			saver.addWay(shore);
			
			way.deleteTag("natural");
			if (others != null)
				way.addTag("natural", others.toString());
			shoreline.add(way);
		}
	}

	/**
	 * With precompiled sea, we don't want to process all natural=coastline
	 * ways as shapes without additional processing.   
	 * This should avoid duplicate shapes for islands that are also in the 
	 * precompiled data. 
	 * @param way the OSM way with tag key natural 
	 * @param naturalVal the tag value
	 */
	private void splitCoastLineToLineAndShape(Way way, String naturalVal){
		if (precompSea == null)
			return;
		if (way.hasIdenticalEndPoints()){
			// add a copy of this way to be able to draw it as a shape
			Way shapeWay = new Way(way.getOriginalId(), way.getPoints());
			shapeWay.setFakeId();
			shapeWay.copyTags(way);
			// change the tag so that only special rules looking for it are firing
			shapeWay.deleteTag("natural"); 
			shapeWay.addTag("mkgmap:removed_natural",naturalVal); 
			// tag that this way so that it is used as shape only
			shapeWay.addTag(MultiPolygonRelation.STYLE_FILTER_TAG, MultiPolygonRelation.STYLE_FILTER_POLYGON);
			saver.addWay(shapeWay);		
		}
		// make sure that the original (unchanged) way is not processed as a shape
		way.addTag(MultiPolygonRelation.STYLE_FILTER_TAG, MultiPolygonRelation.STYLE_FILTER_LINE);
	}
	
	/**
	 * Loads the precomp sea tile with the given filename.
	 * @param filename the filename of the precomp sea tile
	 * @return all ways of the tile
	 * @throws FileNotFoundException if the tile could not be found
	 */
	private static Collection<Way> loadPrecompTile(InputStream is, String filename) {
		OsmPrecompSeaDataSource src = new OsmPrecompSeaDataSource();
		EnhancedProperties props = new EnhancedProperties();
		props.setProperty("style", "empty"); 
		src.config(props);
		log.info("Started loading coastlines from", filename);
		try{
			src.parse(is, filename);
		} catch (FormatException e) {
			log.error("Failed to read " + filename);
			log.error(e);
		}
		log.info("Finished loading coastlines from", filename);
		return src.getElementSaver().getWays().values();
	}
	
	/**
	 * Calculates the key names of the precompiled sea tiles for the bounding box.
	 * The key names are compiled of {@code lat+"_"+lon}.
	 * @return the key names for the bounding box
	 */
	private List<String> getPrecompKeyNames() {
		Area bounds = saver.getBoundingBox();
		List<String> precompKeys = new ArrayList<>();
		for (int lat = getPrecompTileStart(bounds.getMinLat()); lat < getPrecompTileEnd(bounds
				.getMaxLat()); lat += PRECOMP_RASTER) {
			for (int lon = getPrecompTileStart(bounds.getMinLong()); lon < getPrecompTileEnd(bounds
					.getMaxLong()); lon += PRECOMP_RASTER) {
				precompKeys.add(lat+"_"+lon);
			}
		}
		return precompKeys;
	}
	
	/**
	 * Get the tile name from the index. 
	 * @param precompKey The key name is compiled of {@code lat+"_"+lon}. 
	 * @return either "land" or "sea" or a file name or null
	 */
	private static String getTileName(String precompKey){
		PrecompData pi = precompIndex.get();
		String[] tileCoords = KEY_SPLITTER.split(precompKey);
		int lat = Integer.parseInt(tileCoords[0]); 
		int lon = Integer.parseInt(tileCoords[1]); 
		int latIndex = (MAX_LAT-lat) / PRECOMP_RASTER;
		int lonIndex = (MAX_LON-lon) / PRECOMP_RASTER;
		byte type = pi.precompIndex[lonIndex][latIndex]; 
		switch (type){
		case SEA_TILE: return "sea"; 
		case LAND_TILE: return "land"; 
		case MIXED_TILE: return pi.precompSeaPrefix + precompKey + pi.precompSeaExt; 
		default:  return null;
		}
	}
	

	/**
	 * Update the index grid for the element identified by precompKey. 
	 * @param precompKey The key name is compiled of {@code lat+"_"+lon}. 
	 * @param fileName either "land", "sea", or a file name containing OSM data
	 * @param indexGrid the previously allocated index grid  
	 * @return the byte that was saved in the index grid 
	 */
	private static byte updatePrecompSeaTileIndex (String precompKey, String fileName, byte[][] indexGrid){
		String[] tileCoords = KEY_SPLITTER.split(precompKey);
		byte type = '?';
		if (tileCoords.length == 2){
			int lat = Integer.parseInt(tileCoords[0]); 
			int lon = Integer.parseInt(tileCoords[1]); 
			int latIndex = (MAX_LAT - lat) / PRECOMP_RASTER;
			int lonIndex = (MAX_LON - lon) / PRECOMP_RASTER;

			if ("sea".equals(fileName))
				type = SEA_TILE;
			else if ("land".equals(fileName))
				type = LAND_TILE;
			else 
				type = MIXED_TILE;

			indexGrid[lonIndex][latIndex] = type;
		}
		return type;
	}
	
	/**
	 * Loads the precompiled sea tiles and adds the data to the 
	 * element saver.
	 */
	private void addPrecompSea() {
		log.info("Load precompiled sea tiles");

		// flag if all tiles contains sea or way only
		// this is important for polygon processing
		boolean distinctTilesOnly;
		
		List<Way> landWays = new ArrayList<>();
		List<Way> seaWays = new ArrayList<>();
		
		// get the index with assignment key => sea/land/tilename
		distinctTilesOnly = loadLandAndSee(landWays, seaWays);
 		
		if (generateSeaUsingMP || distinctTilesOnly) {
			// when using multipolygons use the data directly from the precomp files 
			// also with polygons if all tiles are using either sea or land only
			for (Way w : seaWays) {
				w.setFullArea(SEA_SIZE);
				saver.addWay(w);
			}
		} else {
			// using polygons
			// first add the complete bounding box as sea
			saver.addWay(createSeaWay(false));
		}
		
		// check if the land tags need to be changed
		boolean changeLadTag = landTag != null && ("natural".equals(landTag[0]) && !"land".equals(landTag[1]));
		for (Way w : landWays) {
			if (changeLadTag) {
				w.deleteTag("natural");
				w.addTag(landTag[0], landTag[1]);
			}
			saver.addWay(w);
		}
	}

	 
	private boolean loadLandAndSee(List<Way> landWays, List<Way> seaWays) {
		boolean distinctTilesOnly = true;
		List<java.awt.geom.Area> seaOnlyAreas = new ArrayList<>();
		List<java.awt.geom.Area> landOnlyAreas = new ArrayList<>();
		
		PrecompData pd = precompIndex.get();

		for (String precompKey : getPrecompKeyNames()) {
			String tileName = getTileName(precompKey);

			if (tileName == null) {
				log.error("Precompile sea tile " + precompKey + " is missing in the index. Skipping.");
				continue;
			}

			if ("sea".equals(tileName) || "land".equals(tileName)) {
				// the whole precompiled tile is filled with either land or sea
				// => create a rectangle that covers the whole precompiled tile
				String[] tileCoords = KEY_SPLITTER.split(precompKey);
				int minLat = Integer.parseInt(tileCoords[0]);
				int minLon = Integer.parseInt(tileCoords[1]);
				Rectangle r = new Rectangle(minLon, minLat, PRECOMP_RASTER, PRECOMP_RASTER);

				if ("sea".equals(tileName)) {
					seaOnlyAreas = addWithoutCreatingHoles(seaOnlyAreas, new java.awt.geom.Area(r));
				} else {
					landOnlyAreas = addWithoutCreatingHoles(landOnlyAreas, new java.awt.geom.Area(r));
				}
			} else {
				distinctTilesOnly = false;
				loadMixedTile(pd, tileName, landWays, seaWays);
			}
		}
 		landWays.addAll(areaToWays(landOnlyAreas,"land"));
 		seaWays.addAll(areaToWays(seaOnlyAreas,"sea"));
 		return distinctTilesOnly;
	}

	private static void loadMixedTile(PrecompData pd, String tileName, List<Way> landWays, List<Way> seaWays) {
		try {
			InputStream is = null;
			if (pd.zipFile != null) {
				ZipEntry entry = pd.zipFile.getEntry(pd.precompZipFileInternalPath + tileName);
				if (entry != null) {
					is = pd.zipFile.getInputStream(entry);
				} else {
					log.error("Preompiled sea tile " + tileName + " not found.");
				}
			} else {
				File precompTile = new File(pd.dirFile, tileName);
				is = new FileInputStream(precompTile);
			}
			if (is != null) {
				Collection<Way> seaPrecompWays = loadPrecompTile(is, tileName);
				if (log.isDebugEnabled())
					log.debug(seaPrecompWays.size(), "precomp sea ways from", tileName, "loaded.");

				for (Way w : seaPrecompWays) {
					// set a new id to be sure that the precompiled ids do not
					// interfere with the ids of this run
					w.setFakeId();

					if ("land".equals(w.getTag("natural"))) {
						landWays.add(w);
					} else {
						seaWays.add(w);
					}
				}
			}
		} catch (FileNotFoundException exp) {
			log.error("Preompiled sea tile " + tileName + " not found.");
		} catch (Exception exp) {
			log.error(exp);
			exp.printStackTrace();
		}
	}

	/**
	 * Try to merge an area with one or more other areas without creating holes.
	 * If it cannot be merged, it is added to the list.
	 * @param areas known areas 
	 * @param toAdd area to add
	 * @return new list of areas
	 */
	private static List<java.awt.geom.Area> addWithoutCreatingHoles(List<java.awt.geom.Area> areas,
			final java.awt.geom.Area toAdd) {
		List<java.awt.geom.Area> result = new LinkedList<>();
		java.awt.geom.Area toMerge = new java.awt.geom.Area(toAdd);
		
		for (java.awt.geom.Area area : areas) {
			java.awt.geom.Area mergedArea = new java.awt.geom.Area(area);
			mergedArea.add(toMerge);
			if (!mergedArea.isSingular()) {
				result.add(area);
				continue;
			}
			toMerge = mergedArea;
		}
		// create a sorted list with "smallest" area at the beginning
		int dimNew = Math.max(toMerge.getBounds().width, toMerge.getBounds().height);
		boolean added = false;
		for (int i = 0; i < result.size(); i++) {
			java.awt.geom.Area area = result.get(i);
			if (dimNew < Math.max(area.getBounds().width, area.getBounds().height)) {
				result.add(i, toMerge);
				added = true;
				break;
			}
		}
		if (!added)
			result.add(toMerge);
		return result;
	}

	/**
	 * @param area
	 * @param type
	 * @return
	 */
	private static List<Way> areaToWays(List<java.awt.geom.Area> areas, String type) {
		List<Way> ways = new ArrayList<>();
		for (java.awt.geom.Area area : areas) {
			List<List<Coord>> shapes = Java2DConverter.areaToShapes(area);
			for (List<Coord> points : shapes) {
				Way w = new Way(FakeIdGenerator.makeFakeId(), points);
				w.addTag("natural", type);
				ways.add(w);
			}
		}
		return ways;
	}

	/**
	 * Joins the given segments to closed ways as good as possible.
	 * @param segments a list of closed and unclosed ways
	 * @return a list of ways completely joined
	 */
	public static List<Way> joinWays(Collection<Way> segments) {
		ArrayList<Way> joined = new ArrayList<>((int) Math.ceil(segments.size() * 0.5));
		Map<Coord, Way> beginMap = new IdentityHashMap<>();

		for (Way w : segments) {
			if (w.hasIdenticalEndPoints()) {
				joined.add(w);
			} else if (w.getPoints().size() > 1){
				beginMap.put(w.getFirstPoint(), w);
			} else {
				log.info("Discarding coastline way", w.getId(), "because it consists of less than 2 points");
			}
		}
		segments.clear();
		
		boolean merged;
		do {
			merged = false;
			for (Way w1 : beginMap.values()) {
				Way w2 = beginMap.get(w1.getLastPoint());
				if (w2 != null) {
					merge(beginMap, joined, w1, w2);
					merged = true;
					break;
				}
			}
		} while (merged);
		
		log.info(joined.size(), "closed ways.", beginMap.size(), "unclosed ways.");
		joined.addAll(beginMap.values());
		return joined;
	}

	// merge the ways and maintain maps and list
	private static void merge(Map<Coord, Way> beginMap, List<Way> joined, Way w1, Way w2) {
		log.info("merging: ", beginMap.size(), w1.getId(), w2.getId());
		Way wm;
		if (FakeIdGenerator.isFakeId(w1.getId())) {
			wm = w1;
		} else {
			wm = new Way(w1.getOriginalId(), w1.getPoints());
			wm.setFakeId();
			beginMap.put(wm.getFirstPoint(), wm);
		}
		beginMap.remove(w2.getFirstPoint());
		wm.getPoints().addAll(w2.getPoints().subList(1, w2.getPoints().size()));
		
		if (wm.hasIdenticalEndPoints()) {
			joined.add(wm);
			beginMap.remove(wm.getFirstPoint());
		}
	}

	/**
	 * All done, process the saved shoreline information and construct the polygons.
	 */
	@Override
	public void end() {
		tileBounds = saver.getBoundingBox();
		// precompiled sea has highest priority
		// if it is set do not perform any other algorithm
		if (precompSea != null && precompIndex.get() != null) {
			addPrecompSea();
			return;
		}

		if (coastlineFilenames == null) {
			log.info("Shorelines before join", shoreline.size());
			shoreline = joinWays(shoreline);
		} else {
			shoreline.addAll(CoastlineFileLoader.getCoastlineLoader().getCoastlines(tileBounds));
			log.info("Shorelines from extra file:", shoreline.size());
		}

		if (log.isInfoEnabled()) {
			long closed = shoreline.stream().filter(Way::hasIdenticalEndPoints).count();
			log.info("Closed shorelines", closed);
			log.info("Unclosed shorelines", shoreline.size() - closed);
		}
		
		// clip all shoreline segments
		clipShorlineSegments();

		if(shoreline.isEmpty()) {
			// No sea required
			// Even though there is no sea, generate a land
			// polygon so that the tile's background colour will
			// match the land colour on the tiles that do contain
			// some sea
			// No matter if the multipolygon option is used it is
			// only necessary to create a land polygon
			saver.addWay(createLandWay());
			// nothing more to do
			return;
		}

		Relation seaRelation = null;
		
		// handle islands (closed shoreline components) first (they're easy)
		handleIslands();

		if (islands.isEmpty()) {
			// the tile doesn't contain any islands so we can assume
			// that it's showing a land mass that contains some, possibly
			// enclosed, sea areas - in which case, we don't want a sea
			// coloured background
			generateSeaBackground = false;
		}

		// the remaining shoreline segments should intersect the boundary
		// find the intersection points and store them in a SortedMap
		NavigableMap<Double, Way> hitMap = findIntesectionPoints();
		verifyHits(hitMap);

		if (generateSeaBackground) {
			// the background is sea so all anti-islands should be
			// contained by land otherwise they won't be visible
			if (generateSeaUsingMP) {
				long multiId = FakeIdGenerator.makeFakeId();
				log.debug("Generate seabounds relation", multiId);
				seaRelation = new GeneralRelation(multiId);
				seaRelation.addTag("type", "multipolygon");
				seaRelation.addTag("natural", "sea");
			}

			createLandPolygons(hitMap);
			processIslands(seaRelation);
			processAntiIslands(true);

			Way sea = createSeaWay(true);

			log.info("sea: ", sea);
			saver.addWay(sea);
			if(seaRelation != null)
				seaRelation.addElement("outer", sea);
		} else {
			// background is land
			createSeaPolygons(hitMap);
			processAntiIslands(false);
			
			// generate a land polygon so that the tile's
			// background colour will match the land colour on the
			// tiles that do contain some sea
			Way land = createLandWay();
			saver.addWay(land);
			log.info("land:", land);
		}

		if (seaRelation != null) {
			SeaPolygonRelation coastRel = saver.createSeaPolyRelation(seaRelation);
			coastRel.setFloodBlocker(floodblocker);
			if (floodblocker) {
				coastRel.setFloodBlockerGap(fbGap);
				coastRel.setFloodBlockerRatio(fbRatio);
				coastRel.setFloodBlockerThreshold(fbThreshold);
				coastRel.setFloodBlockerRules(fbRules.getWayRules());
				coastRel.setLandTag(landTag[0], landTag[1]);
				coastRel.setDebug(fbDebug);
			}
			saver.addRelation(coastRel);
		}
		
		shoreline = null;
		islands = null;
		antiIslands = null;
	}

	/**
	 * These are bit of land that have been generated as polygons
	 * @param seaRelation if set, add as inner
	 */
	private void processIslands(Relation seaRelation) {
		for (Way w : islands) {
			if (seaRelation != null) {
				// create a "inner" way for each island
				seaRelation.addElement("inner", w);
			}
		}
	}

	/**
	 * These are bits of sea have been generated as polygons.
	 * if the tile is also sea based, then check that surrounded by an island
	 * @param seaRelation if set, add as inner
	 * @param seaBased true if the tile is also sea with land [multi-]polygons
	 */
	private void processAntiIslands(boolean seaBased) {
		for (Way ai : antiIslands) {
			if (seaBased) {
				boolean containedByLand = false;
				for (Way i : islands) {
					if (i.containsPointsOf(ai)) {
						containedByLand = true;
						break;
					}
				}
				if (!containedByLand) {
					// found an anti-island that is not contained by land
					log.warn("inner sea", ai , "is surrounded by water");
				}
			}
		}
	}

	private Way createLandWay() {
		long landId = FakeIdGenerator.makeFakeId();
		Way land = new Way(landId, tileBounds.toCoords());
		land.addTag(landTag[0], landTag[1]);
		return land;
	}

	/**
	 * Create a sea polygon from the given tile bounds
	 * @param enlarge if true, make sure that the polygon is slightly larger than the tile bounds
	 * @return the created way
	 */
	private Way createSeaWay(boolean enlarge) {
		log.info("generating sea, seaBounds=", tileBounds);
		Area bbox = tileBounds;
		long seaId = FakeIdGenerator.makeFakeId();
		if (enlarge) {
			// the sea background area must be a little bigger than all
			// inner land areas. this is a workaround for a multipolygon shortcoming:
			// mp is not able to combine outer and inner if they intersect
			// or have overlaying lines
			// the added area will be clipped later
			bbox = new Area(bbox.getMinLat() - 1, bbox.getMinLong() - 1, bbox.getMaxLat() + 1, bbox.getMaxLong() + 1);
		}
		Way sea = new Way(seaId, bbox.toCoords());
		sea.reverse(); // make clockwise for consistency
		sea.addTag("natural", "sea");
		sea.setFullArea(SEA_SIZE);
		return sea;
	}

	/**
	 * Clip the shoreline ways to the bounding box of the map.
	 * @param shoreline All the the ways making up the coast.
	 * @param bounds The map bounds.
	 */
	private void clipShorlineSegments() {
		List<Way> toBeRemoved = new ArrayList<>();
		List<Way> toBeAdded = new ArrayList<>();
		for (Way segment : shoreline) {
			List<Coord> points = segment.getPoints();
			List<List<Coord>> clipped = LineClipper.clip(tileBounds, points);
			if (clipped != null) {
				log.info("clipping", segment);
				toBeRemoved.add(segment);
				for (List<Coord> pts : clipped) {
					Way shore = new Way(segment.getOriginalId(), pts);
					shore.setFakeId();
					toBeAdded.add(shore);
				}
			}
		}

		log.info("clipping: adding", toBeAdded.size(), ", removing", toBeRemoved.size());
		shoreline.removeAll(toBeRemoved);
		shoreline.addAll(toBeAdded);
	}

	/**
	 * Pick out the closed ways and save them for later. They are removed from the
	 * shore line list and added to the [anti]island list.
	 */
	private void handleIslands() {
		Iterator<Way> it = shoreline.iterator();
		while (it.hasNext()) {
			Way w = it.next();
			if (w.hasIdenticalEndPoints()) {
				addClosedShore(w);
				it.remove();
			}
		}

		closeGaps();
		// there may be more islands now
		it = shoreline.iterator();
		while (it.hasNext()) {
			Way w = it.next();
			if (w.hasIdenticalEndPoints()) {
				log.debug("closed after concatenating", w);
				addClosedShore(w);
				it.remove();
			}
		}
	}

	private void closeGaps() {
		if (maxCoastlineGap <= 0)
			return;
	
		// join up coastline segments whose end points are less than 
		// maxCoastlineGap metres apart
		boolean changed;
		do {
			changed = false;
			Iterator<Way> iter = shoreline.iterator();
			while (!changed && iter.hasNext()) {
				Way w1 = iter.next();
				if (w1.hasIdenticalEndPoints())
					continue;
				Coord w1e = w1.getLastPoint();
				if (!tileBounds.onBoundary(w1e)) {
					Way closed = tryCloseGap(w1);
					if (closed != null) {
						saver.addWay(closed);
						changed = true;
					}
				}
			}
		} while (changed);
	}
	
	private Way tryCloseGap(Way w1) {
		Coord w1e = w1.getLastPoint();
		Way nearest = null;
		double smallestGap = Double.MAX_VALUE;
		for (Way w2 : shoreline) {
			if (w1 == w2 || w2.hasIdenticalEndPoints())
				continue;
			Coord w2s = w2.getFirstPoint();
			if (!tileBounds.onBoundary(w2s)) {
				double gap = w1e.distance(w2s);
				if (gap < smallestGap) {
					nearest = w2;
					smallestGap = gap;
				}
			}
		}
		if (nearest != null && smallestGap < maxCoastlineGap) {
			Coord w2s = nearest.getFirstPoint();
			log.warn("Bridging " + (int) smallestGap + "m gap in coastline from " + w1e.toOSMURL() + " to "
					+ w2s.toOSMURL());
			Way wm;
			if (FakeIdGenerator.isFakeId(w1.getId())) {
				wm = w1;
			} else {
				wm = new Way(w1.getOriginalId());
				wm.setFakeId();
				shoreline.remove(w1);
				shoreline.add(wm);
				wm.getPoints().addAll(w1.getPoints());
				wm.copyTags(w1);
			}
			wm.getPoints().addAll(nearest.getPoints());
			shoreline.remove(nearest);
			// make a line that shows the filled gap
			Way w = new Way(FakeIdGenerator.makeFakeId());
			w.addTag("natural", "mkgmap:coastline-gap");
			w.addPoint(w1e);
			w.addPoint(w2s);
			return w;
		}
		return null;
	}

	private void addClosedShore(Way w) {
		if (Way.clockwise(w.getPoints()))
			addAsSea(w);
		else
		    	addAsLand(w);
	}

	private void addAsSea(Way w) {
		w.addTag("natural", "sea");
		log.info("adding anti-island", w);
		antiIslands.add(w);
		w.setFullArea(SEA_SIZE);
		saver.addWay(w);
	}

	private void addAsLand(Way w) {
		w.addTag(landTag[0], landTag[1]);
		log.info("adding island", w);
		islands.add(w);
		saver.addWay(w);
	}

	/**
	 * Add lines to ways that touch or cross the sea bounds so that the way is closed along the edges of the bounds. 
	 * Adds complete edges or parts of them. This is done counter-clockwise.   
	 * @param hitMap A map of the 'hits' where the shore line intersects the boundary.  
	 */
	private void createLandPolygons(NavigableMap<Double, Way> hitMap) {
		NavigableSet<Double> hits = hitMap.navigableKeySet();
		while (!hits.isEmpty()) {
			Way w = new Way(FakeIdGenerator.makeFakeId());
			Double hFirst = hits.first();
			Double hStart = hFirst, hEnd;
			boolean finished = false;
			do {
				Way segment = hitMap.get(hStart);
				log.info("current hit:", hStart, "adding:", segment);
				segment.getPoints().forEach(w::addPointIfNotEqualToLastPoint);
				hits.remove(hStart);
				hEnd = getEdgeHit(tileBounds, segment.getLastPoint());
				if (hEnd < hStart) // gone all the way around
					finished = true;
				else { // if another, join it on
					hStart = hits.higher(hEnd);
					if (hStart == null) {
						hFirst += 4;
						finished = true;
					}
				}
				if (finished)
					hStart = hFirst;
				addCorners(w, hEnd, hStart);
			} while (!finished);
			w.addPoint(w.getFirstPoint()); // close shape
			log.info("adding landPoly, hits.size()", hits.size());
			addAsLand(w);
		}
	}

	/**
	 * Add lines to ways that touch or cross the sea bounds so that the way is closed along the edges of the bounds.
	 * Adds complete edges or parts of them. This is done clockwise.
	 * This is much the same as createLandPolygons, but in reverse.
	 * @param hitMap A map of the 'hits' where the shore line intersects the boundary.
	 */
	private void createSeaPolygons(NavigableMap<Double, Way> hitMap) {
		NavigableSet<Double> hits = hitMap.navigableKeySet();
		while (!hits.isEmpty()) {
			Way w = new Way(FakeIdGenerator.makeFakeId());
			Double hFirst = hits.last();
			Double hStart = hFirst, hEnd;
			boolean finished = false;
			do {
				Way segment = hitMap.get(hStart);
				log.info("current hit:", hStart, "adding:", segment);
				segment.getPoints().forEach(w::addPointIfNotEqualToLastPoint);
				hits.remove(hStart);
				hEnd = getEdgeHit(tileBounds, segment.getLastPoint());
				if (hEnd > hStart) // gone all the way around
					finished = true;
				else { // if another, join it on
					hStart = hits.lower(hEnd);
					if (hStart == null) {
						hEnd += 4;
						finished = true;
					}
				}
				if (finished)
					hStart = hFirst;
				addCorners(w, hEnd, hStart);
			} while (!finished);
			w.addPoint(w.getFirstPoint()); // close shape
			log.info("adding seaPoly, hits.size()", hits.size());
			addAsSea(w);
		}
	}

	/**
	 * Append corner points to the way if necessary, to give lines along the edges of the bounds
	 * It is possible that the line needs to go all the way around the tile!
         * The relationship between hFrom and hTo determines the direction
	 * @param w the way
	 * @param hFrom going from this edgeHit (0 >= hit < 8)
	 * @param hTo to this edgeHit (ditto)
	 */
	private void addCorners(Way w, double hFrom, double hTo) {
		int startEdge = (int)hFrom;
		int endEdge = (int)hTo;
		int direction, toCorner;
		if (hFrom < hTo) { // increasing, anti-clockwise, land
			direction = +1;
			toCorner = 1;
		} else { // decreasing, clockwise, sea
			direction = -1;
			toCorner = 0; // (int)hFrom does the -1
		}
		log.debug("addCorners", hFrom, hTo, direction, startEdge, endEdge, toCorner);
		while (startEdge != endEdge) {
			Coord p = getPoint(tileBounds, startEdge + toCorner);
			w.addPointIfNotEqualToLastPoint(p);
			startEdge += direction;
		}
	}

	/**
	 * Find the points where the remaining shore line segments intersect with the
	 * map boundary.
	 * @return A map of the 'hits' where the shore line intersects the boundary.
	 */
	private NavigableMap<Double, Way> findIntesectionPoints() {
		NavigableMap<Double, Way> hitMap = new TreeMap<>();
		for (Way w : shoreline) {
			Coord pStart = w.getFirstPoint();
			Coord pEnd = w.getLastPoint();

			Double hStart = getEdgeHit(tileBounds, pStart);
			Double hEnd = getEdgeHit(tileBounds, pEnd);
			if (hStart != null && hEnd != null) {
				// nice case: both ends touch the boundary 
				log.debug("hits: ", hStart, hEnd);
				hitMap.put(hStart, w);
				hitMap.put(hEnd, null); // put this for verifyHits which then deletes it
			} else {
 				/*
				 * This problem occurs usually when the shoreline is cut by osmosis (e.g. country-extracts from geofabrik)
				 * and so a tile, covering land outside the selected area, has bits of unclosed shoreline that
				 * don't start and finish outside the tile.
				 * There are various possibilities to show a reasonable map, but there is no full solution.
				 * Mkmap offers various options:
				 * 1. Use --precomp-sea=... This has all the coastline and the following is N/A.
				 * 2. Close short gaps in the coastline; eg --generate-sea=...,close-gaps=500
				 *    Harbour mouths are often fixed by this.
				 * 3. Create a "sea sector" for this shoreline segment. This is a right-angle triangle where the
				 *    the hypotenuse is the shoreline. "sea sector" is a slight mis-nomer because, if the
				 *    tile is sea-based, a "land sector" is created. Often this will show the coast in
				 *    a meaningful way, but it can create a self-intersecting polygons and, if other bits of
				 *    shoreline that reach the edge of the tile cause this area to be the same type, it won't show
				 * 4. Extend the ends of the shoreline to the nearest edge of the tile with ...,extend-sea-sectors
				 *    This, in conjunction with close-gaps, normally works well but it isn't foolproof.
				 */
				List<Coord> points = w.getPoints();
				if (allowSeaSectors) {
					Way seaOrLand = new Way(FakeIdGenerator.makeFakeId());
					seaOrLand.getPoints().addAll(points);
					int startLat = pStart.getHighPrecLat();
					int startLon = pStart.getHighPrecLon();
					int endLat = pEnd.getHighPrecLat();
					int endLon = pEnd.getHighPrecLon();
					boolean startLatIsCorner = (startLat > endLat) == (startLon > endLon);
					int cornerLat, cornerLon;
					if (generateSeaBackground) { // the tile is sea, with islands
						startLatIsCorner = !startLatIsCorner;
						addAsLand(seaOrLand);
					} else { // the tile is land, maybe with sea polygons on edge
						addAsSea(seaOrLand);
					}
					if (startLatIsCorner) {
						cornerLat = startLat;
						cornerLon = endLon;
					} else {
						cornerLat = endLat;
						cornerLon = startLon;
					}
					seaOrLand.addPoint(Coord.makeHighPrecCoord(cornerLat, cornerLon));
					seaOrLand.addPoint(pStart);
					log.info("seaSector: ", generateSeaBackground, startLatIsCorner, Way.clockwise(seaOrLand.getPoints()), seaOrLand);
				} else if (extendSeaSectors) {
					// join to nearest tile border
					if (null == hStart) {
						hStart = getNextEdgeHit(tileBounds, pStart);
						w.getPoints().add(0, getPoint(tileBounds, hStart));
					}
					if (null == hEnd) {
						hEnd = getNextEdgeHit(tileBounds, pEnd);
						w.getPoints().add(getPoint(tileBounds, hEnd));
					}
					log.debug("hits (second try): ", hStart, hEnd);
					hitMap.put(hStart, w);
					hitMap.put(hEnd, null); // put this for verifyHits which then deletes it
				} else {
					// show the coastline even though we can't produce
					// a polygon for the land
					w.addTag("natural", "coastline");
					log.error("adding sea shape that is not really closed");
					saver.addWay(w);
				}
			}
		}
		return hitMap;
	}

	/*
	 * Check the hitHap has alternating start & end of ways - adjacent coastlines on the tile
	 * boundary must be in opposite directions. There may be other errors, for instance crossing (twice)
	 * due to extendSeaSectors when there is another bit of coastline in the gap, that this doesn't detect.
	 * After checking, the end hit is removed
	 */
	private void verifyHits(NavigableMap<Double, Way> hitMap) {
		log.debug("Islands", islands.size(), "Seas", antiIslands.size(), "hits", hitMap.size());
		NavigableSet<Double> hits = hitMap.navigableKeySet();
		Iterator<Double> iter = hits.iterator();
		int lastStatus = 0, thisStatus;
		while (iter.hasNext()) {
			Double aHit = iter.next();
			Way segment = hitMap.get(aHit);
			log.debug("hitmap", aHit, segment);
			if (segment == null) {
				thisStatus = -1;
				iter.remove();
			} else {
				thisStatus = +1;
			}
			if (thisStatus == lastStatus)
				log.error("Adjacent coastlines hit tile edge in same direction", aHit, segment);
			lastStatus = thisStatus;
		}
	}

	// create the point where the shoreline hits the sea bounds
	private static Coord getPoint(Area a, double edgePos) {
		log.info("getPoint: ", a, edgePos);
		int aMinLongHP = a.getMinLong() << Coord.DELTA_SHIFT;
		int aMaxLongHP = a.getMaxLong() << Coord.DELTA_SHIFT;
		int aMinLatHP = a.getMinLat() << Coord.DELTA_SHIFT;
		int aMaxLatHP = a.getMaxLat() << Coord.DELTA_SHIFT;
		int platHp;
		int plonHp;
		int edge = (int) edgePos;
		double t = edgePos - edge;
		if (edge >= 4)
			edge -= 4;
		switch (edge) {
		case 0: // southern
			platHp = aMinLatHP;
			plonHp = (int) Math.round(aMinLongHP + t * (aMaxLongHP - aMinLongHP));
			break;
		case 1: // eastern
			platHp = (int) Math.round(aMinLatHP + t * (aMaxLatHP - aMinLatHP));
			plonHp = aMaxLongHP;
			break;
		case 2: // northern
			platHp = aMaxLatHP;
			plonHp = (int) Math.round(aMaxLongHP - t * (aMaxLongHP - aMinLongHP));
			break;
		case 3: // western
			platHp = (int) Math.round(aMaxLatHP - t * (aMaxLatHP - aMinLatHP));
			plonHp = aMinLongHP;
			break;
		default:
			throw new MapFailedException("GetPoint edge: " + edgePos);
		}
		return Coord.makeHighPrecCoord(platHp, plonHp);
	}

	/**
	 * Calculate a Double that represents the position where the given point touches
	 * the boundary.
	 * 
	 * @param bounds the boundary
	 * @param p the point
	 * @return null if the point is not touching the boundary, else a value
	 *         between 0.0 (inclusive) and 4.0 (exclusive), where 0 means the lower
	 *         left corner, 0.5 means the middle of the bottom edge, 1.5 the
	 *         middle of the right edge, 4 would be the lower left corner again
	 */
	private static Double getEdgeHit(Area bounds, Coord p) {
		Double hit = getEdgeHit(bounds, p, 10); // 10 points in garmin units
		if (hit != null && hit >= 4)
			hit = 0.0;
		return hit;
	}

	private static Double getEdgeHit(Area a, Coord p, int tolerance24) {
		final int toleranceHp = tolerance24 << Coord.DELTA_SHIFT; 
		final int latHp = p.getHighPrecLat();
		final int lonHp = p.getHighPrecLon();
		final int minLatHp = a.getMinLat() << Coord.DELTA_SHIFT;
		final int maxLatHp = a.getMaxLat() << Coord.DELTA_SHIFT;
		final int minLongHp = a.getMinLong() << Coord.DELTA_SHIFT;
		final int maxLongHp = a.getMaxLong() << Coord.DELTA_SHIFT;

		log.info(String.format("getEdgeHit: (%d %d) (%d %d %d %d)", latHp, lonHp, minLatHp, minLongHp, maxLatHp, maxLongHp));
		if (latHp <= minLatHp + toleranceHp) {
			return (double) (lonHp - minLongHp) / (maxLongHp - minLongHp);
		} else if (lonHp >= maxLongHp - toleranceHp) {
			return 1 + ((double) (latHp - minLatHp) / (maxLatHp - minLatHp));
		} else if (latHp >= maxLatHp - toleranceHp) {
			return 2 + ((double) (maxLongHp - lonHp) / (maxLongHp - minLongHp));
		} else if (lonHp <= minLongHp + toleranceHp) {
			return 3 + ((double) (maxLatHp - latHp) / (maxLatHp - minLatHp));
		}
		return null;
	}

	/**
	 * Find the nearest edge for supplied Coord p.
	 */
	private static Double getNextEdgeHit(Area a, Coord p) {
		int latHp = p.getHighPrecLat();
		int lonHp = p.getHighPrecLon();
		int minLatHp = a.getMinLat() << Coord.DELTA_SHIFT;
		int maxLatHp = a.getMaxLat() << Coord.DELTA_SHIFT;
		int minLongHp = a.getMinLong() << Coord.DELTA_SHIFT;
		int maxLongHp = a.getMaxLong() << Coord.DELTA_SHIFT;

		log.info(String.format("getNextEdgeHit: (%d %d) (%d %d %d %d)", latHp, lonHp, minLatHp, minLongHp, maxLatHp, maxLongHp));
		// shortest distance to border (init with distance to southern border)
		int min = latHp - minLatHp;
		// number of edge as used in getEdgeHit.
		// 0 = bottom
		// 1 = right
		// 2 = upper
		// 3 = western edge of Area a
		int i = 0;
		// normalized position at border (0..1)
		double t = ((double) (lonHp - minLongHp)) / (maxLongHp - minLongHp);
		// now compare distance to eastern border with already known distance
		if (maxLongHp - lonHp < min) {
			// update data if distance is shorter
			min = maxLongHp - lonHp;
			i = 1;
			t = ((double) (latHp - minLatHp)) / (maxLatHp - minLatHp);
		}
		// same for northern border
		if (maxLatHp - latHp < min) {
			min = maxLatHp - latHp;
			i = 2;
			t = ((double) (maxLongHp - lonHp)) / (maxLongHp - minLongHp);
		}
		// same for western border
		if (lonHp - minLongHp < min) {
			i = 3;
			t = ((double) (maxLatHp - latHp)) / (maxLatHp - minLatHp);
		}
		// now created the EdgeHit for found values
		return i + t;
	}

	/**
	 * Helper class for threadlocal vars
	 */
	private static class PrecompData {
		/**
		 * The index is a grid [lon][lat]. Each element defines the content of one precompiled 
		 * sea tile which are {@link #SEA_TYPE}, {@link #LAND_TYPE}, or {@link #MIXED_TYPE}, or 0 for unknown
		 */
		private byte[][] precompIndex;
		private String precompSeaExt;
		private String precompSeaPrefix;
		private String precompZipFileInternalPath;
		private ZipFile zipFile;
		private File dirFile;
	}
	
}
