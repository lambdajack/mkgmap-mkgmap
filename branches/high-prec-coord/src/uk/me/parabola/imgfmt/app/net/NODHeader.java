/*
 * Copyright (C) 2008 Steve Ratcliffe
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
 * Create date: 06-Jul-2008
 */
package uk.me.parabola.imgfmt.app.net;

import uk.me.parabola.imgfmt.ReadFailedException;
import uk.me.parabola.imgfmt.app.CommonHeader;
import uk.me.parabola.imgfmt.app.ImgFileReader;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.Section;

/**
 * Header information for the NOD file.
 *
 * This is a routing network for the map.
 *
 * @author Steve Ratcliffe
 */
public class NODHeader extends CommonHeader {
	public static final int HEADER_LEN = 63;

	static final char DEF_ALIGN = 6;
	private static final char BOUNDARY_ITEM_SIZE = 9;

	private final Section nodes = new Section();
	private final Section roads = new Section(nodes);
	private final Section boundary = new Section(roads, BOUNDARY_ITEM_SIZE);
	private final Section highClassBoundary = new Section();
	private final int[] classBoundaries = new int[5];

    private int flags;
    private int align;
    private int mult1;
	private int tableARecordLen;

	/** 
	 * The driveOnLeft flag is set via a static method. Using a ThreadLocal
	 * ensures thread safety when using more than one thread.
	 */
	private static final ThreadLocal<Boolean> driveOnLeft = new ThreadLocal<Boolean>() {
		protected Boolean initialValue() {
			return Boolean.FALSE;
		}
	};

	public NODHeader() {
		super(HEADER_LEN, "GARMIN NOD");
	}

	/**
	 * Read the rest of the header.  Specific to the given file.  It is guaranteed
	 * that the file position will be set to the correct place before this is
	 * called.
	 *
	 * @param reader The header is read from here.
	 */
	protected void readFileHeader(ImgFileReader reader) throws ReadFailedException {
        nodes.readSectionInfo(reader, false);
        flags = reader.getChar();
        reader.getChar();
        align = reader.get();
        mult1 = reader.get();
        tableARecordLen = reader.getChar();
        roads.readSectionInfo(reader, false);
        reader.getInt();
        boundary.readSectionInfo(reader, true);
		reader.getInt();
		if (getHeaderLength() > 0x3f) {
			highClassBoundary.readSectionInfo(reader, false);

			classBoundaries[0] = reader.getInt();
			classBoundaries[1] = classBoundaries[0] + reader.getInt();
			classBoundaries[2] = classBoundaries[1] + reader.getInt();
			classBoundaries[3] = classBoundaries[2] + reader.getInt();
			classBoundaries[4] = classBoundaries[3] + reader.getInt();
		}
	}

	/**
	 * Write the rest of the header.  It is guaranteed that the writer will be set
	 * to the correct position before calling.
	 *
	 * @param writer The header is written here.
	 */
	protected void writeFileHeader(ImgFileWriter writer) {
		nodes.setPosition(HEADER_LEN);
		nodes.writeSectionInfo(writer);

		// now sets 0x02 (enable turn restrictions?)
		int val = 0x27;
		if(driveOnLeft.get())
			val |= 0x0300;
		writer.putInt(val);

		byte align = DEF_ALIGN;
		writer.put(align);
		writer.put((byte) 0); // pointer multiplier
		writer.putChar((char) 5);

		roads.writeSectionInfo(writer);
		writer.putInt(0);

		boundary.writeSectionInfo(writer);
	}

    public int getNodeStart() {
        return nodes.getPosition();
    }

	public void setNodeStart(int start) {
		nodes.setPosition(start);
	}

    public int getNodeSize() {
        return nodes.getSize();
    }

	public void setNodeSize(int size) {
		nodes.setSize(size);
	}

	public Section getNodeSection() {
		return nodes;
	}

	public void setRoadSize(int size) {
		roads.setSize(size);
	}

	public Section getRoadSection() {
		return roads;
	}

	public void setBoundarySize(int size) {
		boundary.setSize(size);
	}

	public Section getBoundarySection() {
		return boundary;
	}

	public Section getHighClassBoundary() {
		return highClassBoundary;
	}

	public int[] getClassBoundaries() {
		return classBoundaries;
	}

	public static void setDriveOnLeft(boolean dol) {
		driveOnLeft.set(dol);
	}

    public int getFlags() {
        return flags;
    }

    public int getAlign() {
        return align;
    }

	public int getMult1() {
		return mult1;
	}

	public int getTableARecordLen() {
		return tableARecordLen;
	}
}
