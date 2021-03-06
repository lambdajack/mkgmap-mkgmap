/*
 * Copyright (C) 2014.
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
package uk.me.parabola.imgfmt.app.srt;

import java.text.Collator;

import uk.me.parabola.mkgmap.srt.SrtTextReader;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class SrtCollatorTest {

	private Collator collator;

	@Before
	public void setUp() {
		Sort sort = SrtTextReader.sortForCodepage(1252);
		collator = sort.getCollator();
	}

	/**
	 * Test primary strength comparisons.
	 */
	@Test
	public void testPrimary() {
		collator.setStrength(Collator.PRIMARY);
		assertEquals("prim: different case", 0, collator.compare("AabBb", "aabbb"));
		assertEquals("prim: different case", 0, collator.compare("aabBb", "aabbb"));
		assertEquals("prim: different length", -1, collator.compare("AabB", "aabbb"));
		assertEquals("prim: different letter", -1, collator.compare("aaac", "aaad"));
		assertEquals("prim: different letter", 1, collator.compare("aaae", "aaad"));
		assertEquals(0, collator.compare("aaaa", "aaaa"));
		assertEquals(0, collator.compare("aáÄâ", "aaaa"));
	}

	@Test
	public void testSecondary() {
		collator.setStrength(Collator.SECONDARY);
		assertEquals(0, collator.compare("AabBb", "aabbb"));
		assertEquals(0, collator.compare("aabBb", "aabBb"));
		assertEquals(0, collator.compare("aabbB", "aabBb"));
		assertEquals(1, collator.compare("aáÄâ", "aaaa"));
		assertEquals("prim len diff", -1, collator.compare("aáÄâ", "aaaaa"));
		assertEquals(-1, collator.compare("aáÄâa", "aaaab"));
	}

	@Test
	public void testTertiary() {
		collator.setStrength(Collator.TERTIARY);
		assertEquals("prim: different case", 1, collator.compare("AabBb", "aabbb"));
		assertEquals(1, collator.compare("AabBb", "aabbb"));
		assertEquals(0, collator.compare("aabBb", "aabBb"));
		assertEquals(-1, collator.compare("aabbB", "aabBb"));
		assertEquals(-1, collator.compare("aAbb", "aabbb"));
		assertEquals(1, collator.compare("t", "a"));
		assertEquals(1, collator.compare("ß", "a"));
		assertEquals(-1, collator.compare("ESA", "Eß"));
		assertEquals(-1, collator.compare(":.e", "\u007fæ"));
		assertEquals(-1, collator.compare(";œ", ";Œ"));
		assertEquals(-1, collator.compare("œ;", "Œ;"));
	}

	/**
	 * Test that ignorable characters do not affect the result in otherwise identical strings.
	 */
	@Test
	public void testIgnoreable() throws Exception {
		assertEquals("ignorable at beginning", 0, collator.compare("\u0008fred", "fred"));
		assertEquals("ignorable at end", 0, collator.compare("fred\u0008", "fred"));
		assertEquals("ignorable in middle", 0, collator.compare("fr\u0008ed", "fred"));
		assertEquals(1, collator.compare("\u0001A", "A\u0008"));

		collator.setStrength(Collator.PRIMARY);
		assertEquals("prim: different case", 0, collator.compare("AabBb\u0008", "aabbb"));
	}

	@Test
	public void testSecondaryIgnorable() {
		assertEquals(-1, collator.compare("A", "A\u0001"));
	}

	@Test
	public void testLengths() {
		assertEquals(-1, collator.compare("-Û", "-ü:X"));
		assertEquals(-1, collator.compare("-Û", "-Û$"));
		assertEquals(-1, collator.compare("-ü:X", "-Û$"));
		assertEquals(-1, collator.compare("–", "–X"));
		assertEquals(1, collator.compare("–TÛ‡²", "–"));
	}

	/**
	 * Test using the java collator, to experiment. Note that our implementation is not
	 * meant to be identical to the java one.
	 */
	@Test
	public void testJavaRules() {
		Collator collator = Collator.getInstance();

		// Testing ignorable
		assertEquals(0, collator.compare("\u0001fred", "fred"));
		assertEquals(0, collator.compare("fre\u0001d", "fred"));

		collator.setStrength(Collator.PRIMARY);
		assertEquals("prim: different case", 0, collator.compare("AabBb", "aabbb"));
		assertEquals("prim: different case", 0, collator.compare("aabBb", "aabbb"));
		assertEquals("prim: different length", -1, collator.compare("AabB", "aabbb"));
		assertEquals("prim: different letter", -1, collator.compare("aaac", "aaad"));
		assertEquals("prim: different letter", 1, collator.compare("aaae", "aaad"));
		assertEquals(0, collator.compare("aaaa", "aaaa"));
		assertEquals(0, collator.compare("aáÄâ", "aaaa"));

		collator.setStrength(Collator.SECONDARY);
		assertEquals(0, collator.compare("AabBb", "aabbb"));
		assertEquals(0, collator.compare("aabBb", "aabBb"));
		assertEquals(0, collator.compare("aabbB", "aabBb"));
		assertEquals(1, collator.compare("aáÄâ", "aaaa"));
		assertEquals("prim len diff", -1, collator.compare("aáÄâ", "aaaaa"));
		assertEquals(-1, collator.compare("aáÄâa", "aaaab"));

		collator.setStrength(Collator.TERTIARY);
		assertEquals("prim: different case", 1, collator.compare("AabBb", "aabbb"));
		assertEquals(1, collator.compare("AabBb", "aabbb"));
		assertEquals(0, collator.compare("aabBb", "aabBb"));
		assertEquals(-1, collator.compare("aabbB", "aabBb"));
		assertEquals(-1, collator.compare("aAbb", "aabbb"));
		assertEquals(1, collator.compare("t", "a"));
	}

}
