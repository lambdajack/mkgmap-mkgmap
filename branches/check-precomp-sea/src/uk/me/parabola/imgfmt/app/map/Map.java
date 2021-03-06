/*
 * Copyright (C) 2006 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 03-Dec-2006
 */
package uk.me.parabola.imgfmt.app.map;

import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileNotWritableException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.dem.DEMFile;
import uk.me.parabola.imgfmt.app.labelenc.CodeFunctions;
import uk.me.parabola.imgfmt.app.lbl.LBLFile;
import uk.me.parabola.imgfmt.app.net.NETFile;
import uk.me.parabola.imgfmt.app.net.NODFile;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.trergn.InternalFiles;
import uk.me.parabola.imgfmt.app.trergn.MapObject;
import uk.me.parabola.imgfmt.app.trergn.PointOverview;
import uk.me.parabola.imgfmt.app.trergn.PolygonOverview;
import uk.me.parabola.imgfmt.app.trergn.PolylineOverview;
import uk.me.parabola.imgfmt.app.trergn.RGNFile;
import uk.me.parabola.imgfmt.app.trergn.Subdivision;
import uk.me.parabola.imgfmt.app.trergn.TREFile;
import uk.me.parabola.imgfmt.app.trergn.Zoom;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.sys.ImgFS;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.combiners.OverviewBuilder;
import uk.me.parabola.util.Configurable;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Holder for a complete map.  A map is made up of several files which
 * include at least the TRE, LBL and RGN files.
 *
 * It is the interface for all information about the whole map, such as the
 * point overviews etc.  Subdivision will hold the map elements.
 *
 * <p>Needless to say, it has nothing to do with java.util.Map and given
 * how it has turned out, with all reading functionality in MapReader
 * it would have been better named MapWriter.
 *
 * @author Steve Ratcliffe
 */
public class Map implements InternalFiles, Configurable {
	private static final Logger log = Logger.getLogger(Map.class);
	private String filename;
	private String mapName;
	private int mapId;
	private FileSystem fileSystem;

	private TREFile treFile;
	private RGNFile rgnFile;
	private LBLFile lblFile;
	private NETFile netFile;
	private NODFile nodFile;
	private DEMFile demFile;
	private boolean isOverviewCombined;

	// Use createMap() or loadMap() instead of creating a map directly.
	private Map() {
	}

	/**
	 * Create a complete map.  This consists of (at least) three
	 * files that all have the same basename and different extensions.
	 *
	 * @param mapname The name of the map.  This is an 8 digit number as a
	 * string.
	 * @param params Parameters that describe the file system that the map
	 * will be created in.
	 * @return A map object that holds together all the files that make it up.
	 * @throws FileExistsException If the file already exists and we do not
	 * want to overwrite it.
	 * @throws FileNotWritableException If the file cannot
	 * be opened for write.
	 */
	public static Map createMap(String mapname, String outputdir, FileSystemParam params, String mapnumber, Sort sort, boolean overviewCombined)
			throws FileExistsException, FileNotWritableException
	{
		Map m = new Map();
		m.mapName = mapname;
		String outFilename = Utils.joinPath(outputdir, mapname, "img");

		FileSystem fs = ImgFS.createFs(outFilename, params);
		m.filename = outFilename;
		m.fileSystem = fs;

		m.rgnFile = new RGNFile(m.fileSystem.create(mapnumber + ".RGN"));
		m.treFile = new TREFile(m.fileSystem.create(mapnumber + ".TRE"));
		m.lblFile = new LBLFile(m.fileSystem.create(mapnumber + ".LBL"), sort);
		m.isOverviewCombined = overviewCombined;

		int mapid;
		try {
			mapid = Integer.parseInt(mapnumber);
		} catch (NumberFormatException e) {
			mapid = 0;
		}
		m.mapId = mapid;
		m.treFile.setMapId(mapid);
		m.fileSystem = fs;

		return m;
	}

	public void config(EnhancedProperties props) {
		boolean isOverviewComponent = OverviewBuilder.isOverviewImg(mapName);
		// we don't want routing info in the overview map (for now)
		if (!isOverviewComponent && !isOverviewCombined) {
			try {
				if (props.containsKey("route") || props.containsKey("net") || props.containsKey("housenumbers")) {
					addNet();
				} 
				if (props.containsKey("route")) {
					addNod();
				} 
			} catch (FileExistsException e) {
				log.warn("Could not add NET and/or NOD sections");
			}
			// this sets things like draw-priority/transparent/custom
			// and not relevant to overview maps
			treFile.config(props);
		}
		if (!isOverviewComponent && (!isOverviewCombined || props.containsKey("overview-dem-dist"))) {
			// allow dem on detail tiles but not final overview unless has own option. Never in the ovm_
			if (props.containsKey("dem")) {
				try {
					addDem();
				} catch (FileExistsException e) {
					log.warn("Could not add DEM section");
				}
			}
		}
	}

	private void addNet() throws FileExistsException {
		netFile = new NETFile(fileSystem.create(mapName + ".NET"));
	}

	private void addNod() throws FileExistsException {
		nodFile = new NODFile(fileSystem.create(mapName + ".NOD"), true);
	}

	private void addDem() throws FileExistsException {
		demFile = new DEMFile(fileSystem.create(mapId + ".DEM"), true);
	}

	
	/**
	 * Set the area that the map covers.
	 * @param area The outer bounds of the map.
	 */
	public void setBounds(Area area) {
		treFile.setBounds(area);
	}

	/**
	 * Add a copyright message to the map.
	 * @param str the copyright message. The second (last?) one set
	 * gets shown when the device starts (sometimes?).
	 */
	public void addCopyright(String str) {
		Label cpy = lblFile.newLabel(str);
		treFile.addCopyright(cpy);
	}

	/**
	 * There is an area after the TRE header and before its data
	 * starts that is used to save licence info.
	 *
	 * It seems that this must follow the code page of the LBL file.  The format6 encoding is not allowed
	 * however.
	 *
	 * @param msg Any string.
	 */
	public void addInfo(String msg) {
		int codePage = lblFile.getCodePage();
		CodeFunctions functions = CodeFunctions.createEncoderForLBL(0, codePage);
		treFile.addInfo(functions.getEncoder().encodeText(msg));
	}

	/**
	 * Create a new zoom level. The level 0 is the most detailed and
	 * level 15 is the most general.  Most maps would just have 4
	 * different levels or less.  We are just having two to start with
	 * but will probably advance to at least 3.
	 *
	 * @param level The zoom level, and integer between 0 and 15. Its
	 * like a logical zoom level.
	 * @param bits  The number of bits per coordinate, a measure of
	 * the actual amount of detail that will be in the level.  So this
	 * is like a physical zoom level.
	 * @return The zoom object.
	 */
	public Zoom createZoom(int level, int bits) {
		return treFile.createZoom(level, bits);
	}

	/**
	 * Create the top level division. It must be empty afaik and cover
	 * the whole area of the map.
	 *
	 * @param area The whole map area.
	 * @param zoom The zoom level that you want the top level to be
	 * at.  Its going to be at least level 1.
	 * @return The top level division.
	 */
	public Subdivision topLevelSubdivision(Area area, Zoom zoom) {
		zoom.setInherited(true); // May not always be necessary/desired

		InternalFiles ifiles = this;
		Subdivision sub = Subdivision.topLevelSubdivision(ifiles, area, zoom);
		rgnFile.startDivision(sub);
		return sub;
	}

	/**
	 * Create a subdivision that is beneath the top level.  We have to
	 * pass the parent division.
	 * <p>
	 * Note that you cannot create these all up front.  You must
	 * create it, fill it will its map elements and then create the
	 * next one.  You must also start at the top level and work down.
	 *
	 * @param parent The parent subdivision.
	 * @param area The area of the new child subdiv.
	 * @param zoom The zoom level of the child.
	 * @return The new division.
	 */
	public Subdivision createSubdivision(Subdivision parent, Area area, Zoom zoom)
	{
		log.debug("creating division");
		return parent.createSubdivision(this, area, zoom);
	}

	public void addPointOverview(PointOverview ov) {
		treFile.addPointOverview(ov);
	}

	public void addPolylineOverview(PolylineOverview ov) {
		treFile.addPolylineOverview(ov);
	}

	public void addPolygonOverview(PolygonOverview ov) {
		treFile.addPolygonOverview(ov);
	}

	/**
	 * Adds the bits to the point of interest flags.
	 * @param flags The POI flags.
	 */
	public void addPoiDisplayFlags(int flags) {
		treFile.addPoiDisplayFlags(flags);
	}

	public void addMapObject(MapObject item) {
		rgnFile.addMapObject(item);
	}

	public void setSort(Sort sort) {
		lblFile.setSort(sort);
		if (netFile != null)
			netFile.setSort(sort);
	}

	public void setLabelCharset(String desc, boolean forceUpper) {
		lblFile.setCharacterType(desc, forceUpper);
	}
	
	/**
	 * Close this map by closing all the constituent files.
	 *
	 * Some history: 
	 */
	public void close() {
		fileSystem.close();
	}

	public String getFilename() {
		return filename;
	}

	public RGNFile getRgnFile() {
		return rgnFile;
	}

	public LBLFile getLblFile() {
		return lblFile;
	}

	public TREFile getTreFile() {
		return treFile;
	}

	public NETFile getNetFile() {
		return netFile;
	}

	public NODFile getNodFile() {
		return nodFile;
	}

	public DEMFile getDemFile() {
		return demFile;
	}
}
