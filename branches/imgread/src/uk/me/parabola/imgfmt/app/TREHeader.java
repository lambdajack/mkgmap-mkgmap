/*
 * Copyright (C) 2007 Steve Ratcliffe
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
 * Create date: Dec 14, 2007
 */
package uk.me.parabola.imgfmt.app;

/**
 * @author Steve Ratcliffe
 */
public class TREHeader extends CommonHeader {
	public static final int HEADER_LEN = 120; // Other values are possible

	// Bounding box.  All units are in map units.
	private Area area = new Area(0,0,0,0);

	private int mapInfoSize;

	private int mapLevelPos;
	private int mapLevelsSize;

	private int subdivPos;
	private int subdivSize;

	private int copyrightPos;
	private int copyrightSize;

	private byte poiDisplayFlags;

	private int polylinePos;
	private int polylineSize;

	private int polygonPos;
	private int polygonSize;

	private int pointPos;
	private int pointSize;

	private int mapId;
	static final int MAP_LEVEL_REC_SIZE = 4;
	static final char POLYLINE_REC_LEN = 2;
	static final char POLYGON_REC_LEN = 2;
	static final char POINT_REC_LEN = 3;
	static final char COPYRIGHT_REC_SIZE = 0x3;
	static final int SUBDIV_REC_SIZE = 14;
	static final int SUBDIV_REC_SIZE2 = 16;

	public TREHeader(ImgFile imgFile) {
		super(imgFile, HEADER_LEN, "GARMIN TRE");
	}

	/**
	 * Set the bounds based upon the latitude and longitude in degrees.
	 * @param area The area bounded by the map.
	 */
	public void setBounds(Area area) {
		this.area = area;
	}

	public Area getBounds() {
		return area;
	}

	public void setMapId(int id) {
		mapId = id;
	}

	public void setPoiDisplayFlags(byte poiDisplayFlags) {
		this.poiDisplayFlags = poiDisplayFlags;
	}

	public int getMapInfoSize() {
		return mapInfoSize;
	}

	public void setMapInfoSize(int mapInfoSize) {
		this.mapInfoSize = mapInfoSize;
	}

	public int getMapLevelPos() {
		return mapLevelPos;
	}

	public void setMapLevelPos(int mapLevelPos) {
		this.mapLevelPos = mapLevelPos;
	}

	public int getMapLevelsSize() {
		return mapLevelsSize;
	}

	public void setMapLevelsSize(int mapLevelsSize) {
		this.mapLevelsSize = mapLevelsSize;
	}

	public int getSubdivPos() {
		return subdivPos;
	}

	public void setSubdivPos(int subdivPos) {
		this.subdivPos = subdivPos;
	}

	public int getSubdivSize() {
		return subdivSize;
	}

	public void setSubdivSize(int subdivSize) {
		this.subdivSize = subdivSize;
	}

	public int getCopyrightPos() {
		return copyrightPos;
	}

	public void setCopyrightPos(int copyrightPos) {
		this.copyrightPos = copyrightPos;
	}

	public int getCopyrightSize() {
		return copyrightSize;
	}

	public void setCopyrightSize(int copyrightSize) {
		this.copyrightSize = copyrightSize;
	}

	public byte getPoiDisplayFlags() {
		return poiDisplayFlags;
	}

	public int getPolylinePos() {
		return polylinePos;
	}

	public void setPolylinePos(int polylinePos) {
		this.polylinePos = polylinePos;
	}

	public int getPolylineSize() {
		return polylineSize;
	}

	public void setPolylineSize(int polylineSize) {
		this.polylineSize = polylineSize;
	}

	public int getPolygonPos() {
		return polygonPos;
	}

	public void setPolygonPos(int polygonPos) {
		this.polygonPos = polygonPos;
	}

	public int getPolygonSize() {
		return polygonSize;
	}

	public void setPolygonSize(int polygonSize) {
		this.polygonSize = polygonSize;
	}

	public int getPointPos() {
		return pointPos;
	}

	public void setPointPos(int pointPos) {
		this.pointPos = pointPos;
	}

	public int getPointSize() {
		return pointSize;
	}

	public void setPointSize(int pointSize) {
		this.pointSize = pointSize;
	}

	public int getMapId() {
		return mapId;
	}

	void writeHeader(TREFile treFile)  {
		writeCommonHeader();
		
		treFile.put3(area.getMaxLat());
		treFile.put3(area.getMaxLong());
		treFile.put3(area.getMinLat());
		treFile.put3(area.getMinLong());

		treFile.putInt(getMapLevelPos());
		treFile.putInt(getMapLevelsSize());

		treFile.putInt(getSubdivPos());
		treFile.putInt(getSubdivSize());

		treFile.putInt(getCopyrightPos());
		treFile.putInt(getCopyrightSize());
		treFile.putChar(COPYRIGHT_REC_SIZE);

		treFile.putInt(0);

		treFile.put(getPoiDisplayFlags());

		treFile.put3(0x19);
		treFile.putInt(0xd0401);

		treFile.putChar((char) 1);
		treFile.put((byte) 0);

		treFile.putInt(getPolylinePos());
		treFile.putInt(getPolylineSize());
		treFile.putChar(POLYLINE_REC_LEN);

		treFile.putChar((char) 0);
		treFile.putChar((char) 0);

		treFile.putInt(getPolygonPos());
		treFile.putInt(getPolygonSize());
		treFile.putChar(POLYGON_REC_LEN);

		treFile.putChar((char) 0);
		treFile.putChar((char) 0);

		treFile.putInt(getPointPos());
		treFile.putInt(getPointSize());
		treFile.putChar(POINT_REC_LEN);

		treFile.putChar((char) 0);
		treFile.putChar((char) 0);

		// Map ID
		treFile.putInt(getMapId());

		treFile.position(HEADER_LEN);
	}

	private static class Section {
		int itemSize;
		int totalSize;

		private Section(int itemSize) {
			this.itemSize = itemSize;
		}

		public void add() {
			totalSize += itemSize;
		}
	}
}
