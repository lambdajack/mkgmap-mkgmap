/*
 * Copyright (C) 2011.
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

/**
 * Allows you to combine another sort key with further integer comparisons.
 * Avoids having to cram two integers into one when there is the possibility that
 * they may not be enough bits to represent all values.
 *
 * @author Steve Ratcliffe
 */
public class CombinedSortKey<T> implements SortKey<T> {
	private final SortKey<T> key;
	private final int first;
	private final int second;

	public CombinedSortKey(SortKey<T> obj, int first, int second) {
		this.key = obj;
		this.first = first;
		this.second = second;
	}

	public T getObject() {
		return key.getObject();
	}

	public int compareTo(SortKey<T> o) {
		CombinedSortKey<T> other = (CombinedSortKey<T>) o;
		int res = key.compareTo(other.key);
		if (res == 0) {
			res = Integer.compare(first, other.first);
			if (res == 0) {
				res = Integer.compare(second, other.second);
			}
		}
		return res;
	}
}
