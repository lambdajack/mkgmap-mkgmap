/*
 * Copyright (C) 2008 Steve Ratcliffe
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package uk.me.parabola.imgfmt.app.net;

/**
 * Exception to throw when we detect that we do not know how to encode a particular case.
 * This should not be thrown any more, when the preparers is called correctly.
 *
 * If it is, then the number preparer is marked as invalid and the data is not written to the
 * output file.
 */
class Abandon extends RuntimeException {
	private static final long serialVersionUID = 1L;

	Abandon(String message) {
		super("HOUSE NUMBER RANGE: " + message);
	}
}