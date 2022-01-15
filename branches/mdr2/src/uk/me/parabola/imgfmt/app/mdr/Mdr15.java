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
package uk.me.parabola.imgfmt.app.mdr;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.log.Logger;

/**
 * The string table. This is not used by the device.
 *
 * There is a compressed and non-compressed version of this section.
 * We are starting with the regular string version.
 *
 * @author Steve Ratcliffe
 */
public class Mdr15 extends MdrSection {
	private final OutputStream stringFile;
	private int nextOffset;
	private int maxOffset;

	private Map<String, Integer> strings = new HashMap<>();
	private final Charset charset;
	private final File tempFile;
	final int[] freqencies = new int[256];
	IntBuffer offsets; 
	Mdr16 mdr16;

	public Mdr15(MdrConfig config) {
		setConfig(config);

		charset = config.getSort().getCharset();
		if (config.isForDevice()) {
			tempFile = null;
			stringFile = null;
			offsets = null;
			return;
		}
		offsets = IntBuffer.allocate(32 * 1024);
		try {
			tempFile = File.createTempFile("strings", null, config.getOutputDir());
			tempFile.deleteOnExit();

			stringFile = new BufferedOutputStream(new FileOutputStream(tempFile), 64 * 1024);

			// reserve the string at offset 0 to be the empty string.
			stringFile.write(0);
			nextOffset = 1;
			freqencies[0]++;
			offsets.put(-1); // place holder for first 0 in mdr15
		} catch (IOException e) {
			throw new ExitException("Could not create temporary file");
		}
	}

	public int createString(String str) {
		Integer offset = strings.get(str);
		if (offset != null)
			return offset;

		int off;
		try {
			off = nextOffset;
			maxOffset = off;

			byte[] bytes = str.getBytes(charset);
			stringFile.write(bytes);
			stringFile.write(0);
			if (mdr16 != null) {
				for (int i = 0; i < bytes.length; i++) {
					freqencies[bytes[i] & 0xff]++;
				}
				freqencies[0]++;
			}
			
			// Increase offset for the length of the string and the null byte
			nextOffset += bytes.length + 1;
			if (mdr16 != null) {
				if (offsets.position() + 1 >= offsets.capacity()) {
					IntBuffer tmp = offsets;
					offsets = IntBuffer.allocate(tmp.capacity() * 2);
					tmp.flip();
					offsets.put(tmp);
				}
				offsets.put(off);
				off = offsets.position() - 1;
			}
		} catch (IOException e) {
			// Can't convert, return empty string instead.
			off = 0;
		}
		strings.put(str, off);
		return off;
	}

	/**
	 * Tidy up after reading files.
	 * Close the temporary file, and release the string table which is no longer
	 * needed.
	 */
	@Override
	public void releaseMemory() {
		strings = null;
		try {
			if (stringFile != null) {
				stringFile.close();
			}
		} catch (IOException e) {
			throw new MapFailedException("Could not close string temporary file");
		}
		// frequencies from adria topo
//		int[] fakeFreq = { 108543, 0, 0, 1, 19, 242, 842, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
//				0, 0, 0, 195, 139214, 18, 614, 0, 0, 1, 153, 95, 2537, 2558, 1905, 7, 2126, 6588, 10184, 64, 6539,
//				13281, 8713, 7478, 7203, 6778, 6634, 5972, 5832, 5314, 1, 1, 0, 0, 0, 2, 0, 181900, 33286, 66619, 37462,
//				99070, 4481, 24930, 12992, 113012, 44778, 63856, 63057, 40220, 79383, 101405, 34866, 80, 95298, 71047,
//				53719, 31985, 63113, 294, 422, 719, 23284, 0, 0, 0, 0, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
//				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 49, 0, 0, 0, 3, 1, 0,
//				0, 2, 4, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
//				0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 2, 0, 43, 0, 34, 4, 0, 51, 0, 0, 0, 0, 5, 0, 0, 0, 0,
//				0, 5, 0, 2, 0, 1, 2, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
//				0, 0, 0, 0, 0, 0, 0, 0 };
//		System.arraycopy(fakeFreq, 0, freqencies, 0, fakeFreq.length);
		
		if (mdr16 != null)
			mdr16.calc(freqencies);
	}

	public void writeSectData(ImgFileWriter writer) {
		offsets.clear();
		try (FileInputStream stream = new FileInputStream(tempFile)) {
			FileChannel channel = stream.getChannel();
			long uncompressed = channel.size();
			ByteBuffer buf = ByteBuffer.allocate(32 * 1024);
			ByteBuffer encoderBuf = ByteBuffer.allocate(1024);
			int start = writer.position();
			while (channel.read(buf) > 0) {
				buf.flip();
				if (mdr16 == null || !mdr16.isValid())
					writer.put(buf);
				else {
					while (buf.remaining() > 0) {
						byte b = buf.get();
						encoderBuf.put(b);
						if (b == 0) {
							offsets.put(writer.position() - start);
							encoderBuf.flip();
							writer.put(mdr16.encode(encoderBuf));
							encoderBuf.clear();
						}
					}
				}
				buf.compact();
			}
			if (mdr16 != null && mdr16.isValid()) {
				maxOffset = offsets.get(offsets.position() - 1);
				int compressed = writer.position() - start;
				Logger.defaultLogger.diagnostic(String.format("compressed/uncompressed MDR 15 size: %d/%d ratio ~%.3f",
						compressed, uncompressed, (double) compressed / uncompressed));
			}
		} catch (IOException e) {
			throw new ExitException("Could not write string section of index");
		}
	}

	public int getItemSize() {
		// variable sized records.
		return 0;
	}

	/**
	 * The meaning of number of items for this section is the largest string
	 * offset possible.  
	 */
	@Override
	public int getSizeForRecord() {
		return Utils.numberToPointerSize(maxOffset);
	}

	/**
	 * There is no use for this as the records are not fixed length.
	 *
	 * @return Always zero, could return the number of strings.
	 */
	protected int numberOfItems() {
		return 0;
	}

	/**
	 * Set reference to compression table
	 * @param mdr16 reference to compression table
	 */
	public void setMdr16(Mdr16 mdr16) {
		this.mdr16 = mdr16;
	}
	
	public int translateOffset(int off) {
		if (mdr16 == null)
			return off;
		return offsets.array()[off];
	}

}
