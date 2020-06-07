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

import uk.me.parabola.mkgmap.osmstyle.function.MaxSpeedFunction.SpeedUnit;

/**
 * A factory for style functions. 
 * @author WanMil
 */
public class FunctionFactory {

	private FunctionFactory() {
		// hide default public constructor
	}
	
	/**
	 * Returns a new instance of a style function with the given name.
	 *
	 * @param name the style function name
	 * @return the style function instance or {@code null} if there is no such function
	 */
	public static StyleFunction createFunction(String name) {
		switch (name) {
		case "length":
			return new LengthFunction();
		case "is_closed":
			return new IsClosedFunction();
		case "is_complete":
			return new IsCompleteFunction();
		case "area_size":
			return new AreaSizeFunction();
		case "maxspeedkmh":
			return new MaxSpeedFunction(SpeedUnit.KMH);
		case "maxspeedmph":
			return new MaxSpeedFunction(SpeedUnit.MPH);
		case "type":
			return new TypeFunction();
		case "osmid":
			return new OsmIdFunction();
		case "is_in":
			return new IsInFunction();
		case "is_drive_on_left":
			return new IsDriveOnLeftFunction();
		default:
			return null;
		}
	}
}
