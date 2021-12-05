/*
 * Copyright (c) 2012.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * Author: GerdP
 */

package uk.me.parabola.mkgmap.reader.osm;


import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 
 */
public class CustomCollectionsTest {

	@Test
	public void testOSMId2ObjectMap() {
		testMap(new OSMId2ObjectMap<>(), 0L);
		testMap(new OSMId2ObjectMap<>(), -10000L);
		testMap(new OSMId2ObjectMap<>(), 1L << 35);
		testMap(new OSMId2ObjectMap<>(), -1L << 35);
	}

	private void testMap(OSMId2ObjectMap<Long> map, long idOffset) {
		
		for (long i = 1; i < 1000; i++) {
			Long j = map.put(idOffset + i, i);
			assertNull(j);
			assertEquals(i, map.size());
		}

		for (long i = 1; i < 1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			assertTrue(b);
		}


		for (long i = 1; i < 1000; i++) {
			assertEquals(Long.valueOf(i), map.get(idOffset + i));
		}

		// random read access 
		for (long i = 1; i < 1000; i++) {
			Long key = (long) Math.max(1, (Math.random() * 1000));
			assertEquals(key, map.get(idOffset + key));
		}

		for (long i = 1000; i < 2000; i++) {
			assertNull(map.get(idOffset + i));
		}
		
		for (long i = 1000; i < 2000; i++) {
			boolean b = map.containsKey(idOffset + i);
			assertFalse(b);
		}
		for (long i = 1000; i < 1200; i++) {
			Long j = map.put(idOffset + i, 333L);
			assertNull(j);
			assertEquals(i, map.size());
		}
		// random read access 2 
		for (int i = 1; i < 1000; i++) {
			Long key = 1000 + (long) (Math.random() * 200);
			assertEquals(Long.valueOf(333), map.get(idOffset + key));
		}

		for (long i = -2000; i < -1000; i++) {
			assertNull(map.get(idOffset + i));
		}
		for (long i = -2000; i < -1000; i++) {
			boolean b = map.containsKey(idOffset + i);
			assertFalse(b);
		}
		long mapSize = map.size();
		// seq. update existing records
		for (int i = 1; i < 1000; i++) {
			long j = map.put(idOffset + i, Long.valueOf(i + 333L));
			assertEquals(i, j);
			assertEquals(mapSize, map.size());
		}
		// random read access 3, update existing entries
		for (int i = 1; i < 1000; i++) {
			long j = map.put(idOffset + i, Long.valueOf(i + 555L));
			assertTrue(j == i + 333 || j == i + 555);
			assertEquals(mapSize, map.size());
		}
				
		assertNull(map.get(idOffset + 123456));
		map.put(idOffset + 123456, (long) 999);
		assertEquals(Long.valueOf(999), map.get(idOffset + 123456));
		map.put(idOffset + 123456, (long) 888);
		assertEquals(Long.valueOf(888), map.get(idOffset + 123456));

		assertNull(map.get(idOffset - 123456));
		map.put(idOffset - 123456, (long) 999);
		assertEquals(Long.valueOf(999), map.get(idOffset - 123456));
		map.put(idOffset - 123456, (long) 888);
		assertEquals(Long.valueOf(888), map.get(idOffset - 123456));
	
		map.clear();
		assertEquals(0, map.size());
		for (long i = 0; i < 100; i++){
			map.put(idOffset + i, i);
		}
		Long old = map.remove(idOffset + 5);
		assertEquals(Long.valueOf(5), old);
		assertEquals(99, map.size());
		assertNull(map.get(idOffset + 5));
	}
	
}
