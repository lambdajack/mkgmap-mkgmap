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
 * Create date: Dec 4, 2007
 */
package uk.me.parabola.imgfmt.app;

import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.sys.ImgFS;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.log.Logger;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * A class to represent reading of an img file.  As the focus of mkgmap is on
 * writing files, this is not as complete as the MapWriter class, and it is
 * mainly designed to create the extra files that are required to combine
 * map tiles.
 *
 * @author Steve Ratcliffe
 */
public class MapReader {
	private static final Logger log = Logger.getLogger(MapReader.class);
	
	private String filename;
	private String description;
	private Area area;

	// These are particularly subject to change and should not be exposed outside this class.
	private ImgChannel treChan;
	private ImgChannel rgnChan;
	private ImgChannel lblChan;

	private FileSystem imgFs;

	public MapReader(String filename) throws FileNotFoundException {
		this.filename = filename;
		imgFs = ImgFS.openFs(filename);

		FileSystemParam params = imgFs.fsparam();
		log.info("Desc", params.getMapDescription());
		log.info("Blocksize", params.getBlockSize());

		description = params.getMapDescription();
		//area = params.
		
		List<DirectoryEntry> entries = imgFs.list();
		for (DirectoryEntry ent : entries) {
			if (ent.isSpecial())
				continue;
			
			log.info("file", ent.getFullName());
		}
	}
}
