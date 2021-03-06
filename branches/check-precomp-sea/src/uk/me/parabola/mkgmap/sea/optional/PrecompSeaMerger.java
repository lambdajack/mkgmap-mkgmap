/*
 * Copyright (C) 2012-2014.
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

package uk.me.parabola.mkgmap.sea.optional;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GeneralRelation;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.Java2DConverter;

/**
 * Merges the polygons for one precompiled sea tile.
 * @author WanMil
  */
class PrecompSeaMerger implements Runnable {
	private final MergeData mergeData;
	private final CountDownLatch signal;
	private final BlockingQueue<Entry<String, List<Way>>> saveQueue;
	private ExecutorService service;

	private static class MergeData {
		final Rectangle2D bounds;
		final BlockingQueue<Area> toMerge;
		final AtomicBoolean ready = new AtomicBoolean(false);
		Path2D.Double tmpLandPath = new Path2D.Double();
		Area landArea = new Area();
		private final String key;

		public MergeData(Rectangle2D bounds, String key) {
			this.key = key;
			this.bounds = bounds;
			toMerge = new LinkedBlockingQueue<>();
		}

		public String getKey() {
			return key;
		}

	}

	public PrecompSeaMerger(Rectangle2D bounds, String key,
			CountDownLatch signal,
			BlockingQueue<Entry<String, List<Way>>> saveQueue) {
		this.mergeData = new MergeData(bounds, key);
		this.signal = signal;
		this.saveQueue = saveQueue;
	}

	public MergeData getMergeData() {
		return mergeData;
	}

	public BlockingQueue<Area> getQueue() {
		return mergeData.toMerge;
	}

	public void signalInputComplete() {
		mergeData.ready.set(true);
	}

	public void setExecutorService(ExecutorService service) {
		this.service = service;
	}

	public Rectangle2D getTileBounds() {
		return mergeData.bounds;
	}

	private static List<Way> convertToWays(Area a, String naturalTag) {
		List<List<Coord>> pointLists = Java2DConverter.areaToShapes(a);
		List<Way> ways = new ArrayList<>(pointLists.size());
		for (List<Coord> points : pointLists) {
			Way w = new Way(FakeIdGenerator.makeFakeId(), points);
			w.addTag("natural", naturalTag);
			w.setClosedInOSM(true);
			ways.add(w);
		}
		return ways;
	}

	public void run() {
		Area merge = null;
		try {
			merge = mergeData.toMerge.poll(5, TimeUnit.MILLISECONDS);
		} catch (InterruptedException exp) {
			exp.printStackTrace();
		}
		int merges = 0;
		while (merge != null) {
			Area landClipped = new Area(mergeData.bounds);
			landClipped.intersect(merge);
			mergeData.tmpLandPath.append(landClipped, false);
			merges++;
			
			if (merges % 500 == 0) {
				// store each 500 polygons into a temporary area
				// and merge them after that. That seems to be quicker
				// than adding lots of very small areas to a highly 
				// scattered area 
				Area tmpLandArea = new Area(mergeData.tmpLandPath);
				mergeData.landArea.add(tmpLandArea);
				mergeData.tmpLandPath.reset();
			}

			if (merges % 500 == 0) {
				break;
			}
			
			merge = mergeData.toMerge.poll();
		}

		if (!mergeData.ready.get() || !mergeData.toMerge.isEmpty()) {
			// repost the merge thread
			service.execute(this);
			return;
		}
		if (mergeData.landArea.isEmpty())
			mergeData.landArea = new Area(mergeData.tmpLandPath);
		else
			mergeData.landArea.add(new Area(mergeData.tmpLandPath));
		mergeData.tmpLandPath = null;

		// post processing //
		
		// convert the land area to a list of ways
		List<Way> ways = convertToWays(mergeData.landArea, "land");

		if (ways.isEmpty()) {
			// no land in this tile => create a sea way only
			ways.addAll(convertToWays(new Area(mergeData.bounds), "sea"));
		} else {
			Map<Long, Way> wayMap = new HashMap<>();
			List<List<Coord>> landParts = Java2DConverter
					.areaToShapes(mergeData.landArea);
			for (List<Coord> landPoints : landParts) {
				Way landWay = new Way(FakeIdGenerator.makeFakeId(), landPoints);
				wayMap.put(landWay.getId(), landWay);
			}

			Way seaWay = new Way(FakeIdGenerator.makeFakeId(), uk.me.parabola.imgfmt.app.Area.PLANET.toCoords());
			seaWay.setClosedInOSM(true);
			wayMap.put(seaWay.getId(), seaWay);

			Relation rel = new GeneralRelation(FakeIdGenerator.makeFakeId());
			for (Way w : wayMap.values()) {
				rel.addElement((w == seaWay ? "outer" : "inner"), w);
			}

			// process the tile as sea multipolygon to create simple polygons only
			MultiPolygonRelation mpr = new MultiPolygonRelation(rel, wayMap,
					Java2DConverter.createBbox(new Area(mergeData.bounds))) 
			{
				// do not calculate the area size => it is not required and adds
				// a superfluous tag 
				@Override
				protected boolean isAreaSizeCalculated() {
					return false;
				}
			};
			mpr.addTag("type", "multipolygon");
			mpr.addTag("natural", "sea");
			mpr.processElements();

			for (Way w : wayMap.values()) {
				// process the polygon ways only
				// the mp processing also creates line ways which must 
				// be ignored here
				if (MultiPolygonRelation.STYLE_FILTER_POLYGON.equals(w.getTag(MultiPolygonRelation.STYLE_FILTER_TAG))
						&& "sea".equals(w.getTag("natural"))) {
					// ignore the land polygons - we already have them in our list
					w.deleteTag(MultiPolygonRelation.STYLE_FILTER_TAG);
					w.deleteTag(MultiPolygonRelation.TKM_MP_CREATED);
					ways.add(w);
				}
			}
		}

		try {
			// forward the ways to the queue of the saver thread
			saveQueue.put(new SimpleEntry<>(mergeData.getKey(), ways));
		} catch (InterruptedException exp) {
			exp.printStackTrace();
		}

		// signal that this tile is finished
		signal.countDown();
	}
}