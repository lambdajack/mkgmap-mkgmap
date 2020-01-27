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
 * Create date: Feb 19, 2008
 */
package uk.me.parabola.imgfmt.app.labelenc;

import java.util.Locale;
import java.nio.charset.StandardCharsets;

/**
 * Encoder for labels in utf-8.
 * 
 * @author Steve Ratcliffe
 */
public class Utf8Encoder extends BaseEncoder implements CharacterEncoder {
	
	public EncodedText encodeText(String text) {
		if (text == null || text.isEmpty())
			return NO_TEXT;

		String uctext;
		if (isUpperCase())
			uctext = text.toUpperCase(Locale.ENGLISH);
		else
			uctext = text;

		EncodedText et;
		byte[] buf = uctext.getBytes(StandardCharsets.UTF_8);
		byte[] res = new byte[buf.length + 1];
		System.arraycopy(buf, 0, res, 0, buf.length);
		res[buf.length] = 0;
		et = new EncodedText(res, res.length, uctext.toCharArray());
		return et;
	}
}
