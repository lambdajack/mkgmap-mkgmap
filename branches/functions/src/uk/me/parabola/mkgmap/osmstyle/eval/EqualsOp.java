/*
 * Copyright (C) 2008-2012 Steve Ratcliffe
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
 * 
 * Author: Steve Ratcliffe
 * Create date: 03-Nov-2008
 */
package uk.me.parabola.mkgmap.osmstyle.eval;

import uk.me.parabola.mkgmap.reader.osm.Element;

/**
 * Holds tag=value relationship.
 * 
 * @author Steve Ratcliffe
 */
public class EqualsOp extends AbstractBinaryOp {

	public EqualsOp() {
		setType(EQUALS);
	}

	public void setFirst(Op first) {
		super.setFirst(first);
	}

	public void setSecond(Op second) {
		super.setSecond(second);
	}

	public boolean eval(Element el) {
		String s = first.value(el);
		if (s == null)
			return false;
		return s.equals(getSecond().value(el));
	}

	public int priority() {
		return 10;
	}
}
