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
 * Create date: 07-Dec-2006
 */
package uk.me.parabola.imgfmt.app;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Interface for writing structured data to an img file.
 *
 * Implementations will have a constructor that passes in a file channel that will eventually
 * be written to.  If the output is being buffered, then it should be written on a call to sync().
 *
 * @author Steve Ratcliffe
 */
public interface ImgFileWriter extends Closeable {
	/**
	 * Called to write out any saved buffers.  The strategy may write
	 * directly to the file in which case this would have nothing or
	 * little to do.
	 * @throws IOException If there is an error writing.
	 */
	public void sync() throws IOException;

	/**
	 * Get the position.  Needed because may not be reflected in the underlying
	 * file if being buffered.
	 *
	 * @return The logical position within the file.
	 */
	public int position();

	/**
	 * Set the position of the file.
	 * @param pos The new position in the file.
	 */
	void position(long pos);

	/**
	 * Write out a single byte.
	 * @param b The byte to write.
	 */
	public void put(byte b);

	/**
	 * Write out two bytes.  Done in the correct byte order.
	 * @param c The value to write.
	 */
	public void putChar(char c);

	/**
	 * Write out int in range 0..255 as single byte.
	 * Use instead of put() for unsigned for clarity.
	 * @param val The value to write.
	 */
	public void put1(int val);

	/**
	 * Write out int in range 0..65535 as two bytes in correct byte order.
	 * Use instead of putChar() for unsigned for clarity.
	 * @param val The value to write.
	 */
	public void put2(int val);

	/**
	 * Write out three bytes.  Done in the correct byte order.
	 *
	 * @param val The value to write, only the bottom three bytes will be
	 * written.
	 */
	public void put3(int val);
	
	/**
	 * Write out 1-4 bytes.  Done in the correct byte order.
	 *
	 * @param nBytes The number of bytes to write.
	 * @param val The value to write.
	 */
	public void putN(int nBytes, int val);

	/**
	 * Write out 4 byte value.
	 * @param val The value to write.
	 */
	public void putInt(int val);

	/**
	 * Write out an arbitrary length sequence of bytes.
	 *
	 * @param val The values to write.
	 */
	public void put(byte[] val);

	/**
	 * Write out part of a byte array.
	 *
	 * @param src The array to take bytes from.
	 * @param start The start position.
	 * @param length The number of bytes to write.
	 */
	public void put(byte[] src, int start, int length);

	/**
	 * Write out a complete byte buffer.
	 *
	 * @param src The buffer to write.
	 */
	public void put(ByteBuffer src);

	/**
	 * Returns the size of the file.
	 *
	 * Note that this is not a general purpose routine and it may not be
	 * possible to give the correct answer at all times.
	 * 
	 * @return The file size in bytes.
	 */
	public long getSize();
}
