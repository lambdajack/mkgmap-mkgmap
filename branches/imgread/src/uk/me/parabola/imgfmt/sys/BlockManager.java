/*
 * Copyright (C) 2007 Steve Ratcliffe
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
 * Create date: 25-Oct-2007
 */
package uk.me.parabola.imgfmt.sys;

/**
 * This is used to allocate blocks for files in the filesystem/archive.
 *
 * @author Steve Ratcliffe
 */
class BlockManager {
	private int currentBlock;
	private int blockSize;
	private int maxBlock;

	BlockManager(int blockSize, int initialBlock) {
		this.blockSize = blockSize;
		this.currentBlock = initialBlock;
	}

	/**
	 * Well the algorithm is pretty simple - you just get the next unused block
	 * number.
	 *
	 * @return A block number that is free to be used.
	 */
	public int allocate() {
		int n = currentBlock++;
		if (maxBlock > 0 && n > maxBlock)
			System.err.println("Directory overflow.  Map will not work");
		return n;
	}

	/**
	 * Returns the next block that would be allocated if you were to call
	 * {@link #allocate}.
	 *
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

	public int getMaxBlock() {
		return maxBlock;
	}

	public void setMaxBlock(int maxBlock) {
		this.maxBlock = maxBlock;
	}
}
