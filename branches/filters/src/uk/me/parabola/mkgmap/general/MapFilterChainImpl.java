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
 * Create date: Dec 1, 2007
 */
package uk.me.parabola.mkgmap.general;

import uk.me.parabola.mkgmap.filters.MapFilter;
import uk.me.parabola.mkgmap.filters.MapFilterChain;

import java.util.List;
import java.util.ArrayList;

/**
 * This calls all the filters that are applied to an element as it is added to
 * the map at a particular level.
 *
 * @author Steve Ratcliffe
 */
public class MapFilterChainImpl implements MapFilterChain {
	// The filters that will be applied to the element.
	private List<MapFilter> filters = new ArrayList<MapFilter>();

	// The position in the filter list.
	private int position;

	// Elements will be added here.
	private List<MapElement> result = new ArrayList<MapElement>();

	public MapFilterChainImpl(List<MapElement> result) {
		this.result = result;
	}

	public void doFilter(MapElement element) {
		int nfilters = filters.size();

		if (position == nfilters) {
			// We must have got to the end of the chain, so save the element.
			result.add(element);
		} else {
			MapFilter f = filters.get(position);
			f.doFilter(element, this);
		}
	}

	public void addElement(MapElement element) {
		MapFilterChainImpl newChain = new MapFilterChainImpl(result);
		newChain.position = this.position;
		newChain.filters = this.filters;

		newChain.doFilter(element);
	}

	/**
	 * Start the filtering process for an element.
	 * @param element The element to add to the map.
	 */
	void startFilter(MapElement element) {
		position = 0;
		doFilter(element);
	}

	/**
	 * Add a filter to this chain.
	 *
	 * @param filter Filter to added at the end of the chain.
	 */
	void addFilter(MapFilter filter) {
		filters.add(filter);
	}
}
