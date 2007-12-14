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
package uk.me.parabola.imgfmt.app;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * This is the file that contains the overview of the map.  There
 * can be different zoom levels and each level of zoom has an
 * associated set of subdivided areas.  Each of these areas then points
 * into the RGN file.
 *
 * This is quite a complex file as there are quite a few miscellaneous pieces
 * of information stored.
 *
 * @author Steve Ratcliffe
 */
public class TREFile extends ImgFile {
	private static final Logger log = Logger.getLogger(TREFile.class);

	// Zoom levels for map
	//	private List<Zoom> mapLevels = new ArrayList<Zoom>();
	private final Zoom[] mapLevels = new Zoom[16];

	private final List<Label> copyrights = new ArrayList<Label>();

	// Information about polylines.  eg roads etc.
	private final List<PolylineOverview> polylineOverviews = new ArrayList<PolylineOverview>();

	private final List<PolygonOverview> polygonOverviews = new ArrayList<PolygonOverview>();

	private final List<PointOverview> pointOverviews = new ArrayList<PointOverview>();

	private int lastRgnPos;

	private final boolean readOnly;
	private final TREHeader treHeader;

	public TREFile(ImgChannel chan, boolean write) {
		if (write) {
			readOnly = false;
			setWriter(new BufferedWriteStrategy(chan));

			// Position at the start of the writable area.
			position(TREHeader.HEADER_LEN);
			treHeader = new TREHeader(this);
		} else {
			readOnly = true;
			treHeader = new TREHeader(this);
			readin(chan);
		}
	}

	public Zoom createZoom(int zoom, int bits) {
		Zoom z = new Zoom(zoom, bits);
		mapLevels[zoom] = z;
		return z;
	}

	/**
	 * Add a string to the 'mapinfo' section.  This is a section between the
	 * header and the start of the data.  Nothing points to it directly.
	 *
	 * @param msg A string, usually used to describe the program that generated
	 * the file.
	 */
	public void addInfo(String msg) {
		byte[] val = Utils.toBytes(msg);
		if (position() != TREHeader.HEADER_LEN + treHeader.getMapInfoSize())
			throw new IllegalStateException("All info must be added before anything else");

		treHeader.setMapInfoSize(treHeader.getMapInfoSize() + (val.length+1));
		put(val);
		put((byte) 0);
	}

	public Area getBounds() {
		return treHeader.getBounds();
	}

	public void addCopyright(Label cr) {
		copyrights.add(cr);
	}

	public void addPointOverview(PointOverview ov) {
		pointOverviews.add(ov);
	}

	public void addPolylineOverview(PolylineOverview ov) {
		polylineOverviews.add(ov);
	}

	public void addPolygonOverview(PolygonOverview ov) {
		polygonOverviews.add(ov);
	}

	private void readin(ImgChannel chan) {
		try {
			readHeader(chan);
		} catch (IOException e) {
			log.error("Cound not read TRE header");
		}
	}

	private void readHeader(ImgChannel chan) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(512);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		chan.position(CommonHeader.COMMON_HEADER_LEN);
		chan.read(buf);

		buf.flip();

		int maxLat = get3(buf);
		int maxLon = get3(buf);
		int minLat = get3(buf);
		int minLon = get3(buf);

		treHeader.setBounds(new Area(minLat, minLon, maxLat, maxLon));
		log.info("read area is", treHeader.getBounds());
	}

	/**
	 * Write out the body of the TRE file.  The act of writing the body sections
	 * out provides us with pointers that are needed for the header.  Therefore
	 * the header needs to be written after the body (or obviously we could
	 * make two passes).
	 */
	private void writeBody() {
		writeMapLevels();

		writeSubdivs();

		writeCopyrights();

		writeOverviews();
	}

	/**
	 * Write out the subdivisions.  This is quite complex as they have to be
	 * numbered and written out keeping their parent/child relationship
	 * intact.
	 */
	private void writeSubdivs() {
		treHeader.setSubdivPos(position());
		int subdivnum = 1; // numbers start at one

		// First prepare to number them all
		for (int i = 15; i >= 0; i--) {
			Zoom z = mapLevels[i];
			if (z == null)
				continue;

			Iterator<Subdivision> it = z.subdivIterator();
			while (it.hasNext()) {
				Subdivision sd = it.next();
				TREFile.this.log.debug("setting number to", subdivnum);
				sd.setNumber(subdivnum++);
			}
		}

		// Now we can write them all out.
		for (int i = 15; i >= 0; i--) {
			Zoom z = mapLevels[i];
			if (z == null)
				continue;

			Iterator<Subdivision> it = z.subdivIterator();
			while (it.hasNext()) {
				Subdivision sd = it.next();

				sd.write(this);
				if (sd.hasNextLevel())
					treHeader.setSubdivSize(treHeader.getSubdivSize() + TREHeader.SUBDIV_REC_SIZE2);
				else
					treHeader.setSubdivSize(treHeader.getSubdivSize() + TREHeader.SUBDIV_REC_SIZE);
			}
		}
		putInt(lastRgnPos);
		treHeader.setSubdivSize(treHeader.getSubdivSize() + 4);
	}

	/**
	 * Write out the map levels.  This is a mapping between the level number
	 * and the resolution.
	 */
	private void writeMapLevels() {
		// Write out the map levels (zoom)
		treHeader.setMapLevelPos(position());
		for (int i = 15; i >= 0; i--) {
			// They need to be written in reverse order I think
			Zoom z = mapLevels[i];
			if (z == null)
				continue;
			treHeader.setMapLevelsSize(treHeader.getMapLevelsSize() + TREHeader.MAP_LEVEL_REC_SIZE);
			z.write(this);
		}
	}

	/**
	 * Write out the overview section.  This is a mapping between the map feature
	 * type and the highest level (lowest detail) that it appears at.  There
	 * are separate ones for points, lines and polygons.
	 */
	private void writeOverviews() {
		treHeader.setPointPos(position());
		
		// Point overview section
		Collections.sort(pointOverviews);
		for (Overview ov : pointOverviews) {
			ov.setMaxLevel(decodeLevel(ov.getMinResolution()));
			ov.write(this);
			treHeader.setPointSize(treHeader.getPointSize() + TREHeader.POINT_REC_LEN);
		}

		// Line overview section.
		treHeader.setPolylinePos(position());
		Collections.sort(polylineOverviews);
		for (Overview ov : polylineOverviews) {
			ov.setMaxLevel(decodeLevel(ov.getMinResolution()));
			ov.write(this);
			treHeader.setPolylineSize(treHeader.getPolylineSize() + TREHeader.POLYLINE_REC_LEN);
		}

		// Polygon overview section
		treHeader.setPolygonPos(position());
		Collections.sort(polygonOverviews);
		for (Overview ov : polygonOverviews) {
			ov.setMaxLevel(decodeLevel(ov.getMinResolution()));
			ov.write(this);
			treHeader.setPolygonSize(treHeader.getPolygonSize() + TREHeader.POLYGON_REC_LEN);
		}
	}

	/**
	 * Convert a min resolution to a level.  We return the lowest level (most
	 * detailed) that has a resolution less than or equal to the given resolution.
	 * 
	 * @param minResolution The minimum resolution.
	 * @return The level corresponding to the resulution.
	 */
	private int decodeLevel(int minResolution) {
		Zoom top = null;
		for (int i = 15; i >= 0; i--) {
			Zoom z = mapLevels[i];
			if (z == null)
				continue;

			if (top == null)
				top = z;

			if (z.getResolution() >= minResolution)
				return z.getLevel();
		}

		// If not found, then allow it only at the top level
		if (top != null)
			return top.getLevel();
		else
			return 0; // Fail safe, shouldn't really happen
	}

	/**
	 * Write out the copyrights.  This is just a list of pointers to strings
	 * in the label section basically.
	 */
	private void writeCopyrights() {
		// Write out the pointers to the labels that hold the copyright strings
		treHeader.setCopyrightPos(position());
		for (Label l : copyrights) {
			treHeader.setCopyrightSize(treHeader.getCopyrightSize() + TREHeader.COPYRIGHT_REC_SIZE);
			put3(l.getOffset());
		}
	}

	public void setLastRgnPos(int lastRgnPos) {
		TREFile.this.lastRgnPos = lastRgnPos;
	}

		public void sync() throws IOException {
			if (readOnly)
				return;

			// Do anything that is in structures and that needs to be dealt with.
			writeBody();

			// Now refresh the header
			position(0);
			treHeader.writeHeader(this);

			getWriter().sync();
		}

	public void setMapId(int mapid) {
		treHeader.setMapId(mapid);
	}

	public void setBounds(Area area) {
		treHeader.setBounds(area);
	}
}
