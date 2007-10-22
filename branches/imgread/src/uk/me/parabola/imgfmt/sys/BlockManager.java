/*
 * Copyright (C) 2006 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 02-Dec-2006
 */
package uk.me.parabola.imgfmt.sys;


/**
 * Used to allocate blocks to files.
 *
 * @author Steve Ratcliffe
 */
class BlockManager {
	private int currentBlock;
	private int blockSize;

	BlockManager(int blockSize, int initialBlock) {
		this.blockSize = blockSize;
		this.currentBlock = initialBlock;
	}

	BlockManager() {
	}

	/**
	 * Well the algorithm is pretty simple - you just get the next unused block
	 * number.
	 *
	 * @return A block number that is free to be used.
	 */
	public int allocate() {
		return currentBlock++;
	}

	/**
	 * Reserve a number of blocks.  Used mainly to make room for the directory
	 * blocks.
	 *
	 * @param n Number of blocks to reserve.
	 */
	public void reserveBlocks(int n) {
		currentBlock += n;
	}

	/**
	 * Returns the next block that would be allocated if you were to call
	 * {@link #allocate}.
	 * @return The next block that would be allocated.
	 */
	public int getCurrentBlock() {
		return currentBlock;
	}

	public void setCurrentBlock(int startBlock) {
		this.currentBlock = startBlock;
	}

	public int getBlockSize() {
		return blockSize;
	}

	public void setBlockSize(int blockSize) {
		this.blockSize = blockSize;
	}
}
