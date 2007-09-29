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
 * Create date: 24-Sep-2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.imgfmt.app.Map;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.ExitException;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.mkgmap.reader.overview.OvermapMapDataSource;
import uk.me.parabola.mkgmap.reader.overview.OverviewMap;
import uk.me.parabola.tdbfmt.TdbFile;

/**
 * The new main program.  There can be many filenames to process and 
 * @author Steve Ratcliffe
 */
public class Main implements ArgumentProcessor {
	private static final Logger log = Logger.getLogger(Main.class);

	private OverviewMapMaker overview;
	private FilenameProcessor action;

	public Main() {
		overview = new OverviewMapMaker();

		// The default is to make a map
		action = new MakeMap();
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: mkgmap <file.osm>");
			System.exit(1);
		}

		Main mm = new Main();

		try {
			// Read the command line arguments and process each filename found.
			CommandArgs commandArgs = new CommandArgs(mm);
			commandArgs.readArgs(args);
		} catch (ExitException e) {
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	public void processOption(String opt, String val) {
		log.debug("proc option", opt, val);
	}

	/**
	 * Switch out to the appropriate class to process the filename.
	 *
	 * @param args The command arguments.
	 * @param filename The filename to process.
	 */
	public void processFilename(CommandArgs args, String filename) {
		action.processFilename(args, filename);
	}

	private void makeMap(CommandArgs args, String filename) {
		CreateImgFile imgFile = new CreateImgFile();
		imgFile.makeMap(args, filename);
	}

	private void startMap(CommandArgs args) {
		OverviewMapMaker overviewMapMaker = new OverviewMapMaker();

		//tdb = events.createTdbFile(this);
		overview = new OverviewMapMaker();


	}

	/**
	 * @author Steve Ratcliffe
	 */
	public static class OverviewMapMaker implements MapEvents {
		private OverviewMap overviewSource = new OvermapMapDataSource();

		//private Main overview;
		private TdbFile tdb;

		public void onSourceLoad(LoadableMapDataSource src) {
			overviewSource.addMapDataSource(src);

			//tdb.
			//overview.
		}

		public void onMapComplete(Map map) {
			//map.get

		}

		private TdbFile createTdbFile(Main main) {

			tdb.setProductInfo(12, 1, "OSM map", "OSM map");

			return tdb;
		}

		public void makeOverviewMap(String name) {
			//CommandArgs args = new CommandArgs();
			String[] args1 = {"hello", "world"};
			//args.readArgs(args1, fnproc);
		}

		public void saveTdbFile(String name) {

		}
	}
}
