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
package uk.me.parabola.mkgmap.filters;

import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.filters.MapFilterChain;

/**
 * Used for filtering the elements that are added to the levels.  We make serveral
 * transformations, such as smoothing lines and splitting them so they do not
 * overflow limitations for example.
 *
 * @author Steve Ratcliffe
 */
public interface MapFilter {

	/**
	 * Filter an element.  The filter looks at the element and can simply
	 * pass it on to the next filter in the chain by calling the
	 * {@link MapFilterChain#doFilter(uk.me.parabola.mkgmap.general.MapElement)} method.
	 *
	 * <p>The filter may modify the element or create a new element or even
	 * more than one element and pass them all to the next part of the chain.
	 *
	 * <p>It is allowed to call the next doFilter more than once (this is used
	 * to split elements for example).  You are also allowed to not call it
	 * at all, in which case the element will not appear in the map at that
	 * level.
	 *
	 * @param element A map element.
	 * @param next This is used to pass the possibly transformed element onward.
	 * This can be used more than once.
	 */
	public void doFilter(MapElement element, MapFilterChain next);
}
