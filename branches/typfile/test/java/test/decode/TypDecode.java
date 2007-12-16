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
package test.decode;

import uk.me.parabola.imgfmt.app.BufferedReadStrategy;
import uk.me.parabola.imgfmt.app.ReadStrategy;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.imgfmt.sys.FileImgChannel;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Standalone program to decode the TYP file as it is worked out.
 *
 * @author Steve Ratcliffe
 */
public class TypDecode {
	private ReadStrategy reader;
	private PrintStream outStream = System.out;

	public TypDecode(ReadStrategy reader) {
		this.reader = reader;
	}

	private void decode() {
		readCommonHeader();
		readFileHeader();
	}

	private void readFileHeader() {
		Displayer d = new Displayer(reader);
		d.setTitle("TYP Header");

		d.charValue("File header");
		
		d.intValue("Unknown sect5 start %x");
		d.intValue("Unknown sect5 size %d");

		d.intValue("line data start %x");
		d.intValue("line data size %d");

		d.intValue("Unknown sect1 start %x");
		d.intValue("Unknown sect1 size %d");

		d.charValue("product id %x");
		d.charValue("???");

		d.intValue("Unknown sect6 start %x");
		d.charValue("Unknown sect6 item size %d");
		d.intValue("Unknown sect6 size %d");

		d.intValue("Unknown sect3 start %x");
		d.charValue("Unknown sect3 item size %d");
		d.intValue("Unknown sect3 size %d");

		d.intValue("Unknown sect4 start %x");
		d.charValue("Unknown sect4 item size %d");
		d.intValue("Unknown sect4 size %d");

		d.intValue("Polygon stack order start %x");
		d.charValue("Polygon stack order item size %d");
		d.intValue("Polygon stack order size %d");

		d.print(outStream);
	}

	private void readCommonHeader() {

		Displayer d = new Displayer(reader);
		d.setTitle("Common Header");

		d.charValue("Header length %d");

		DisplayItem item = d.item();
		byte[] b = item.setBytes(reader.get(10));
		item.addText("File type %s", new String(b));

		d.byteValue("???");

		d.byteValue("Set if locked");

		d.value(7, "The date");
		
		d.print(outStream);
	}


	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: typdecode <filename>");
			System.exit(1);
		}

		String name = args[0];

		FileInputStream is = null;
		try {
			is = new FileInputStream(name);
			ImgChannel chan = new FileImgChannel(is.getChannel());
			BufferedReadStrategy reader = new BufferedReadStrategy(chan);
			TypDecode decode = new TypDecode(reader);
			decode.decode();
		} catch (FileNotFoundException e) {
			System.err.println("Could not open file: " + name);
			System.exit(1);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					// ok
				}
			}
		}
	}
}
