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

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.ExitException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * The new main program.  There can be many filenames to process and there can
 * be differing outputs determined by options.  So the actual work is mostly
 * done in other classes.  This one just works out what is wanted.
 *
 * @author Steve Ratcliffe
 */
public class Main implements ArgumentProcessor {
	private static final Logger log = Logger.getLogger(Main.class);

	//private OverviewMapMaker overview;
	private final MapProcessor maker = new MapMaker();
	//private final MapProcessor reader = new MapReader();

	private boolean doGmapsupp;
	private boolean doTdbfile;

	// The filenames that will be used in pass2.
	private List<String> filenames = new ArrayList<String>();

	private Map<String, MapProcessor> processMap = new HashMap<String, MapProcessor>();

	/**
	 * The main program to make or combine maps.  We now use a two pass process,
	 * first going through the arguments and make any maps and collect names
	 * to be used for creating summary files like the TDB and gmapsupp.
	 *
	 * @param args The command line arguments.
	 */
	public static void main(String[] args) {

		// We need at least one argument.
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

	public void startOptions() {
		MapProcessor saver = new NameSaver();
		processMap.put("img", saver);
		processMap.put("typ", saver);
	}

	/**
	 * Switch out to the appropriate class to process the filename.
	 *
	 * @param args The command arguments.
	 * @param filename The filename to process.
	 */
	public void processFilename(CommandArgs args, String filename) {
		String ext = extractExtension(filename);
		log.debug("file", filename, ", extension is", ext);

		MapProcessor mp = mapMaker(ext);
		String output = mp.makeMap(args, filename);
		filenames.add(output);
	}

	private MapProcessor mapMaker(String ext) {
		MapProcessor mp = processMap.get(ext);
		if (mp == null)
			mp = maker;
		return mp;
	}

	public void processOption(String opt, String val) {
		log.debug("option:", opt, val);

		if (opt.equals("number-of-files")) {

			// This option always appears first.  We use it to turn on/off
			// generation of the overview files if there is only one file
			// to process.
			int n = Integer.valueOf(val);
			if (n > 1)
				doTdbfile = true;
		} else if (opt.equals("tdbfile")) {
			doTdbfile = true;
		} else if (opt.equals("gmapsupp")) {
			doGmapsupp = true;
		}
	}

	public void endOfOptions() {
		if (!doGmapsupp && !doTdbfile)
			return;

		for (String file : filenames) {
			System.out.println("do file " + file);
		}
	}

	private String extractExtension(String filename) {
		String[] parts = filename.toLowerCase(Locale.ENGLISH).split("\\.");
		List<String> ignore = Arrays.asList("gz", "bz2", "bz");

		// We want the last part that is not gz, bz etc (and isn't the first part ;)
		for (int i = parts.length - 1; i > 0; i--) {
			String ext = parts[i];
			if (!ignore.contains(ext))
				return ext;
		}
		return "";
	}

	/**
	 * A null implementation that just returns the input name as the output.
	 */
	private static class NameSaver implements MapProcessor {
		public String makeMap(CommandArgs args, String filename) {
			return filename;
		}
	}
}
