/*
 * Copyright (C) 2010.
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
package uk.me.parabola.mkgmap.reader.osm;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.util.EnhancedProperties;

/**
 * A single class that holds several {@link OsmReadingHooks} and calls them in
 * order.
 *
 * It implements {@link OsmReadingHooks} itself.
 *
 * @author Steve Ratcliffe
 */ 
public class OsmReadingHooksChain implements OsmReadingHooks {

	private OsmReadingHooks[] readingHooks = {}; // no default hooks

	/**
	 * Add a new hook at the end of the chain.
	 * @param hook The reading hook.
	 */
	public void add(OsmReadingHooks hook) {
		readingHooks = Arrays.copyOfRange(readingHooks, 0, readingHooks.length + 1);
		readingHooks[readingHooks.length - 1] = hook;
	}
	
	@Override
	public Set<String> getUsedTags() {
		HashSet<String> usedTags = new HashSet<>();
		for (int i = 0; i < readingHooks.length; i++)
			usedTags.addAll(readingHooks[i].getUsedTags());
		return usedTags;
	}
	
	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props) {
		for (int i = 0; i < readingHooks.length; i++)
			readingHooks[i].init(saver, props);
		return true;
	}

	@Override
	public void onAddNode(Node node) {
		for (int i = 0; i < readingHooks.length; i++)
			readingHooks[i].onAddNode(node);
	}

	@Override
	public void onCoordAddedToWay(Way way, long coordId, Coord co) {
		for (int i = 0; i < readingHooks.length; i++)
			readingHooks[i].onCoordAddedToWay(way, coordId, co);
	}

	@Override
	public void onAddWay(Way way) {
		for (int i = 0; i < readingHooks.length; i++)
			readingHooks[i].onAddWay(way);
	}

	@Override
	public void end() {
		for (int i = 0; i < readingHooks.length; i++)
			readingHooks[i].end();
	}
}
