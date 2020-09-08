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
 * 
 */
package uk.me.parabola.mkgmap.osmstyle.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class ValueBuildedAction implements Action {

	private final List<ValueBuilder> valueBuilder = new ArrayList<>();

	/**
	 * Adds a value building rule.
	 * @param val rule
	 */
	public void add(String val) {
		valueBuilder.add(new ValueBuilder(val));
	}

	/**
	 * Retrieve the tags that might be used to build the value.
	 * @return all required tags
	 */
	public Set<String> getUsedTags() {
		return valueBuilder.stream().flatMap(vb -> vb.getUsedTags().stream()).collect(Collectors.toSet());
	}

	/**
	 * Retrieves the list of value builders.
	 * @return value builders
	 */
	protected List<ValueBuilder> getValueBuilder() {
		return valueBuilder;
	}
	
	protected String calcValueBuildersString() {
		return valueBuilder.stream().map(ValueBuilder::toString).collect(Collectors.joining(" | "));
	}
}
