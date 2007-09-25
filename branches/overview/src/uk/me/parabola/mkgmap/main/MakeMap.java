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
 * Create date: 24-Sep-2007
 */
package uk.me.parabola.mkgmap.main;

import uk.me.parabola.mkgmap.ExitException;

import java.util.Iterator;

/**
 * @author Steve Ratcliffe
 */
public class MakeMap {
	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: mkgmap <file.osm>");
			System.exit(1);
		}

		try {
			CommandArgs a = new CommandArgs();
			a.readArgs(args);

			Iterator it = a.fileNameIterator();
			while (it.hasNext()) {
				String filename = (String) it.next();

				CreateImgFile imgFile = new CreateImgFile();
				imgFile.makeMap(a, filename);
			}
		} catch (ExitException e) {
			System.err.println(e.getMessage());
		}
	}
}
