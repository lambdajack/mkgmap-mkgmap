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
package uk.me.parabola.imgfmt.app;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.log.Logger;

import java.util.Date;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Base class for all the img files.  There is a common header that
 * all the sub-files share.  This will be handled in this class.
 * Also provides common services for writing the file.
 * 
 * @author Steve Ratcliffe
 */
public abstract class ImgFile  {
	private static final Logger log = Logger.getLogger(ImgFile.class);

	private WriteStrategy writer;
	private ReadStrategy reader;

	public void close() {
		try {
			sync();
			if (writer != null)
				writer.close();
		} catch (IOException e) {
			log.error("error on file close", e);
		}
	}

	public int position() {
		return writer.position();
	}

	void position(long pos) {
		writer.position(pos);
	}
	
	protected abstract void sync() throws IOException;

	WriteStrategy getWriter() {
		return writer;
	}

	void setWriter(WriteStrategy writer) {
		this.writer = writer;
	}

	ReadStrategy getReader() {
		return reader;
	}

	void setReader(ReadStrategy reader) {
		this.reader = reader;
	}

	/**
	 * Write out a 3 byte value in the correct byte order etc.
	 *
	 * @param val The value to write.
	 */
	public void put3(int val) {
		if (log.isDebugEnabled())
			log.debug("put3", val);
		writer.put((byte) (val & 0xff));
		writer.putChar((char) (val >> 8));
	}

	/**
	 * Write out a 4 byte value.
	 *
	 * @param val The integer value to write.
	 */
	void putInt(int val) {
		writer.putInt(val);
	}

	/**
	 * Write out a 2 byte value.
	 *
	 * @param val The value to write.
	 */
	public void putChar(char val) {
		writer.putChar(val);
	}

	/**
	 * Write out a single byte value.
	 *
	 * @param val The value to write.
	 */
	public void put(byte val) {
		writer.put(val);
	}

	/**
	 * Write out a number of bytes from an array.
	 *
	 * @param val The values to write.
	 */
	void put(byte[] val) {
		writer.put(val);
	}

	/**
	 * Write out a selected section of a byte array.
	 * @param src The source array.
	 * @param start The starting position.
	 * @param length The number to take.
	 */
	public void put(byte[] src, int start, int length) {
		writer.put(src, start, length);
	}

	void setWriteStrategy(WriteStrategy writer) {
		this.writer = writer;
	}

	/**
	 * @deprecated will be new routines via reader.
	 * @param buf
	 * @return
	 */
	protected int get3(ByteBuffer buf) {
		int val;
		val = buf.get() & 0xff;
		val |= (buf.get() & 0xff) << 8;
		val |= buf.get() << 16;
		return val;
	}

	public byte get() throws IOException {
		return reader.get();
	}

	public char getChar() throws IOException {
		return reader.getChar();
	}

	public int getInt() throws IOException {
		return reader.getInt();
	}

	public byte[] get(int len) throws IOException {
		return reader.get(len);
	}
}
