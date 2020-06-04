/*
 * Copyright (C) 2006, 2011.
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
package uk.me.parabola.mkgmap.build;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.util.EnhancedProperties;

public class LocatorUtil {
	private LocatorUtil () {
		// private constructor to hide the implicit one
	}
	
	/**
	 * Parses the parameters of the location-autofill option. Establishes also downwards
	 * compatibility with the old integer values of location-autofill. 
	 * @param props program properties
	 * @return the options
	 */
	public static Set<String> parseAutofillOption(EnhancedProperties props) {
		final String optName = "location-autofill";
		final String IS_IN = "is_in";
		final String NEAREST = "nearest";
		String optionStr = props.getProperty(optName, null);
		if (optionStr == null) {
			return Collections.emptySet();
		}
		Set<String> autofillOptions = CommandArgs.stringToSet(optionStr, optName);
	
		// convert the old autofill options to the new parameters
		if (autofillOptions.contains("0")) {
			autofillOptions.add(IS_IN);
			autofillOptions.remove("0");
		}
		if (autofillOptions.contains("1")) {
			autofillOptions.add(IS_IN);
			autofillOptions.remove("1");
		}
		if (autofillOptions.contains("2")) {
			autofillOptions.add(IS_IN);
			// PENDING: fuzzy search
			autofillOptions.add(NEAREST);
			autofillOptions.remove("2");
		}		
		if (autofillOptions.contains("3")) {
			autofillOptions.add(IS_IN);
			// PENDING: fuzzy search
			autofillOptions.add(NEAREST);
			autofillOptions.remove("3");
		}	
		final List<String> knownOptions = Arrays.asList("bounds", IS_IN, NEAREST);
		for (String s : autofillOptions){
			if (!knownOptions.contains(s)) {
				throw new IllegalArgumentException(s + " is not a known sub option for option location-autofill: " + optionStr);
			}
		}
		return autofillOptions;
	}
}
