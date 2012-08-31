/*
 * Copyright (C) 2012.
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

package uk.me.parabola.mkgmap.osmstyle.function;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;

public abstract class AbstractFunction implements StyleFunction {

	public boolean supportsNode() {
		return false;
	}

	public boolean supportsWay() {
		return false;
	}

	public boolean supportsShape() {
		return false;
	}

	public boolean supportsRelation() {
		return false;
	}

	public final String calcValue(Element el) {
		if (el instanceof Node ) {
			if (supportsNode() == false) {
				return null;
			}
		} else if (el instanceof Way) {
			if (supportsWay() == false) {
				return null;
			}
		} else  if (el instanceof Relation) {
			if (supportsRelation() == false) {
				return null;
			}
		}
		
		if (supportsCaching()) {
			String cachedValue = el.getTag(getCacheTag());
			if (cachedValue != null) {
				return cachedValue;
			}
		}
		
		String functionResult = calcImpl(el);
		
		if (supportsCaching()) {
			el.addTag(getCacheTag(), functionResult);
		}
		
		return functionResult;
	}
	
	protected abstract String calcImpl(Element el);

	protected abstract String getName();
	
	protected boolean supportsCaching() {
		return true;
	}
	
	protected String getCacheTag() {
		return "mkgmap:cache_"+getName();
	}
	
}
