/*
 * Copyright (C) 2013-2021.
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

/**
 * A class to write the bitstream from left to right.
 *
 * @author Gerd Petermann
 */
package uk.me.parabola.imgfmt.app;

public class BitWriterLR {
	private static final int INITIAL_BUF_SIZE = 20;

	// The byte buffer and its current length (allocated length)
	private byte[] buf;  // The buffer
	private int bufsize;  // The allocated size
	private int buflen; // The actual used length

	// The bit offset into the byte array.
	private int bitoff;
	private static final int BUFSIZE_INC = 50;


	public BitWriterLR() {
		bufsize = INITIAL_BUF_SIZE;
		buf = new byte[bufsize];
	}

	public void put1(boolean bit) {
		if (bitoff >= 8) {
			bitoff = 0;

			buflen++;
			if (buflen >= buf.length) {
				reallocBuffer();
			}
		}
		if (bit) {
			int mask = (1 << (7 - bitoff));
			buf[buflen] |= mask;
		}
		bitoff++;
	}

	public byte[] getBytes() {
		return buf;
	}

	public int getBitPosition() {
		return bitoff;
	}
	/**
	 * Get the number of bytes actually used to hold the bit stream. This therefore can be and usually
	 * is less than the length of the buffer returned by {@link #getBytes()}.
	 * @return Number of bytes required to hold the output.
	 */
	public int getLength() {
		return bitoff == 0 ? buflen : buflen + 1;
	}
	
	/**
	 * Reallocate the byte buffer.
	 */
	private void reallocBuffer() {
		bufsize += BUFSIZE_INC;
		byte[] newbuf = new byte[bufsize];

		System.arraycopy(this.buf, 0, newbuf, 0, this.buf.length);
		this.buf = newbuf;
	}
	
}
