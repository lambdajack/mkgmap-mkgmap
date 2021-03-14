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

import static uk.me.parabola.imgfmt.app.net.AccessTagsAndBits.ACCESS_TAGS_COMPILED;

import uk.me.parabola.mkgmap.reader.osm.Element;

/**
 * Add one value to all mkgmap access tags, optionally changing them if they already exist.  
 * The value can have replacements from the current tags.
 *
 * @author WanMil
 */
public class AddAccessAction extends ValueBuildedAction {
	private final boolean modify;

	// The tags used to build the value.
	private Element valueTags;

	/**
	 * Create an action to add the given value to all mkgmap access tags.
	 * If the modify flag is false, then only those tags are set that do
	 * not already exist.
	 */
	public AddAccessAction(String value, boolean modify) {
		this.modify = modify;
		add(value);
	}

	public boolean perform(Element el) {
		// 1st build the value
		Element tags = valueTags != null ? valueTags : el;
		String accessValue = null;
		for (ValueBuilder value : getValueBuilder()) {
			accessValue = value.build(tags, el);
			if (accessValue != null) {
				break;
			}
		}
		if (accessValue == null) {
			return false;
		}
		for (Short accessTag : ACCESS_TAGS_COMPILED.keySet()) {
			setTag(el, accessTag, accessValue);
		}
		return true;
	}
	
	/**
	 * Set the tag of the given element. In case the modify flag
	 * is {@code true} the tag is always set. Otherwise the tag
	 * is set only if it does not already exist.
	 * @param el OSM element
	 * @param tagKey the compiled tag key 
	 * @param value the value to be set
	 */
	private void setTag(Element el, Short tagKey, String value) {
		if (modify) {
			el.addTag(tagKey, value);
		} else {
			String tv = el.getTag(tagKey);
			if (tv == null) {
				el.addTag(tagKey, value);
			}
		}
	}

	public void setValueTags(Element valueTags) {
		this.valueTags = valueTags;
	}

	public String toString() {
		return  modify ? "setaccess " : "addaccess " + calcValueBuildersString() + ";";
	}
}
