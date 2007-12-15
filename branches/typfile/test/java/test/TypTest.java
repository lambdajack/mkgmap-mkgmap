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
package test;

import uk.me.parabola.imgfmt.app.CommonHeader;
import static uk.me.parabola.imgfmt.app.CommonHeader.COMMON_HEADER_LEN;
import uk.me.parabola.imgfmt.app.ReadStrategy;
import uk.me.parabola.imgfmt.app.TYPFile;
import uk.me.parabola.imgfmt.app.TYPHeader;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.imgfmt.sys.FileImgChannel;

import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;

/**
 * @author Steve Ratcliffe
 */
public class TypTest {

	public static void main(String[] args) throws FileNotFoundException {
		String name = args[0];

		RandomAccessFile raf = new RandomAccessFile(name, "r");
		FileChannel channel = raf.getChannel();

		ImgChannel typ = new FileImgChannel(channel);

		TYPFile typFile = new TYPFile(typ, false);
		CommonHeader header = typFile.getHeader();

		printHeader(header);

		ReadStrategy reader = typFile.getReader();
		printBody(reader);
	}

	private static void printBody(ReadStrategy reader) {
		int pos = 91;
		for (; ; pos++) {
			reader.position(pos);
			byte b = reader.get();

			System.out.printf("%#06x  %-10s: ", pos, "???");
			System.out.printf("%#04x", b);
			System.out.printf("\n");

		}
	}

	private static void printHeader(CommonHeader header) {
		System.out.println("Common Header");
		System.out.printf("%-10s: %s\n", "Type", header.getType());
		System.out.printf("%-10s: %d\n", "Length", header.getHeaderLength());
		System.out.printf("%-10s: %s\n", "Date", header.getCreationDate());

		printHeader((TYPHeader) header);
	}

	// 0x27  Offset of first thing after header, could be up to 4 bytes
	// 0x2f  product id?
	// 0x51  ptr to start of something else, looks like it ends 0xdd or d8

	private static void printHeader(TYPHeader header) {
		byte[] un = header.getUnknown();

		int off = COMMON_HEADER_LEN;

		System.out.println("\nFile header");

		off = printUnknown(off, un, 0x1f);
		int value = getInt(un, 0x1f);
		int size = getInt(un, 0x23);
		//off = printInt(off, "Line def offset", value);
		//off = printInt(off, "Line def size", size);
		Section section = Section.addSection("Lines", value, size);
		off = printSection(off, "sect1", section);

		value = getInt(un, 0x27);
		size = getInt(un, 0x2b);
		//off = printInt(off, "u sect1", value);
		//off = printInt(off, "u sect1 size?", size);
		section = Section.addSection("u sect1", value, size);
		off = printSection(off, "sect2", section);

		char cvalue = getShort(un, 0x2f);
		off = printUShort(off, "product", cvalue);
		
		off = printUnknown(off, un, 0x3d);

		value = getInt(un, 0x3d);
		off = printInt(off, "u sect3", value);

		off = printUnknown(off, un, 0x51);

		value = getInt(un, 0x51);
		off = printInt(off, "polygon stack", value);

		off = printUnknown(off, un, 0x5b);


		System.out.println("end offset is " + Integer.toHexString(off));
		System.exit(0);
	}

	/**
	 * print a section.  Allows us to print the end offset which may help in
	 * finding other sections.
	 * @param off Offset to print.
	 * @param desc The description.
	 * @param sect The section to print.
	 * @return The new offset.
	 */
	private static int printSection(int off, String desc, Section sect) {
		printOffDesc(off, desc);
		System.out.format("Off: %8x,    Next: %8x\n", sect.getOffset(),
				sect.getOffset()+sect.getLen());

		return off + 8;
	}

	private static int printUnknown(int startoff, byte[] un, int end) {
		int commLen = COMMON_HEADER_LEN;
		int off = startoff;
		for (int i = startoff-commLen; i < end-commLen; i++) {
			int b = un[i] & 0xff;
			int c = b;
			if (i < un.length - 2)
				c = b | ((un[i+1] & 0xff) << 8);
			int i3 = c;
			if (i < un.length - 3)
				i3 = c | ((un[i+2] & 0xff) << 16);
			int i4 = i3;
			if (i < un.length - 4)
				i4 = i3 | ((un[i+3] & 0xff) << 24);
			printOffDesc(off++, "???");
			System.out.printf(
					"%#04x (%3d) %#06x (%5d) %#08x (%8d) %#010x (%10d)\n",
					b, b, c, c, i3, i3, i4, i4);
		}
		return off;
	}

	private static int printInt(int offset, String desc, int value) {
		printOffDesc(offset, desc);
		System.out.printf("%08x (%d)\n", value, value);
		return offset + 4;
	}

	private static int printUShort(int offset, String desc, char cvalue) {
		printOffDesc(offset, desc);
		System.out.printf("%04x (%d)\n", (int) cvalue, (int) cvalue);
		return offset + 2;
	}

	private static void printOffDesc(int offset, String desc) {
		System.out.printf("%#06x:  %-20s: ", offset, desc);
	}

	private static int getInt(byte[] un, int off) {
		int ind = off - COMMON_HEADER_LEN;
		return (un[ind] & 0xff)
				+ (((un[ind+1]) & 0xff) << 8)
				+ (((un[ind+2]) & 0xff) << 16)
				+ (((un[ind+3]) & 0xff) << 24)
				;
	}

	private static char getShort(byte[] un, int off) {
		int ind = off - COMMON_HEADER_LEN;
		return (char) ((un[ind] & 0xff)
				+ (((un[ind+1]) & 0xff) << 8)
		);
	}

	static class Section {

		private String description;
		private int offset;
		private int len;
		private static List<Section> list = new ArrayList<Section>();

		public static Section addSection(String desc, int off, int size) {
			Section section = new Section();
			section.description = desc;
			section.offset = off;
			section.len = size;

			list.add(section);
			return section;
		}

		public static List<Section> getList() {
			return list;
		}

		public String getDescription() {
			return description;
		}

		public int getOffset() {
			return offset;
		}

		public int getLen() {
			return len;
		}
	}
}
