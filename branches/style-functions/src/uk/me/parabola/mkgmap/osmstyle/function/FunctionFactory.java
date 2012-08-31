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

import java.util.HashMap;
import java.util.Map;

public class FunctionFactory {

	private static Map<String, StyleFunction> cache = new HashMap<String, StyleFunction>();
	
	public static StyleFunction getFunction(String name) {
		StyleFunction function = cache.get(name);
		if (function == null) {
			function = createFunction(name);
			if (function != null) {
				cache.put(name, function);
			}
		}
		return function;
	}
	
	
	public static StyleFunction createFunction(String name) {
		if ("length".equals(name)) {
			return new LengthFunction();
		}
		return null;
	}
}
