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
import uk.me.parabola.imgfmt.FileNotWritableException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.log.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.List;

/**
 * The img file is really a filesystem containing several files.
 * It is made up of a header, a directory area and a data area which
 * occur in the filesystem in that order.
 *
 * @author steve
 */
public class ImgFS implements FileSystem {
	private static final Logger log = Logger.getLogger(ImgFS.class);
	private static final int DIRECTORY_START_BLOCK = 2;

	// The directory is just like any other file, but with a name of 8+3 spaces
	private static final String DIRECTORY_FILE_NAME = "        .   ";

	// This is the read or write channel to the real file system.
	private final FileChannel file;

	// The header contains general information.
	private ImgHeader header;

	// There is only one directory that holds all filename and block allocation
	// information.
	private Directory directory;

	// The filesystem is responsible for allocating blocks
	private BlockManager fileBlockManager;

	private FileSystemParam fsParams;

	/**
	 * Private constructor, use the static {@link #createFs} and {@link #openFs}
	 * routines to make a filesystem.
	 *
	 * @param chan The open file.
	 */
	private ImgFS(FileChannel chan) {
		file = chan;
	}

	/**
	 * Create an IMG file from its external filesystem name and optionally some
	 * parameters.
	 *
	 * @param chan The file channel to write to.
	 * @param params File system parameters.  Can not be null.
	 * @throws FileNotWritableException If the file can not be written to.
	 */
	public static FileSystem createFs(FileChannel chan, FileSystemParam params)
			throws FileNotWritableException
	{
		assert params != null;

		// Truncate the file, because extra bytes beyond the end make for a
		// map that doesn't work on the GPS (although its likely to work in
		// other software viewers).
		try {
			chan.truncate(0);
		} catch (IOException e) {
			throw new FileNotWritableException("Failed to truncate file", e);
		}

		ImgFS fs = new ImgFS(chan);
		fs.initFs(chan, params);

		return fs;
	}

	public static FileSystem createFs(String filename, FileSystemParam params) throws FileNotWritableException {
		RandomAccessFile rafile  ;
		try {
			rafile = new RandomAccessFile(filename, "rw");
			return createFs(rafile.getChannel(), params);
		} catch (FileNotFoundException e) {
			throw new FileNotWritableException("Could not create file", e);
		}
	}

	public static FileSystem openFs(String name) throws FileNotFoundException {
		RandomAccessFile rafile  ;
		try {
			System.out.println("open file " + name);
			rafile = new RandomAccessFile(name, "r");
			System.out.println("rafile " + rafile);
			return openFs(rafile.getChannel());
		} catch (FileNotFoundException e) {
			throw new FileNotFoundException("Could not open file " + e.getMessage());
		}
	}

	private static FileSystem openFs(FileChannel chan) throws FileNotFoundException {
		ImgFS fs = new ImgFS(chan);

		ImgHeader h = fs.header;
		try {
			h.readHeader();
		} catch (IOException e) {
			throw new FileNotFoundException("Could not read header " + e.getMessage());
		}

		return fs;
	}

	/**
	 * Create a new file, it must not allready exist.
	 *
	 * @param name The file name.
	 * @return A directory entry for the new file.
	 */
	public ImgChannel create(String name) throws FileExistsException {
		Dirent dir = directory.create(name, fileBlockManager);

		FileNode f = new FileNode(file, dir, "w");
		return f;
	}

	/**
	 * Open a file.  The returned file object can be used to read and write the
	 * underlying file.
	 *
	 * @param name The file name to open.
	 * @param mode Either "r" for read access, "w" for write access or "rw"
	 *             for both read and write.
	 * @return A file descriptor.
	 * @throws FileNotFoundException When the file does not exist.
	 */
	public ImgChannel open(String name, String mode) throws FileNotFoundException {
		if (name == null || mode == null)
			throw new IllegalArgumentException("null argument");

		// Its wrong to do this as this routine should not throw an exception
		// when the file exists.  Needs lookup().
		if (mode.indexOf('w') >= 0) {
			try {
				DirectoryEntry entry = lookup(name);
			} catch (IOException e) {
				try {
					ImgChannel channel = create(name);
					return channel;
				} catch (FileExistsException e1) {
					// This shouldn't happen as we have already checked.
					FileNotFoundException exception = new FileNotFoundException("Trying to recreate exising file");
					exception.initCause(e1);
					throw exception;
				}
			}
			//return entry;
		}

		throw new FileNotFoundException("File not found because it isn't implemented yet");
	}

	/**
	 * Lookup the file and return a directory entry for it.
	 *
	 * @param name The filename to look up.
	 * @return A directory entry.
	 * @throws IOException If an error occurs reading the directory.
	 */
	public DirectoryEntry lookup(String name) throws IOException {
		if (name == null)
			throw new IllegalArgumentException("null name argument");

		throw new IOException("not implemented");
	}

	/**
	 * List all the files in the directory.
	 *
	 * @return A List of directory entries.
	 * @throws IOException If an error occurs reading the directory.
	 */
	public List<DirectoryEntry> list() throws IOException {
		return directory.getEntries();
	}

	/**
	 * Sync with the underlying file.  All unwritten data is written out to
	 * the underlying file.
	 *
	 * @throws IOException If an error occurs during the write.
	 */
	public void sync() throws IOException {
		header.sync();

		file.position((long) header.getDirectoryStartBlock() * header.getBlockSize());
		directory.sync();
	}

	/**
	 * Close the filesystem.  Any saved data is flushed out.  It is better
	 * to explicitly sync the data out first, to be sure that it has worked.
	 */
	public void close() {
		try {
			sync();
		} catch (IOException e) {
			log.debug("could not sync filesystem");
		}
	}

	private void initFs(FileChannel chan, FileSystemParam params) throws FileNotWritableException {
		// The block manager allocates blocks for files.
		BlockManager bm = new BlockManager(params.getBlockSize(), params.getDirectoryStartBlock());

		// This bit is tricky.  We want to use a regular ImgChannel to write
		// to the header and directory, but to create one normally would involve
		// it already existing, so it is created by hand.

		Dirent dir;
		Directory direct = new Directory();
		try {
			dir = direct.create(DIRECTORY_FILE_NAME, bm);

			FileNode f = new FileNode(chan, dir, "w");
			direct.setFile(f);
			directory = direct;
			header = new ImgHeader(f);
			header.createHeader(params);
		} catch (FileExistsException e) {
			throw new FileNotWritableException("Could not create img file directory", e);
		}

		fileBlockManager = new BlockManager(params.getBlockSize(), params.getReservedDirectoryBlocks());

		assert directory != null && header != null;
	}
}
