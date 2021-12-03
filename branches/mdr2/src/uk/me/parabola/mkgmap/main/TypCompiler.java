/*
 * Copyright (C) 2011.
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
package uk.me.parabola.mkgmap.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.imgfmt.app.typ.TYPFile;
import uk.me.parabola.imgfmt.app.typ.TypData;
import uk.me.parabola.imgfmt.app.typ.TypLabelException;
import uk.me.parabola.imgfmt.app.typ.TypParam;
import uk.me.parabola.imgfmt.sys.FileImgChannel;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.scan.SyntaxException;
import uk.me.parabola.mkgmap.typ.TypTextReader;

/**
 * Standalone program to compile a TYP file from the text format.
 * Simple main program to demonstrate compiling a typ.txt file.
 *
 * Usage: TypTextReader [in-file] [out-file]
 *
 * in-file defaults to 'default.txt'
 * out-file defaults to 'OUT.TYP'
 *
 */
public class TypCompiler implements MapProcessor {

	/**
	 * The integration with mkgmap.
	 *
	 * @param args The options that are in force.
	 * @param filename The input filename.
	 * @return Returns the name of the file that was written. It depends on the family id.
	 */
	public String makeMap(CommandArgs args, String filename) {
		assert filename.toLowerCase().endsWith(".txt");

		CharsetProbe probe = new CharsetProbe();
		String readCharset = probe.probeCharset(filename);

		TypData data;
		try {
			data = compile(filename, readCharset, args.getSort());
		} catch (SyntaxException e) {
			throw new MapFailedException("Compiling TYP txt file: " + e.getMessage());
		} catch (FileNotFoundException e) {
			throw new MapFailedException("Could not open TYP file " + filename + " to read");
		}

		TypParam param = data.getParam();
		int family = args.get("family-id", -1);
		int product = args.get("product-id", -1);
		int cp = args.get("code-page", -1);

		if (family != -1)
			param.setFamilyId(family);
		if (product != -1)
			param.setProductId(product);
		if (cp != -1)
			param.setCodePage(cp);

		File outFile = new File(filename);
		String outName = outFile.getName();

		int last;
		if (outName.length() > 4 && (last = outName.lastIndexOf('.')) > 0)
			outName = outName.substring(0, last);

		outName += ".typ";
		outFile = new File(args.getOutputDir(), outName);

		try {
			writeTyp(data, outFile);
		} catch (TypLabelException e) {
			throw new MapFailedException("TYP file cannot be written in code page "
					+ data.getSort().getCodepage());
		} catch (IOException e) {
			throw new MapFailedException("Error while writing typ file", e);
		}

		return outFile.getPath();
	}

	/**
	 * Read and compile a TYP file, returning the compiled form.
	 *
	 * @param filename The input filename.
	 * @param charset The character set to use to read this file. We should have already determined
	 * that this character set is valid and can be used to read the file.
	 * @param sort The sort information from command line options, used for the output code page
	 * only. If null, then the code page set by CodePage in the typ.txt file will be used.
	 *
	 * @return The compiled form as a data structure.
	 * @throws FileNotFoundException If the file doesn't exist.
	 * @throws SyntaxException All user correctable problems in the input file.
	 */
	private static TypData compile(String filename, String charset, Sort sort)
			throws FileNotFoundException, SyntaxException
	{
		TypTextReader tr = new TypTextReader();

		TypData data = tr.getData();

		data.setSort(sort);
		try (Reader r = new BufferedReader(new InputStreamReader(new FileInputStream(filename), charset))) {
			tr.read(filename, r, charset);
		} catch (UnsupportedEncodingException e) {
			// Not likely to happen as we should have already used this character set!
			throw new MapFailedException("Unsupported character set", e);
		} catch (IOException e) {
			throw new ExitException("Unable to read/close file " + filename);
		}

		return tr.getData();
	}

	/**
	 * Write the type file out from the compiled form to the given name.
	 */
	private static void writeTyp(TypData data, File file) throws IOException {
		try (FileChannel channel = FileChannel.open(file.toPath(),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ))
		{
			channel.truncate(0);

			FileImgChannel w = new FileImgChannel(channel);
			try (TYPFile typ = new TYPFile(w)) {
				typ.setData(data);
				typ.write();
			}
		}
	}

	/**
	 * Simple standalone compiler.
	 *
	 * Usage: TypCompiler [in-file] [out-file]
	 *  in-file defaults to 'default.txt'
	 *  out-file defaults to OUT.TYP
	 */
	public static void main(String... args) {
		String in = "default.txt";
		if (args.length > 0)
			in = args[0];
		String out = "OUT.TYP";
		if (args.length > 1)
			out = args[1];

		new TypCompiler().standAloneRun(in, out);
	}

	private void standAloneRun(String in, String out) {
		CharsetProbe probe = new CharsetProbe();
		String readCharset = probe.probeCharset(in);

		TypData data;
		try {
			data = compile(in, readCharset, null);
		} catch (SyntaxException e) {
			System.out.println(e.getMessage());
			return;
		} catch (FileNotFoundException e) {
			throw new MapFailedException("Could not open TYP file " + in + " to read");
		}

		try {
			writeTyp(data, new File(out));
		} catch (IOException e) {
			System.out.println("Error writing file: " + e.getMessage());
		}
	}


	class CharsetProbe {
		// TODO: this should could be moved to somewhere like util and used on other text files
		// except looking for Codepage is particular to Typ files
		// and want to have ability to return default environment decoder
		// (ie inputStream without 2nd parameter)

		private String probeCharset(String file) {

			final String BOM_UTF_8    = "\u00EF\u00BB\u00BF";
			final String BOM_UTF_16LE = "\u00FF\u00FE";
			final String BOM_UTF_16BE = "\u00FE\u00FF";
			final String BOM_UTF_32LE = "\u00FF\u00FE\u0000\u0000";
			final String BOM_UTF_32BE = "\u0000\u0000\u00FE\u00FF";

			final Charset byteCharNoMap = StandardCharsets.ISO_8859_1; // byteVal == charVal
			final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();

			String charset = null;
			boolean validUTF8 = true;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), byteCharNoMap))) {
				String line;
				int lineNo = 0;
				do {
					line = br.readLine();
					if (line == null)
						break;
					++lineNo;
					if (line.isEmpty())
						continue;
					if (lineNo <= 2) { // only check the first few lines for these
						if (line.contains(BOM_UTF_8))
							charset = "UTF-8";
						else if (line.contains(BOM_UTF_32LE)) // must test _32 before _16
							charset = "UTF-32LE";
						else if (line.contains(BOM_UTF_32BE))
							charset = "UTF-32BE";
						else if (line.contains(BOM_UTF_16LE))
							charset = "UTF-16LE";
						else if (line.contains(BOM_UTF_16BE))
							charset = "UTF-16BE";
						if (charset != null)
							break;

						int strInx = line.indexOf("-*- coding:"); // be lax about start/end
						if (strInx >= 0) {
							charset = line.substring(strInx+11).trim();
							strInx = charset.indexOf(' ');
							if (strInx >= 0)
								charset = charset.substring(0, strInx);
							break;
						}
					}

					// special for TypFile; to be compatible with possible old usage
					if (line.startsWith("CodePage=")) {
						charset = line.substring(9).trim();
						try {
							int codePage = Integer.decode(charset);
							if (codePage == 65001)
								charset = "UTF-8";
							else
								charset = "cp" + codePage;
						} catch (NumberFormatException e) {
						}
						break;
					}

					if (validUTF8) { // test the line for being valid UTF-8
						ByteBuffer asBytes = byteCharNoMap.encode(line);
						try { // arbitrary sequences of bytes > 127 tend not to be UTF8
							/*CharBuffer asChars =*/ utf8Decoder.decode(asBytes);
						} catch (CharacterCodingException e) {
							validUTF8 = false;
							// don't stop as might still get coding directive
						}
					}
				} while (true);
			} catch (FileNotFoundException e) {
				throw new ExitException("File not found " + file);
			} catch (IOException e) {
				throw new ExitException("Unable to read file " + file);
			}
			return charset != null ? charset : (validUTF8 ? "UTF-8" : "cp1252");
		}
	}
}
