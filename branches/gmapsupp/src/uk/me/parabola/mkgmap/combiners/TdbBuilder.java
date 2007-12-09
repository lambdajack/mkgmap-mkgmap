/*
 * Copyright (C) 2007 Steve Ratcliffe
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
 * Create date: Dec 9, 2007
 */
package uk.me.parabola.mkgmap.combiners;

import uk.me.parabola.mkgmap.main.CommandArgs;
import uk.me.parabola.mkgmap.main.FileInfo;

/**
 * @author Steve Ratcliffe
 */
public class TdbBuilder implements Combiner {
	/**
	 * This is called when an individual map is complete.
	 *
	 * @param args The current options.
	 * @param reader
	 */
	public void onMapEnd(CommandArgs args, FileInfo reader) {
	}

	/**
	 * The complete map set has been processed.  Finish off anything that needs
	 * doing.
	 */
	public void onFinish() {
	}
}
