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
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

import java.io.IOException;
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

	//private final FileChannel file;
	private ImgChannel chan;

	private BlockManager headerBlockManager;

	// The list of files themselves.
	private final Map<String, DirectoryEntry> entries = new LinkedHashMap<String, DirectoryEntry>();

	Directory(BlockManager headerBlockManager) {
		this.headerBlockManager = headerBlockManager;
	}

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

		// Check to see if it is already there.
		if (entries.get(name) != null)
			throw new FileExistsException("File " + name + " already exists");

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

		// The first entry can't really be written until the rest of the directory is
		// so we have to step through once to calculate the size and then again
		// to write it out.
		int blocks = 0;
		for (DirectoryEntry dir : entries.values()) {
			Dirent ent = (Dirent) dir;
			log.debug("ent size", ent.getSize());
			int n = ent.numberHeaderBlocks();
			blocks += n;
		}

		int blockSize = headerBlockManager.getBlockSize();
		int n = blockSize - 0x20;

		int headerBlocksNeeded = (blocks + (n - 1)) / n;
		log.debug("header blocks needed", headerBlocksNeeded);

		for (DirectoryEntry ent : entries.values()) {
			log.debug("wrting ", ent.getFullName(), " at ", chan.position());
			((Dirent) ent).sync(chan);
		}
	}

	public List<DirectoryEntry> getEntries() {
		return new ArrayList<DirectoryEntry>(entries.values());
	}

	public void setFile(ImgChannel chan) {
		this.chan = chan;
	}

	public void setStartBlock(int startBlock) {
		this.startBlock = startBlock;
	}

	/**
	 * Add an entry to the directory.
	 *
	 * @param ent The entry to add.
	 */
	private void addEntry(DirectoryEntry ent) {
		entries.put(ent.getFullName(), ent);
	}
}
