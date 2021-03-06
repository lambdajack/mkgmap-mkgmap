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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.ReadFailedException;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

/**
 * Read from an img file via a buffer.
 *
 * @author Steve Ratcliffe
 */
public class BufferedImgFileReader implements ImgFileReader {
	private static final Logger log = Logger.getLogger(BufferedImgFileReader.class);

	// Buffer size, must be a power of 2
	private static final int BUF_SIZE = 0x1000;

	private final ImgChannel chan;

	// The buffer that we read out of
	private final ByteBuffer buf = ByteBuffer.allocate(BUF_SIZE);
	private long bufStart;
	private int bufSize = -1;

	// We keep our own idea of the file position.
	private long position;
	// the position of the header, normally 0, but not in GMP format
	private final int gmpOffset;

	public BufferedImgFileReader(ImgChannel chan) {
		this(chan, 0);
	}

	public BufferedImgFileReader(ImgChannel chan, int gmpOffset) {
		this.chan = chan;
		this.gmpOffset = gmpOffset;
//		buf.order(ByteOrder.LITTLE_ENDIAN);
// could use getShort/getChar/getInt if sure buffer loaded		
	}

	@Override
	public int getGMPOffset() {
		return gmpOffset;
	}

	/**
	 * Called when the stream is closed.  Any resources can be freed.
	 *
	 * @throws IOException When there is an error in closing.
	 */
	public void close() throws IOException {
		chan.close();
	}

	/**
	 * Get the position.  Needed because may not be reflected in the underlying
	 * file if being buffered.
	 *
	 * @return The logical position within the file.
	 */
	public long position() {
		return position;
	}

	/**
	 * Set the position of the file.
	 *
	 * @param pos The new position in the file.
	 */
	public void position(long pos) {
		position = pos;
	}

	/**
	 * Read in a single byte from the current position.
	 * Should not be used for reading numbers, use get1s/u instead.
	 * @return The byte that was read.
	 */
	public byte get() throws ReadFailedException {
		// Check if the current position is within the buffer
		fillBuffer();

		int pos = (int) (position - bufStart);
		if (pos >= bufSize)
			return 0; // XXX do something else
// doing following makes test/func/files/GmapsuppTest.java test fail 		
//			throw new MapFailedException("buffered input unavailable");

		position++;
		return buf.get(pos);
	}

	/**
	 * Read in a single byte from the current position.
	 * @return int sign-extended value that was read.
	 */
	public int get1s() throws ReadFailedException {
		return get();
	}

	/**
	 * Read in two bytes.  Done in the correct byte order.
	 * @return int sign-extended value that was read.
	 */
	public int get2s() throws ReadFailedException {
		byte b1 = get();
		byte b2 = get();
		return (b1 & 0xff)
				| (b2 << 8)
				;
	}

	/**
	 * Read a three byte signed quantity.
	 * @return int sign-extended value that was read.
	 * @throws ReadFailedException
	 */
	public int get3s() throws ReadFailedException {
		// Slow but sure implementation
		byte b1 = get();
		byte b2 = get();
		byte b3 = get();
		return (b1 & 0xff)
				| ((b2 & 0xff) << 8)
				| (b3 << 16)
				;
	}

	public int get1u() throws ReadFailedException {
		return get() & 0xff;
	}

	/**
	 * Read in two bytes.  Done in the correct byte order.
	 *
	 * @return The 2 byte integer that was read.
	 */
	public int get2u() throws ReadFailedException {
		byte b1 = get();
		byte b2 = get();
		return (b1 & 0xff)
				| ((b2 & 0xff) << 8)
				;
	}

	/**
	 * Read a three byte signed quantity.
	 * @return The read value.
	 * @throws ReadFailedException
	 */
	public int get3u() throws ReadFailedException {
		// Slow but sure implementation
		byte b1 = get();
		byte b2 = get();
		byte b3 = get();
		return (b1 & 0xff)
				| ((b2 & 0xff) << 8)
				| ((b3 & 0xff) << 16)
				;
	}


	public int getNu(int nBytes) throws ReadFailedException {
		switch (nBytes) {
		case 1: return get1u();
		case 2: return get2u();
		case 3: return get3u();
		case 4: return get4();
		default: // this is a programming error so exit
			throw new MapFailedException("bad integer size " + nBytes);
		}
	}

	/**
	 * Read in a 4 byte signed value.
	 *
	 * @return A 4 byte integer.
	 */
	public int get4() throws ReadFailedException {
		byte b1 = get();
		byte b2 = get();
		byte b3 = get();
		byte b4 = get();
		return (b1 & 0xff)
				| ((b2 & 0xff) << 8)
				| ((b3 & 0xff) << 16)
				|  (b4 << 24)
				;
	}
	/**
	 * Read in an arbitrary length sequence of bytes.
	 *
	 * @param len The number of bytes to read.
	 */
	public byte[] get(int len) throws ReadFailedException {
		byte[] bytes = new byte[len];

		// Slow but sure implementation.
		for (int i = 0; i < len; i++) {
			bytes[i] = get();
		}
		return bytes;
	}

	/**
	 * Read a zero terminated string from the file, still as raw bytes.
	 *
	 * @return A byte array containing the encoded representation of the string.
	 * @throws ReadFailedException For failures.
	 */
	public byte[] getZString() throws ReadFailedException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// Slow but sure implementation.
		for (byte b = get(); b != 0; b = get()) {
			out.write(b);
		}
		return out.toByteArray();
	}

	/**
	 * Read in a string of digits in the compressed base 11 format that is used
	 * for phone numbers in the POI section.
	 * @param delimiter This will replace all digit 11 characters.  Usually a
	 * '-' to separate numbers in a telephone.  No doubt there is a different
	 * standard in each country.
	 * @return A phone number possibly containing the delimiter character.
	 */
	public String getBase11str(byte firstChar, char delimiter) {
		// NB totally untested.
		StringBuilder str11 = new StringBuilder();
		int term = 2;

		int ch = firstChar & 0xff;
		do {
			assert !(str11.length() == 0 && (ch & 0x80) == 0);

			if ((ch & 0x80) != 0)
				--term;
			str11.append(base(ch & 0x7F, 11, 2));
			if (term != 0)
				ch = get();
		} while (term != 0);

		// Remove any trailing delimiters
		while (str11.length() > 0 && str11.charAt(str11.length()-1) == 'A')
			str11.setLength(str11.length()-1);

		// Convert in-line delimiters to the char delimiter
		int len = str11.length();
		for (int i = 0; i < len; i++) {
			if (str11.charAt(i) == 'A')
				str11.setCharAt(i, delimiter);
		}

		return str11.toString();
	}

	private String base(int inNum, int base, int width) {
		int num = inNum;
		StringBuilder val = new StringBuilder();

		if (base < 2 || base > 36 || width < 1)
			return "";

		String digit = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		while (num != 0) {
			val.append(digit.charAt(num % base));
			num /= base;
		}

		while (val.length() < width)
			val.append('0');

		val.reverse();
		return val.toString();
	}

	/**
	 * Check to see if the buffer contains the byte at the current position.
	 * If not then it is re-read so that it does.
	 *
	 * @throws ReadFailedException If the buffer needs filling and the file cannot be
	 * read.
	 */
	private void fillBuffer() throws ReadFailedException {
		// If we are no longer inside the buffer, then re-read it.
		if (position < bufStart || position >= bufStart + bufSize) {

			// Get channel position on a block boundary.
			bufStart = position & ~(BUF_SIZE - 1);
			chan.position(bufStart);
			log.debug("reading in a buffer start=", bufStart);

			// Fill buffer
			buf.clear();
			bufSize = 0;
			try {
				bufSize = chan.read(buf);
			} catch (IOException e) {
				throw new ReadFailedException("failed to fill buffer", e);
			}

			log.debug("there were", bufSize, "bytes read");
		}
	}
}
