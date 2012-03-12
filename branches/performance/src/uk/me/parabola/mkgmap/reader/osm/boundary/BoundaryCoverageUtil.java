/*
 * Copyright (C) 2006, 2011.
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
package uk.me.parabola.mkgmap.reader.osm.boundary;

import java.awt.geom.Area;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.GpxCreator;
import uk.me.parabola.util.Java2DConverter;

public class BoundaryCoverageUtil {
	private final BoundaryQuadTree bqt; 

	public BoundaryCoverageUtil(String boundaryDirName, String boundaryFileName) {
		bqt = BoundaryUtil.loadQuadTree(boundaryDirName, boundaryFileName);
	}

	public Area getCoveredArea(int admLevel) {
		return bqt.getCoveredArea(admLevel);
	}
	
	private static void saveArea(String attribute, Integer level, Area covered) {
		String gpxBasename = "gpx/summary/"+attribute+"/admin_level=" + level;

		List<List<Coord>> emptyPolys = Java2DConverter
				.areaToShapes(covered);
		Collections.reverse(emptyPolys);
		int i = 0;
		for (List<Coord> emptyPart : emptyPolys) {
			Way w = new Way(0, emptyPart);
			String attr = w.clockwise() ? "o" : "i";
			GpxCreator.createGpx(gpxBasename + "/" + i + "_" + attr,
					emptyPart);
			i++;
		}
	}

	public static void main(String[] args) {
		int processors = Runtime.getRuntime().availableProcessors();
		ExecutorService excSvc = Executors.newFixedThreadPool(processors);
		ExecutorCompletionService<String> executor = new ExecutorCompletionService<String>(
				excSvc);
		String workDirName = args[0];
		System.out.println(workDirName );
		File boundaryDir = new File(workDirName );
		List<String> boundaryFileNames;
		if (boundaryDir.isFile() && boundaryDir.getName().endsWith(".bnd")) {
			workDirName  = boundaryDir.getParent();
			if (workDirName  == null)
				workDirName  = ".";
			boundaryFileNames = new ArrayList<String>();
			boundaryFileNames.add(boundaryDir.getName());
		} else {
			boundaryFileNames = BoundaryUtil.getBoundaryDirContent(workDirName);
		}
		final String boundaryDirName = workDirName;
		final Map<Integer, BlockingQueue<Area>> coveredAreas = new Hashtable<Integer, BlockingQueue<Area>>();
		for (int adminlevel = 2; adminlevel < 12; adminlevel++) {
			BlockingQueue<Area> queue = new LinkedBlockingQueue<Area>();
			for (int i = 0; i < 12; i++) {
				queue.add(new Area());
			}
			coveredAreas.put(adminlevel, queue);
		}

		
		for (final String boundaryFileName : boundaryFileNames) {
			executor.submit(new Runnable() {
				public void run() {
					BoundaryCoverageUtil converter = new BoundaryCoverageUtil(
							boundaryDirName,boundaryFileName);
					for (int adminLevel = 2; adminLevel < 12; adminLevel++) {

						Area covered = converter.getCoveredArea(adminLevel);
						
						if (covered != null && covered.isEmpty() == false) {
							BlockingQueue<Area> qArea = coveredAreas
									.get(adminLevel);
							
							Area aArea = qArea.poll();
							aArea.add(covered);
							qArea.add(aArea);
						}
					}
				}
			}, boundaryFileName);
		}

		long bCompleted = 0;
		long bSize = boundaryFileNames.size();
		for (int bi = 1; bi <= boundaryFileNames.size(); bi++) {
			try {
				String fName = executor.take().get();
				bCompleted ++;
				System.out.format("%4.2f %% of all files completed. %s%n",
						(bCompleted * 100.0d / bSize), fName);
			} catch (InterruptedException exp) {
				// TODO Auto-generated catch block
				exp.printStackTrace();
			} catch (ExecutionException exp) {
				// TODO Auto-generated catch block
				exp.printStackTrace();
			}
		}
		
		for (int adminLevel = 2; adminLevel < 12; adminLevel++) {
			System.out.println("Start joining for admin_level " + adminLevel);
			BlockingQueue<Area> queue = coveredAreas.remove(adminLevel);
			Area a = new Area();
			while (queue.isEmpty() == false) {
				a.add(queue.poll());
			}
			System.out.println("Joining finished. Saving results.");
			saveArea("covered", adminLevel, a);
			// }
		}
		excSvc.shutdown();
	}

}
