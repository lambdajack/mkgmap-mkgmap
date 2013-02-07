/*
 * Copyright (C) 2013.
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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.mkgmap.scan.TokType;
import uk.me.parabola.mkgmap.scan.Token;
import uk.me.parabola.mkgmap.scan.TokenScanner;


/**
 * All the information contained in the options help file is held here.
 *
 * @author Steve Ratcliffe
 */
public class HelpOptions {

	private final List<HelpOptionItem> list = new ArrayList<HelpOptionItem>();

	private HelpOptionItem current = new HelpOptionItem();

	/**
	 * Read the file given.
	 * @param stream An opened file stream to the help file.
	 */
	public void parse(InputStream stream) {
		InputStreamReader r = null;
		try {
			r = new InputStreamReader(stream, "utf-8");
			parse(r);
		} catch (UnsupportedEncodingException e) {
			// java guarantees utf-8 support, so this can't happen in normal circumstances
			throw new ExitException("unsupported encoding", e);
		} finally {
			Utils.closeFile(r);
		}
	}

	private void parse(InputStreamReader r) {
		TokenScanner scan = new TokenScanner("options", r);
		scan.setExtraWordChars("-");

		while (!scan.isEndOfFile()) {
			scan.skipSpace();
			Token tok = scan.peekToken();

			if (isOption(tok)) {
				startNew();
				parseOpt(scan);
			} else {
				parseDescription(scan);
			}
		}

		list.add(current);
	}

	private void parseDescription(TokenScanner scan) {
		System.out.println("description");
		boolean para = false;
		while (!scan.isEndOfFile()) {

			Token tok = scan.peekToken();
			if (tok.isType(TokType.TEXT) && tok.getValue().startsWith("-")) {
				break;
			}

			String line = null;
			if (tok.isType(TokType.SPACE)) {
				line = scan.readLine();
			} else if (tok.isType(TokType.TEXT) && tok.getValue().startsWith("-")) {
					break;

			} else {
				line = scan.readLine();
			}

			if (line != null)
				current.addDescriptionLine(line);
		}
	}

	private void parseOpt(TokenScanner scan) {
		System.out.println("opt");
		while (!scan.isEndOfFile()) {
			System.out.println("next opt");
			String optname = scan.nextValue();

			boolean longOpt = false;
			if (optname.startsWith("--")) {
				longOpt = true;
				optname = optname.substring(2);
			} else {
				optname = optname.substring(1);
			}

			String meta = null;
			if (longOpt) {
				if (scan.checkToken("=")) {
					scan.nextToken();
					meta = scan.nextWord().toUpperCase();
				}
			} else {
				Token next = scan.peekToken();
				if (next.getType() == TokType.SPACE) {
					meta = scan.readLine().trim().toUpperCase();
					if (meta.isEmpty())
						meta = null;
				}
			}
			current.addOption(optname, meta);

			Token next = scan.peekToken();
			if (next.getType() != TokType.TEXT || !next.getValue().startsWith("-"))
				break;
		}
	}

	private void startNew() {
		if (current.isUsed())
			list.add(current);
		current = new HelpOptionItem();
	}

	private boolean isOption(Token tok) {
		return tok.isText() && tok.getValue().startsWith("-");
	}

	public void dump() { // XXX temp
		for (HelpOptionItem item : list) {
			System.out.println("\n\n" + item);
		}
	}

	/**
	 * Get a list of the long option names.
	 */
	public Set<String> getOptionNameSet() {
		Set<String> set = new HashSet();
		for (HelpOptionItem item : list) {
			item.getOptionNames();
			//set.add(item.)
		}
		return set;
	}
}
