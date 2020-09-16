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
package uk.me.parabola.mkgmap.osmstyle.actions;

import uk.me.parabola.mkgmap.reader.osm.Element;

/**
 * Delete all tags from an element. This is useful to stop its processing.
 * 
 * @author WanMil
 */
public class DeleteAllTagsAction implements Action {

	
	public boolean perform(Element el) {
		el.removeAllTags();
		return true;
	}

	public String toString() {
		return "deletealltags;";
	}
}