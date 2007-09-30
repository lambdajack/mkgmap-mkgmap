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
 * Create date: 27-Sep-2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.imgfmt.app.Map;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;

/**
 * @author Steve Ratcliffe
 */
public interface MapEvents {
	/**
	 * This is called when a data source has been loaded.
	 *
	 * @param src The data source that was loaded.
	 */
	public void onSourceLoad(LoadableMapDataSource src);

	/**
	 * This is called when the map is complete.
	 *
	 * @param map The complete map.
	 */
	public void onMapEnd(Map map);

	/**
	 * The complete map set has been processed.  Finish off anything that needs
	 * doing.
	 */
	public void onFinish();
}
