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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.ArrayImgWriter;
import uk.me.parabola.imgfmt.app.BitWriterLR;
import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.log.Logger;


/**
 * The MDR 16 section contains data that allows to decode the data in MDR15. The basic algorithm is
 * Huffman encoding. The decoder works with some lookup tables. 
 * The section contains a few header bytes with a variable length, followed by an array of structures,
 * followed by an array of 32x2 or 64x2 bytes which encodes the most frequent symbols and finally an array 
 * of further symbols.    
 * It is possible to reconstruct a normal Huffman tree from this data but Garmin software probably only
 * uses the tables.  
 * @author Gerd Petermann
 *
 */
public class Mdr16 extends MdrSection implements HasHeaderFlags {
	private final Sort sort;
	private final Charset charset;
	private static final String ZEROS = "00000000000000000000000000000000";
	
	private final Code[] codes = new Code[256];

	/** the data that is written to MDR file */
	private byte[] data = new byte[0];
//	data = {
//			  (byte) 0xeb , (byte) 0x15 , (byte) 0x0d , (byte) 0x07 , (byte) 0x08 , (byte) 0x29 , (byte) 0x00 , (byte) 0x00
//			  , (byte) 0x0d , (byte) 0x00 , (byte) 0x04 , (byte) 0x00 , (byte) 0x0c , (byte) 0x04 , (byte) 0x08 , (byte) 0x00
//			  , (byte) 0x0a , (byte) 0x06 , (byte) 0x10 , (byte) 0x00 , (byte) 0x09 , (byte) 0x07 , (byte) 0x20 , (byte) 0x00
//			  , (byte) 0x08 , (byte) 0x08 , (byte) 0x80 , (byte) 0x00 , (byte) 0x07 , (byte) 0x0b , (byte) 0x80 , (byte) 0x01
//			  , (byte) 0x06 , (byte) 0x0f , (byte) 0x00 , (byte) 0x05 , (byte) 0x0a , (byte) 0x06 , (byte) 0x0c , (byte) 0x06
//			  , (byte) 0x0c , (byte) 0x06 , (byte) 0x0b , (byte) 0x47 , (byte) 0x0b , (byte) 0x54 , (byte) 0x0b , (byte) 0x55
//			  , (byte) 0x0b , (byte) 0x4d , (byte) 0x0b , (byte) 0x48 , (byte) 0x0b , (byte) 0x2d , (byte) 0x09 , (byte) 0x4c
//			  , (byte) 0x09 , (byte) 0x4c , (byte) 0x09 , (byte) 0x52 , (byte) 0x09 , (byte) 0x52 , (byte) 0x09 , (byte) 0x53
//			  , (byte) 0x09 , (byte) 0x53 , (byte) 0x09 , (byte) 0x4e , (byte) 0x09 , (byte) 0x4e , (byte) 0x09 , (byte) 0x49
//			  , (byte) 0x09 , (byte) 0x49 , (byte) 0x09 , (byte) 0x41 , (byte) 0x09 , (byte) 0x41 , (byte) 0x09 , (byte) 0x4f
//			  , (byte) 0x09 , (byte) 0x4f , (byte) 0x07 , (byte) 0x45 , (byte) 0x07 , (byte) 0x45 , (byte) 0x07 , (byte) 0x45
//			  , (byte) 0x07 , (byte) 0x45 , (byte) 0x07 , (byte) 0x00 , (byte) 0x07 , (byte) 0x00 , (byte) 0x07 , (byte) 0x00
//			  , (byte) 0x07 , (byte) 0x00 , (byte) 0x27 , (byte) 0x28 , (byte) 0x29 , (byte) 0xc7 , (byte) 0x8c , (byte) 0xcb
//			  , (byte) 0x20 , (byte) 0x51 , (byte) 0x4a , (byte) 0x58 , (byte) 0x59 , (byte) 0x46 , (byte) 0x50 , (byte) 0x57
//			  , (byte) 0x5a , (byte) 0x43 , (byte) 0x4b , (byte) 0x42 , (byte) 0x56 , (byte) 0x44
//	};	

	/* sample from old Garmin map:
--------- MDR 16 (decompression codebook Huffman tree) -------------------------
         |        |                         | Unknown 118 bytes:
00000238 | 000000 | eb 15 0d 07 08 29 00 00 | ├½....)..
00000240 | 000008 | 0d 00 04 00 0c 04 08 00 | ........
00000248 | 000010 | 0a 06 10 00 09 07 20 00 | ...... .
00000250 | 000018 | 08 08 80 00 07 0b 80 01 | ..┬Ç...┬Ç.
00000258 | 000020 | 06 0f 00 05 0a 06 0c 06 | ........
00000260 | 000028 | 0c 06 0b 47 0b 54 0b 55 | ...G.T.U
00000268 | 000030 | 0b 4d 0b 48 0b 2d 09 4c | .M.H.-.L
00000270 | 000038 | 09 4c 09 52 09 52 09 53 | .L.R.R.S
00000278 | 000040 | 09 53 09 4e 09 4e 09 49 | .S.N.N.I
00000280 | 000048 | 09 49 09 41 09 41 09 4f | .I.A.A.O
00000288 | 000050 | 09 4f 07 45 07 45 07 45 | .O.E.E.E
00000290 | 000058 | 07 45 07 00 07 00 07 00 | .E......
00000298 | 000060 | 07 00 27 28 29 c7 8c cb | ..'()├ç┬î├ï
000002a0 | 000068 | 20 51 4a 58 59 46 50 57 |  QJXYFPW
000002a8 | 000070 | 5a 43 4b 42 56 44       | ZCKBVD
*/	
	
	public Mdr16(MdrConfig config) {
		setConfig(config);
		sort = config.getSort();
		charset = sort.getCharset();
	}

	@Override
	public void writeSectData(ImgFileWriter writer) {
		writer.put(data);
	}
	
	@Override
	public int getItemSize() {
		return data.length;
	}

	@Override
	protected int numberOfItems() {
		return 1;
	}

	@Override
	public int getExtraValue() {
		return 0;
	}

	public void calc(int[] freqencies) {
		HuffmanNode root = buildTree(freqencies);
		if (root.ch != null) {
			// only one character in tree, this will be the 0, so nothing to compress
			return;
		}
		 
		printHuffmanTree(0, root);
		
		int maxDepth = root.maxDepth(0);
		if (maxDepth >= 32) {
			Logger.defaultLogger.warn("Huffman tree depth to high. Don't know how to encode that: ", maxDepth);
			return;
		}
		
		// Do we want a lookup table? Not sure how Garmin determines if it improves performance.
		int lookupBits = sort.getCodepage() == 65001 ? 6 : 5; // unicode always 6? Not sure about this
		if (maxDepth <= lookupBits)
			lookupBits = 0; // lookup table is not used.
		
		int code = 0;
		
		// contains the symbols which are not encoded in the lookup table. This will be written at the end of MDR16
		ByteBuffer remSymbols = ByteBuffer.allocate(256);
		
		// First table contains information about the deeper levels of the Huffman tree.
		// If lookupBits is 0 it contains all levels. Levels without any symbol are not stored.
		List<Mdr16Tab> tab1 = new ArrayList<>();
		for (int depth = maxDepth; depth > lookupBits; depth--) {
			ByteBuffer vals = getSymbolsForDepth(depth, root);
			if (vals.position() > 0) {
				Mdr16Tab tabEntry = new Mdr16Tab();
				tabEntry.depth = depth;
				tabEntry.offset = remSymbols.position();
				tabEntry.vals = Arrays.copyOf(vals.array(), vals.position());
				tabEntry.minCode = code << (maxDepth - depth); // shifting is needed for binary search
				tab1.add(tabEntry);
				vals.flip();
				while (vals.remaining() > 0) {
					byte b = vals.get();
					// Garmin doesn't use the codes from the Huffman tree
					// instead new values (with the same length) are assigned
					// the depth gives the code length
					addCode(b, depth, code++);
					remSymbols.put(b);
				}
			}
			// shift the code for each level once a none-empty level was found
			if (code > 0)
				code >>= 1;
		}
		
		byte[] lookupTable = calcLookupTable(root, tab1, lookupBits, maxDepth);
		
		int tab1Width = 2 + (int) Math.ceil(maxDepth / 8.0); // not sure about this
		int remSymbolsSizeBytes = MdrUtils.writeVarLength(null, remSymbols.position());
		int headerSize = 4 + remSymbolsSizeBytes;
		int remainingBytes = headerSize + remSymbols.position() + lookupTable.length + tab1.size() * tab1Width;
		int mdr16Size = MdrUtils.writeVarLength(null, remainingBytes) + remainingBytes;
		
		// compare number of bits required with/without compression
		long numBitsNormal = 0; 
		long numBitsCompressed = 0; 
		for (int i = 0; i < freqencies.length; i++) {
			numBitsNormal += 8 * freqencies[i];
			if (codes[i] != null) {
				numBitsCompressed += freqencies[i] * codes[i].len;
			}
		}
		// add size of MDR16 and on average we can expect 4 wasted bits for the terminating 0 in each string
		numBitsCompressed += mdr16Size * 8 + freqencies[0] * 4L;
		
		if (numBitsNormal <= numBitsCompressed ) {
			// we ignore the case that the string offsets might be written with only 3 instead of 4 bytes
			// as it is very unlikely that a file > 16MM  shows no gain
			Logger.defaultLogger.warn("Huffman encoding disbled, will not save any space.");
			return;
		}
		
		try (ArrayImgWriter writer = new ArrayImgWriter()) {
			MdrUtils.writeVarLength(writer, remainingBytes);
			writer.put1u(0x10 | lookupBits);
			writer.put1u(maxDepth);
			writer.put1u(tab1.size());
			writer.put1u(8); // symbol width
			MdrUtils.writeVarLength(writer, remSymbols.position());
			for (Mdr16Tab e : tab1) {
				writer.putNu(tab1Width - 2, e.minCode);
				writer.put1u(e.depth);
				writer.put1u(e.offset);
			}
			writer.put(lookupTable);
			remSymbols.flip();
			writer.put(remSymbols);
			data = writer.getBytes();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Calculate the lookup table. This is only used if the tree has more than 5 or 6 levels
	 */
	private byte[] calcLookupTable(HuffmanNode root, List<Mdr16Tab> tab1, int lookupBits, int maxDepth) {
		byte[] tab2 = new byte[(1 << lookupBits) * 2];
		if (lookupBits <= 0)
			return tab2; // will just contain two bytes with 0

		int tab2Rows = 1 << lookupBits;
		int pos = tab2Rows - 1; // we start at the end of the table

		for (int depth = 0; depth <= lookupBits; depth++) {
			ByteBuffer symbols = getSymbolsForDepth(depth, root);
			if (symbols.position() == 0)
				continue;
			symbols.flip();

			// the depth gives the code length. If that length is smaller than the number of bits
			// in the lookup part we have to fill multiple rows of the lookup table with the same 
			// value.
			int repeat = 1 << (lookupBits - depth);
			while (symbols.remaining() > 0) {
				byte v0 = (byte) (depth * 2 + 1); // odd value is flag for a symbol
				byte v1 = symbols.get();
				
				int code = pos >> (lookupBits - depth);
				addCode(v1, depth, code);
				for (int i = 0; i < repeat; i++) {
					tab2[pos * 2] = v0;
					tab2[pos * 2 + 1] = v1;
					
					String prefix = Integer.toBinaryString(pos);
					// add leading 0 to make it at least lookupBits long
					prefix = ZEROS.substring(0, lookupBits - prefix.length()) + prefix;
					Logger.defaultLogger
							.diagnostic(String.format("tab2: %2d %s %d %s", pos, prefix, v0, displayChar(v1)));
					pos--;
				}
			}
		}
		// the lower entries in the table may give min/max entries for the binary search in search table 
		int maxIdx = tab1.size() - 1;
		if (maxIdx <= 0)
			return tab2; // avoid special cases, table is already filled with 0 
		int minIdx = maxIdx;
		
		while (pos >= 0) {
			int minCode = pos << (maxDepth - lookupBits);
			if (tab1.get(minIdx).minCode <= minCode) {
				byte v0 = (byte) (minIdx * 2); // even value signals that an index into tab1 
				byte v1 = (byte) maxIdx; // highest index in tab1 that contains values for this entry
				tab2[pos * 2] = v0;
				tab2[pos * 2 + 1] = v1;
				String prefix = Integer.toBinaryString(pos);
				prefix = ZEROS.substring(0, lookupBits - prefix.length()) + prefix;
				Logger.defaultLogger.diagnostic(String.format("tab2: %2d %s %2d %2d",pos, prefix, v0, v1));
				pos--;
				if (minCode == tab1.get(minIdx).minCode && minIdx > 0) {
					minIdx--;
				}
				maxIdx = minIdx;
			} else {
				minIdx--;
				if (minIdx < 0) {
					throw new MapFailedException("Failed to calculate data for MDR16");
				}
			}
		}
		return tab2;
	}
	
	private void addCode(byte ch, int len, int code) {
		String prefix = Integer.toBinaryString(code);
		if (prefix.length() < len)
			prefix = ZEROS.substring(0, len - prefix.length()) + prefix;
		Logger.defaultLogger.diagnostic(String.format("Huffman code: %s %s", prefix, displayChar(ch)));
		
		Code coded = new Code(len, code);
		codes[ch & 0xff] = coded;
	}

	private HuffmanNode buildTree(int[] freqencies) {
		PriorityQueue<HuffmanNode> q = new PriorityQueue<>(128, (o1, o2) -> Integer.compare(o1.freq, o2.freq));
		
		for (int i = 0; i < freqencies.length; i++) {
			if (freqencies[i] > 0) {
				q.add(new HuffmanNode((byte) i, freqencies[i], null, null));
			}
		}
		HuffmanNode parent = null;
		while (q.size() > 1) {
			HuffmanNode left = q.remove();
			HuffmanNode right = q.remove();
			parent = new HuffmanNode(null, left.freq + right.freq, left, right);
			q.add(parent);
		}
		parent = q.remove();
		return parent;
	}

	private void printHuffmanTree(int depth, HuffmanNode node) {
		if (node == null)
			return;
		if (node.ch != null) {
			Logger.defaultLogger.diagnostic(
					String.format("depth:%d %s freq: %d", depth, displayChar((byte) (node.ch & 0xff)), node.freq));
		}
		printHuffmanTree(depth + 1, node.left);
		printHuffmanTree(depth + 1, node.right);
	}
	
	private ByteBuffer getSymbolsForDepth(int wantedDepth, HuffmanNode root) {
		ByteBuffer buffer = ByteBuffer.allocate(256);
		getSymbolsForDepth(0, wantedDepth, buffer, root);
		return buffer;
	}

	private void getSymbolsForDepth(int depth, int wantedDepth, ByteBuffer symbols, HuffmanNode node) {
		if (node.ch != null && wantedDepth == depth) {
			symbols.put(node.ch);
		}
		if (node.left != null)
			getSymbolsForDepth(depth + 1, wantedDepth, symbols, node.left);
		if (node.right != null)
			getSymbolsForDepth(depth + 1, wantedDepth, symbols, node.right);
	}
	
	private String displayChar(byte val) {
		byte[] bb = { val };
		CharBuffer cbuf = charset.decode(ByteBuffer.wrap(bb));
		char v = cbuf.get();

		return String.format("'%c' 0x%02x", v == 0 ? '.' : v, val & 0xff);
	}

	private static class HuffmanNode {
		final Byte ch;
		final int freq;
		final HuffmanNode left;
		final HuffmanNode right;
		public HuffmanNode(Byte b, int freq, HuffmanNode l, HuffmanNode r) {
			this.ch = b;
			this.freq = freq;
			this.left = l;
			this.right = r;
		}
		
		int maxDepth(int depth) {
			if (ch != null)
				return depth;
			return Math.max(left.maxDepth(depth + 1), right.maxDepth(depth + 1));
		}
	}
	
	private static class Mdr16Tab {
		int depth;
		int offset;
		byte[] vals;
		int minCode;
		
		@Override
		public String toString() {
			return "depth=" + depth + ", offset=" + offset + ", vals=" + Arrays.toString(vals);
		}
	}

	private static class Code {
		final int len;
		final int bits;
		
		public Code(int len, int bits) {
			super();
			this.len = len;
			this.bits = bits;
		}
	}
	
	public boolean canEncode() {
		// TODO: refuse to encode codepage with more than 8 bit like 932
		return sort.getCodepage() == 65001 || (sort.getCodepage() >= 1250 && sort.getCodepage() <= 1258);
	}
	
	public byte[] encode(ByteBuffer buf) {
		BitWriterLR bw = new BitWriterLR();
		byte b;
		while (buf.remaining() > 0) {
			b = buf.get();
			Code code = codes[b & 0xff];
			if (code == null || code.len == 0)
				throw new MapFailedException("invalid code found");

			int bitToWrite = 1 << (code.len - 1);
			while (bitToWrite != 0) {
				bw.put1((bitToWrite & code.bits) != 0);
				bitToWrite >>= 1;
			}
		}
		return Arrays.copyOf(bw.getBytes(), bw.getLength());
	}

	public boolean isValid() {
		return data.length > 0;
	}
	
	public byte[] getData() {
		return data;
	}
}
