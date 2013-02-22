/*
 * Copyright (C) 2008 Steve Ratcliffe
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package uk.me.parabola.mkgmap.main;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A generic help item.
 *
 * @author Steve Ratcliffe
 */
public class HelpItem {
	protected final List<String> descriptionLines = new ArrayList<String>();

	public void addDescriptionLine(String line) {
		descriptionLines.add(line);
	}

	public String toString() {
		return Arrays.toString(descriptionLines.toArray());
	}

	public String getDescription() {
		if (descriptionLines.isEmpty())
			return "";

		StringBuilder sb = new StringBuilder();
		for (String l : descriptionLines) {
			sb.append(l);
			sb.append('\n');
		}
		sb.deleteCharAt(sb.length() - 1);
		return sb.toString();
	}
}
