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
 * Create date: Dec 16, 2007
 */
package test.display;

import uk.me.parabola.imgfmt.app.ReadStrategy;

import java.io.PrintStream;

/**
 * @author Steve Ratcliffe
 */
public class LineSectDisplayer {
	private ReadStrategy reader;
	private PrintStream outStream;

	public LineSectDisplayer(ReadStrategy reader, PrintStream outStream) {
		this.reader = reader;
		this.outStream = outStream;
	}

	public void print() {
		Displayer d = new Displayer(reader);
		d.setTitle("Line type styles");

		DisplayItem item = d.item();
		item.addText("A list of line types, and pointers to their styles");

		d.print(outStream);
	}
}
