/*
 * Copyright (C) 2010.
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
package uk.me.parabola.mkgmap.reader.osm.bin;

import java.util.List;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.OsmHandler;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.EnhancedProperties;

import crosby.binary.BinaryParser;
import crosby.binary.Osmformat;

/**
 * Handler for Scott Crosby's binary format, based on the Google
 * protobuf format.
 *
 * @author Steve Ratcliffe
 */
public class OsmBinHandler extends OsmHandler {
	private static final Logger log = Logger.getLogger(OsmBinHandler.class);

	private boolean reportUndefinedNodes;

	public OsmBinHandler(EnhancedProperties props) {
	}

	public class BinParser extends BinaryParser {

		protected void parse(Osmformat.HeaderBlock header) {
			double multiplier = .000000001;
			double maxLon = header.getBbox().getRight() * multiplier;
			double minLon = header.getBbox().getLeft() * multiplier;
			double maxLat = header.getBbox().getTop() * multiplier;
			double minLat = header.getBbox().getBottom() * multiplier;

			for (String s : header.getRequiredFeaturesList()) {
				if (s.equals("OsmSchema-V0.6"))
					continue; // We can parse this.

				if (s.equals("DenseNodes"))
					continue; // We can parse this.
				
				throw new MapFailedException("File requires unknown feature: " + s);
			}

			setBBox(minLat, minLon, maxLat, maxLon);
			System.out.println("bb" + saver.getBoundingBox());
		}

		protected void parseNodes(List<Osmformat.Node> nodes) {
			for (Osmformat.Node bnode : nodes) {
				Coord co = new Coord(parseLat(bnode.getLat()), parseLon(bnode.getLon()));
				long id = bnode.getId();
				saver.addPoint(id, co);

				int tagCount = bnode.getKeysCount();
				if (tagCount > 0) {
					Node node = new Node(id, co);
					for (int tid = 0; tid < tagCount; tid++) {
						node.addTag(getStringById(bnode.getKeys(tid)), getStringById(bnode.getVals(tid)));
					}

					saver.addNode(node);
					hooks.addNode(node);
				}
			}
		}

		protected final void parseDense(Osmformat.DenseNodes nodes) {
			//System.out.println("Dense " + nodes);
			long lastId = 0, lastLat = 0, lastLon = 0;

			int kvid = 0; // Index into the keysvals array.

			for (int nid = 0; nid < nodes.getIdCount(); nid++) {
				long lat = nodes.getLat(nid) + lastLat;
				long lon = nodes.getLon(nid) + lastLon;
				long id = nodes.getId(nid) + lastId;
				lastLat = lat;
				lastLon = lon;
				lastId = id;

				Coord co = new Coord(parseLat(lat), parseLon(lon));
				saver.addPoint(id, co);

				if (nodes.getKeysValsCount() > 0) {
					// If there are tags, then we create a proper node for it.
					Node osmnode = new Node(id, co);
					while (nodes.getKeysVals(kvid) != 0) {
						int keyid = nodes.getKeysVals(kvid++);
						int valid = nodes.getKeysVals(kvid++);
						String key = getStringById(keyid);
						String val = getStringById(valid);
						key = keepTag(key, val);
						if (key != null)
							osmnode.addTag(key, val);
					}
					kvid++; // Skip over the '0' delimiter.
					saver.addNode(osmnode);
					hooks.addNode(osmnode);
				}
			}
		}

		protected void parseWays(List<Osmformat.Way> ways) {
			for (Osmformat.Way bway : ways) {
				Way way = new Way(bway.getId());

				for (int j = 0; j < bway.getKeysCount(); j++) {

					String key = getStringById(bway.getKeys(j));
					String val = getStringById(bway.getVals(j));
					key = keepTag(key, val);
					if (key != null)
						way.addTag(key, val);
				}

				long nid = 0;
				for (long idDelta : bway.getRefsList()) {
					nid += idDelta;
					Coord co = saver.getCoord(nid);
					if (co != null) {
						hooks.coordAddedToWay(way, nid, co);
						way.addPoint(co);

						// nodes (way joins) will have highwayCount > 1
						co.incHighwayCount();
					} else if(reportUndefinedNodes) {
						log.warn("Way", way.toBrowseURL(), "references undefined node", nid);
					}
				}

				saver.addWay(way);
				hooks.addWay(way);
			}
		}

		protected void parseRelations(List<Osmformat.Relation> rels) {
		}

		/**
		 * Called when the file is fully read.
		 */
		public void complete() {
		}
	}
}
