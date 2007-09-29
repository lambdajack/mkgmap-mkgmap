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
 * Create date: 01-Jan-2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.ExitException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Command line arguments for Main.
 * Since it is likely that the number of options will become quite large, you
 * can place options in a file and have options given on the command line over-
 * ride them.
 *
 * @author Steve Ratcliffe
 */
class CommandArgs {
	private static final Logger log = Logger.getLogger(CommandArgs.class);

	private static final Properties defaults;
	static {
		defaults = new Properties();
		defaults.setProperty("overview-name", "63240000");
		defaults.setProperty("mapname", "63240001");
		defaults.setProperty("description", "OSM street map");
	}

	private ArgProperties argvalues = new ArgProperties(defaults);

	private ArgumentProcessor proc;

	CommandArgs(ArgumentProcessor proc) {
		this.proc = proc;
	}

	/**
	 * Read and interpret the command line arguments.  Most have a double hyphen
	 * preceeding them and these work just the same if they are in a config
	 * file.
	 *
	 * There are a few options that consist of a single hyphen followed by a
	 * single letter that are short cuts for a long option.
	 *
	 * The -c option is special.  It is followed by the name of a file in which
	 * there are further command line options.  Any option on the command line
	 * that comes after the -c option will override the value that is set in
	 * this file.
	 *
	 * @param args The command line arguments.
	 */
	public void readArgs(String[] args) {
		int i = 0;
		while (i < args.length) {
			String arg = args[i++];
			if (arg.startsWith("--")) {
				// This is a long style 'property' format option.
				setPropertyFromArg(arg.substring(2));

			} else if (arg.equals("-c")) {
				// Config file
				readConfigFile(args[i++]);

			} else if (arg.equals("-n")) {
				// Map name (should be an 8 digit number).
				argvalues.setProperty("mapname", args[i++]);

			} else if (arg.startsWith("-")) {
				// this is an unrecognised option.
				log.warn("unrecognised option");

			} else {
				// A file name
				proc.processFilename(this, arg);
			}
		}
	}

	public Properties getProperties() {
		return argvalues;
	}

	public String getDescription() {
		return argvalues.getProperty("description");
	}

	public int getBlockSize() {
		return getValue("block-size", 512);
	}

	public String getMapname() {
		return argvalues.getProperty("mapname");
	}

	public String getCharset() {
		String s = argvalues.getProperty("latin1");
		if (s != null)
			return "latin1";

		// xcharset is the old value, use charset instead.
		return argvalues.getProperty("charset", argvalues.getProperty("xcharset", "ascii"));
	}

	public int getCodePage() {
		int cp;

		String s = argvalues.getProperty("xcode-page", "850");
		try {
			cp = Integer.parseInt(s);
		} catch (NumberFormatException e) {
			cp = 850;
		}

		return cp;
	}

	/**
	 * Get an integer value.  A default is used if the property does not exist.
	 * @param name The name of the property.
	 * @param defval The default value to supply.
	 * @return An integer that is the value of the property.  If the property
	 * does not exist or if it is not numeric then the default value is returned.
	 */
	private int getValue(String name, int defval) {
		String s = argvalues.getProperty(name);
		if (s == null)
			return defval;

		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return defval;
		}
	}

	/**
	 * Set a long property.  These have the form --name=value.  The '--' has
	 * already been stripped off when passed to this function.
	 *
	 * If there is no value part then the option will be set to the string "1".
	 *
	 * @param opt The option with leading '--' removed.  eg name=value.
	 */
	private void setPropertyFromArg(String opt) {
		int eq = opt.indexOf('=');
		if (eq > 0) {
			String key = opt.substring(0, eq);
			String val = opt.substring(eq + 1);
			argvalues.setProperty(key, val);
		} else {
			argvalues.setProperty(opt, "1");
		}
	}

	private void readConfigFile(String filename) {
		log.info("reading config file", filename);
		ArgProperties fileprops = new ArgProperties(argvalues);
		try {
			InputStream is = new FileInputStream(filename);
			fileprops.load(is);
			argvalues = fileprops;
		} catch (FileNotFoundException e) {
			throw new ExitException("Cannot find configuration file " + filename, e);
		} catch (IOException e) {
			throw new ExitException("Error reading configuration file", e);
		}
	}

	/**
	 * Properties implementation that also triggers the callbacks.  This will
	 * work with load() as long as it ultimately calls put() or setProperty(),
	 * the sun jdk does.
	 */
	private class ArgProperties extends Properties {

		private ArgProperties(Properties defaults) {
			super(defaults);
			log.debug("created props");
		}

		public synchronized Object setProperty(String key, String value) {
			return put(key, value);
		}

		public Object put(Object key, Object value) {
			log.debug("setting prop", key, value);
			proc.processOption((String) key, (String) value);
			return super.put(key, value);
		}

		public Object clone() {
			return super.clone();
		}
	}
}
