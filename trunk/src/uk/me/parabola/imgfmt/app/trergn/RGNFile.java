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
package uk.me.parabola.imgfmt.app.trergn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import uk.me.parabola.imgfmt.app.BufferedImgFileWriter;
import uk.me.parabola.imgfmt.app.ImgFile;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

/**
 * The region file.  Holds actual details of points and lines etc.
 *
 * 
 *
 * The data is rather complicated and is packed to save space.  This class does
 * not really handle that format however as it is written by the
 * {@link MapObject}s themselves.
 *
 * Each subdivision takes space in this file.  The I am expecting this to be the
 * biggest file, although it seems that TRE may be in some circumstances.
 *
 * @author Steve Ratcliffe
 */
public class RGNFile extends ImgFile {
	private static final Logger log = Logger.getLogger(RGNFile.class);

	private static final int HEADER_LEN = RGNHeader.HEADER_LEN;

	private final RGNHeader header = new RGNHeader();

	private Subdivision currentDivision;
	private int indPointPtrOff;
	private int polylinePtrOff;
	private int polygonPtrOff;
	private final ByteArrayOutputStream extTypePointsData = new ByteArrayOutputStream();
	private final ByteArrayOutputStream extTypeLinesData = new ByteArrayOutputStream();
	private final ByteArrayOutputStream extTypeAreasData = new ByteArrayOutputStream();

	public RGNFile(ImgChannel chan) {
		setHeader(header);

		setWriter(new BufferedImgFileWriter(chan, "RGN"));

		// Position at the start of the writable area.
		position(HEADER_LEN);
	} 

	public void write() {
		if (!isWritable())
			throw new IllegalStateException("File not writable");

		header.setDataSize(position() - HEADER_LEN);

		if(extTypeAreasData.size() > 0) {
			header.setExtTypeAreasInfo(position(), extTypeAreasData.size());
			getWriter().put(extTypeAreasData.toByteArray());
		}
		if(extTypeLinesData.size() > 0) {
			header.setExtTypeLinesInfo(position(), extTypeLinesData.size());
			getWriter().put(extTypeLinesData.toByteArray());
		}
		if(extTypePointsData.size() > 0) {
			header.setExtTypePointsInfo(position(), extTypePointsData.size());
			getWriter().put(extTypePointsData.toByteArray());
		}

		getHeader().writeHeader(getWriter());
	}

	public void startDivision(Subdivision sd) {

		sd.setStartRgnPointer(position() - HEADER_LEN);

		// We need to reserve space for a pointer for each type of map
		// element that is supported by this division.  Note that these
		// pointers are only 2bytes long.  A pointer to the points is never
		// needed as it will always be first if present.
		if (sd.needsIndPointPtr()) {
			indPointPtrOff = position();
			position(position() + 2L);
		}

		if (sd.needsPolylinePtr()) {
			polylinePtrOff = position();
			position(position() + 2L);
		}

		if (sd.needsPolygonPtr()) {
			polygonPtrOff = position();
			position(position() + 2L);
		}

		currentDivision = sd;
	}

	public void addMapObject(MapObject item) {
		if (item.hasExtendedType()) {
			try {
				if (item instanceof Point) {
					item.write(extTypePointsData);
				} else if (item instanceof Polygon) {
					item.write(extTypeAreasData);
				} else if (item instanceof Polyline) {
					item.write(extTypeLinesData);
				} else
					log.error("Can't add object of type " + item.getClass());
			} catch (IOException ioe) {
				log.error("Error writing extended type object: " + ioe.getMessage());
			}
		} else {
			item.write(getWriter());
		}
	}

	public void setIndPointPtr() {
		if (currentDivision.needsIndPointPtr()) {
			long currPos = position();
			position(indPointPtrOff);
			long off = currPos - currentDivision.getStartRgnPointer() - HEADER_LEN;
			if (off > 0xffff)
				throw new IllegalStateException("IndPoint offset too large: " + off);

			getWriter().put2u((int) off);
			position(currPos);
		}
	}

	public void setPolylinePtr() {
		if (currentDivision.needsPolylinePtr()) {
			long currPos = position();
			position(polylinePtrOff);
			long off = currPos - currentDivision.getStartRgnPointer() - HEADER_LEN;
			if (off > 0xffff)
				throw new IllegalStateException("Polyline offset too large: " + off);

			if (log.isDebugEnabled())
				log.debug("setting polyline offset to", off);
			getWriter().put2u((int) off);

			position(currPos);
		}
	}

	public void setPolygonPtr() {
		if (currentDivision.needsPolygonPtr()) {
			long currPos = position();
			long off = currPos - currentDivision.getStartRgnPointer() - HEADER_LEN;
			log.debug("currpos=", currPos, ", off=", off);
			if (off > 0xffff)
				throw new IllegalStateException("Polygon offset too large: " + off);

			if (log.isDebugEnabled())
				log.debug("setting polygon offset to", off, "@", polygonPtrOff);
			position(polygonPtrOff);
			getWriter().put2u((int) off);
			position(currPos);
		}
	}

	@Override
	public ImgFileWriter getWriter() {
		return super.getWriter();
	}

	public int getExtTypePointsSize() {
		return extTypePointsData.size();
	}

	public int getExtTypeLinesSize() {
		return extTypeLinesData.size();
	}

	public int getExtTypeAreasSize() {
		return extTypeAreasData.size();
	}

	public boolean haveExtendedTypes() {
		return extTypeAreasData.size() != 0 || 
				extTypeLinesData.size() != 0 || 
				extTypePointsData.size() != 0;
	}
}
