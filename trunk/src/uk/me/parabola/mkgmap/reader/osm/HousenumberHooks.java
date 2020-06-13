/*
 * Copyright (C) 2014.
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

package uk.me.parabola.mkgmap.reader.osm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Collect data from ways with addr:interpolation tag.
 * @author GerdP
 *  
 */
public class HousenumberHooks implements OsmReadingHooks {
	private static final Logger log = Logger.getLogger(HousenumberHooks.class);
	
	private ElementSaver saver;
	private final List<Node> nodes = new ArrayList<>();
	private boolean clearNodes;
	
	private static final short TK_ADDR_HOUSENUMBER = TagDict.getInstance().xlate("addr:housenumber");
	private static final short TK_ADDR_INTERPOLATION = TagDict.getInstance().xlate("addr:interpolation");
	
	public static final short TKM_PART_OF_INTERPOLATION = TagDict.getInstance().xlate("mkgmap:part-of-interpolation");
	public static final short TKM_NODE_IDS = TagDict.getInstance().xlate("mkgmap:node-ids");
	@Override
	public boolean init(ElementSaver saver, EnhancedProperties props, Style style) {
		this.saver = saver;
		if (!props.getProperty("addr-interpolation", true))
			return false;
		return (props.getProperty("housenumbers", false));
	}

	@Override
	public Set<String> getUsedTags() {
		return new HashSet<>(Arrays.asList("addr:street", "addr:housenumber", "addr:interpolation", "addr:place"));
	}
	
	@Override
	public void onCoordAddedToWay(Way way, long id, Coord co) {
		if (clearNodes){
			nodes.clear();
			clearNodes = false;
		}
		Node currentNodeInWay = saver.getNode(id);
		if (currentNodeInWay == null || currentNodeInWay.getTag(TK_ADDR_HOUSENUMBER) == null)
			return;
		// this node might be part of a way that has the addr:interpolation tag
		nodes.add(currentNodeInWay);
	}

	@Override
	public void onAddWay(Way way) {
		clearNodes = true; // make sure that the list is cleared with the next coord
		String ai = way.getTag(TK_ADDR_INTERPOLATION);
		if (ai == null)
			return;
		if (nodes.size() < 2){
				log.warn(way.toBrowseURL(),"tag addr:interpolation="+ai, "is ignored, found less than two valid nodes.");
			return;
		}
		switch (ai) {
		case "odd":
		case "even":
		case "all":
		case "1":
		case "2":
			break;
		default:
			if (log.isInfoEnabled())
				log.warn(way.toBrowseURL(),"tag addr:interpolation="+ai, "is ignored");
			return;
		}
		
		StringBuilder sb = new StringBuilder();
		int num = nodes.size();
		for (int i = 0; i < num; i++) {
			Node n = nodes.get(i);
			String id = String.valueOf(n.getId());
			sb.append(id);
			if (i + 1 < num)
				sb.append(",");
			n.addTag(TKM_PART_OF_INTERPOLATION, "1");
		}
		way.addTag(TKM_NODE_IDS, sb.toString());
	}

	@Override
	public void end() {
		nodes.clear();
	}
}
