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
 * Create date: 31-Oct-2007
 */
package uk.me.parabola.imgfmt.app.labelenc;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.SortedMap;

/**
 * @author Steve Ratcliffe
 */
public class AnyCharsetEncoder extends BaseEncoder implements CharacterEncoder {

	//private String charSet;
	private Charset charSet;

	public AnyCharsetEncoder(String cs) {
		prepareForCharacterSet(cs);
		charSet = Charset.forName(cs);
		//SortedMap<String,Charset> smap = Charset.availableCharsets();
		//for (String c : smap.keySet()) {
		//	System.out.println(c);
		//}
	}

	public EncodedText encodeText(String text) {
		if (text == null)
			return NO_TEXT;
		
		if (!isCharsetSupported())
			return simpleEncode(text);

		System.out.println("any charset");
		// Guess that 8859-1 is used in the Garmin.
		byte[] bytes = text.toUpperCase().getBytes(charSet);

		byte[] res = new byte[bytes.length + 1];
		System.arraycopy(bytes, 0, res, 0, bytes.length);

		return new EncodedText(res, res.length);
	}
}
