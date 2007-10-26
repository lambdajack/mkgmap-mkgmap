/*
 * Copyright (C) 2006 Steve Ratcliffe
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
 * Create date: 26-Nov-2006
 */
package uk.me.parabola.imgfmt.sys;

import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The directory.  There is only one directory and it contains the
 * filenames and block information.  On disk each entry is a
 * multiple of the block size.
 *
 * @author Steve Ratcliffe
 */
class Directory {
	private static final Logger log = Logger.getLogger(Directory.class);

	private int startBlock; // The starting block for the directory.
	private int nEntries;

	//private final FileChannel file;
	private ImgChannel dir;

	// The list of files themselves.
	private final Map<String, DirectoryEntry> entries = new LinkedHashMap<String, DirectoryEntry>();
	
	/**
	 * Create a new file in the directory.
	 * 
	 * @param name The file name.  Must be 8+3 characters.
	 * @param blockManager To allocate blocks for the created file entry.
	 * @return The new directory entity.
	 * @throws FileExistsException If the entry already
	 * exists.
	 */
	Dirent create(String name, BlockManager blockManager) throws FileExistsException {
		for (DirectoryEntry e : entries.values()) {
			String name2 = e.getName() + '.' + e.getExt();
			if (name.equals(name2)) {
				throw new FileExistsException("File " + name + " exists");
			}
		}

		Dirent ent = new Dirent(name, blockManager);
		addEntry(ent);

		return ent;
	}

	/**
	 * Write out the directory to the file.  The file should be correctly
	 * positioned by the caller.
	 *
	 * @throws IOException If there is a problem writing out any
	 * of the directory entries.
	 */
	public void sync() throws IOException {

		for (DirectoryEntry ent : entries.values()) {
			log.debug("wrting ent at " + dir.position());
			((Dirent) ent).sync(dir);
		}
	}

	public List<DirectoryEntry> getEntries() {
		return new ArrayList<DirectoryEntry>(entries.values());
	}

	public void setFile(ImgChannel chan) {
		this.dir = chan;
	}

	/**
	 * Add an entry to the directory. This updates the header block allocation
	 * too.
	 *
	 * @param ent The entry to add.
	 */
	private void addEntry(DirectoryEntry ent) {
		nEntries++;

		entries.put(ent.getFullName(), ent);
	}
}
