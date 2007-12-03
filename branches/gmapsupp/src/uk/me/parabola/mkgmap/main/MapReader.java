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
 * Create date: Dec 3, 2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.imgfmt.app.InternalFiles;
import uk.me.parabola.imgfmt.app.RGNFile;
import uk.me.parabola.imgfmt.app.LBLFile;
import uk.me.parabola.imgfmt.app.TREFile;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.fs.ImgChannel;
import uk.me.parabola.imgfmt.sys.ImgFS;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Steve Ratcliffe
 */
public class MapReader implements MapProcessor {
	private List<MapEventListener> mapListeners = new ArrayList<MapEventListener>();

	public void addMapListener(MapEventListener l) {
		mapListeners.add(l);
	}

	/**
	 * Process the given filename.
	 *
	 * @param args The user supplied arguments.
	 * @param filename The name of a file that was given to the program, eg
	 */
	public void processFilename(CommandArgs args, String filename) {
		try {
			FileSystem fs = ImgFS.openFs(filename);
			List<DirectoryEntry> entries = fs.list();

			ReadingFiles files = new ReadingFiles();
			for (DirectoryEntry ent : entries) {
				String name = ent.getFullName();
				ImgChannel channel = fs.open(name, "r");

				if (ent.getExt().equals("TRE")) {
					//files.tre = channel;
					// XXX we just need the sizes here.
				} else if (ent.getExt().equals("RGN")) {

				} else if (ent.getExt().equals("LBL")) {

				}
			}

		} catch (FileNotFoundException e) {
			System.err.println("Could not open " + filename);
		}
	}

	/**
	 * Called when all the command line options have been processed.
	 */
	public void endOfOptions() {
	}

	private void fireMapEnd() {

	}

	private static class ReadingFiles implements InternalFiles {
		private RGNFile rgn;
		private TREFile tre;
		private LBLFile lbl;

		public RGNFile getRgnFile() {
			return rgn;
		}

		public LBLFile getLblFile() {
			return lbl;
		}

		public TREFile getTreFile() {
			return tre;
		}

	}
}
