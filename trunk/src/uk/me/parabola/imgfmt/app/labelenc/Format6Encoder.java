/*
 * Copyright (C) 2007,2014 Steve Ratcliffe
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
 * Create date: 14-Jan-2007
 */
package uk.me.parabola.imgfmt.app.labelenc;

import java.text.Normalizer;

/**
 * Format according to the '6 bit' .img format.
 * Any letter with a diacritic or accent is replaced with its base letter.
 * Characters from other alphabets are transliterated if resources/chars/ascii/ data exists.
 * If set/forceUpperCase (mkgmap default), the text is upper-cased by the transliterator.
 * NB lower-case is supported but each lower-case letter needs 12 bits, so, with typical OSM data,
 * almost any other code-page will be more compact
 *
 * For example Körnerstraße would become KORNERSTRASSE,
 * Řípovská would become RIPOVSKA etc.
 *
 * I believe that some Garmin units are only capable of showing uppercase
 * ascii characters, so this will be the default.
 *
 * @author Steve Ratcliffe
 * @see <a href="http://garmin-img.sf.net">Garmin IMG File Format</a>
 */
public class Format6Encoder extends BaseEncoder implements CharacterEncoder {

	// Following are swapped in the above John Mechalas document, but this is what works:
	private static final int LOWERCASE_SHIFT = 0x1b;
	private static final int SYMBOL_SHIFT    = 0x1c;

	public static final String LETTERS =
		" ABCDEFGHIJKLMNO" +	// 0x00-0x0F
		"PQRSTUVWXYZxx\u001d\u001e\u001f" +	// 0x10-0x1F  xx are above SHIFTs. prefix/suffix indicators
		"0123456789\u0001\u0002\u0003\u0004\u0005\u0006";	// 0x20-0x2F  digits + shields

	public static final String SYMBOLS =
		"@!\"#$%&'()*+,-./" +	// 0x00-0x0F
		"          :;<=>?" +	// 0x10-0x1F
		"°          [\\]^_";	// 0x20-0x2F
	//   ^ looks like degree (\u00b0) on MapSource/eTrex. Won't happen as transliterated to "deg"
	//   0123456789abcdef
	public static final String LOWERCASE =
		"`abcdefghijklmno" +	// 0x00-0x0F  back-tick
		"pqrstuvwxyz{|}~ " +	// 0x10-0x1F
		"           \u001b\u001c   ";     // 0x20-0x2F  more prefix/suffix indicators

	private final Transliterator transliterator;

	public Format6Encoder() {
		transliterator = new TableTransliterator("ascii");
	}

	/**
	 * Encode the text into the 6 bit format.  See the class level notes.
	 *
	 * @param text The original text, which can contain non-ascii characters.
	 * @return Encoded form of the text.  Only uppercase ascii characters and
	 * some escape sequences will be present.
	 */
	public EncodedText encodeText(String text) {
		if (text == null || text.isEmpty())
			return NO_TEXT;
		String normalisedText = Normalizer.normalize(text, Normalizer.Form.NFC);
		String s = transliterator.transliterate(normalisedText);  // it does the upper if forceUpper

		// Allocate more than enough space on average for the label.
		// if you overdo it then it will waste a lot of space , but
		// not enough and there will be an error
		byte[] buf = new byte[2 * s.length() + 4];
		int off = 0;

		for (char c : s.toCharArray()) {

			if (c == ' ') {
				put6(buf, off++, 0);
			} else if (c >= 'A' && c <= 'Z') {
				put6(buf, off++, c - 'A' + 1);
			} else if (c >= '0' && c <= '9') {
				put6(buf, off++, c - '0' + 0x20);
			} else if (c == 0x1b || c == 0x1c) {  // shiftedLowerCase() does same thing
				put6(buf, off++, LOWERCASE_SHIFT);
				put6(buf, off++, c + 0x10);
			} else if (c >= 0x1d && c <= 0x1f) {
				put6(buf, off++, c);
			} else if (c >= 1 && c <= 6) {
				// Highway shields
				put6(buf, off++, 0x29 + c);
			} else if (c >= 'a' && c <= 'z') {
				put6(buf, off++, LOWERCASE_SHIFT);
				put6(buf, off++, c - 'a' + 1);
			} else {
				int rememberOff = off;
				off = shiftedSymbol(buf, off, c);
				if (off == rememberOff)
					off = shiftedLowerCase(buf, off, c);
			}
		}

		buf = put6(buf, off++, 0xff);

		int len = ((off - 1) * 6) / 8 + 1;

		char[] chars = s.toCharArray();
		return new EncodedText(buf, len, chars);
	}

	/**
	 * Certain characters have to be represented by two 6byte quantities.  This
	 * routine sorts these out.
	 *
	 * @param buf The buffer to write into.
	 * @param startOffset The offset to start writing to in the output buffer.
	 * @param c The character that we are decoding.
	 * @return The final offset.  This will be unchanged if there was nothing
	 * written because the character does not have any representation.
	 */
	private int shiftedSymbol(byte[] buf, int startOffset, char c) {
		int off = startOffset;
		int ind = SYMBOLS.indexOf(c);
		if (ind >= 0) {
			put6(buf, off++, SYMBOL_SHIFT);
			put6(buf, off++, ind);
		}
		return off;
	}

	private int shiftedLowerCase(byte[] buf, int startOffset, char c) {
		int off = startOffset;
		int ind = LOWERCASE.indexOf(c);
		if (ind >= 0) {
			put6(buf, off++, LOWERCASE_SHIFT);
			put6(buf, off++, ind);
		}
		return off;
	}

	/**
	 * Each character is packed into 6 bits.  This keeps track of everything so
	 * that the character can be put into the right place in the byte array.
	 *
	 * @param buf The buffer to populate.
	 * @param off The character offset, that is the number of the six bit
	 * character.
	 * @param c The character to place.
	 */
	private byte[] put6(byte[] buf, int off, int c) {
		int bitOff = off * 6;

		// The byte offset
		int byteOff = bitOff/8;

		// The offset within the byte
		int shift = bitOff - 8*byteOff;

		int mask = 0xfc >> shift;
		buf[byteOff] |= ((c << 2) >> shift) & mask;

		// IF the shift is greater than two we have to put the rest in the
		// next byte.
		if (shift > 2) {
			mask = 0xfc << (8 - shift);
			buf[byteOff + 1] = (byte) (((c << 2) << (8 - shift)) & mask);
		}

		return buf;
	}

	@Override
	public void setUpperCase(boolean upperCase) {
		super.setUpperCase(upperCase);
		transliterator.forceUppercase(upperCase);
	}
}
