/*
 * Copyright (C) 2012.
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
package uk.me.parabola.mkgmap.reader.osm.o5m;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.GeneralRelation;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmHandler;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;

/**
 * Parser for the o5m format described here: http://wiki.openstreetmap.org/wiki/O5m
 * The routines to are based on the osmconvert.c source from Markus Weber who allows 
 * to copy them for any o5m IO, thanks a lot for that. 
 * @author GerdP  
 *
 */
public class O5mBinHandler extends OsmHandler{
	// O5M data set constants
	private static final int NODE_DATASET = 0x10;
	private static final int WAY_DATASET = 0x11;
	private static final int REL_DATASET = 0x12;
	private static final int BBOX_DATASET = 0xdb;
	private static final int TIMESTAMP_DATASET = 0xdc;
	private static final int HEADER_DATASET = 0xe0;
	private static final int EOD_FLAG = 0xfe;
	private static final int RESET_FLAG = 0xff;
	
	private static final int EOF_FLAG = -1;
	
	// o5m constants
	private static final int STRING_TABLE_SIZE = 15000;
	private static final int MAX_STRING_PAIR_SIZE = 250 + 2;
	private static final String[] REL_REF_TYPES = {"node", "way", "relation", "?"};
	private static final double FACTOR = 1d/1_000_000_000; // used with 100*<Val>*FACTOR 
	
	private BufferedInputStream fis;
	private InputStream is;
	
	// buffer for byte -> String conversions
	private byte[] cnvBuffer; 
	
	private byte[] ioBuf;
	private int ioBufPos;
	// the o5m string table
	private String[][] stringTable;
	private String[] stringPair;
	private int currStringTablePos;
	// a counter that must be maintained by all routines that read data from the stream
	private int bytesToRead;
	// total number of bytes read from stream
	private long countBytes;

	// for delta calculations
	private long lastNodeId;
	private long lastWayId;
	private long lastRelId;
	private long[] lastRef;
	private long lastTs;
	private long lastChangeSet;
	private int lastLon;
	private int lastLat;
	
	/**
	 * A parser for the o5m format
	 */
	public O5mBinHandler() {
		// nothing to do
	}

	@Override
	public boolean isFileSupported(String name) {
		return name.endsWith(".o5m") || name.endsWith(".o5m.gz");
	}

	/**
	 * Parse the input stream.
	 */
	@Override
	public void parse(InputStream stream) {
		this.fis = new BufferedInputStream(stream);
		is = fis;
		this.cnvBuffer = new byte[4000]; // OSM data should not contain string pairs with length > 512
		this.ioBuf = new byte[8192];
		this.ioBufPos = 0;
		this.stringPair = new String[2];
		this.lastRef = new long[3];
		reset();
		try {
			int start = is.read();
			++countBytes;
			if (start != RESET_FLAG)
				throw new IOException("wrong header byte " + start);
			readFile();
		} catch (IOException e) {
			System.err.println("exception after " + countBytes + " bytes");
			e.printStackTrace();
		}
	}
	
	private void readFile() throws IOException{
		boolean done = false;
		while(!done) {
			is = fis;
			long size = 0;
			int fileType = is.read();
			++countBytes;
			if (fileType >= 0 && fileType < 0xf0) {
				bytesToRead = 0;
				size = readUnsignedNum64FromStream();
				countBytes += size - bytesToRead; // bytesToRead is negative
				bytesToRead = (int) size;
				
				switch (fileType) {
				case NODE_DATASET: 
				case WAY_DATASET: 
				case REL_DATASET: 
				case BBOX_DATASET:
				case TIMESTAMP_DATASET:
				case HEADER_DATASET:
					if (bytesToRead > ioBuf.length) {
						ioBuf = new byte[bytesToRead + 100];
					}
					int bytesRead = 0;
					int neededBytes = bytesToRead;
					while (neededBytes > 0) {
						bytesRead += is.read(ioBuf, bytesRead, neededBytes);
						neededBytes -= bytesRead;
					}
					ioBufPos = 0;
					is = new ByteArrayInputStream(ioBuf, 0, bytesToRead);
					break;
				default:	
				}
			}
			if (fileType == EOF_FLAG) done = true; 
			else if (fileType == NODE_DATASET) readNode();
			else if (fileType == WAY_DATASET) readWay();
			else if (fileType == REL_DATASET) readRel();
			else if (fileType == BBOX_DATASET) readBBox();
			else if (fileType == TIMESTAMP_DATASET) readFileTimestamp();
			else if (fileType == HEADER_DATASET) readHeader();
			else if (fileType == EOD_FLAG) done = true;
			else if (fileType == RESET_FLAG) reset();
			else {
				if (fileType < 0xf0 ) skip(size); // skip unknown data set 
			}
		}
	}
	
	/**
	 * Read (and ignore) the file timestamp data set.
	 */
	private void readFileTimestamp() {
		/* long fileTimeStamp = */readSignedNum64();
	}
	
	/**
	 * Skip the given number of bytes
	 * @param bytes 
	 * @throws IOException
	 */
	private void skip(long bytes) throws IOException {
		long toSkip = bytes;
		while (toSkip > 0) {
			toSkip -= is.skip(toSkip);
		}
	}
	
	/**
	 * Read the bounding box data set.
	 */
	private void readBBox() {
		double minlon = FACTOR * 100L * readSignedNum32();
		double minlat = FACTOR * 100L * readSignedNum32();
		double maxlon = FACTOR * 100L * readSignedNum32();
		double maxlat = FACTOR * 100L * readSignedNum32();

		assert bytesToRead == 0;
		setBBox(minlat, minlon, maxlat, maxlon);
	}

	/**
	 * Read a node data set. 
	 */
	private void readNode() {
		lastNodeId += readSignedNum64();
		if (bytesToRead == 0)
			return; // only nodeId: this is a delete action, we ignore it 
		readVersionTsAuthor();
		if (bytesToRead == 0)
			return; // only nodeId+version: this is a delete action, we ignore it 
		int lon = readSignedNum32() + lastLon; lastLon = lon;
		int lat = readSignedNum32() + lastLat; lastLat = lat;
			
		double flon = FACTOR * (100L * lon);
		double flat = FACTOR * (100L * lat);
		assert flat >= -90.0 && flat <= 90.0;  
		assert flon >= -180.0 && flon <= 180.0;  

		Coord co = new Coord(flat, flon);
		saver.addPoint(lastNodeId, co);
		if (bytesToRead > 0) {
			Node node = new Node(lastNodeId, co);
			readTags(node);
			if (node.getTagCount() > 0) {
				// If there are tags, then we save a proper node for it.
				saver.addNode(node);
				hooks.onAddNode(node);
			}
		}
	}
	
	/**
	 * Read a way data set.
	 */
	private void readWay() {
		lastWayId += readSignedNum64();
		if (bytesToRead == 0)
			return; // only wayId: this is a delete action, we ignore it

		readVersionTsAuthor();
		if (bytesToRead == 0)
			return; // only wayId + version: this is a delete action, we ignore it
		Way way = startWay(lastWayId);
		long refSize = readUnsignedNum32();
		long stop = bytesToRead - refSize;

		while (bytesToRead > stop) {
			lastRef[0] += readSignedNum64();
			addCoordToWay(way, lastRef[0]);
		}

		readTags(way);
		endWay(way);
	}
	
	/**
	 * Read a relation data set.
	 */
	private void readRel() {
		lastRelId += readSignedNum64(); 
		if (bytesToRead == 0)
			return; // only relId: this is a delete action, we ignore it 
		readVersionTsAuthor();
		if (bytesToRead == 0)
			return; // only relId + version: this is a delete action, we ignore it 

		GeneralRelation rel = new GeneralRelation(lastRelId);
		long refSize = readUnsignedNum32();
		long stop = bytesToRead - refSize;
		while (bytesToRead > stop) {
			Element el = null;
			long deltaRef = readSignedNum64();
			int refType = readRelRef();
			String role = stringPair[1];
			lastRef[refType] += deltaRef;
			long memId = lastRef[refType];
			if (refType == 0) {
				el = saver.getNode(memId);
				if (el == null) {
					// we didn't make a node for this point earlier,
					// do it now (if it exists)
					Coord co = saver.getCoord(memId);
					if (co != null) {
						el = new Node(memId, co);
						saver.addNode((Node) el);
					}
				}
			} else if (refType == 1) {
				el = saver.getWay(memId);
			} else if (refType == 2) {
				el = saver.getRelation(memId);
				if (el == null) {
					saver.deferRelation(memId, rel, role);
				}
			} else {
				assert false;
			}
			if (el != null) // ignore non existing ways caused by splitting files
				rel.addElement(role, el);
		}
		boolean tagsIncomplete = readTags(rel);
		rel.setTagsIncomplete(tagsIncomplete);
		saver.addRelation(rel);
	}
	
	private boolean readTags(Element elem) {
		boolean tagsIncomplete = false;
		while (bytesToRead > 0) {
			readStringPair();
			String key = stringPair[0];
			String val = stringPair[1];
			// the type tag is required for relations - all other tags are filtered
			if (elem instanceof Relation && "type".equals(key))
				// intern the string
				key = "type";
			else
				key = keepTag(key, val);
			if (key != null)
				elem.addTagFromRawOSM(key, val);
			else
				tagsIncomplete = true;
		}
		assert bytesToRead == 0;
		return tagsIncomplete;
	}

	/**
	 * Store a new string pair (length check must be performed by caller).
	 */
	private void storeStringPair() {
		stringTable[0][currStringTablePos] = stringPair[0];
		stringTable[1][currStringTablePos] = stringPair[1];
		++currStringTablePos;
		if (currStringTablePos >= STRING_TABLE_SIZE)
			currStringTablePos = 0;
	}

	/**
	 * set stringPair to the values referenced by given string reference No checking
	 * is performed.
	 * 
	 * @param ref valid values are 1 .. STRING_TABLE_SIZE
	 */
	private void setStringRefPair(int ref) {
		int pos = currStringTablePos - ref;
		if (pos < 0)
			pos += STRING_TABLE_SIZE;
		stringPair[0] = stringTable[0][pos];
		stringPair[1] = stringTable[1][pos];
	}

	/**
	 * Read version, time stamp and change set and author. We are not interested in
	 * the values, but we have to maintain the string table.
	 */
	private void readVersionTsAuthor() {
		int version = readUnsignedNum32();
		if (version != 0) {
			// version info
			long ts = readSignedNum64() + lastTs;
			lastTs = ts;
			if (ts != 0) {
				long changeSet = readSignedNum32() + lastChangeSet;
				lastChangeSet = changeSet;
				readAuthor();
			}
		}
	}

	/**
	 * Read author. 
	 */
	private void readAuthor() {
		int stringRef = readUnsignedNum32();
		if (stringRef == 0) {
			long toReadStart = bytesToRead;
			long uidNum = readUnsignedNum64();
			if (uidNum == 0)
				stringPair[0] = "";
			else {
				stringPair[0] = Long.toUnsignedString(uidNum);
				ioBufPos++; // skip terminating zero from uid
				--bytesToRead;
			}
			stringPair[1] = readString();
			long bytes = toReadStart - bytesToRead;
			if (bytes <= MAX_STRING_PAIR_SIZE)
				storeStringPair();
		} else {
			setStringRefPair(stringRef);
		}
	}
	
	/**
	 * Read object type ("0".."2") concatenated with role (single string) .
	 * @return 0..3 for type (3 means unknown)
	 */
	private int readRelRef () {
		int refType = -1;
		long toReadStart = bytesToRead;
		int stringRef = readUnsignedNum32();
		if (stringRef == 0) {
			refType = ioBuf[ioBufPos++] - 0x30;
			--bytesToRead;

			if (refType < 0 || refType > 2)
				refType = 3;
			stringPair[0] = REL_REF_TYPES[refType];

			stringPair[1] = readString();
			long bytes = toReadStart - bytesToRead;
			if (bytes <= MAX_STRING_PAIR_SIZE)
				storeStringPair();
		} else {
			setStringRefPair(stringRef);
			char c = stringPair[0].charAt(0);
			switch (c) {
			case 'n': refType = 0; break;
			case 'w': refType = 1; break;
			case 'r': refType = 2; break;
			default: refType = 3;
			}
		}
		return refType;
	}
	
	/**
	 * Read a string pair (see o5m definition).
	 */
	private void readStringPair() {
		int stringRef = readUnsignedNum32();
		if (stringRef == 0) {
			long toReadStart = bytesToRead;
			int cnt = 0;
			while (cnt < 2) {
				stringPair[cnt++] = readString();
			}
			long bytes = toReadStart - bytesToRead;
			if (bytes <= MAX_STRING_PAIR_SIZE)
				storeStringPair();
		} else {
			setStringRefPair(stringRef);
		}
	}
	
	/**
	 * Read a zero-terminated string (see o5m definition).
	 * @throws IOException
	 */
	private String readString() {
		int length = 0;
		while (true) {
			final int b = ioBuf[ioBufPos++];
			--bytesToRead;
			if (b == 0)
				return new String(cnvBuffer, 0, length, StandardCharsets.UTF_8);
			cnvBuffer[length++] = (byte) b;
		}
	}
	
	/** reset the delta values and string table */
	private void reset() {
		lastNodeId = 0; lastWayId = 0; lastRelId = 0;
		lastRef[0] = 0; lastRef[1] = 0;lastRef[2] = 0;
		lastTs = 0; lastChangeSet = 0;
		lastLon = 0; lastLat = 0;
		stringTable = new String[2][STRING_TABLE_SIZE];
		currStringTablePos = 0;
	}

	/**
	 * Read and verify o5m header (known values are o5m2 and o5c2).
	 * @throws IOException in case of error
	 */
	private void readHeader() throws IOException {
		if (ioBuf[0] != 'o' || ioBuf[1] != '5' || (ioBuf[2] != 'c' && ioBuf[2] != 'm') || ioBuf[3] != '2') {
			throw new IOException("unsupported header");
		}
	}
	
	/**
	 * Read a varying length signed number (see o5m definition).
	 * @return the number as int
	 */
	private int readSignedNum32() {
		return (int) readSignedNum64(); 
	}

	/**
	 * Read a varying length signed number (see o5m definition).
	 * @return the number as long
	 */
	private long readSignedNum64() {
		long result;
		int b = ioBuf[ioBufPos++];
		--bytesToRead;
		result = b;
		if ((b & 0x80) == 0) { // just one byte
			if ((b & 0x01) == 1)
				return -1 - (result >> 1);
			return result >> 1;

		}
		int sign = b & 0x01;
		result = (result & 0x7e) >> 1;
		int shift = 6;
		while (((b = ioBuf[ioBufPos++]) & 0x80) != 0) { // more bytes will follow
			--bytesToRead;
			result += ((long) (b & 0x7f)) << shift;
			shift += 7;
		}
		--bytesToRead;
		result += ((long) b) << shift;
		if (sign == 1) // negative
			return -1 - result;
		return result;
	}

	/**
	 * Read a varying length unsigned number (see o5m definition).
	 * 
	 * @return the number as long
	 * @throws IOException in case of I/O error
	 */
	private long readUnsignedNum64FromStream() throws IOException {
		int b = is.read();
		--bytesToRead;
		long result = b;
		if ((b & 0x80) == 0) { // just one byte
			return result;
		}
		result &= 0x7f;
		int shift = 7;
		while (((b = is.read()) & 0x80) != 0) { // more bytes will follow
			--bytesToRead;
			result += ((long) (b & 0x7f)) << shift;
			shift += 7;
		}
		--bytesToRead;
		result += ((long) b) << shift;
		return result;
	}
	
	/**
	 * Read a varying length unsigned number (see o5m definition).
	 * 
	 * @return the number as long
	 */
	private long readUnsignedNum64() {
		int b = ioBuf[ioBufPos++];
		--bytesToRead;
		long result = b;
		if ((b & 0x80) == 0) { // just one byte
			return result;
		}
		result &= 0x7f;
		int shift = 7;
		while (((b = ioBuf[ioBufPos++]) & 0x80) != 0) { // more bytes will follow
			--bytesToRead;
			result += ((long) (b & 0x7f)) << shift;
			shift += 7;
		}
		--bytesToRead;
		result += ((long) b) << shift;
		return result;
	}

	/**
	 * Read a varying length unsigned number (see o5m definition).
	 * 
	 * @return the number as int
	 */
	private int readUnsignedNum32() {
		return (int) readUnsignedNum64();
	}

}
