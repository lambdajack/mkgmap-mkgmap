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
 * Create date: Nov 15, 2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.imgfmt.app.InternalFiles;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.log.Logger;

/**
 * Create the gmapsupp file.  There is nothing much special about this file
 * (as far as I know - theres not a public official spec or anything) it is
 * just a regular .img file which is why it works to rename a single .img file
 * and send it to the device.
 *
 * Effectively we just 'unzip' the constituent .img files and then 'zip' them
 * back into the gmapsupp.img file.
 *
 * In addition we need to create and add the TDB file, if we don't already
 * have one.
 * 
 * @author Steve Ratcliffe
 */
public class GmapsuppBuilder implements MapEventListener {
	private static final Logger log = Logger.getLogger(GmapsuppBuilder.class);

	/**
	 * This is called when the map is complete.
	 * We collect information about the map to be used in the TDB file and
	 * for preparing the gmapsupp file.
	 *
	 * @param args The current options.
	 * @param src  The map data.
	 * @param map  The map.
	 */
	public void onMapEnd(CommandArgs args, LoadableMapDataSource src, InternalFiles map) {
		int tresize = map.getTreFile().position();
		int rgnsize = map.getRgnFile().position();
		int lblsize = map.getLblFile().position();

		log.debug("sizes", tresize, rgnsize, lblsize);
		log.debug("mapname", args.getMapname());
		log.debug("description", args.getDescription());
	}

	/**
	 * The complete map set has been processed.
	 * Creates the gmapsupp file.  This is done by stepping through each img
	 * file, reading all the sub files and copying them into the gmapsupp file.
	 */
	public void onFinish() {
	}
}
