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

package uk.me.parabola.imgfmt.mdxfmt;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.log.Logger;

/**
 * The MDX index file.  Used with the global index.  This is located
 * at the family level in the windows registry and can perhaps index
 * across different products (and maybe families), although such a thing
 * hasn't been seen.
 *
 * @author Steve Ratcliffe
 */
public class MdxFile {
	private final char familyId;
	private final char productId;
	
	private final List<MapInfo> maps = new ArrayList<>();

	/**
	 * Create with default family and product ids.
	 * @param familyId The default family id that will be used if no other one
	 * is supplied.
	 * @param productId The default product id for the maps indexed by this
	 * file.
	 */
	public MdxFile(int familyId, int productId) {
		this.familyId = (char) familyId;
		this.productId = (char) productId;
	}

	/**
	 * Add a map with the default family and product id's and with equal
	 * name and hex name.
	 * @param name The map name (from the filename of the map) as an integer.
	 * @param hexname The map id that is inside the TRE header
	 * @param filename The file name of the map being added. Mainly for diagnostics,
	 * it is not needed for the file.
	 */
	public void addMap(int name, int hexname, String filename) {
		MapInfo info = new MapInfo();
		info.setHexMapname(hexname);
		info.setMapname(name);
		info.setFamilyId(familyId);
		info.setProductId(productId);
		info.setFilename(filename);

		maps.add(info);
	}

	/**
	 * Write the file out to the given filename.
	 */
	public void write(String filename) throws IOException {
		try (FileOutputStream stream = new FileOutputStream(filename); FileChannel chan = stream.getChannel();) {
			ByteBuffer buf = ByteBuffer.allocate(1024);
			buf.order(ByteOrder.LITTLE_ENDIAN);

			writeHeader(chan, buf);
			writeBody(chan, buf);
		}
	}

	private void writeHeader(WritableByteChannel chan, ByteBuffer buf) throws IOException {
		buf.put("Midx".getBytes(StandardCharsets.US_ASCII));
		buf.putChar((char) 100);
		buf.putInt(12);
		buf.putInt(maps.size());

		buf.flip();
		chan.write(buf);
	}

	private void writeBody(WritableByteChannel chan, ByteBuffer buf) throws IOException {
		// Sort the maps by the hex number.
		maps.sort((o1, o2) -> Integer.compare(o1.getHexMapname(), o2.getHexMapname()));

		for (MapInfo info : maps) {
			// Although its not necessarily wrong for them to be zero, it probably
			// sign that something is wrong.
			if (info.getHexMapname() == 0 || info.getMapname() == 0)
				Logger.defaultLogger.error("Invalid mapname for " + info.getFilename() + ", perhaps it is not a .img file");

			buf.compact();
			info.write(buf);

			buf.flip();
			chan.write(buf);
		}
	}
}
