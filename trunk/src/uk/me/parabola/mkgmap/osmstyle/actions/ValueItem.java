/*
 * Copyright (C) 2008 Steve Ratcliffe
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
 * Create date: 07-Dec-2008
 */
package uk.me.parabola.mkgmap.osmstyle.actions;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;

/**
 * Part of a substitution string.  This can represent a constant
 * value or value that is taken from the element tags.
 * 
 * @author Steve Ratcliffe
 */
public class ValueItem {
	private String tagname;
	private short tagKey;
	private ValueFilter filter;
	private String value;
	private boolean tagnameIsLocal;

	public ValueItem() {
	}

	public ValueItem(String value) {
		this.value = value;
	}

	public String getValue(Element el, Element localElement) {
		if (tagname == null && value != null)
			return value;   // already known

		if (tagname != null) {
			Element e = tagnameIsLocal ? localElement : el;
			String tagval = e.getTag(tagKey);
			if (filter != null)
				value = filter.filter(tagval, localElement);
			else
				value = tagval;
		}

		return value;
	}

	public void addFilter(ValueFilter f) {
		if (filter == null)
			filter = f;
		else
			filter.add(f);
	}

	public String getTagname() {
		return tagname;
	}

	public void setTagname(String tagname, boolean local) {
		this.tagname = tagname;
		this.tagnameIsLocal = local;
		this.tagKey = TagDict.getInstance().xlate(tagname);
	}

	public String toString() {
		// TODO: don't ignore filter.
		if (tagname == null)
			return value;
		if (tagnameIsLocal) {
			return "$(" + tagname + ")";
		} else {
			return "${" + tagname + "}";
		}
	}
}
