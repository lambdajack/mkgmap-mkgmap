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
package uk.me.parabola.imgfmt.app;

import uk.me.parabola.imgfmt.Utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;

/**
 * The header that is common to all application files within the .img file.
 * It basically contains two things of interest, the size of the header and
 * its type.  The type is usually of the form 'GARMIN.YYY' where YYY is the
 * file extension of the type eg TRE, LBL, RGN etc.
 *
 * @author Steve Ratcliffe
 */
public class CommonHeader {
	public static final int COMMON_HEADER_LEN = 21;
	private static final int TYPE_LEN = 10;

	// The common header contains the length and the type which are set at
	// construction time.
	private int headerLength;
	private String type;

	// Set to 0x80 on locked maps.  We are not interested in creating locked
	// maps, but may be useful to recognise them for completeness.
	private byte lockFlag;

	// A date of creation.
	private Date creationDate;

	// The file that this header belongs to.
	protected final ImgFile imgFile;

	public CommonHeader(ImgFile imgFile, int headerLength, String type) {
		this.headerLength = headerLength;
		this.type = type;
		this.imgFile = imgFile;
	}

	/**
	 * Writes out the header that is common to all the file types.  It should
	 * be called by the sync() methods of subclasses when they are ready.
	 */
	protected void writeCommonHeader()  {
		imgFile.putChar((char) headerLength);
		imgFile.put(Utils.toBytes(type, TYPE_LEN, (byte) 0));
		imgFile.put((byte) 1);  // unknown
		imgFile.put((byte) 0);  // not locked
		byte[] date = Utils.makeCreationTime(new Date());
		imgFile.put(date);
	}

	/**
	 * Read the common header.  It starts at the beginning of the file.
	 */
	protected void readCommonHeader() throws IOException {
		imgFile.position(0);
		headerLength = imgFile.getChar();
		byte[] bytes = imgFile.get(TYPE_LEN);
		try {
			type = new String(bytes, "ascii");
		} catch (UnsupportedEncodingException e) {
			// ascii is supported always, so this can't happen
		}
		imgFile.get(); // ignore
		imgFile.get(); // ignore

		byte[] date = imgFile.get(7);
		creationDate = Utils.makeCreationTime(date);
	}

	public byte getLockFlag() {
		return lockFlag;
	}

	public void setLockFlag(byte lockFlag) {
		this.lockFlag = lockFlag;
	}

	public Date getCreationDate() {
		return creationDate;
	}

	public void setCreationDate(Date creationDate) {
		this.creationDate = creationDate;
	}
}
