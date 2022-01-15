/*
 * Copyright (C) 2022.
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
package uk.me.parabola.imgfmt.app.mdr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.mkgmap.srt.SrtTextReader;

public class Mdr16Test {

	// data from demo map
	static final int[] ADRIA_TOPO_FREQ = { 108544, 0, 0, 1, 19, 242, 842, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 195, 139214, 18, 614, 0, 0, 1, 153, 95, 2537, 2558, 1905, 7, 2126, 6588, 10184,
			64, 6539, 13281, 8713, 7478, 7203, 6778, 6634, 5972, 5832, 5314, 1, 1, 0, 0, 0, 2, 0, 181900, 33286, 66619,
			37462, 99070, 4481, 24930, 12992, 113012, 44778, 63856, 63057, 40220, 79383, 101405, 34866, 80, 95298,
			71047, 53719, 31985, 63113, 294, 422, 719, 23284, 0, 0, 0, 0, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 49, 0, 0, 0, 3, 1, 0, 0,
			2, 4, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 2, 0, 43, 0, 34, 4, 0, 51, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 5, 0,
			2, 0, 1, 2, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0 };
	
	/*
	 * 
	 * --------- MDR 16 (unknown) -----------------------------------------------------
000002b6 | 000000 | 4a 03 15 14 0f 08 85 00 | record 0 (len: 212, 0xd4)
000002be | 000008 | 00 00 14 00 06 00 00 13 | 
000002c6 | 000010 | 06 18 00 00 12 0f 30 00 | 
000002ce | 000018 | 00 11 15 50 00 00 10 19 | 
000002d6 | 000020 | 80 00 00 0f 1c c0 00 00 | 
000002de | 000028 | 0e 1e 00 02 00 0d 23 00 | 
000002e6 | 000030 | 03 00 0c 25 00 06 00 0b | 
000002ee | 000038 | 28 00 0c 00 0a 2b 00 10 | 
000002f6 | 000040 | 00 09 2c 00 30 00 08 30 | 
000002fe | 000048 | 00 c0 00 07 39 00 40 01 | 
00000306 | 000050 | 06 3d 00 0c 18 0d 1a 0e | 
0000030e | 000058 | 1c 0e 1c 0e 0b 43 0b 53 | 
00000316 | 000060 | 0b 44 0b 54 0b 4d 0b 56 | 
0000031e | 000068 | 0b 4a 0b 4b 0b 4c 09 00 | 
00000326 | 000070 | 09 00 09 20 09 20 09 45 | 
0000032e | 000078 | 09 45 09 4e 09 4e 09 4f | 
00000336 | 000080 | 09 4f 09 49 09 49 09 52 | 
0000033e | 000088 | 09 52 07 41 07 41 07 41 | 
00000346 | 000090 | 07 41 03 25 3a 3b 8f b8 | 
0000034e | 000098 | 3f 60 84 8e 92 c4 d8 da | 
00000356 | 0000a0 | db 5f 7b 93 c9 d0 d6 2b | 
0000035e | 0000a8 | 96 c1 dc 04 21 9e c6 c8 | 
00000366 | 0000b0 | 51 8a 27 cb 2f 26 1f 57 | 
0000036e | 0000b8 | 58 05 59 22 06 2a 46 2c | 
00000376 | 0000c0 | 28 29 35 36 2d 37 33 38 | 
0000037e | 0000c8 | 39 30 34 48 31 32 2e 47 | 
00000386 | 0000d0 | 5a 55 50 42             | 

	 */
	/**
	 * Very simple test that the bit reader is working.
	 */
	@Test
	public void testAdriaTopo() {
		MdrConfig config = new MdrConfig();
		Sort sort = SrtTextReader.sortForCodepage(1252);
		config.setSort(sort);
		Mdr16 mdr16 = new Mdr16(config);
		assertTrue(mdr16.canEncode());
		mdr16.calc(ADRIA_TOPO_FREQ);
		final byte[] result = mdr16.getData();
		assertEquals(212, result.length);
		byte[] expected1 = { 
				 (byte) 0x4a, (byte) 0x03, (byte) 0x15, (byte) 0x14, (byte) 0x0f, (byte) 0x08, (byte) 0x85, (byte) 0x00
				 , (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x13
				 , (byte) 0x06, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x12, (byte) 0x0f, (byte) 0x30, (byte) 0x00
				 , (byte) 0x00, (byte) 0x11, (byte) 0x15, (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x19
				 , (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x0f, (byte) 0x1c, (byte) 0xc0, (byte) 0x00, (byte) 0x00
				 , (byte) 0x0e, (byte) 0x1e, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0d, (byte) 0x23, (byte) 0x00
				 , (byte) 0x03, (byte) 0x00, (byte) 0x0c, (byte) 0x25, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x0b
				 , (byte) 0x28, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x0a, (byte) 0x2b, (byte) 0x00, (byte) 0x10
				 , (byte) 0x00, (byte) 0x09, (byte) 0x2c, (byte) 0x00, (byte) 0x30, (byte) 0x00, (byte) 0x08, (byte) 0x30
				 , (byte) 0x00, (byte) 0xc0, (byte) 0x00, (byte) 0x07, (byte) 0x39, (byte) 0x00, (byte) 0x40, (byte) 0x01
				 , (byte) 0x06, (byte) 0x3d, (byte) 0x00, (byte) 0x0c, (byte) 0x18, (byte) 0x0d, (byte) 0x1a, (byte) 0x0e
				 , (byte) 0x1c, (byte) 0x0e, (byte) 0x1c, (byte) 0x0e, (byte) 0x0b
		};
		assertArrayEquals("Header and search table", expected1, Arrays.copyOf(result, expected1.length));
		byte[] symbols = {
				(byte) 0x03, (byte) 0x25, (byte) 0x3a, (byte) 0x3b, (byte) 0x8f, (byte) 0xb8
				, (byte) 0x3f, (byte) 0x60, (byte) 0x84, (byte) 0x8e, (byte) 0x92, (byte) 0xc4, (byte) 0xd8, (byte) 0xda
				, (byte) 0xdb, (byte) 0x5f, (byte) 0x7b, (byte) 0x93, (byte) 0xc9, (byte) 0xd0, (byte) 0xd6, (byte) 0x2b
				, (byte) 0x96, (byte) 0xc1, (byte) 0xdc, (byte) 0x04, (byte) 0x21, (byte) 0x9e, (byte) 0xc6, (byte) 0xc8
				, (byte) 0x51, (byte) 0x8a, (byte) 0x27, (byte) 0xcb, (byte) 0x2f, (byte) 0x26, (byte) 0x1f, (byte) 0x57
				, (byte) 0x58, (byte) 0x05, (byte) 0x59, (byte) 0x22, (byte) 0x06, (byte) 0x2a, (byte) 0x46, (byte) 0x2c
				, (byte) 0x28, (byte) 0x29, (byte) 0x35, (byte) 0x36, (byte) 0x2d, (byte) 0x37, (byte) 0x33, (byte) 0x38
				, (byte) 0x39, (byte) 0x30, (byte) 0x34, (byte) 0x48, (byte) 0x31, (byte) 0x32, (byte) 0x2e, (byte) 0x47
				, (byte) 0x5a, (byte) 0x55, (byte) 0x50, (byte) 0x42
		};
		byte[] expectedSymbols= Arrays.copyOf(symbols, symbols.length);
		int off = result.length - symbols.length;
		byte[] resultSymbols = Arrays.copyOfRange(result, off, result.length);
//		assertArrayEquals("Symbols", expectedSymbols, resultSymbols);
	}



}
