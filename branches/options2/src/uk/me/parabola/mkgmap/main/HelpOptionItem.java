/*
 * Copyright (C) 2013.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */
package uk.me.parabola.mkgmap.main;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.mkgmap.Option;

/**
 * The help file information about a single option.
 *
 * @see HelpOptions
 *
 * @author Steve Ratcliffe
 */
public class HelpOptionItem extends HelpItem {
	private final List<Option> options = new ArrayList<Option>();

	//private String defaultValue;

	/**
	 * Add an option name to the help item.
	 *
	 * @param name The option name.
	 * @param meta If it takes an argument, this it the meta variable for it.
	 */
	public void addOption(String name, String meta) {
		options.add(new Option(name, meta));
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Option opt : options) {
			String key = opt.getOption();

			sb.append(opt.isLongOpt() ? "--" : "-");
			sb.append(key);

			String val = opt.getValue();
			if (val != null && !val.isEmpty()) {
				sb.append(opt.isLongOpt()? "=": " ");
				sb.append(val);
			}

			sb.append('\n');
		}

		for (String line : descriptionLines) {
			if (hasOptions())
				sb.append("    ");
			sb.append(line);
			sb.append('\n');
		}
		return sb.toString();
	}

	private boolean hasOptions() {
		return !options.isEmpty();
	}

	public Set<String> getOptionNames() {
		Set<String> set = new HashSet<String>();
		for (Option opt : options) {
			String optionName = opt.getOption();
			set.add(optionName);
		}
		return set;
	}

	public String getMeta(String name) {
		for (Option opt : options) {
			if (opt.getOption().equals(name))
				return opt.getValue();
		}
		return null;
	}
}
