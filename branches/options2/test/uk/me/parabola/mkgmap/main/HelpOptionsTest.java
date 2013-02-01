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

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import func.lib.TestUtils;
import org.junit.Test;

import static org.junit.Assert.*;

public class HelpOptionsTest {

	@Test
	public void testName() throws IOException {
		InputStream io = new ByteArrayInputStream("-c\nSingle letter option\n"
				.getBytes("utf-8"));
		HelpOptions ho = new HelpOptions();
		ho.parse(io);
		ho.dump(); // XXX
	}

	@Test
	public void testOptionList() throws IOException {
		InputStream stream = new FileInputStream("test/resources/help/test");
		TestUtils.registerFile(stream);
		assertNotNull(stream);

		HelpOptions ho = new HelpOptions();
		ho.parse(stream);
		ho.dump();
	}

	@Test
	public void testDefaultValue() {

	}

	@Test
	public void testDefaultBoolean() {

	}

}
