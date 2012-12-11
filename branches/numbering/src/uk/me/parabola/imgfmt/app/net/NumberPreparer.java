/*
 * Copyright (C) 2008 Steve Ratcliffe
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package uk.me.parabola.imgfmt.app.net;

import java.util.EnumSet;
import java.util.List;

import uk.me.parabola.imgfmt.app.BitReader;
import uk.me.parabola.imgfmt.app.BitWriter;
import uk.me.parabola.log.Logger;

import static uk.me.parabola.imgfmt.app.net.NumberStyle.*;

/**
 * Class to prepare the bit stream of the house numbering information.
 *
 * There are multiple ways to encode the same numbers, the trick is to find a way that is reasonably
 * small. Will start out with a basic implementation and then optimise as we go on.
 *
 * @author Steve Ratcliffe
 */
public class NumberPreparer {
	private static final Logger log = Logger.getLogger(NumberPreparer.class);

	private final List<Numbers> numbers;
	private boolean valid;

	// The minimum values of the start and end bit widths.
	private static final int START_WIDTH_MIN = 5;
	private static final int END_WIDTH_MIN = 2;

	private BitWriter bw;
	private boolean swappedDefaultStyle;

	public NumberPreparer(List<Numbers> numbers) {
		this.numbers = numbers;
	}

	/**
	 * Make the bit stream and return it. This is only done once, if you call this several times
	 * the same bit writer is returned every time.
	 * @return A bit writer containing the computed house number stream.
	 */
	public BitWriter fetchBitStream() {
		if (bw != null)
			return bw;

		// Calculate some initial things, if we should use the swapped default numbering style EVEN/ODD rather than
		// ODD/EVEN and the initialValue.
		Numbers first = numbers.get(0);
		if (first.getLeftNumberStyle() == EVEN && first.getRightNumberStyle() == ODD)
			swappedDefaultStyle = true;

		int leftStart = first.getLeftStart();
		int rightStart = first.getRightStart();
		if (first.getLeftStart() > first.getLeftEnd())
			leftStart = Math.max(leftStart, rightStart);
		else
			leftStart = Math.min(leftStart, rightStart);

		// Write the bitstream
		bw = new BitWriter();

		try {

			// Look at the numbers and calculate some optimal values for the bit field widths etc.
			State state = new GatheringState(leftStart);
			checkSupported(state); // XXX to be removed.
			process(bw, state);

			// Write the initial values.
			writeWidths(state);
			writeInitialValue(state);

			state = new WritingState(state);
			process(bw, state);

			// TODO remove, just for debugging
			System.out.println(numbers.get(0));
			printBits(bw);

			// If we get this far and there is something there, the stream might be valid!
			if (bw.getLength() > 1)
				valid = true;
		} catch (Abandon e) {
			System.out.println(e.getMessage());
			valid = false;
		}

		return bw;
	}

	private void process(BitWriter bw, State state) {
		if (swappedDefaultStyle)
			state.swapDefaults();

		for (Numbers n : numbers) {
			if (state.needSkip(n)) {
				state.writeSkip(n);
				continue;
			}

			state.setTarget(n);

			state.calcNumbers();
			state.writeNumberingStyle();
			state.writeBitWidths(bw);
			state.writeNumbers(bw);
		}
	}

	/** For debugging */
	private void printBits(BitWriter bw) {
		StringBuilder sb = new StringBuilder();
		BitReader br = new BitReader(bw.getBytes());
		for (int i = 0; i < bw.getLength() * 8; i++) {
			sb.insert(0, br.get1() ? "1" : "0");
		}
		System.out.println(sb.toString());
	}

	/** Temporary routine to check for supported cases, will be removed. */
	private void checkSupported(State state) {
		Numbers first = numbers.get(0);

		if (first.getNodeNumber() != 0)
			fail("first node not 0");

		if (first.getLeftNumberStyle() == EVEN && first.getRightNumberStyle() == ODD)
			swapDefaults(state);

		EnumSet<NumberStyle> notyet = EnumSet.of(NONE, BOTH);
		if (notyet.contains(first.getLeftNumberStyle()) || notyet.contains(first.getRightNumberStyle()))
			fail("NONE or BOTH numbering styles");
	}

	private void swapDefaults(State state) {
		state.left.style = EVEN;
		state.right.style = ODD;
	}

	/**
	 * The initial base value is written out separately before anything else.
	 * All numbers are derived from differences from this value.
	 * @param state Holds the initial value to write.
	 */
	private void writeInitialValue(State state) {
		int width = 32 - Integer.numberOfLeadingZeros(state.initialValue);
		if (width > 5) {
			bw.put1(false);
			bw.putn(width - 5, 4);
		} else {
			bw.put1(true);
			width = 5;
		}
		bw.putn(state.initialValue, width);
	}

	/**
	 * Write out a block that describes the number of bits to use. Numbers can be
	 * either all positive or all negative, or they can be signed and each bit field
	 * also has an extra sign bit. This is like how lines are encoded. See the LinePreparer
	 * class.
	 * @param state Holds the width information.
	 */
	private void writeWidths(State state) {
		state.getStartWriter().writeFormat();
		state.getEndWriter().writeFormat();
	}

	/**
	 * Temporary routine to bail out on an unimplemented condition.
	 */
	private void fail(String msg) {
		for (Numbers n : numbers)
			System.out.println(n);
		throw new Abandon(msg);
	}

	/**
	 * Returns true if the bit stream was calculated on the basis that the initial even/odd defaults
	 * should be swapped.
	 * @return True to signify swapped default, ie bit 0x20 in the net flags should be set.
	 */
	public boolean getSwapped() {
		return swappedDefaultStyle;
	}

	/**
	 * During development, any case that cannot be written correctly is marked invalid so it can
	 * be skipped on output.
	 *
	 * This will probably go away when complete.
	 *
	 * @return True if the preparer believes that the output is valid.
	 */
	public boolean isValid() {
		return valid;
	}


	/**
	 * The current state of the writing process.
	 */
	static abstract class State {

		protected final Side left = new Side();
		protected final Side right = new Side();
		private int initialValue;

		State() {
			left.style = ODD;
			right.style = EVEN;
		}

		/**
		 * Set the initial value. All numbers are derived from this by adding differences.
		 */
		public void setInitialValue(int val) {
			initialValue = val;
			left.base = val;
			right.base = val;
		}

		/**
		 * Set the next number to output. Once the target is set, we then output commands to
		 * transform the current state into the target state.
		 * @param numbers The target numbers.
		 */
		public void setTarget(Numbers numbers) {
			left.setTargets(numbers.getLeftNumberStyle(), numbers.getLeftStart(), numbers.getLeftEnd());
			right.setTargets(numbers.getRightNumberStyle(), numbers.getRightStart(), numbers.getRightEnd());
		}

		/**
		 * If the target numbering style is different to the current one, then write out
		 * the command to change it.
		 */
		public void writeNumberingStyle() {
		}

		/**
		 * If we need a larger bit width for this node, then write out a command to
		 * change it. Changes are temporary and it reverts to the default after the
		 * next number output command.
		 */
		public void writeBitWidths(BitWriter bw) {
		}

		private int lastNode;
		public boolean needSkip(Numbers n) {
			if (n.getNodeNumber() > 0 && n.getNodeNumber() != lastNode + 1)
				throw new Abandon("need skip");
			lastNode = n.getNodeNumber();
			return false;
		}

		public void writeSkip(Numbers n) {
		}

		public void calcNumbers() {
			equalizeBases();

			left.calcLeft(right);
			right.calcRight(left);
		}

		private boolean equalizeBases() {
			left.equalized = false;
			right.equalized = false;
			return false;
		}

		public abstract void writeNumbers(BitWriter bw);

		public abstract VarBitWriter getStartWriter();
		public abstract VarBitWriter getEndWriter();

		public void swapDefaults() {
			left.style = EVEN;
			right.style = ODD;
		}
	}

	static class Side {
		private NumberStyle style;
		private int base;

		// The calculated start and end numbers for the node. These might be different to the actual numbers
		// that are wanted that are in targetStart and End.
		private int start;
		private int end;

		// These are the target start and end numbers for the node. The real numbers are different as there
		// is an adjustment applied.
		private NumberStyle targetStyle;
		private int targetStart;
		private int targetEnd;

		private int startDiff;
		private int endDiff;
		private int lastEndDiff;

		private int endAdj;
		private int roundDirection = 1;

		// Bases equalised to this side.
		private boolean equalized;

		public void init() {
			if (targetStart < targetEnd)
				endAdj = 1;
			else if (targetEnd < targetStart) {
				endAdj = -1;
				roundDirection = -1;
			} else
				endAdj = 0;
		}

		public void setTargets(NumberStyle style, int start, int end) {
			this.targetStyle = style;
			this.targetStart = start;
			this.targetEnd = end;
			init();
		}

		private boolean tryStart(int value) {
			return value == targetStart || round(value) == targetStart;
		}

		private boolean tryEnd(int value) {
			return (value + endAdj) == targetEnd || round(value + endAdj) == targetEnd;
		}

		private int round(int value) {
			int adjValue = value;
			if ((style == EVEN) && ((base & 1) == 1)) adjValue += roundDirection;
			if ((style == ODD) && ((base & 1) == 0)) adjValue += roundDirection;
			return adjValue;
		}

		public boolean needOverride() {
			return true;
		}

		/**
		 * Calculate the start and end.  The right hand side details are given
		 * to help pick good values that will optimise the right hand side. The right hand
		 * side has not been calculated yet, so you can only use the target values.
		 */
		public void calcLeft(Side right) {
			// default left start diff is 0
			// default left end diff is the previous one
			calcCommon(right, true);
		}

		public void calcRight(Side left) {
			// The default right start diff is 0
			// the default right end diff is the left end diff, unless we have doRightOverride or
			// a left end diff was not read, in which case it defaults to the last right end diff.
			calcCommon(left, false);
		}

		private void calcCommon(Side side, boolean left) {

			if (targetStart == targetEnd) {
				if (tryStart(base))
					startDiff = 0;
				else
					startDiff = targetStart - base;

				if (left) {
					if (base == targetStart && lastEndDiff == 0)
						endDiff = 0;
					else
						endDiff = 2;
				} else {
					if (base == targetStart && lastEndDiff == 0 && side.endDiff == 0)
						endDiff = 0;
					else
						endDiff = 2;
				}
				return;
			}

			if (tryStart(base))
				startDiff = 0;
			else
				startDiff = targetStart - base;

			endDiff = targetEnd - (base + startDiff) + endAdj;
		}

		public void finish() {
			lastEndDiff = endDiff;
			start = base + startDiff;
			end = start + endDiff;
			base = end;
		}
	}

	private class GatheringState extends State {
		private boolean negative;
		private boolean positive;
		private int maxStartDiff;
		private int maxEndDiff;

		public GatheringState(int initialValue) {
			setInitialValue(initialValue);
		}

		public void writeNumbers(BitWriter bw) {
			int val = left.startDiff;
			val = testSign(val);
			if (val > maxStartDiff)
				maxStartDiff = val;

			val = right.startDiff;
			val = testSign(val);
			if (val > maxStartDiff)
				maxStartDiff = val;

			val = left.endDiff;
			val = testSign(val);
			if (val > maxEndDiff)
				maxEndDiff = val;

			val = right.endDiff;
			val = testSign(val);
			if (val > maxEndDiff)
				maxEndDiff = val;
		}

		private int testSign(int val) {
			if (val > 0) {
				positive = true;
			} else if (val < 0) {
				negative = true;
				return -val;
			}
			return val;
		}

		public VarBitWriter getStartWriter() {
			return getVarBitWriter(calcWidth(maxStartDiff), START_WIDTH_MIN);
		}

		public VarBitWriter getEndWriter() {
			return getVarBitWriter(calcWidth(maxEndDiff), END_WIDTH_MIN);
		}

		private int calcWidth(int n) {
			if (isSigned())
				n++;
			return 32 - Integer.numberOfLeadingZeros(n);
		}

		private boolean isSigned() {
			return positive && negative;
		}

		private VarBitWriter getVarBitWriter(int width, int minWidth) {
			VarBitWriter writer = new VarBitWriter(bw, minWidth);
			if (isSigned())
				writer.signed = true;
			else if (negative)
				writer.negative = true;
			if (width > minWidth)
				writer.bitWidth = width - minWidth;
			return writer;
		}
	}

	static class WritingState extends State {

		private VarBitWriter startWriter;
		private VarBitWriter endWriter;
		private boolean restoreBitWriters;
		private final VarBitWriter savedStartWriter;
		private final VarBitWriter savedEndWriter;

		public WritingState(State state) {
			setInitialValue(state.initialValue);
			left.base = state.initialValue;
			right.base = state.initialValue;

			startWriter = state.getStartWriter();
			endWriter = state.getEndWriter();
			this.savedStartWriter = startWriter;
			this.savedEndWriter = endWriter;
		}

		public void writeNumbers(BitWriter bw) {
			boolean doSingleSide = left.style == NONE || right.style == NONE;
			if (left.style == NONE) {
				left.base = right.base;
			}

			// Output the command that a number follows.
			bw.put1(true);

			boolean equalized = false;
			if (!doSingleSide) {
				equalized = left.equalized || right.equalized;
				bw.put1(equalized);
				if (equalized)
					bw.put1(left.equalized);
			}

			if (!doSingleSide) {
				bw.put1(!right.needOverride());
			}
			boolean doStart = left.startDiff != 0;
			boolean doEnd = left.endDiff != 0;
			bw.put1(!doStart);
			bw.put1(!doEnd);

			if (doStart)
				startWriter.write(left.startDiff);
			if (doEnd)
				endWriter.write(left.endDiff);

			left.finish();

			if (doSingleSide) {
				writeSingleSide(bw);
				restoreWriters();
				return;
			}

			doStart = right.startDiff != 0;
			doEnd = right.endDiff != 0;

			if (!equalized)
				bw.put1(!doStart);
			if (right.needOverride())
				bw.put1(!doEnd);

			if (doStart)
				startWriter.write(right.startDiff);
			if (doEnd)
				endWriter.write(right.endDiff);

			right.finish();
			restoreWriters();
		}

		public void writeNumberingStyle() {
			if (left.targetStyle != left.style)
				throw new Abandon("left numbering");
			if (right.targetStyle != right.style)
				throw new Abandon("right numbering");
		}

		/**
		 * You can change the number of bits and the sign properties of the writers before writing a nodes
		 * numbers.  We don't try and work out the optimum sequence, but use this for tricky cases where
		 * we fail to work out the correct sizes in advance.
		 *
		 * This routine means that we will always be using writers that will deal with the next node numbers.
		 *
		 * @param bw The output stream writer.
		 */
		public void writeBitWidths(BitWriter bw) {
			newWriter(bw, startWriter, left.startDiff, right.startDiff, true);
			newWriter(bw, endWriter, left.endDiff, right.endDiff, false);
		}

		/**
		 * Common code for writeBitWidths.
		 */
		private void newWriter(BitWriter bw, VarBitWriter writer, int leftDiff, int rightDiff, boolean start) {
			if (!writer.checkFit(leftDiff) || !writer.checkFit(rightDiff)) {
				int min = Math.min(leftDiff, rightDiff);
				int max = Math.max(leftDiff, rightDiff);
				boolean signed = false;
				boolean negative = false;
				if (max < 0)
					negative = true;
				else if (min < 0)
					signed = true;

				int val = Math.max(Math.abs(min), Math.abs(max));
				int width = 32 - Integer.numberOfLeadingZeros(val);
				if (signed) width++;

				restoreBitWriters = true;
				VarBitWriter nw;
				if (start) {
					startWriter = nw = new VarBitWriter(bw, START_WIDTH_MIN, negative, signed, width);
					bw.putn(2, 4); // change width start
				} else {
					endWriter = nw = new VarBitWriter(bw, END_WIDTH_MIN, negative, signed, width);
					bw.putn(0xa, 4); // change width end (0x8 | 0x2)
				}
				nw.writeFormat();
			}
		}

		public void writeSkip(Numbers n) {
		}

		private void writeSingleSide(BitWriter bw) {
			throw new Abandon("single side");
		}

		public VarBitWriter getStartWriter() {
			return startWriter;
		}

		public VarBitWriter getEndWriter() {
			return endWriter;
		}

		private void restoreWriters() {
			if (restoreBitWriters) {
				startWriter = savedStartWriter;
				endWriter = savedEndWriter;
				restoreBitWriters = false;
			}
		}
	}

}

class VarBitWriter {
	private final BitWriter bw;
	private final int minWidth;
	int bitWidth;
	boolean negative;
	boolean signed;

	VarBitWriter(BitWriter bw, int minWidth) {
		this.bw = bw;
		this.minWidth = minWidth;
	}

	public VarBitWriter(BitWriter bw, int minWidth, boolean negative, boolean signed, int width) {
		this(bw, minWidth);
		this.negative = negative;
		this.signed = signed;
		if (width > minWidth)
			this.bitWidth = width - minWidth;
	}

	public void write(int n) {
		if (!checkFit(n))
					throw new Abandon("number does not fit bit space available");

		if (n < 0 && negative)
			n = -n;

		if (signed) {
			int mask = (1 << (minWidth + bitWidth+2)) - 1;
			n &= mask;
		}

		bw.putn(n, minWidth+bitWidth + ((signed)?1:0));
	}

	boolean checkFit(int n) {
		if (negative) {
			if (n > 0)
				return false;
			else
				n = -n;
		} else if (signed && n < 0)
			n = -1 - n;

		int w = minWidth + bitWidth;
		int mask = (1 << w) - 1;
		if (n == (n & mask))
			return true;
		else return false;

	}

	/**
	 * Write the format of this bit writer to the output stream. Used at the beginning and
	 * when changing the bit widths.
	 */
	public void writeFormat() {
		bw.put1(negative);
		bw.put1(signed);
		bw.putn(bitWidth, 4);
	}
}

class Abandon extends RuntimeException {
	Abandon(String message) {
		super("NOT YET " + message);
	}
}
