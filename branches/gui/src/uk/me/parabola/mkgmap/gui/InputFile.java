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
 * Create date: 13-Oct-2007
 */
package uk.me.parabola.mkgmap.gui;

import java.io.File;

/**
 * @author Steve Ratcliffe
 */
class InputFile {
	private final File inputFile;
	private final String outputName;

	public InputFile(File inputFile, String outputName) {
		this.inputFile = inputFile;
		this.outputName = outputName;
	}

	public Object getInputFile() {
		return inputFile;
	}

	public String getOutputName() {
		return outputName;
	}
}
