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
	static final int[] FAM_928_FREQ = { 108544, 0, 0, 1, 19, 242, 842, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 195, 139214, 18, 614, 0, 0, 1, 153, 95, 2537, 2558, 1905, 7, 2126, 6588, 10184, 64,
			6539, 13281, 8713, 7478, 7203, 6778, 6634, 5972, 5832, 5314, 1, 1, 0, 0, 0, 2, 0, 181900, 33286, 66619,
			37462, 99070, 4481, 24930, 12992, 113012, 44778, 63856, 63057, 40220, 79383, 101405, 34866, 80, 95298,
			71047, 53719, 31985, 63113, 294, 422, 719, 23284, 0, 0, 0, 0, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 49, 0, 0, 0, 3, 1, 0, 0,
			2, 4, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 2, 0, 43, 0, 34, 4, 0, 51, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 5, 0,
			2, 0, 1, 2, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0 };
	static final int[] FAM_1725_FREQ = { 24921, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 8926, 0, 2, 0, 0, 0, 0, 325, 27, 27, 0, 0, 23, 1933, 24, 26, 2, 25, 16, 13, 5, 6, 5, 6,
			8, 1, 0, 0, 2, 0, 2, 0, 0, 18236, 6594, 4516, 11988, 48129, 1729, 7187, 9671, 13595, 2845, 9839, 14874,
			6821, 19989, 17574, 4482, 81, 21149, 15226, 14716, 7051, 6133, 4954, 245, 423, 2300, 0, 0, 0, 0, 0, 1, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 0, 31, 0, 10, 0, 0, 1, 61, 214, 15, 79, 0,
			0, 0, 2, 0, 0, 0, 0, 3, 0, 19, 0, 0, 0, 0, 9, 33, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

	/*
	 * FAM_928 
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
	 * compare result for Frequencies detected with MdrDisplay with that from mkgmap
	 */
	@Test
	public void testFamily928() {
		MdrConfig config = new MdrConfig();
		Sort sort = SrtTextReader.sortForCodepage(1252);
		config.setSort(sort);
		Mdr16 mdr16 = new Mdr16(config);
		assertTrue(mdr16.canEncode());
		mdr16.calc(FAM_928_FREQ);
		final byte[] result = mdr16.getData();
		assertEquals(212, result.length);
		// compare up to the first odd value in the lookup table, the rest seems unpredictable reg. order
		byte[] expectedStart = { (byte) 0x4a, (byte) 0x03, (byte) 0x15, (byte) 0x14, (byte) 0x0f, (byte) 0x08, (byte) 0x85,
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x00,
				(byte) 0x13, (byte) 0x06, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x12, (byte) 0x0f, (byte) 0x30,
				(byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0x15, (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x10,
				(byte) 0x19, (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x0f, (byte) 0x1c, (byte) 0xc0, (byte) 0x00,
				(byte) 0x00, (byte) 0x0e, (byte) 0x1e, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0d, (byte) 0x23,
				(byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x0c, (byte) 0x25, (byte) 0x00, (byte) 0x06, (byte) 0x00,
				(byte) 0x0b, (byte) 0x28, (byte) 0x00, (byte) 0x0c, (byte) 0x00, (byte) 0x0a, (byte) 0x2b, (byte) 0x00,
				(byte) 0x10, (byte) 0x00, (byte) 0x09, (byte) 0x2c, (byte) 0x00, (byte) 0x30, (byte) 0x00, (byte) 0x08,
				(byte) 0x30, (byte) 0x00, (byte) 0xc0, (byte) 0x00, (byte) 0x07, (byte) 0x39, (byte) 0x00, (byte) 0x40,
				(byte) 0x01, (byte) 0x06, (byte) 0x3d, (byte) 0x00, (byte) 0x0c, (byte) 0x18, (byte) 0x0d, (byte) 0x1a,
				(byte) 0x0e, (byte) 0x1c, (byte) 0x0e, (byte) 0x1c, (byte) 0x0e, (byte) 0x0b };
		assertArrayEquals("Header and search table", expectedStart, Arrays.copyOf(result, expectedStart.length));
	}

	/* FAM_1725
	 * --------- MDR 16 (unknown) -----------------------------------------------------
000002ae | 000000 | be 02 15 12 0c 08 5d 00 | record 0 (len: 177, 0xb1)
000002b6 | 000008 | 00 00 12 00 06 00 00 11 | 
000002be | 000010 | 06 0c 00 00 10 09 20 00 | 
000002c6 | 000018 | 00 0f 0e 50 00 00 0e 14 | 
000002ce | 000020 | e0 00 00 0d 1d 00 01 00 | 
000002d6 | 000028 | 0c 1e 80 01 00 0b 20 00 | 
000002de | 000030 | 03 00 0a 23 00 04 00 08 | 
000002e6 | 000038 | 24 00 08 00 07 25 00 20 | 
000002ee | 000040 | 00 06 28 00 0a 16 0b 16 | 
000002f6 | 000048 | 0b 16 0b 0b 55 0b 44 0b | 
000002fe | 000050 | 48 0b 4b 0b 20 0b 47 09 | 
00000306 | 000058 | 4e 09 4e 09 4c 09 4c 09 | 
0000030e | 000060 | 41 09 41 09 53 09 53 09 | 
00000316 | 000068 | 49 09 49 09 52 09 52 09 | 
0000031e | 000070 | 4f 09 4f 09 00 09 00 09 | 
00000326 | 000078 | 54 09 54 07 45 07 45 07 | 
0000032e | 000080 | 45 07 45 22 30 39 3c 60 | 
00000336 | 000088 | c7 3e cf d4 34 35 36 37 | 
0000033e | 000090 | c0 32 38 33 c4 ca db 2c | 
00000346 | 000098 | 28 31 c2 2f 29 2e d6 dc | 
0000034e | 0000a0 | c8 51 cb 58 27 c9 59 46 | 
00000356 | 0000a8 | 5a 4a 2d 56 57 50 42 43 | 
0000035e | 0000b0 | 4d                      | 

	 */
	/**
	 * Compare result for frequencies detected with MdrDisplay with that from mkgmap
	 */
	@Test
	public void testFamily1725() {
		MdrConfig config = new MdrConfig();
		Sort sort = SrtTextReader.sortForCodepage(1252);
		config.setSort(sort);
		Mdr16 mdr16 = new Mdr16(config);
		assertTrue(mdr16.canEncode());
		mdr16.calc(FAM_1725_FREQ);
		final byte[] result = mdr16.getData();
		assertEquals(177, result.length);
		// compare up to the first odd value in the lookup table, the rest seems unpredictable reg. order
		byte[] expectedStart = { 
				    (byte) 0xbe, (byte) 0x02, (byte) 0x15, (byte) 0x12, (byte) 0x0c, (byte) 0x08, (byte) 0x5d, (byte) 0x00
				  , (byte) 0x00, (byte) 0x00, (byte) 0x12, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x11
				  , (byte) 0x06, (byte) 0x0c, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x09, (byte) 0x20, (byte) 0x00
				  , (byte) 0x00, (byte) 0x0f, (byte) 0x0e, (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x0e, (byte) 0x14
				  , (byte) 0xe0, (byte) 0x00, (byte) 0x00, (byte) 0x0d, (byte) 0x1d, (byte) 0x00, (byte) 0x01, (byte) 0x00
				  , (byte) 0x0c, (byte) 0x1e, (byte) 0x80, (byte) 0x01, (byte) 0x00, (byte) 0x0b, (byte) 0x20, (byte) 0x00
				  , (byte) 0x03, (byte) 0x00, (byte) 0x0a, (byte) 0x23, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x08
				  , (byte) 0x24, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x07, (byte) 0x25, (byte) 0x00, (byte) 0x20
				  , (byte) 0x00, (byte) 0x06, (byte) 0x28, (byte) 0x00, (byte) 0x0a, (byte) 0x16, (byte) 0x0b, (byte) 0x16
				  , (byte) 0x0b, (byte) 0x16, (byte) 0x0b, (byte) 0x0b
		};
		assertArrayEquals("Header and search table", expectedStart, Arrays.copyOf(result, expectedStart.length));
	}


}
