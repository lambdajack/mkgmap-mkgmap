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
 * Create date: 30-Nov-2006
 */
package uk.me.parabola.imgfmt.sys;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * An entry within a directory.  This holds its name and a list
 * of blocks that go to make up this file.
 *
 * A directory entry may take more than block in the file system.
 *
 * All documentation seems to point to the block numbers having to be
 * contiguous, but seems strange so I shall experiment.
 *
 * @author Steve Ratcliffe
 */
class Dirent implements DirectoryEntry {
	private static final Logger log = Logger.getLogger(Dirent.class);

	// Constants.
	private static final int MAX_FILE_LEN = 8;
	private static final int MAX_EXT_LEN = 3;

	// Filenames are a base+extension
	private String name;
	private String ext;

	// The file size.
	private int size;

	private final BlockManager blockManager;

	// The block table holds all the blocks that belong to this file.  The
	// documentation suggests that block numbers are always contiguous.
	private BlockTable blockTable;

	private boolean special;

	Dirent(String name, BlockManager blockManager) {
		this.blockManager = blockManager;

		int dot;
		dot = name.indexOf('.');
		if (dot >= 0) {
			setName(name.substring(0, dot));
			setExt(name.substring(dot+1));
		} else
			throw new IllegalArgumentException("Filename did not have dot");

		blockTable = new BlockTable(blockManager.getBlockSize());
	}

	/**
	 * Write this entry out to disk.
	 *
	 * @param file The file to write to.
	 * @throws IOException If writing fails for any reason.
	 */
	void sync(ImgChannel file) throws IOException {
		int ntables = blockTable.getNBlockTables();
		ByteBuffer buf = ByteBuffer.allocate(blockManager.getBlockSize() * ntables);
		buf.order(ByteOrder.LITTLE_ENDIAN);

		for (int part = 0; part < ntables; part++) {
			log.debug("position at part", part, "is", buf.position());
			
			buf.put((byte) 1);

			buf.put(Utils.toBytes(name, MAX_FILE_LEN, (byte) ' '));
			buf.put(Utils.toBytes(ext, MAX_EXT_LEN, (byte) ' '));

			// Size is only present in the first part
			if (part == 0) {
				log.debug("dirent", name, '.', ext, "size is going to", size);
				buf.putInt(size);
			} else {
				buf.putInt(0);
			}

			buf.put((byte) (special? 0x3: 0));
			buf.putChar((char) part);

			// Write out the allocation of blocks for this entry.
			buf.position(blockManager.getBlockSize() * part + 0x20);
			blockTable.writeTable(buf, part);
		}

		buf.flip();
		file.write(buf);
	}

	int numberHeaderBlocks() {
		return blockTable.getNBlockTables();
	}

	/**
	 * Get the file name.
	 *
	 * @return The file name.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Get the file extension.
	 *
	 * @return The file extension.
	 */
	public String getExt() {
		return ext;
	}

	/**
	 * Set the file name.  It cannot be too long.
	 *
	 * @param name The file name.
	 */
	private void setName(String name) {
		if (name.length() != MAX_FILE_LEN)
			throw new IllegalArgumentException("File name is wrong size "
			+ "was " + name.length() + ", should be " + MAX_FILE_LEN);
		this.name = name;
	}

	/**
	 * Set the file extension.  Can't be longer than three characters.
	 * @param ext The file extension.
	 */
	private void setExt(String ext) {
		log.debug("ext len" + ext.length());
		if (ext.length() != MAX_EXT_LEN)
			throw new IllegalArgumentException("File extension is wrong size");
		this.ext = ext;
	}

	public String getFullName() {
		return name + '.' + ext;
	}

	/**
	 * Get the file size.
	 *
	 * @return The size of the file in bytes.
	 */
	public int getSize() {
		return size;
	}


	void setSize(int size) {
		log.debug("setting size " + getName() + getExt() + " to " + size);
		this.size = size;
	}

	/**
	 * Add a block without increasing the size of the file.
	 *
	 * @param n The block number.
	 */
	void addBlock(int n) {
		blockTable.addBlock(n);
	}

	/**
	 * Set for the first directory entry that covers the header and directory
	 * itself.
	 *
	 * @param special Set to true to mark as the special first entry.
	 */
	public void setSpecial(boolean special) {
		this.special = special;
	}

	public boolean isSpecial() {
		return special;
	}

	/**
	 * Converts from a logical block to a physical block.  If the block does
	 * not exist then 0xffff will be returned.
	 *
	 * @param lblock The logical block in the file.
	 * @return The corresponding physical block in the filesystem.
	 */
	public int getPhysicalBlock(int lblock) {
		return blockTable.physFromLogical(lblock);
	}

	public BlockManager getBlockManager() {
		return blockManager;
	}
}
