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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.scan.SyntaxException;

public class LengthFunction extends AbstractFunction {

	private final DecimalFormat nf = new DecimalFormat("0.0#####################", DecimalFormatSymbols.getInstance(Locale.US)); 

	protected String calcImpl(Element el) {
		double length = calcLength(el);
		return nf.format(length);
	}

	
	private double calcLength(Element el) {
		if (el instanceof Way) {
			Way w = (Way)el;
			double length = 0;
			Coord prevC = null;
			for (Coord c : w.getPoints()) {
				if (prevC != null) {
					length += prevC.distance(c);
				}
				prevC = c;
			}
			return length;
		} else if (el instanceof Relation) {
			Relation rel = (Relation)el;
			double length = 0;
			for (Entry<String,Element> relElem : rel.getElements()) {
				if (relElem.getValue() instanceof Way || relElem.getValue() instanceof Relation) {
					length += calcLength(relElem.getValue());
				}
			}
			return length;
		} else {
			throw new SyntaxException("mkgmap::length cannot calculate elements of type "+el.getClass().getName());
		}
	}
	
	protected String getName() {
		return "length";
	}


	public boolean supportsWay() {
		return true;
	}


	public boolean supportsShape() {
		return true;
	}


	public boolean supportsRelation() {
		return true;
	}

}
