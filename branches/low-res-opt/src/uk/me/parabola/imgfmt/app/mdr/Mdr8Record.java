/*
 * Copyright (C) 2011.
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

/**
 * This section is a simple index into the streets section (mdr7).
 *
 * @author Steve Ratcliffe
 */
public class Mdr8Record {
	private final char[] prefix;
	private final int recordNumber;

	
	public Mdr8Record(char[] prefix, int recordNumber) {
		this.prefix = prefix;
		this.recordNumber = recordNumber;
	}

	public char[] getPrefix() {
		return prefix;
	}

	public int getRecordNumber() {
		return recordNumber;
	}
}
