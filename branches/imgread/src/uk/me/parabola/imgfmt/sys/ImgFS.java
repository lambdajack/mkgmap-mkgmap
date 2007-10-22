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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Date;
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
	private final ImgHeader header;

	// There is only one directory that holds all filename and block allocation
	// information.
	private final Directory directory;

	// The filesystem is responsible for allocating blocks
	private final BlockManager blockManager;

	private FileSystemParam fsParams;

	/**
	 * Private constructor, use the static {@link #createFs} and {@link #openFs}
	 * routines to make a filesystem.
	 *
	 * @param chan The open file.
	 */
	private ImgFS(FileChannel chan) {
		file = chan;
		header = new ImgHeader(chan);
		directory = new Directory(chan);
		blockManager = new BlockManager();
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
		ImgFS fs = new ImgFS(chan);

		// Truncate the file, because extra bytes beyond the end make for a
		// map that doesn't work on the GPS (although its likely to work in
		// other software viewers).
		try {
			chan.truncate(0);
		} catch (IOException e) {
			throw new FileNotWritableException("Failed to truncate file", e);
		}

		ImgHeader h = fs.header;
		h.createHeader(params);
		h.setDirectoryStartBlock(DIRECTORY_START_BLOCK); // could be from params

		// Set the times.
		Date date = new Date();
		h.setCreationTime(date);
		h.setUpdateTime(date);
		h.setDescription(params.getMapDescription());

		// The block manager allocates blocks for files.
		BlockManager bm = fs.blockManager;
		bm.setBlockSize(params.getBlockSize());
		bm.setCurrentBlock(params.getDirectoryStartBlock());

		// Initialise the directory.
		ImgChannel f;
		Directory dir = fs.directory;
		dir.setStartBlock(params.getDirectoryStartBlock());
		try {
			f = fs.create(DIRECTORY_FILE_NAME);
		} catch (FileExistsException e) {
			// Well it shouldn't exist but if it does, all well and good then..
			try {
				f = fs.open(DIRECTORY_FILE_NAME, "w");
				f.position(0);
			} catch (FileNotFoundException e1) {
				// OK give up then...
				throw new FileNotWritableException("Could not create directory", e1);
			}
		}

		ByteBuffer buf = ByteBuffer.allocate(h.getBlockSize());
		for (int i = 0; i < params.getReservedDirectoryBlocks(); i++) {
			try {
				f.write(buf);
			} catch (IOException e) {
				throw new FileNotWritableException("Could not write directory blocks", e);
			}
		}
		f.position(0);
		dir.setFile(f);

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

	//public ImgFS(FileChannel chan, FileSystemParam params) {
	//	this(chan);
	//	blockManager = new BlockManager(params.getBlockSize(), DIRECTORY_START_BLOCK);
	//}


	//private void createFS(String filename, FileSystemParam params) throws FileNotWritableException
	//{
	//	log.info("Creating file system");
	//	RandomAccessFile rafile  ;
	//	try {
	//		rafile = new RandomAccessFile(filename, "rw");
	//	} catch (FileNotFoundException e) {
	//		throw new FileNotWritableException("Could not create file", e);
	//	}
	//
	//	try {
	//		// Truncate the file to zero lenght.  If the new map is shorter than
	//		// the existing data, then the map will not work.
	//		rafile.setLength(0);
	//	} catch (IOException e) {
	//		// It doesn't matter that much.
	//		log.warn("Could not set file length to zero");
	//	}
	//
	//	createFs(rafile.getChannel(), params);
	//}

	//private void createFs(FileChannel chan, FileSystemParam params) {
	//	header = new ImgHeader(file);
	//	header.createHeader();
	//	header.setDirectoryStartBlock(DIRECTORY_START_BLOCK); // could be from params
	//
	//	// Set the times.
	//	Date date = new Date();
	//	header.setCreationTime(date);
	//	header.setUpdateTime(date);
	//
	//	// The block manager allocates blocks for files.
	//	blockManager = new BlockManager(blockSize,
	//			header.getDirectoryStartBlock());
	//
	//	directory = new Directory(file, blockManager);
	//
	//	if (params != null)
	//		setParams(params);
	//
	//	// Initialise the directory.
	//	directory.init();
	//}

	///**
	// * Open an existing img file for reading.
	// * @param filename The filename.
	// */
	//private void read(String filename) throws IOException {
	//	log.info("opening file system");
	//	RandomAccessFile rafile  ;
	//
	//	rafile = new RandomAccessFile(filename, "rw");
	//	file = rafile.getChannel();
	//
	//	header = new ImgHeader(file);
	//
	//	directory = new Directory(file, blockManager);
	//}

	public static FileSystem openFs() {
		return null;
	}

	/**
	 * Create a new file, it must not allready exist.
	 *
	 * @param name The file name.
	 * @return A directory entry for the new file.
	 */
	public ImgChannel create(String name) throws FileExistsException {
		Dirent dir = directory.create(name);

		FileNode f = new FileNode(file, blockManager, dir, "w");
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
		throw new IOException("not implemented yet");
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
}
