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
 * Create date: 29-Sep-2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.imgfmt.app.Map;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.mkgmap.reader.overview.OverviewMapDataSource;
import uk.me.parabola.tdbfmt.TdbFile;
import uk.me.parabola.log.Logger;

/**
 * Builds an overview map and the corresponding TDB file for use with
 * QLandkarte and MapSource etc.
 *
 * @author Steve Ratcliffe
 */
public class OverviewMapBuilder implements MapEvents {
	private static final Logger log = Logger.getLogger(OverviewMapBuilder.class);
	
	private OverviewMapDataSource overviewSource = new OverviewMapDataSource();
	private TdbFile tdb = new TdbFile();

	private void init() {
		tdb.setProductInfo(42, 1, "OSM map", "OSM map");
	}

	public void onSourceLoad(LoadableMapDataSource src) {
		overviewSource.addMapDataSource(src);
	}

	public void onMapComplete(Map map) {
		long lblsize = map.getLblFile().position();
		long tresize = map.getTreFile().position();
		long rgnsize = map.getRgnFile().position();

		log.debug("sizes", lblsize, tresize, rgnsize);
	}
}
