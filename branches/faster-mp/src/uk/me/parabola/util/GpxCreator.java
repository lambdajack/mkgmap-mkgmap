package uk.me.parabola.util;

import java.awt.Shape;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.Way;

public class GpxCreator {
	private static final Logger log = Logger.getLogger(GpxCreator.class);

	public static String getGpxBaseName() {
		String tilePath = (log.threadTag() == null ? "unknown" : log.threadTag());
		
		int tilenameStart = tilePath.lastIndexOf('/');
		// check the case if the tiles are defined without path
		tilenameStart = (tilenameStart < 0 ? 0 : tilenameStart+1);
		
		int tilenameEnd = tilePath.lastIndexOf(".osm");
		if (tilenameEnd < tilenameStart) {
			// the tiles do not end with .osm*
			// do not cut the file ending
			tilenameEnd = tilePath.length();
		}
		
		return tilePath.substring(tilenameStart,tilenameEnd) + "/";
	}

	private static void addTrkPoint(PrintWriter pw, int latitude, int longitude) {
		addGpxPoint(pw, "trkpt", latitude, longitude);
	}

	private static void addWptPoint(PrintWriter pw, int latitude, int longitude) {
		addGpxPoint(pw, "wpt", latitude, longitude);
	}
	private static void addTrkPoint(PrintWriter pw, Coord co) {
		addGpxPoint(pw, "trkpt", co);
	}

	private static void addWptPoint(PrintWriter pw, Coord co) {
		addGpxPoint(pw, "wpt", co);
	}

	private static void addGpxPoint(PrintWriter pw, String type, int latitude,
			int longitude) {
		pw.print("<");
		pw.print(type);
		pw.print(" lat=\"");
		pw.print(Utils.toDegrees(latitude));
		pw.print("\" lon=\"");
		pw.print(Utils.toDegrees(longitude));
		pw.print("\"/>");
	}

	private static void addGpxPoint(PrintWriter pw, String type, Coord co) {
		pw.print("<");
		pw.print(type);
		pw.print(" lat=\"");
		pw.print(co.getLatDegrees());
		pw.print("\" lon=\"");
		pw.print(co.getLonDegrees());
		pw.print("\"/>");
	}

	public static void createAreaGpx(String name, Area bbox) {
		GpxCreator.createGpx(name, bbox.toCoords());
	}

	/**
	 * Create gpx file(s) for java Shape. 
	 * @param baseDir the base directory name
	 * @param shape the shape to convert
	 */
	public static void createShapeGpx(String baseDir, Shape shape) {
		// have to convert to area to make sure that clockwise/counterclockwise idea works for inner/outer
		java.awt.geom.Area area = shape instanceof java.awt.geom.Area ? (java.awt.geom.Area) shape
				: new java.awt.geom.Area(shape);
		List<List<Coord>> shapes = Java2DConverter.areaToShapes(area);
		for (int i = 0; i < shapes.size(); i++) {
			List<Coord> points = shapes.get(i);
			String extName = baseDir + Integer.toString(i) + "_" + (Way.clockwise(points) ? "o" : "i");
			GpxCreator.createGpx(extName, points);
		}
	}	

	/**
	 * Creates a gpx file for each way. The filename is the baseDir plus the id
	 * of the way.
	 * @param baseDir the base directory name
	 * @param ways list of ways
	 */
	public static void createGpx(String baseDir, Collection<? extends Way> ways) {
		for (Way w : ways) {
			GpxCreator.createGpx(baseDir+w.getId(), w.getPoints());
		}
	}

	public static void createGpx(String name, List<Coord> points) {
		for (int i = 0; i < 2; i++){
			String fname = name + (i==0 ? "_mu":"_hp");
			try {
				File f = new File(fname);
				if (f.getParentFile() != null) {
					f.getParentFile().mkdirs();
				}
				PrintWriter pw = new PrintWriter(new FileWriter(fname + ".gpx"));
				pw.print("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" creator=\"mkgmap\" ");
				pw.print("version=\"1.1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
				pw.print("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"> ");

				pw.print("<trk><name>");
				pw.print(fname);
				pw.print("</name><trkseg>");

				for (Coord c : points) {
					if (i == 0)
						addTrkPoint(pw, c.getLatitude(), c.getLongitude());
					else 
						addTrkPoint(pw, c);
				}
				pw.print("</trkseg></trk>");
				pw.print("</gpx>");
				pw.close();
			} catch (Exception exp) {
				// only for debugging so just log
				log.warn("Could not create gpx file", fname);
			}
		}
	}
	
	public static void createGpx(String name, List<Coord> polygonpoints, Coord... singlePoints) {
		createGpx(name, polygonpoints, Arrays.asList(singlePoints));
	}

	public static void createGpx(String name, List<Coord> polygonpoints, List<Coord> singlePoints) {
		for (int i = 0; i < 2; i++){
			String fname = name + (i==0 ? "_mu":"_hp");
			try {
				File f = new File(fname);
				f.getParentFile().mkdirs();
				PrintWriter pw = new PrintWriter(new FileWriter(fname + ".gpx"));
				pw.print("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" creator=\"mkgmap\" ");
				pw.print("version=\"1.1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
				pw.print("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"> ");

				if (singlePoints != null) {
					for (Coord c : singlePoints) {
						if (i == 0)
							addWptPoint(pw, c.getLatitude(), c.getLongitude());
						else 
							addWptPoint(pw, c);
					}
				}

				if (polygonpoints != null && !polygonpoints.isEmpty()) {
					pw.print("<trk><name>");
					pw.print(fname);
					pw.print("</name><trkseg>");

					for (Coord c : polygonpoints) {
						if (i == 0)
							addTrkPoint(pw, c.getLatitude(), c.getLongitude());
						else 
							addTrkPoint(pw, c);
					}
					pw.print("</trkseg></trk>");
				}
				pw.print("</gpx>");
				pw.close();
			} catch (Exception exp) {
				// only for debugging so just log
				log.warn("Could not create gpx file", fname);
			}
		}
	}
}
