/*
 * Copyright (C) 2010, 2011.
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.app.Label;

/**
 * Represents the sorting positions for all the characters in a codepage.
 *
 * A map contains a file that determines how the characters are to be sorted. So we
 * have to have to be able to create such a file and sort with exactly the same rules
 * as is contained in it.
 *
 * What about the java {@link java.text.RuleBasedCollator}? It turns out that it is possible to
 * make it work in the way we need it to, although it doesn't help with creating the srt file.
 * Also it is significantly slower than this implementation, so this one is staying. I also
 * found that sorting with the sort keys and the collator gave different results in some
 * cases. This implementation does not.
 *
 * Be careful when benchmarking. With small lists (< 10000 entries) repeated runs cause some
 * pretty aggressive optimisation to kick in. This tends to favour this implementation which has
 * much tighter loops that the java7 or ICU implementations, but this may not be realised with
 * real workloads.
 *
 * @author Steve Ratcliffe
 */
public class Sort {
	private static final byte[] ZERO_KEY = new byte[3];
	private static final Integer NO_ORDER = 0;

	private int codepage;
	private int id1; // Unknown - identifies the sort
	private int id2; // Unknown - identifies the sort

	private String description;
	private Charset charset;

	private final Page[] pages = new Page[256];

	private final List<CodePosition> expansions = new ArrayList<>();
	private int maxExpSize = 1;

	private CharsetEncoder encoder;

	public Sort() {
		pages[0] = new Page();
	}

	public void add(int ch, int primary, int secondary, int tertiary, int flags) {
		if (getPrimary(ch) != 0)
			throw new ExitException(String.format("Repeated primary index 0x%x", ch & 0xff));
		setPrimary (ch, primary);
		setSecondary(ch, secondary);
		setTertiary( ch, tertiary);

		setFlags(ch, flags);
	}

	/**
	 * Run after all sorting order points have been added.
	 *
	 * Make sure that all tertiary values of secondary ignorable are greater
	 * than any normal tertiary value.
	 *
	 * And the same for secondaries on primary ignorable.
	 */
	public void finish() {
		int maxSecondary = 0;
		int maxTertiary = 0;
		for (Page p : pages) {
			if (p == null)
				continue;

			for (int i = 0; i < 256; i++) {
				if (((p.flags[i] >>> 4) & 0x3) == 0) {
					if (p.primary[i] != 0) {
						byte second = p.secondary[i];
						maxSecondary = Math.max(maxSecondary, second);
						if (second != 0) {
							maxTertiary = Math.max(maxTertiary, p.tertiary[i]);
						}
					}
				}
			}
		}

		for (Page p : pages) {
			if (p == null)
				continue;

			for (int i = 0; i < 256; i++) {
				if (((p.flags[i] >>> 4) & 0x3) != 0) continue;

				if (p.primary[i] == 0) {
					if (p.secondary[i] == 0) {
						if (p.tertiary[i] != 0) {
							p.tertiary[i] += maxTertiary;
						}
					} else {
						p.secondary[i] += maxSecondary;
					}
				}
			}
		}
	}

	/**
	 * Return a table indexed by a character value in the target codepage, that gives the complete sort
	 * position of the character.
	 *
	 * This is only used for testing.
	 *
	 * @return A table of sort positions.
	 */
	public char[] getSortPositions() {
		char[] tab = new char[256];

		for (int i = 1; i < 256; i++) {
			tab[i] = (char) (((getPrimary(i) << 8) & 0xff00) | ((getSecondary(i) << 4) & 0xf0) | (getTertiary(i) & 0xf));
		}

		return tab;
	}

	/**
	 * Create a sort key for a given unicode string.  The sort key can be compared instead of the original strings
	 * and will compare based on the sorting represented by this Sort class.
	 *
	 * Using a sort key is more efficient if many comparisons are being done (for example if you are sorting a
	 * list of strings).
	 *
	 * @param object This is saved in the sort key for later retrieval and plays no part in the sorting.
	 * @param s The string for which the sort key is to be created.
	 * @param second Secondary sort key.
	 * @param cache A cache for the created keys. This is for saving memory so it is essential that this
	 * is managed by the caller.
	 * @return A sort key.
	 */
	public <T> SortKey<T> createSortKey(T object, String s, int second, Map<String, byte[]> cache) {
		// If there is a cache then look up and return the key.
		// This is primarily for memory management, not for speed.
		byte[] key;
		if (cache != null) {
			key = cache.get(s);
			if (key != null)
				return new SrtSortKey<>(object, key, second);
		}

		try {
			ByteBuffer out = encoder.encode(CharBuffer.wrap(s));
			byte[] bval = out.array();

			// In theory you could have a string where every character expands into maxExpSize separate characters
			// in the key.  However if we allocate enough space to deal with the worst case, then we waste a
			// vast amount of memory. So allocate a minimal amount of space, try it and if it fails reallocate the
			// maximum amount.
			//
			// We need +1 for the null bytes, we also +2 for a couple of expanded characters. For a complete
			// german map this was always enough in tests.
			key = new byte[(bval.length + 1 + 2) * 3];
			try {
				fillCompleteKey(bval, key);
			} catch (ArrayIndexOutOfBoundsException e) {
				// Ok try again with the max possible key size allocated.
				key = new byte[bval.length * 3 * maxExpSize + 3];
				fillCompleteKey(bval, key);
			}

			if (cache != null)
				cache.put(s, key);

			return new SrtSortKey<>(object, key, second);
		} catch (CharacterCodingException e) {
			return new SrtSortKey<>(object, ZERO_KEY);
		}
	}

	public <T> SortKey<T> createSortKey(T object, Label label, int second, Map<Label, byte[]> cache) {
		byte[] key;
		if (cache != null) {
			key = cache.get(label);
			if (key != null)
				return new SrtSortKey<>(object, key, second);
		}

		char[] encText = label.getEncText();
		byte[] bval = new byte[encText.length];
		for (int i = 0; i < encText.length; i++) {
			assert (encText[i] & 0xff00) == 0;
			bval[i] = (byte) encText[i];
		}

		// In theory you could have a string where every character expands into maxExpSize separate characters
		// in the key.  However if we allocate enough space to deal with the worst case, then we waste a
		// vast amount of memory. So allocate a minimal amount of space, try it and if it fails reallocate the
		// maximum amount.
		//
		// We need +1 for the null bytes, we also +2 for a couple of expanded characters. For a complete
		// german map this was always enough in tests.
		key = new byte[(bval.length + 1 + 2) * 3];
		try {
			fillCompleteKey(bval, key);
		} catch (ArrayIndexOutOfBoundsException e) {
			// Ok try again with the max possible key size allocated.
			key = new byte[bval.length * 3 * maxExpSize + 3];
			fillCompleteKey(bval, key);
		}

		if (cache != null)
			cache.put(label, key);

		return new SrtSortKey<>(object, key, second);
	}

	/**
	 * Convenient version of create sort key method.
	 * @see #createSortKey(Object, String, int, Map)
	 */
	public <T> SortKey<T> createSortKey(T object, String s, int second) {
		return createSortKey(object, s, second, null);
	}

	/**
	 * Convenient version of create sort key method.
	 *
	 * @see #createSortKey(Object, String, int, Map)
	 */
	public <T> SortKey<T> createSortKey(T object, String s) {
		return createSortKey(object, s, 0, null);
	}

	public <T> SortKey<T> createSortKey(T object, Label label) {
		return createSortKey(object, label, 0, null);
	}

	public <T> SortKey<T> createSortKey(T object, Label label, int second) {
		return createSortKey(object, label, second, null);
	}

	/**
	 * Fill in the key from the given byte string.
	 *
	 * @param bval The string for which we are creating the sort key.
	 * @param key The sort key. This will be filled in.
	 */
	private void fillCompleteKey(byte[] bval, byte[] key) {
		int start = fillKey(Collator.PRIMARY, pages[0].primary, bval, key, 0);
		start = fillKey(Collator.SECONDARY, pages[0].secondary, bval, key, start);
		fillKey(Collator.TERTIARY, pages[0].tertiary, bval, key, start);
	}

	/**
	 * Fill in the output key for a given strength.
	 *
	 * @param sortPositions An array giving the sort position for each of the 256 characters.
	 * @param input The input string in a particular 8 bit codepage.
	 * @param outKey The output sort key.
	 * @param start The index into the output key to start at.
	 * @return The next position in the output key.
	 */
	private int fillKey(int type, byte[] sortPositions, byte[] input, byte[] outKey, int start) {
		int index = start;
		for (byte inb : input) {
			int b = inb & 0xff;

			int exp = (getFlags(b) >> 4) & 0x3;
			if (exp == 0) {
				byte pos = sortPositions[b];
				if (pos != 0)
					outKey[index++] = pos;
			} else {
				// now have to redirect to a list of input chars, get the list via the primary value always.
				int idx = getPrimary(b);
				//List<CodePosition> list = expansions.get(idx-1);

				for (int i = idx - 1; i < idx + exp; i++) {
					byte pos = expansions.get(i).getPosition(type);
					if (pos != 0)
						outKey[index++] = pos;
				}
			}
		}

		outKey[index++] = '\0';
		return index;
	}

	public int getPrimary(int ch) {
		return this.pages[ch >>> 8].primary[ch & 0xff];
	}

	public int getSecondary(int ch) {
		return this.pages[ch >>> 8].secondary[ch & 0xff];
	}

	public int getTertiary(int ch) {
		return this.pages[ch >>> 8].tertiary[ch & 0xff];
	}

	public byte getFlags(int ch) {
		return this.pages[ch >>> 8].flags[ch & 0xff];
	}

	public int getCodepage() {
		return codepage;
	}

	public Charset getCharset() {
		return charset;
	}

	public int getId1() {
		return id1;
	}

	public void setId1(int id1) {
		this.id1 = id1;
	}

	public int getId2() {
		return id2;
	}

	public void setId2(int id2) {
		this.id2 = id2 & 0x7fff;
	}

	/**
	 * Get the sort order as a single integer.
	 * A combination of id1 and id2. I think that they are arbitrary so may as well treat them as one.
	 *
	 * @return id1 and id2 as if they were a little endian 2 byte integer.
	 */
	public int getSortOrderId() {
		return (this.id2 << 16) + (this.id1 & 0xffff);
	}

	/**
	 * Set the sort order as a single integer.
	 * @param id The sort order id.
	 */
	public void setSortOrderId(int id) {
		id1 = id & 0xffff;
		id2 = (id >>> 16) & 0x7fff;
	}

	public void setCodepage(int codepage) {
		this.codepage = codepage;
		charset = charsetFromCodepage(codepage);

		encoder = charset.newEncoder();
		encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * Add an expansion to the sort.
	 * An expansion is a letter that sorts as if it were two separate letters.
	 *
	 * The case were two letters sort as if the were just one (and more complex cases) are
	 * not supported or are unknown to us.
	 *
	 * @param bval The code point of this letter in the code page.
	 * @param inFlags The initial flags, eg if it is a letter or not.
	 * @param expansionList The letters that this letter sorts as, as code points in the codepage.
	 */
	public void addExpansion(byte bval, int inFlags, List<Byte> expansionList) {
		int idx = bval & 0xff;
		setFlags(idx, (byte) ((inFlags & 0xf) | (((expansionList.size()-1) << 4) & 0x30)));

		// Check for repeated definitions
		if (getPrimary(idx) != 0)
			throw new ExitException(String.format("repeated code point %x", idx));

		setPrimary(idx, (expansions.size() + 1));
		setSecondary(idx,  0);
		setTertiary(idx, 0);
		maxExpSize = Math.max(maxExpSize, expansionList.size());

		for (Byte b : expansionList) {
			CodePosition cp = new CodePosition();
			cp.setPrimary((byte) getPrimary(b & 0xff));

			// Currently sort without secondary or tertiary differences to the base letters.
			cp.setSecondary((byte) getSecondary(b & 0xff));
			cp.setTertiary((byte) getTertiary(b & 0xff));
			expansions.add(cp);
		}
	}

	/**
	 * Get the expansion with the given index, one based.
	 * @param val The one-based index number of the extension.
	 */
	public CodePosition getExpansion(int val) {
		return expansions.get(val - 1);
	}

	public Collator getCollator() {
		return new SrtCollator(codepage);
	}

	public int getExpansionSize() {
		return expansions.size();
	}

	public String toString() {
		return String.format("sort cp=%d order=%08x", codepage, getSortOrderId());
	}

	private void setPrimary(int ch, int val) {
		this.pages[ch >>> 8].primary[ch & 0xff] = (byte) val;
	}

	private void setSecondary(int ch, int val) {
		this.pages[ch >>> 8].secondary[ch & 0xff] = (byte) val;
	}

	private void setTertiary(int ch, int val) {
		this.pages[ch >>> 8].tertiary[ch & 0xff] = (byte) val;
	}

	private void setFlags(int ch, int val) {
		this.pages[ch >>> 8].flags[ch & 0xff] = (byte) val;
	}

	public static Charset charsetFromCodepage(int codepage) {
		Charset charset;
		switch (codepage) {
		case 0:
			charset = Charset.forName("ascii");
			break;
		case 65001:
			charset = Charset.forName("UTF-8");
			break;
		case 932:
			// Java uses "ms932" for code page 932
			// (Windows-31J, Shift-JIS + MS extensions)
			charset = Charset.forName("ms932");
			break;
		default:
			charset = Charset.forName("cp" + codepage);
			break;
		}
		return charset;
	}

	private static class Page {
		private final byte[] primary = new byte[256];
		private final byte[] secondary = new byte[256];
		private final byte[] tertiary = new byte[256];
		private final byte[] flags = new byte[256];
	}

	/**
	 * A collator that works with this sort. This should be used if you just need to compare two
	 * strings against each other once.
	 *
	 * The sort key is better when the comparison must be done several times as in a sort operation.
	 *
	 * This implementation has the same effect when used for sorting as the sort keys.
	 */
	private class SrtCollator extends Collator {
		private final int codepage;

		private SrtCollator(int codepage) {
			this.codepage = codepage;
		}

		public int compare(String source, String target) {
			CharBuffer in1 = CharBuffer.wrap(source);
			CharBuffer in2 = CharBuffer.wrap(target);
			byte[] bytes1;
			byte[] bytes2;
			try {
				bytes1 = encoder.encode(in1).array();
				bytes2 = encoder.encode(in2).array();
			} catch (CharacterCodingException e) {
				throw new ExitException("character encoding failed unexpectedly", e);
			}

			int strength = getStrength();
			int res = compareOneStrength(bytes1, bytes2, pages[0].primary, Collator.PRIMARY);

			if (res == 0 && strength != PRIMARY) {
				res = compareOneStrength(bytes1, bytes2, pages[0].secondary, Collator.SECONDARY);
				if (res == 0 && strength != SECONDARY) {
					res = compareOneStrength(bytes1, bytes2, pages[0].tertiary, Collator.TERTIARY);
				}
			}

			return res;
		}

		/**
		 * Compare the bytes against primary, secondary or tertiary arrays.
		 * @param bytes1 Bytes for the first string in the codepage encoding.
		 * @param bytes2 Bytes for the second string in the codepage encoding.
		 * @param typePositions The strength array to use in the comparison.
		 * @return Comparison result -1, 0 or 1.
		 */
		private int compareOneStrength(byte[] bytes1, byte[] bytes2, byte[] typePositions, int type) {
			int res = 0;

			PositionIterator it1 = new PositionIterator(bytes1, typePositions, type);
			PositionIterator it2 = new PositionIterator(bytes2, typePositions, type);

			while (it1.hasNext() || it2.hasNext()) {
				int p1 = it1.next();
				int p2 = it2.next();

				if (p1 < p2) {
					res = -1;
					break;
				} else if (p1 > p2) {
					res = 1;
					break;
				}
			}

			return res;
		}

		public CollationKey getCollationKey(String source) {
			throw new UnsupportedOperationException("use Sort.createSortKey() instead");
		}

		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			SrtCollator that = (SrtCollator) o;

			if (codepage != that.codepage) return false;
			return true;
		}

		public int hashCode() {
			return codepage;
		}

		class PositionIterator implements Iterator<Integer> {
			private final byte[] bytes;
			private final byte[] sortPositions;
			private final int len;
			private final int type;

			private int pos;

			private int expStart;
			private int expEnd;
			private int expPos;

			PositionIterator(byte[] bytes, byte[] sortPositions, int type) {
				this.bytes = bytes;
				this.sortPositions = sortPositions;
				this.len = bytes.length;
				this.type = type;
			}

			public boolean hasNext() {
				return pos < len || expPos != 0;
			}

			/**
			 * Get the next sort order value for the input string. Does not ever return values
			 * that are ignorable. Returns NO_ORDER at (and beyond) the end of the string, this
			 * value sorts less than any other and so makes shorter strings sort first.
			 * @return The next non-ignored sort position. At the end of the string it returns
			 * NO_ORDER.
			 */
			public Integer next() {
				int next;
				if (expPos == 0) {

					do {
						if (pos >= len) {
							next = NO_ORDER;
							break;
						}

						// Get the first non-ignorable at this level
						byte b = bytes[(pos++ & 0xff)];
						next = sortPositions[b & 0xff] & 0xff;
						int nExpand = (getFlags(b & 0xff) >> 4) & 0x3;

						// Check if this is an expansion.
						if (nExpand > 0) {
							expStart = getPrimary(b & 0xff) - 1;
							expEnd = expStart + nExpand;
							expPos = expStart;
							next = expansions.get(expPos).getPosition(type) & 0xff;

							if (++expPos > expEnd)
								expPos = 0;
						}
					} while (next == 0);
				} else {
					next = expansions.get(expPos).getPosition(type) & 0xff;
					if (++expPos > expEnd)
						expPos = 0;
				}

				return next;
			}

			public void remove() {
				throw new UnsupportedOperationException("remove not supported");
			}
		}
	}
}
