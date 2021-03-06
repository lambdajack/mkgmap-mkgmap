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

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.scan.SyntaxException;

/**
 * Calculates the length of a way or a relation in meter. The length of a
 * relation is defined as the sum of its member lengths.
 * 
 * @author WanMil
 */
public class LengthFunction extends CachedFunction {
	private static final Logger log = Logger.getLogger(LengthFunction.class);

	private final DecimalFormat nf = new DecimalFormat("0.0#####################", DecimalFormatSymbols.getInstance(Locale.US));

	public LengthFunction() {
		super(null);
	}

	protected String calcImpl(Element el) {
		double length = calcLength(el);
		return nf.format(length);
	}
	
	private static double calcLength(Element el) {
		if (el instanceof Way) {
			return ((Way) el).calcLengthInMetres();
		} else if (el instanceof Relation) {
			Relation rel = (Relation)el;
			double length = 0;
			for (Entry<String,Element> relElem : rel.getElements()) {
				if (relElem.getValue() instanceof Way || relElem.getValue() instanceof Relation) {
					if (rel == relElem.getValue()) {
						// avoid recursive call
						log.error("Relation "+rel.getId()+" contains itself as element. This is not supported.");
					} else {
						length += calcLength(relElem.getValue());
					}
				}
			}
			return length;
		} else {
			throw new SyntaxException("length() cannot calculate elements of type "+el.getClass().getName());
		}
	}
	
	@Override
	public String getName() {
		return "length";
	}

	@Override
	public boolean supportsWay() {
		return true;
	}

	@Override
	public boolean supportsRelation() {
		return true;
	}
	
	@Override
	public int getComplexity() {
		return 2;
	}
}
