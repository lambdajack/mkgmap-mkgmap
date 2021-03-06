/*
 * Copyright (C) 2009.
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
package uk.me.parabola.imgfmt.app.trergn;

import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.BufferedImgFileReader;
import uk.me.parabola.imgfmt.app.ImgFileReader;
import uk.me.parabola.imgfmt.app.ImgReader;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.Section;
import uk.me.parabola.imgfmt.app.labelenc.CharacterDecoder;
import uk.me.parabola.imgfmt.app.labelenc.CodeFunctions;
import uk.me.parabola.imgfmt.app.labelenc.DecodedText;
import uk.me.parabola.imgfmt.app.lbl.LBLFileReader;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * This is the file that contains the overview of the map.  There
 * can be different zoom levels and each level of zoom has an
 * associated set of subdivided areas.  Each of these areas then points
 * into the RGN file.
 *
 * The main focus of mkgmap is creating files, there are plenty of applications
 * that read and display the data, reading is implemented only to the
 * extent required to support creating the various auxiliary files etc.
 *
 * @author Steve Ratcliffe
 */
public class TREFileReader extends ImgReader {
	private Zoom[] mapLevels;
	private Subdivision[][] levelDivs;

	private static final Subdivision[] EMPTY_SUBDIVISIONS = new Subdivision[0];

	private final TREHeader header = new TREHeader();
	private int tre7Magic;

	public TREFileReader(ImgChannel chan) {
		this(chan, 0);
	}

	public TREFileReader(ImgChannel chan, int gmpOffset) {
		setHeader(header);

		setReader(new BufferedImgFileReader(chan, gmpOffset));
		header.readHeader(getReader());
		tre7Magic = header.getTre7Magic();
		readMapLevels();
		readSubdivs();
		readExtTypeOffsetsRecords();
	}

	public Area getBounds() {
		return header.getBounds();
	}

	public Zoom[] getMapLevels() {
		return mapLevels;
	}
	
	/**
	 * Return the subdivisions for the given level.
	 * @param level The level, 0 being the most detailed.  There may not be
	 * a level zero in the map.
	 * @return The subdivisions for the level. Never returns null; a zero length
	 * array is returned if there is no such level.
	 */
	public Subdivision[] subdivForLevel(int level) {
		for (int i = 0; i < mapLevels.length; i++) {
			if (mapLevels[i].getLevel() == level) {
				return levelDivs[i];
			}
		}
		return EMPTY_SUBDIVISIONS;
	}

	/**
	 * Read in the subdivisions.  There are a set of subdivision for each level.
	 */
	private void readSubdivs() {
		ImgFileReader reader = getReader();

		int start = header.getSubdivPos();
		int end = start + header.getSubdivSize();
		reader.position(start);

		int subdivNum = 1;
		int lastRgnOffset = reader.get3u();
		for (int count = 0; count < levelDivs.length && reader.position() < end; count++) {

			Subdivision[] divs = levelDivs[count];
			Zoom zoom = mapLevels[count];
			if (divs == null)
				break;

			for (int i = 0; i < divs.length; i++) {
				int flags = reader.get1u();
				int lon = reader.get3s();
				int lat = reader.get3s();
				int width = reader.get2u() & 0x7fff;
				int height = reader.get2u();
				int extFlags = flags; 
				if (reader.getGMPOffset() > 0 && (height & 0x8000) != 0) {
					extFlags |= 0x1;
					height &= 0x7fff;
				}

				if (count < levelDivs.length-1)
					reader.get2u();

				int endRgnOffset = reader.get3u();

				SubdivData subdivData = new SubdivData(extFlags,
						lat, lon, width, height,
						lastRgnOffset, endRgnOffset);

				Subdivision subdiv = Subdivision.readSubdivision(mapLevels[count], subdivData);
				subdiv.setNumber(subdivNum++);
				
				divs[i] = subdiv;
				zoom.addSubdivision(subdiv);

				lastRgnOffset = endRgnOffset;
			}
		}
	}
	
	/**
	 * Read the extended type info for the sub divisions. Corresponds to {@link TREFile#writeExtTypeOffsetsRecords()}.
	 */
	private void readExtTypeOffsetsRecords() {
		if ((tre7Magic & 7) == 0) {
			return;
		}
		ImgFileReader reader = getReader();
		int start = header.getExtTypeOffsetsPos();
		int end = start + header.getExtTypeOffsetsSize();
		int recSize = header.getExtTypeSectionSize();
		if (recSize == 0)
			return;
		reader.position(start);
		Subdivision sd = null;
		Subdivision sdPrev = null;
		if (header.getExtTypeOffsetsSize() % recSize != 0) {
			Logger.defaultLogger.error("TRE7 data seems to have varying length records, don't know how to read extended types offsets");
			return;
			
		}
		int available = header.getExtTypeOffsetsSize() / recSize;
		// with record size > 13 there may be no data for the first level(s).
		int firstDivIndex = 0;
		for (int i = levelDivs.length-1; i >= 0; i--) {
			available -= levelDivs[i].length;
			if (available < 0) {
				Logger.defaultLogger.error("TRE7 data contains unexpected values, don't know how to read extended types offsets");
				return;
			}
			if (available == 1) {
				firstDivIndex = i;
				break;
			}
		}
		for (int count = firstDivIndex; count < levelDivs.length && reader.position() < end; count++) {
			Subdivision[] divs = levelDivs[count];
			if (divs == null)
				break;

			for (int i = 0; i < divs.length; i++) {
				sdPrev = sd;
				sd = divs[i];
				sd.readExtTypeOffsetsRecord(reader, sdPrev, recSize, tre7Magic);
			}
		}
		if(sd != null && reader.position() < end) {
			sd.readLastExtTypeOffsetsRecord(reader, recSize, tre7Magic);
		}
		assert reader.position() == end : "Failed to read TRE7"; 
	}


	/**
	 * Read the map levels.  This is needed to make sense of the subdivision
	 * data.  Unlike in the write case, we just keep an array of zoom levels
	 * as found, there is no correspondence between the array index and level.
	 */
	private void readMapLevels() {
		ImgFileReader reader = getReader();

		int levelsPos = header.getMapLevelsPos();
		int levelsSize = header.getMapLevelsSize();
		reader.position(levelsPos);
		byte[] levelsData = reader.get(levelsSize);

		List<Subdivision[]> levelDivsList = new ArrayList<>();
		List<Zoom> mapLevelsList = new ArrayList<>();
		int key = 0;
		if (header.getLockFlag() != 0) {
			long pos = reader.position();
			if (header.getHeaderLength() >= 0xAA) {
				reader.position((reader.getGMPOffset() + 0xAA));
				key = reader.get4();
				
			}
			reader.position(pos);
			
			demangle(levelsData, levelsSize, key);
		}
		
		int used = 0;
		while (used < levelsSize) {
			int level = levelsData[used++] & 0xff;
			int nbits = levelsData[used++] & 0xff;
			byte b1 = levelsData[used++];
			byte b2 = levelsData[used++];
			int ndivs = (b1 & 0xff) | ((b2 & 0xff) << 8);
			Subdivision[] divs = new Subdivision[ndivs];
			levelDivsList.add(divs);
			level &= 0x7f;

			Zoom z = new Zoom(level, nbits);
			mapLevelsList.add(z);
		}

		this.levelDivs = levelDivsList.toArray(new Subdivision[levelDivsList.size()][]);
		this.mapLevels = mapLevelsList.toArray(new Zoom[mapLevelsList.size()]);
	}

	public void config(EnhancedProperties props) {
		header.config(props);
	}

	public String[] getMapInfo(int codePage) {
		CodeFunctions funcs = CodeFunctions.createEncoderForLBL(0, codePage);
		CharacterDecoder decoder = funcs.getDecoder();

		// First do the ones in the TRE header gap
		ImgFileReader reader = getReader();
		reader.position(reader.getGMPOffset() + header.getHeaderLength());
		List<String> msgs = new ArrayList<>();
		while (reader.position() < header.getHeaderLength() + header.getMapInfoSize()) {
			byte[] m = reader.getZString();

			decoder.reset();
			for (byte b : m)
				decoder.addByte(b);

			DecodedText text = decoder.getText();
			String text1 = text.getText();

			msgs.add(text1);
		}


		return msgs.toArray(new String[msgs.size()]);
	}

	public String[] getCopyrights(LBLFileReader lblReader) {
		Section sect = header.getCopyrightSection();
		ImgFileReader reader = getReader();
		List<String> msgs = new ArrayList<>();

		long pos = sect.getPosition();
		while (pos < sect.getEndPos()) {
			reader.position(pos);
			int offset = reader.get3u();
			Label label = lblReader.fetchLabel(offset);
			if (label != null) {
				msgs.add(label.getText());
			}
		
			pos += sect.getItemSize();
		}
		return msgs.toArray(new String[msgs.size()]);
	}
	
	
	// code taken from GPXsee 
	// origin is much older: 
	// https://github.com/wuyongzheng/gimgtools/blob/92d015749e105c5fb8eb704ae503a5c7e51af2bd/gimgunlock.c#L15
	private static void demangle(byte[] data, int size, int key) {
		final byte[] shuf = {
			0xb, 0xc, 0xa, 0x0,
			0x8, 0xf, 0x2, 0x1,
			0x6, 0x4, 0x9, 0x3,
			0xd, 0x5, 0x7, 0xe
		};

		int sum = shuf[((key >> 24) + (key >> 16) + (key >> 8) + key) & 0xf];
		int ringctr = 16;
		for (int i = 0; i < size; i++) {
			int upper = data[i] >> 4;
			int lower = data[i];

			upper -= sum;
			upper -= key >> ringctr;
			upper -= shuf[(key >> ringctr) & 0xf];
			ringctr = ringctr != 0 ? ringctr - 4 : 16;

			lower -= sum;
			lower -= key >> ringctr;
			lower -= shuf[(key >> ringctr) & 0xf];
			ringctr = ringctr != 0 ? ringctr - 4 : 16;

			data[i] = (byte) (((upper << 4) & 0xf0) | (lower & 0xf));
		}
	}
	 
	public int getTre7Magic() {
		return tre7Magic;
	}
}
