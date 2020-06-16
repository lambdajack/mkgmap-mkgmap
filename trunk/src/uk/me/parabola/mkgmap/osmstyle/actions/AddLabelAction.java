/*
 * Copyright (C) 2013
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 */
package uk.me.parabola.mkgmap.osmstyle.actions;

import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.mkgmap.reader.osm.Element;

/**
 * Set the first unset label on the given element. The tags of the element may be
 * used in setting the label.
 *
 * We have a list of possible substitutions.
 */
public class AddLabelAction extends ValueBuildedAction {
	private final List<String> labels = new ArrayList<>(4);
	
	/**
	 * Search for the first matching pattern and set the first unset element label
	 * to it.
	 *
	 * If all four labels are already set, then nothing is done.
	 *
	 * @param el The element on which a label may be set.
	 * @return 
	 */
	public boolean perform(Element el) {
		labels.clear();
		for (int index = 1; index <= 4; index++) {
			String tag = "mkgmap:label:" + index;
			String label = el.getTag(tag);
			// find the first unset label and set it
			if (label != null) {
				labels.add(label);
				continue;
			}
			for (ValueBuilder vb : getValueBuilder()) {
				String s = vb.build(el, el);
				if (s != null) {
					// now check if the new label is different to all other labels
					if (labels.contains(s))
						return false;
					// set the label
					el.addTag(tag, s);
					return true;
				}
			}
			return false;
		}
		return false;
	}

	public String toString() {
		return "addlabel " + calcValueBuildersString();
	}
}
