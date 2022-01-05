/*
 * Copyright (C) 2013-2021.
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

	// initialize with 
	byte[] data = new byte[0];
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
		printHuffmanTree(0, root);
		int initBits = sort.getCodepage() == 65001 ? 6:5; // not sure about this
		
		int maxDepth = 0;
		int code = 0;
		ByteBuffer remSymbols = ByteBuffer.allocate(256);
		List<Mdr16Tab> tab1 = new ArrayList<>();
		for (int depth = 32; depth > initBits; depth--) {
			ByteBuffer vals = ByteBuffer.allocate(256);
			getValsForDepth(0, depth, vals, root);
			if (vals.position() > 0) {
				if (depth > maxDepth)
					maxDepth = depth;
				Mdr16Tab tabEntry = new Mdr16Tab();
				tabEntry.depth = depth;
				tabEntry.offset = remSymbols.position();
				tabEntry.vals = Arrays.copyOf(vals.array(), vals.position());
				tabEntry.minCode = code << (maxDepth - depth); // why the shifting???
				tab1.add(tabEntry);
				vals.flip();
				while (vals.remaining() > 0) {
					byte b = vals.get();
					addCode(b, depth, code++, initBits);
					remSymbols.put(b);
				}
			}
			if (code > 0)
				code >>= 1;
		}
		byte[] tab2 = calcTab2(tab1, initBits, root);
		
		
		int tab1Width = 2 + (int) Math.ceil(maxDepth / 8.0); // not sure about this
		
		int neededBytes = remSymbols.position() + tab2.length + tab1.size() * tab1Width;
		int remSymbolsSizeBytes = remSymbols.position() >= 128 ? 2 : 1;
		int headerBytes = neededBytes + remSymbolsSizeBytes + 4;
		ByteBuffer mdr16Bytes = ByteBuffer.allocate(headerBytes + 2);
		if (headerBytes >= 128) {
			mdr16Bytes.put((byte) ((headerBytes << 2 & 0xff) + 2));
			mdr16Bytes.put((byte) (headerBytes >> 6));
		} else {
			mdr16Bytes.put((byte) (headerBytes * 2 + 1));
		}
		mdr16Bytes.put((byte) (0x10 | initBits));
		mdr16Bytes.put((byte) maxDepth);
		mdr16Bytes.put((byte) tab1.size());
		mdr16Bytes.put((byte) 8); // symbol width
		final int remSymboulsSize = remSymbols.position();
		if (remSymboulsSize >= 128) {
			mdr16Bytes.put((byte) ((remSymboulsSize << 2 & 0xff) + 2));
			mdr16Bytes.put((byte) (remSymboulsSize >> 6));
		} else {
			mdr16Bytes.put((byte) (remSymboulsSize * 2 + 1));
		}
		try (ArrayImgWriter writer = new ArrayImgWriter()) {
			for (Mdr16Tab e : tab1) {
				writer.putNu(tab1Width - 2, e.minCode);
				assert e.depth > 0 && e.depth < 32 : "invalid depth:" + e.depth;
				assert e.offset >= 0 && e.offset < 128: "invalid offset:" + e.offset;
				writer.put1u(e.depth);
				writer.put1u(e.offset);
			}
			mdr16Bytes.put(writer.getBytes());
		} catch (IOException e) {
			e.printStackTrace();
		}
		mdr16Bytes.put(tab2);
		remSymbols.flip();
		mdr16Bytes.put(remSymbols);
		mdr16Bytes.flip();
		data = Arrays.copyOf(mdr16Bytes.array(), mdr16Bytes.remaining());
	}

	private byte[] calcTab2(List<Mdr16Tab> tab1, int initBits, HuffmanNode root) {
		int tab2Rows = 1 << initBits; 
		int pos = tab2Rows - 1;
		byte[] tab2 = new byte[tab2Rows * 2];
		int idx1 = tab1.size() + 1;
		int lastIndex = tab1.size() - 1;
		int carry = 0;
		
		for (int depth = 0; depth <= 32; depth++) {
			ByteBuffer vals = ByteBuffer.allocate(256);
			getValsForDepth(0, depth, vals, root);
			if (vals.position() == 0)
				continue;
			
			int repeat = 1;
			if (depth < initBits)
				repeat = 1 << (initBits - depth);
			else {
				idx1--;
			}

			if (depth > initBits)
				assert tab1.get(idx1).vals.length == vals.position();
			vals.flip();
			if (depth <= initBits) {
				while (vals.remaining() > 0) {
					byte ch = vals.get();
					for (int i = 0; i < repeat; i++) {
						byte v0 = (byte) (depth * 2 + 1);
						byte v1 = ch;
						tab2[pos * 2] = v0;
						tab2[pos * 2 + 1] = v1;

						String prefix = Integer.toBinaryString(pos);
						// add leading 0 to make it at least initBits long
						prefix = ZEROS.substring(0, initBits - prefix.length()) + prefix;
						Logger.defaultLogger.diagnostic(String.format("tab2: %2d %s %d %s",pos, prefix, v0, displayChar(v1)));
						addCode(ch, depth, pos, initBits);
						pos--;
						if (pos <= 0) {
							// no idea if this can happen
							tab2[0] = 0;
							tab2[1] = (byte) depth;
							return tab2;
						}
					}
				}
			} else {
				int numVals = (1 << (depth - initBits)) - carry;
				while (vals.remaining() >= numVals) {
					for (int i = 0; i < numVals; i++)
						vals.get();
					byte v0 = (byte) (idx1 * 2);
					byte v1 = (byte) lastIndex;
					tab2[pos * 2] = v0;
					tab2[pos * 2 + 1] = v1;
					carry = 0;
					lastIndex = idx1;
					numVals = (1 << (depth - initBits));
					String prefix = Integer.toBinaryString(pos);
					prefix = ZEROS.substring(0, initBits - prefix.length()) + prefix;
					Logger.defaultLogger.diagnostic(String.format("tab2: %2d %s %2d %2d",pos, prefix, v0, v1));
					pos--;
					if (pos <= 0) {
						tab2[0] = 0;
						if (vals.remaining() == 0)
							idx1--;
						tab2[1] = (byte) idx1;
						prefix = ZEROS.substring(0, initBits);
						Logger.defaultLogger.diagnostic(String.format("tab2: %2d %s %2d %2d",0, prefix, 0, idx1));
						return tab2;
					}
				}
				if (vals.remaining() == 0) 
					lastIndex = idx1 - 1;  

				carry += vals.remaining();
				
			}
		}
		Logger.defaultLogger.error("Possibly failed to calculate Mdr16");
		while (pos >= 0) {
			tab2[pos * 2] = 0;
			tab2[pos * 2 + 1] = (byte) lastIndex;
			pos--;
		}
		// we should not get here
		return tab2; 
	}

	private void addCode(byte ch, int len, int code, int initBits) {
		if (len > initBits) {
			String prefix = Integer.toBinaryString(code);
			if (prefix.length() < len)
				prefix = ZEROS.substring(0, len - prefix.length()) + prefix;
			Logger.defaultLogger.diagnostic(String.format("Huffman code: %s %s", prefix, displayChar(ch)));
		}
		Code coded = new Code(len, len < initBits ? code >> (initBits - len) : code);
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
			Logger.defaultLogger.debug(
					String.format("depth:%d %s freq: %d", depth, displayChar((byte) (node.ch & 0xff)), node.freq));
		}
		printHuffmanTree(depth + 1, node.left);
		printHuffmanTree(depth + 1, node.right);
	}
	
	private void getValsForDepth(int depth, int wantedDepth, ByteBuffer vals, HuffmanNode node) {
		if (node.ch != null && wantedDepth == depth) {
			vals.put(node.ch);
		}
		if (node.left != null)
			getValsForDepth(depth + 1, wantedDepth, vals, node.left);
		if (node.right != null)
			getValsForDepth(depth + 1, wantedDepth, vals, node.right);
	}			

	private String displayChar(byte val) {
		byte[] bb = { val };
		CharBuffer cbuf = charset.decode(ByteBuffer.wrap(bb));
		char v = cbuf.get();

		return String.format("'%c' 0x%02x", v == 0 ? '.' : v, val & 0xff);
	}

	private static class HuffmanNode {
		Byte ch;
		int freq;
		HuffmanNode left;
		HuffmanNode right;
		public HuffmanNode(Byte b, int freq, HuffmanNode l, HuffmanNode r) {
			this.ch = b;
			this.freq = freq;
			this.left = l;
			this.right = r;
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
			if (code == null)
				throw new MapFailedException("invalid code found");

			int bitToWrite = 1 << (code.len - 1);
			while (bitToWrite != 0) {
				bw.put1((bitToWrite & code.bits) != 0);
				bitToWrite >>= 1;
			}
		}
		return Arrays.copyOf(bw.getBytes(), bw.getLength());
	}
	
}
