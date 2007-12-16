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
import java.util.List;
import java.util.ArrayList;

/**
 * Displays data in a manner similar to imgdecode written by John Mechalas.
 *
 * So we have an address on the left, undecoded bytes in the middle and
 * the decoded text and explination on the right.
 *
 * @author Steve Ratcliffe
 */
public class Displayer {
	private static final String SEPARATOR = "---------------------------------"
			+ "---------------------------------------------------------------"
			+ "---------------------------------------------------------------"
			;
	private static final int TABLE_WIDTH = 80;

	private String title;
	private List<DisplayItem> items = new ArrayList<DisplayItem>();

	private ReadStrategy reader;

	public Displayer(ReadStrategy reader) {
		this.reader = reader;
	}

	public void print(PrintStream writer) {
		printTitle(writer);
		for (DisplayItem item : items) {
			item.print(writer);
		}
	}

	private void printTitle(PrintStream writer) {
		if (title == null)
			return;

		int leadin = 9;
		writer.printf("%s ", SEPARATOR.substring(0, leadin));
		writer.print(title);
		writer.printf(" %s", SEPARATOR.substring(0, TABLE_WIDTH - leadin - title.length() - 2));
		writer.println();
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public DisplayItem item() {
		DisplayItem item = new DisplayItem();
		item.setStartPos(reader.position());

		items.add(item);
		
		return item;
	}

	public void byteValue(String text) {
		DisplayItem item = item();
		int val = item.setBytes(reader.get());
		item.addText(text, val);
	}

	public void charValue(String text) {
		DisplayItem item = item();
		int val = item.setBytes(reader.getChar());
		item.addText(text, val);
	}

	public void intValue(String text) {
		DisplayItem item = item();
		int val = item.setBytes(reader.getInt());
		item.addText(text, val);
	}

	public void rawValue(int n, String text) {
		DisplayItem item = item();
		item.setBytes(reader.get(n));
		item.addText(text);
	}

	public void stringValue(int n, String text) {
		DisplayItem item = item();
		byte[] b = item.setBytes(reader.get(n));
		item.addText(text, new String(b));
	}
}
