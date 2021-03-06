package uk.me.parabola.mkgmap.reader.osm;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 
 * Represent a Relation.
 * 
 * @author Rene_A
 */
public abstract class Relation extends Element {
	private final List<Map.Entry<String,Element>> elements = new ArrayList<>();
	// if set, one or more tags were ignored because they are not used in the style or in mkgmap 
	private boolean tagsIncomplete;

	/**
	 * Add a (role, Element) pair to this Relation.
	 * @param role The role this element performs in this relation
	 * @param el The Element added
	 */
	public void addElement(String role, Element el) {
		elements.add(new AbstractMap.SimpleEntry<String,Element>(role, el));
	}

	/** Invoked after addElement() has been invoked on all Node and Way
	 * members of the relations.  Relation members (sub-relations) may be
	 * added later. */
	public abstract void processElements();

	/** Get the ordered list of relation members.
	 * @return list of pairs of (role, Element)
	 */
	public List<Map.Entry<String,Element>> getElements() {
		return elements;
	}

	@Override
	public String kind() {
		return "relation";
	}

	/**
	 * Used in MultipolygonRelation.
	 * @param tagsIncomplete
	 */
	public void setTagsIncomplete(boolean tagsIncomplete) {
		this.tagsIncomplete = tagsIncomplete;
	}
	
	/**
	 * @return true if any tag was removed by the loader
	 */
	public boolean getTagsIncomplete() {
		return tagsIncomplete;
	}
	
}
