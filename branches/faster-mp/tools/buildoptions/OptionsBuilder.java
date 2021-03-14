/*
 * Copyright (C) 2019.
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
package buildoptions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Convert options.txt to format needed in help file options. 
 * Saves some manual edits to keep both files in sync.
 * Used with ant gen-options-file  
 * @author Mike Baggaley
 *
 */
public class OptionsBuilder {

	public static void main(String[] args) {
		final int indentSize = 4;
		boolean hasNonAscii = false;
		int lineNumber = 0;
		File outputFile = new File(args[0]);
		System.setProperty("line.separator", "\n");
		try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
				BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile))) {
			String line;
			String previousLine = "";
			int indent = 0;
			boolean preformatted = false;
			while((line = br.readLine()) != null) {
				++lineNumber;
				if (!line.matches("\\p{ASCII}*")) {
					System.err.println("Line " + lineNumber + " contains one or more non-ASCII characters.\r\n" + line);
					hasNonAscii = true;
				}
				if (preformatted) {
					if (line.trim().compareToIgnoreCase("</pre>") == 0)
						preformatted = false;
					else {
					    bw.write(line);
					    bw.newLine();
					}
				} else {
					line = line.replaceAll("\\s+", " ");
					if (line.length() > 0) {
						if (line.startsWith(";")) {
							line = line.substring(1);
							indent = 0;
							if (!previousLine.isEmpty()) {
							    bw.write(previousLine);
							    bw.newLine();
							    previousLine = "";
							}
						}
						else if (line.charAt(0) == ':') {
							if (!previousLine.isEmpty()) {
							    bw.write(previousLine);
							    bw.newLine();
							    previousLine = "";
							}
							indent = 1;
							line = line.substring(1);
							while (line.charAt(0) == ':') {
								indent++;
								line = line.substring(1);
							}
							if (line.charAt(0) == ';')
								line = line.substring(1);
						}
						else if (line.trim().compareToIgnoreCase("<p>") == 0) {
							if (!previousLine.isEmpty()) {
							    bw.write(previousLine);
							    bw.newLine();
							    previousLine = "";
							}
							line = "";
						    bw.newLine();
						}
						else if (line.trim().compareToIgnoreCase("<pre>") == 0) {
							if (!previousLine.isEmpty()) {
							    bw.write(previousLine);
							    bw.newLine();
							    previousLine = "";
							}
							line = "";
						    preformatted = true;
						}
						line = line.trim();
						if (!previousLine.isEmpty()) {
							if (!line.isEmpty()) {
								previousLine += " " + line;
							}
						} else {
							previousLine = line;
							for (int i = 0; i < indent; i++) {
								for (int j = 0; j < indentSize; j++)
									previousLine = " " + previousLine;
							}
						}
						while (previousLine.length() > 79) {
							line = previousLine.substring(0, 80);
							int lastSpaceIndex = line.lastIndexOf(' ');
							int firstNonSpaceIndex = 0;
							while (firstNonSpaceIndex < 79) {
								if (line.charAt(firstNonSpaceIndex) != ' ')
									break;
								firstNonSpaceIndex++;
							}
							if (lastSpaceIndex > firstNonSpaceIndex) {
								line = line.substring(0, lastSpaceIndex);
								previousLine = previousLine.substring(lastSpaceIndex + 1);
								for (int i = 0; i < indent; i++) {
									for (int j = 0; j < indentSize; j++)
										previousLine = " " + previousLine;
								}
							    bw.write(line);
							    bw.newLine();
							}
							else {
							    bw.write(previousLine);
							    bw.newLine();
							    previousLine = "";
							}
						}
					}
					else {
						indent = 0;
						if (!previousLine.isEmpty()) {
						    bw.write(previousLine);
						    bw.newLine();
						    previousLine = "";
						}
						bw.newLine();
					}
				}
			}
			if (!previousLine.isEmpty()) {
			    bw.write(previousLine);
			    bw.newLine();
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
		if (hasNonAscii) {
			System.exit(-1);
		}
	}

}
