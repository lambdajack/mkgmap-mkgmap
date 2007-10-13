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

import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Model for the list of file names that will be processed.
 * 
 * @author Steve Ratcliffe
 */
class FileModel extends AbstractTableModel {
	private static final String FILE_LIST_RESOURCES = "uk/me/parabola/mkgmap/gui/MainFileList";

	private final transient ResourceBundle resource = ResourceBundle.getBundle(FILE_LIST_RESOURCES);
	private final transient List<InputFile> files = new ArrayList<InputFile>();
	private final String[] headers = {
			"",
			resource.getString("heading.input.file"),
			resource.getString("heading.output.file"),
	};

	private int nextOutput = 63240001;
	
	public int getRowCount() {
		return files.size();
	}

	public int getColumnCount() {
		return headers.length;
	}

	public Object getValueAt(int rowIndex, int columnIndex) {
		if (rowIndex >= files.size())
			return "";

		InputFile f = files.get(rowIndex);
		switch (columnIndex) {
		case 0:
			return Boolean.FALSE;
		case 1:
			return f.getInputFile();
		case 2:
			return f.getOutputName();
		default:
			return "";
		}
	}

	public void addFile(File input) {
		InputFile file = new InputFile(input, String.valueOf(nextOutput++));
		int size = files.size();
		files.add(file);
		fireTableRowsInserted(size, size+1);
	}
}
