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
 * Create date: 16-Dec-2006
 */
package uk.me.parabola.mkgmap.reader.osm.xml;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.osmstyle.StyleImpl;
import uk.me.parabola.mkgmap.osmstyle.StyledConverter;
import uk.me.parabola.mkgmap.osmstyle.eval.SyntaxException;
import uk.me.parabola.mkgmap.reader.osm.ElementSaver;
import uk.me.parabola.mkgmap.reader.osm.HighwayHooks;
import uk.me.parabola.mkgmap.reader.osm.OsmConverter;
import uk.me.parabola.mkgmap.reader.osm.OsmReadingHooks;
import uk.me.parabola.mkgmap.reader.osm.OsmReadingHooksAdaptor;
import uk.me.parabola.mkgmap.reader.osm.OsmReadingHooksChain;
import uk.me.parabola.mkgmap.reader.osm.Style;
import uk.me.parabola.util.EnhancedProperties;

import org.xml.sax.SAXException;


/**
 * Read an OpenStreetMap data file in .osm version 0.5 format.  It is converted
 * into a generic format that the map is built from.
 * <p>The intermediate format is important as several passes are required to
 * produce the map at different zoom levels. At lower resolutions, some roads
 * will have fewer points or won't be shown at all.
 *
 * @author Steve Ratcliffe
 */
public class Osm5MapDataSource extends OsmMapDataSource {
	private static final Logger log = Logger.getLogger(Osm5MapDataSource.class);
	
	private final OsmReadingHooks[] POSSIBLE_HOOKS = {
			//new SeaGenerator(),
			//new HighwayPreConvert()
			new HighwayHooks(),
	};

	public boolean isFileSupported(String name) {
		// This is the default format so say supported if we get this far,
		// this one must always be last for this reason.
		return true;
	}

	/**
	 * Load the .osm file and produce the intermediate format.
	 *
	 * @param name The filename to read.
	 * @throws FileNotFoundException If the file does not exist.
	 */
	public void load(String name) throws FileNotFoundException, FormatException {
		try {
			InputStream is = Utils.openFile(name);
			SAXParserFactory parserFactory = SAXParserFactory.newInstance();
			parserFactory.setXIncludeAware(true);
			parserFactory.setNamespaceAware(true);
			SAXParser parser = parserFactory.newSAXParser();

			try {
				Osm5XmlHandler handler = new Osm5XmlHandler(getConfig());

				ElementSaver saver = new ElementSaver(getConfig());
				OsmReadingHooks hooks = pluginChain(saver, getConfig());

				handler.setElementSaver(saver);
				handler.setHooks(hooks);

				ConverterData converterData = createConverter();

				handler.setUsedTags(converterData.getUsedTags());

				String deleteTagsFileName = getConfig().getProperty("delete-tags-file");
				if(deleteTagsFileName != null) {
					Map<String, Set<String>> deltags = readDeleteTagsFile(deleteTagsFileName);
					handler.setDeletedTags(deltags);
				}
				
				parser.parse(is, handler);
				hooks.end();

				OsmConverter converter = converterData.getConverter();
				saver.convert(converter);
				addBackground();

			} catch (IOException e) {
				throw new FormatException("Error reading file", e);
			}
		} catch (SAXException e) {
			throw new FormatException("Error parsing file", e);
		} catch (ParserConfigurationException e) {
			throw new FormatException("Internal error configuring xml parser", e);
		}
	}


	private OsmReadingHooks pluginChain(ElementSaver saver, EnhancedProperties props) {
		List<OsmReadingHooks> plugins = new ArrayList<OsmReadingHooks>();

		for (OsmReadingHooks p : this.POSSIBLE_HOOKS) {
			if (p.init(saver, props))
				plugins.add(p);
		}

		switch (plugins.size()) {
		case 0:
			return new OsmReadingHooksAdaptor();
		case 1:
			return plugins.get(0);
		default:
			OsmReadingHooksChain chain = new OsmReadingHooksChain();
			for (OsmReadingHooks p : plugins) {
				chain.add(p);
			}
			return chain;
		}
	}

	private Map<String, Set<String>> readDeleteTagsFile(String fileName) {
		Map<String, Set<String>> deletedTags = new HashMap<String,Set<String>>();
		try {
			BufferedReader br = new BufferedReader(new FileReader(fileName));
			String line;
			while((line = br.readLine()) != null) {
				line = line.trim();
				if(line.length() > 0 && !line.startsWith("#") && !line.startsWith(";")) {
					String[] parts = line.split("=");
					if (parts.length == 2) {
						parts[0] = parts[0].trim();
						parts[1] = parts[1].trim();
						if ("*".equals(parts[1])) {
							deletedTags.put(parts[0], new HashSet<String>());
						} else {
							Set<String> vals = deletedTags.get(parts[0]);
							if (vals == null)
								vals = new HashSet<String>();
							vals.add(parts[1]);
							deletedTags.put(parts[0], vals);
						}
					} else {
						log.error("Ignoring bad line in deleted tags file: " + line);
					}
				}
			}
			br.close();
		}
		catch(FileNotFoundException e) {
			log.error("Could not open delete tags file " + fileName);
		}
		catch(IOException e) {
			log.error("Error reading delete tags file " + fileName);
		}

		if(deletedTags.isEmpty())
			deletedTags = null;

		return deletedTags;
	}

	/**
	 * Create the appropriate converter from osm to garmin styles.
	 *
	 * The option --style-file give the location of an alternate file or
	 * directory containing styles rather than the default built in ones.
	 *
	 * The option --style gives the name of a style, either one of the
	 * built in ones or selects one from the given style-file.
	 *
	 * If there is no name given, but there is a file then the file should
	 * just contain one style.
	 *
	 * @return An OsmConverter based on the command line options passed in.
	 */
	private ConverterData createConverter() {

		Properties props = getConfig();
		String loc = props.getProperty("style-file");
		if (loc == null)
			loc = props.getProperty("map-features");
		String name = props.getProperty("style");

		if (loc == null && name == null)
			name = "default";

		Set<String> tags;
		OsmConverter converter;
		try {
			Style style = new StyleImpl(loc, name);
			style.applyOptionOverride(props);
			setStyle(style);

			tags = style.getUsedTags();
			converter = new StyledConverter(style, mapper, props);
		} catch (SyntaxException e) {
			System.err.println("Error in style: " + e.getMessage());
			throw new ExitException("Could not open style " + name);
		} catch (FileNotFoundException e) {
			String name1 = (name != null)? name: loc;
			throw new ExitException("Could not open style " + name1);
		}

		return new ConverterData(converter, tags);
	}

	public class ConverterData {
		private final OsmConverter converter;
		private final Set<String> usedTags;

		public ConverterData(OsmConverter converter, Set<String> usedTags) {
			this.converter = converter;
			this.usedTags = usedTags;
		}

		public OsmConverter getConverter() {
			return converter;
		}

		public Set<String> getUsedTags() {
			return usedTags;
		}
	}
}
