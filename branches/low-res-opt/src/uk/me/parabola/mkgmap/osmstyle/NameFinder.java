/*
 * Copyright (C) 2017.
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
package uk.me.parabola.mkgmap.osmstyle;

import java.util.List;
import java.util.Properties;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;
import uk.me.parabola.mkgmap.reader.osm.Tags;

/**
 * Class to handle the option name-tag-list
 * @author Gerd Petermann
 *
 */
public class NameFinder {
	private final ShortArrayList compiledNameTagList;
	
	private static final short TK_NAME = TagDict.getInstance().xlate("name");  
	
	public NameFinder(Properties props) {
		this.compiledNameTagList = computeCompiledNameTags(props);
	}

	/**
	 * Analyse name-tag-list option.
	 * @param props program properties
	 * @return list of compiled tag keys or null if only name should be used.
	 */
	private static ShortArrayList computeCompiledNameTags(Properties props) {
		if (props == null)
			return null;
		List<String> nametags = CommandArgs.getNameTags(props);
		if (nametags.size() == 1 && "name".equals(nametags.get(0)))
			return null;
		return TagDict.compileTags(nametags.toArray(new String[0]));
	}
	
	
	/**
	 * Get name tag value according to name-tag-list.
	 * @param el
	 * @return the tag value or null if none of the name tags is set. 
	 */
	public String getName(Element el) {
		if (compiledNameTagList == null)
			return el.getTag(TK_NAME);

		for (short tagKey : compiledNameTagList) {
			String val = el.getTag(tagKey);
			if (val != null) {
				return val;
			}
		}
		return null;
	}

	/**
	 * Get name tag value according to name-tag-list.
	 * @param tags the tags to check
	 * @return the tag value or null if none of the name tags is set. 
	 */
	public String getName(Tags tags) {
		if (compiledNameTagList == null)
			return tags.get(TK_NAME);

		for (short tagKey : compiledNameTagList) {
			String val = tags.get(tagKey);
			if (val != null) {
				return val;
			}
		}
		return null;
	}
	
	/**
	 * Use name-tag-list to set the name tag for the element. 
	 * @param el the element
	 */
	public void setNameWithNameTagList(Element el) {
		if (compiledNameTagList == null)
			return;

		for (short tagKey : compiledNameTagList) {
			String val = el.getTag(tagKey);
			if (val != null) {
				if (tagKey != TK_NAME) {
					// add or replace name 
					el.addTag(TK_NAME, val);
				}
				break;
			}
		}
		
	}
}
