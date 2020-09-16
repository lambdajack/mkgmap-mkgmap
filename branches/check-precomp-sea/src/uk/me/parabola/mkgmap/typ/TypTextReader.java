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
package uk.me.parabola.mkgmap.typ;

import java.io.Reader;

import uk.me.parabola.imgfmt.app.typ.TypData;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.scan.SyntaxException;
import uk.me.parabola.mkgmap.scan.TokType;
import uk.me.parabola.mkgmap.scan.Token;
import uk.me.parabola.mkgmap.scan.TokenScanner;

/**
 * Read in a TYP file in the text format.
 *
 * @author Steve Ratcliffe
 */
public class TypTextReader {
	private static final Logger log = Logger.getLogger(TypTextReader.class);

	// As the file is read in, the information is saved into this data structure.
	private final TypData data = new TypData();

	public void read(String filename, Reader r, String charset) {
		TokenScanner scanner = new TokenScanner(filename, r);
		scanner.setCommentChar(null); // the '#' comment character is not appropriate for this file
		scanner.setCharset(charset);

		ProcessSection currentSection = null;

		while (!scanner.isEndOfFile()) {
			Token tok = scanner.nextToken();
			if (tok.getType() == TokType.EOF)
				break;

			// We deal with whole line comments here
			if (tok.isValue(";")) {
				scanner.skipLine();
				continue;
			}

			if (tok.getType() == TokType.SYMBOL) {
				switch (tok.getValue().charAt(0)) {
				case ';':
					scanner.skipLine();
					break;
				case '[':
					ProcessSection newSection = readSectionType(scanner);
					if (currentSection != null)
						currentSection.finish(scanner);
					currentSection = newSection;
					break;
				case '"':
					scanner.skipLine();
					break;
				}
			} else {
				if (currentSection == null)
					throw new SyntaxException(scanner, "Missing section start");

				// Line inside a section
				String name = tok.getValue();

				String sep = scanner.nextValue();
				if (!"=".equals(sep) && !":".equals(sep))
					throw new SyntaxException(scanner, "Expecting '=' or ':' instead of " + sep);

				String value = scanner.readLine();

				currentSection.processLine(scanner, name, value);
			}
			scanner.skipSpace();
		}
	}

	/**
	 * Read the section name and return a section processor for it.
	 *
	 * The input stream must be positioned just after the open bracket of the section name. The closing bracket
	 * is also consumed. The section name is case insensitive.
	 *
	 * Unknown sections result in a processor that ignores all lines in the section.
	 *
	 * @param scanner Input token stream.
	 * @return A section processor to process lines from the section. Returns null if this is
	 * the end of the section rather than the start.
	 */
	private ProcessSection readSectionType(TokenScanner scanner) {
		String sectionName = scanner.nextValue().toLowerCase();
		scanner.validateNext("]"); // Check for closing bracket

		// End of the section, so the processor is reset to null
		if ("end".equals(sectionName)) {
			return null;

		} else if ("_point".equals(sectionName)) {
			return new PointSection(data);
		} else if ("_line".equals(sectionName)) {
			return new LineSection(data);
		} else if ("_polygon".equals(sectionName)) {
			return new PolygonSection(data);
		} else if ("_draworder".equals(sectionName)) {
			return new DrawOrderSection(data);
		} else if ("_icons".equals(sectionName)) {
			return new IconSection(data);
		} else if ("_id".equals(sectionName)) {
			return new IdSection(data);
		} else if ("_comments".equals(sectionName)) {
			// Need to really ignore everything (IgnoreSection tries to parse first bit of each line as name=...)
			Token tstTok;
			do {
				scanner.skipLine(); // after _comments] or testing next line
				tstTok = scanner.nextToken();
				if (tstTok.getType() == TokType.SYMBOL && "[".equals(tstTok.getValue())) {
					tstTok = scanner.nextRawToken();
					if (tstTok.getType() == TokType.TEXT && "end".equalsIgnoreCase(tstTok.getValue())) {
						tstTok = scanner.nextRawToken();
						if (tstTok.getType() == TokType.SYMBOL && "]".equals(tstTok.getValue())) {
							break;
						}
					}
				}
			} while (tstTok.getType() != TokType.EOF);
			return null;
		} else {
			log.warn("Unrecognised section " + sectionName);
			return new IgnoreSection(data);
		}
	}

	public TypData getData() {
		return data;
	}
}
