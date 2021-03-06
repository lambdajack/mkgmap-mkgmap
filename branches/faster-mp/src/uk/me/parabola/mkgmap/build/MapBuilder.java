/*
 * Copyright (C) 2007 - 2012.
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

package uk.me.parabola.mkgmap.build;

import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.MapTooBigException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.Exit;
import uk.me.parabola.imgfmt.app.Label;
import uk.me.parabola.imgfmt.app.dem.DEMFile;
import uk.me.parabola.imgfmt.app.lbl.City;
import uk.me.parabola.imgfmt.app.lbl.Country;
import uk.me.parabola.imgfmt.app.lbl.ExitFacility;
import uk.me.parabola.imgfmt.app.lbl.Highway;
import uk.me.parabola.imgfmt.app.lbl.LBLFile;
import uk.me.parabola.imgfmt.app.lbl.POIRecord;
import uk.me.parabola.imgfmt.app.lbl.Region;
import uk.me.parabola.imgfmt.app.lbl.Zip;
import uk.me.parabola.imgfmt.app.map.Map;
import uk.me.parabola.imgfmt.app.net.NETFile;
import uk.me.parabola.imgfmt.app.net.NODFile;
import uk.me.parabola.imgfmt.app.net.Numbers;
import uk.me.parabola.imgfmt.app.net.RoadDef;
import uk.me.parabola.imgfmt.app.net.RoadNetwork;
import uk.me.parabola.imgfmt.app.net.RouteCenter;
import uk.me.parabola.imgfmt.app.trergn.ExtTypeAttributes;
import uk.me.parabola.imgfmt.app.trergn.Overview;
import uk.me.parabola.imgfmt.app.trergn.Point;
import uk.me.parabola.imgfmt.app.trergn.PointOverview;
import uk.me.parabola.imgfmt.app.trergn.Polygon;
import uk.me.parabola.imgfmt.app.trergn.PolygonOverview;
import uk.me.parabola.imgfmt.app.trergn.Polyline;
import uk.me.parabola.imgfmt.app.trergn.PolylineOverview;
import uk.me.parabola.imgfmt.app.trergn.RGNFile;
import uk.me.parabola.imgfmt.app.trergn.RGNHeader;
import uk.me.parabola.imgfmt.app.trergn.Subdivision;
import uk.me.parabola.imgfmt.app.trergn.TREFile;
import uk.me.parabola.imgfmt.app.trergn.TREHeader;
import uk.me.parabola.imgfmt.app.trergn.Zoom;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.Version;
import uk.me.parabola.mkgmap.filters.BaseFilter;
import uk.me.parabola.mkgmap.filters.DouglasPeuckerFilter;
import uk.me.parabola.mkgmap.filters.FilterConfig;
import uk.me.parabola.mkgmap.filters.LineMergeFilter;
import uk.me.parabola.mkgmap.filters.LinePreparerFilter;
import uk.me.parabola.mkgmap.filters.LineSplitterFilter;
import uk.me.parabola.mkgmap.filters.MapFilter;
import uk.me.parabola.mkgmap.filters.MapFilterChain;
import uk.me.parabola.mkgmap.filters.PolygonSplitterFilter;
import uk.me.parabola.mkgmap.filters.RemoveEmpty;
import uk.me.parabola.mkgmap.filters.RemoveObsoletePointsFilter;
import uk.me.parabola.mkgmap.filters.RoundCoordsFilter;
import uk.me.parabola.mkgmap.filters.ShapeMergeFilter;
import uk.me.parabola.mkgmap.filters.ShapeMergeFilter.MapShapeComparator;
import uk.me.parabola.mkgmap.filters.SizeFilter;
import uk.me.parabola.mkgmap.general.CityInfo;
import uk.me.parabola.mkgmap.general.LevelInfo;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.mkgmap.general.MapDataSource;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapExitPoint;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.general.ZipCodeInfo;
import uk.me.parabola.mkgmap.reader.MapperBasedMapDataSource;
import uk.me.parabola.mkgmap.reader.hgt.HGTConverter;
import uk.me.parabola.mkgmap.reader.hgt.HGTConverter.InterpolationMethod;
import uk.me.parabola.mkgmap.reader.hgt.HGTReader;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GType;
import uk.me.parabola.mkgmap.reader.osm.GeneralRelation;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.mkgmap.reader.overview.OverviewMapDataSource;
import uk.me.parabola.util.Configurable;
import uk.me.parabola.util.EnhancedProperties;
import uk.me.parabola.util.Java2DConverter;
import uk.me.parabola.util.ShapeSplitter;

/**
 * This is the core of the code to translate from the general representation
 * into the garmin representation.
 *
 * We need to go through the data several times, once for each level, filter
 * out features that are not required at the level and simplify paths for
 * lower resolutions if required.
 *
 * @author Steve Ratcliffe
 */
public class MapBuilder implements Configurable {
	private static final Logger log = Logger.getLogger(MapBuilder.class);
	private static final int CLEAR_TOP_BITS = (32 - 15);
	private static final LocalDateTime now = LocalDateTime.now();
	
	private static final int MIN_SIZE_LINE = 1;

	private final boolean isOverviewComponent;
	private final boolean isOverviewCombined;

	private final java.util.Map<MapPoint,POIRecord> poimap = new HashMap<>();
	private final java.util.Map<MapPoint,City> cityMap = new HashMap<>();
	private List<String> mapInfo = new ArrayList<>();
	private List<String> copyrights = new ArrayList<>();

	private boolean hasNet; 
	private Boolean driveOnLeft; // needs to be Boolean for later null test 
	private Locator locator;

	private final java.util.Map<String, Highway> highways = new HashMap<>();

	/** name that is used for cities which name are unknown */
	private static final String UNKNOWN_CITY_NAME = "";

	private Country defaultCountry;
	private String countryName = "COUNTRY";
	private String countryAbbr = "ABC";
	private String regionName;
	private String regionAbbr;
	
	private Set<String> locationAutofill;

	private int minSizePolygon;
	private String polygonSizeLimitsOpt;
	private TreeMap<Integer,Integer> polygonSizeLimits;
	private TreeMap<Integer, Double> dpFilterLineResMap; 
	private TreeMap<Integer, Double> dpFilterShapeResMap; 
	private boolean mergeLines;
	private boolean mergeShapes;

	private boolean	poiAddresses;
	private int		poiDisplayFlags;
	private boolean enableLineCleanFilters = true;
	private boolean makePOIIndex;
	private int routeCenterBoundaryType;
	
	private LBLFile lblFile;

	private String licenseFileName;

	private boolean orderByDecreasingArea;
	private String pathsToHGT;
	private List<Integer> demDists;
	private short demOutsidePolygonHeight;
	private java.awt.geom.Area demPolygon;
	private HGTConverter.InterpolationMethod demInterpolationMethod;
	private boolean allowReverseMerge;
	private boolean improveOverview;

	/**
	 * Construct a new MapBuilder.
	 * 
	 * @param overviewComponent set to {@code true} if the map is a work file that
	 *                          is later used as input for the OverviewBuilder
	 * @param overviewCombined  set to {@code true} if the map is the combined
	 *                          overview map
	 */
	public MapBuilder(boolean overviewComponent, boolean overviewCombined) {
		regionName = null;
		locationAutofill = Collections.emptySet();
		locator = new Locator();
		this.isOverviewComponent = overviewComponent;
		this.isOverviewCombined = overviewCombined;
	}

	public void config(EnhancedProperties props) {

		countryName = props.getProperty("country-name", countryName);
		countryAbbr = props.getProperty("country-abbr", countryAbbr);
		regionName = props.getProperty("region-name", null);
		regionAbbr = props.getProperty("region-abbr", null);
 		minSizePolygon = props.getProperty("min-size-polygon", 8);
 		polygonSizeLimitsOpt = props.getProperty("polygon-size-limits", null);
 		// options for DouglasPeuckerFilter
		double reducePointError = props.getProperty("reduce-point-density", 2.6);
 		double reducePointErrorPolygon = props.getProperty("reduce-point-density-polygon", -1);
		if (reducePointErrorPolygon == -1)
			reducePointErrorPolygon = reducePointError;
		dpFilterLineResMap = parseLevelOption(props, "simplify-lines", reducePointError);
		dpFilterShapeResMap = parseLevelOption(props, "simplify-polygons", reducePointErrorPolygon);
		
		mergeLines = props.containsKey("merge-lines");
		allowReverseMerge = props.getProperty("allow-reverse-merge", false); 

		// undocumented option - usually used for debugging only
		mergeShapes = !props.getProperty("no-mergeshapes", false);
		improveOverview = props.getProperty("improve-overview", false);
		
		makePOIIndex = props.getProperty("make-poi-index", false);

		if(props.getProperty("poi-address") != null)
			poiAddresses = true;

		routeCenterBoundaryType = props.getProperty("route-center-boundary", 0);

		licenseFileName = props.getProperty("license-file", null);
		
		locationAutofill = LocatorUtil.parseAutofillOption(props);
		
		locator = new Locator(props);
		locator.setDefaultCountry(countryName, countryAbbr);
		String driveOn = props.getProperty("drive-on",null);
		if ("left".equals(driveOn))
			driveOnLeft = true;
		if ("right".equals(driveOn))
			driveOnLeft = false;
		orderByDecreasingArea = props.getProperty("order-by-decreasing-area", false);
		pathsToHGT = props.getProperty("dem", null);
		String demDistStr = props.getProperty("dem-dists", "-1");
		demOutsidePolygonHeight = (short) props.getProperty("dem-outside-polygon", HGTReader.UNDEF);
		String demPolygonFile = props.getProperty("dem-poly", null);
		if (demPolygonFile != null) {
			demPolygon = Java2DConverter.readPolyFile(demPolygonFile);
		}
		String ipm = props.getProperty("dem-interpolation", "auto");
		switch (ipm) {
		case "auto": 
			demInterpolationMethod = InterpolationMethod.AUTOMATIC;
			break;
		case "bicubic": 
			demInterpolationMethod = InterpolationMethod.BICUBIC;
			break;
		case "bilinear":
			demInterpolationMethod = InterpolationMethod.BILINEAR;
			break;
		default:
			throw new IllegalArgumentException("invalid argument for option dem-interpolation: '" + ipm + 
					"' supported are 'bilinear', 'bicubic', or 'auto'");
		}

		if (isOverviewCombined) { // some alternate options, some invalid etc
			demDistStr = props.getProperty("overview-dem-dist", "-1");
			mergeLines = true;
			if (orderByDecreasingArea) {
				orderByDecreasingArea = false;
				mergeShapes = false;  // shape order in ovm_ imgs must be preserved to have the effect of above 
			} else {
				mergeShapes = true;
			}
		}
		demDists = parseDemDists(demDistStr);
	}

	private static List<Integer> parseDemDists(String demDists) {
		List<Integer> dists = CommandArgs.stringToList(demDists, "dem-dists")
				.stream().map(Integer::parseInt).collect(Collectors.toList());
		if (dists.isEmpty())
			return Arrays.asList(-1);
		return dists;
	}

	/**
	 * Main method to create the map, just calls out to several routines
	 * that do the work.
	 *
	 * @param map The map.
	 * @param src The map data.
	 */
	public void makeMap(Map map, LoadableMapDataSource src) {

		RGNFile rgnFile = map.getRgnFile();
		TREFile treFile = map.getTreFile();
		lblFile = map.getLblFile();
		NETFile netFile = map.getNetFile();
		
		hasNet = netFile != null;
		
		if (routeCenterBoundaryType != 0 && netFile != null && src instanceof MapperBasedMapDataSource) {
			for (RouteCenter rc : src.getRoadNetwork().getCenters()) {
				((MapperBasedMapDataSource) src).addBoundaryLine(rc.getArea(), routeCenterBoundaryType,
						rc.reportSizes());
			}
		}
		if (mapInfo.isEmpty())
			getMapInfo();

		if (map.getNodFile() != null) {
			// make sure that island detection is done before we write any map data so that NOD flags are properly set 
			src.getRoadNetwork().getCenters();
		}
		normalizeCountries(src);
		
		processCities(map, src);
		processRoads(map,src);
		processPOIs(map, src);
		processOverviews(map, src);
		processInfo(map, src);
		makeMapAreas(map, src);
		 
		if (driveOnLeft == null && src instanceof MapperBasedMapDataSource) {
			// source can give info about driving side
			driveOnLeft = ((MapperBasedMapDataSource) src).getDriveOnLeft();
		}
		if (driveOnLeft == null)
			driveOnLeft = false;
		treFile.setDriveOnLeft(driveOnLeft);

		treFile.setLastRgnPos(rgnFile.position() - RGNHeader.HEADER_LEN);

		rgnFile.write();
		treFile.write(rgnFile.haveExtendedTypes());
		lblFile.write();
		lblFile.writePost();

		if (netFile != null) {
			RoadNetwork network = src.getRoadNetwork();
			netFile.setNetwork(network.getRoadDefs());
			NODFile nodFile = map.getNodFile();
			if (nodFile != null) {
				nodFile.setNetwork(network.getCenters(), network.getRoadDefs(), network.getBoundary());
				nodFile.setDriveOnLeft(driveOnLeft);
				nodFile.write();
			}
			netFile.write(lblFile.numCities(), lblFile.numZips());

			if (nodFile != null) {
				nodFile.writePost();
			}
			netFile.writePost(rgnFile.getWriter());
		}
		warnAbout3ByteImgRefs();
		buildDem(map, src);
		treFile.writePost();
	}

	private void buildDem(Map map, LoadableMapDataSource src) {
		DEMFile demFile = map.getDemFile();
		if (demFile == null)
			return;
		try{
			long t1 = System.currentTimeMillis();
			java.awt.geom.Area  demArea = null;
			if (demPolygon != null) {
				Area bbox = src.getBounds();
				// the rectangle is a bit larger to avoid problems at tile boundaries
				Rectangle2D r = new Rectangle(bbox.getMinLong() - 2, bbox.getMinLat() - 2, 
						bbox.getWidth() + 4, bbox.getHeight() + 4);
				if (demPolygon.intersects(r) && !demPolygon.contains(r)) {
					demArea = demPolygon;
				}
			} 
			if (demArea == null && isOverviewCombined) {
				Path2D demPoly = ((OverviewMapDataSource) src).getTileAreaPath();
				if (demPoly != null) {
					demArea = new java.awt.geom.Area(demPoly);
				}
			}
			Area treArea = demFile.calc(src.getBounds(), demArea, pathsToHGT, demDists, demOutsidePolygonHeight, demInterpolationMethod);
			map.setBounds(treArea);
			long t2 = System.currentTimeMillis();
			log.info("DEM file calculation for", map.getFilename(), "took", (t2 - t1), "ms");
			demFile.write();
		} catch (MapTooBigException e) {
			throw new MapTooBigException(e.getMaxAllowedSize(), "The DEM section of the map or tile is too big.", "Try increasing the DEM distance."); 
		} catch (MapFailedException e) {
			throw new MapFailedException("Error creating DEM File. " + e.getMessage()); 
		}
	}

	private void warnAbout3ByteImgRefs() {
		String mapContains = "Map contains";
		String infoMsg = "- more than 65535 might cause indexing problems and excess size. Suggest splitter with lower --max-nodes";
		int itemCount;
		itemCount = lblFile.numCities();
		if (itemCount > 0xffff)
			log.error(mapContains, itemCount, "Cities", infoMsg);
		itemCount = lblFile.numZips();
		if (itemCount > 0xffff)
			log.error(mapContains, itemCount, "Zips", infoMsg);
		itemCount = lblFile.numHighways();
		if (itemCount > 0xffff)
			log.error(mapContains, itemCount, "Highways", infoMsg);
		itemCount = lblFile.numExitFacilities();
		if (itemCount > 0xffff)
			log.error(mapContains, itemCount, "Exit facilities", infoMsg);
	} // warnAbout3ByteImgRefs

	private Country getDefaultCountry() {
		if (defaultCountry == null && lblFile != null) {
			defaultCountry = lblFile.createCountry(countryName, countryAbbr);
		}
		return defaultCountry;
	}
	
	/**
	 * Retrieves the region with the default name in the given country.
	 * @param country the country ({@code null} = use default country)
	 * @return the default region in the given country ({@code null} if not available)
	 */
	private Region getDefaultRegion(Country country) {
		if (lblFile == null || regionName == null) {
			return null;
		}
		if (country == null) {
			if (getDefaultCountry() == null) {
				return null;
			} else {
				return lblFile.createRegion(getDefaultCountry(), regionName, regionAbbr);
			}
		} else {
			return lblFile.createRegion(country, regionName, regionAbbr);
		}
	}

	/**
	 * Process the country names of all elements and normalize them
	 * so that one consistent country name is used for the same country 
	 * instead of different spellings.
	 * @param src the source of elements
	 */
	private void normalizeCountries(MapDataSource src) {
		for (MapPoint p : src.getPoints()) {
			String countryStr = p.getCountry();
			if (countryStr != null) {
				countryStr = locator.normalizeCountry(countryStr);
				p.setCountry(countryStr);
			}
		}

		for (MapLine l : src.getLines()) {
			String countryStr = l.getCountry();
			if (countryStr != null) {
				countryStr = locator.normalizeCountry(countryStr);
				l.setCountry(countryStr);
			}
		}

		// shapes do not have address information
	}
	
	/**
	 * Processing of Cities
	 *
	 * Fills the city list in lbl block that is required for find by name
	 * It also builds up information that is required to get address info
	 * for the POIs
	 *
	 * @param map The map.
	 * @param src The map data.
	 */
	private void processCities(Map map, MapDataSource src) {
		LBLFile lbl = map.getLblFile();
		
		if (!locationAutofill.isEmpty()) {
			// collect the names of the cities
			for (MapPoint p : src.getPoints()) {
				if(p.isCity() && p.getName() != null)
					locator.addCityOrPlace(p); // Put the city info the map for missing info 
			}

			locator.autofillCities(); // Try to fill missing information that include search of next city
		}
		
		for (MapPoint p : src.getPoints()) {
			if (!p.isCity() || p.getName() == null)
				continue;
			String countryStr = p.getCountry();
			Country thisCountry;
			if (countryStr != null) {
				thisCountry = lbl.createCountry(countryStr, locator.getCountryISOCode(countryStr));
			} else {
				thisCountry = getDefaultCountry();
			}
			String regionStr = p.getRegion();
			Region thisRegion;
			if (regionStr != null) {
				thisRegion = lbl.createRegion(thisCountry, regionStr, null);
			} else {
				thisRegion = getDefaultRegion(thisCountry);
			}
			City thisCity;
			if (thisRegion != null)
				thisCity = lbl.createCity(thisRegion, p.getName(), true);
			else
				thisCity = lbl.createCity(thisCountry, p.getName(), true);

			cityMap.put(p, thisCity);
		}

	}

	private void processRoads(Map map, MapDataSource src) {
		LBLFile lbl = map.getLblFile();
		MapPoint searchPoint = new MapPoint();
		for (MapLine line : src.getLines()) {
			if(!line.isRoad()) 
				continue;
			String cityName = line.getCity();
			String cityCountryName = line.getCountry();
			String cityRegionName  = line.getRegion();
			String zipStr = line.getZip();

			if(cityName == null && locationAutofill.contains("nearest")) {
				// Get name of next city if untagged

				searchPoint.setLocation(line.getLocation());
				MapPoint nextCity = locator.findNextPoint(searchPoint);

				if(nextCity != null) {
					cityName = nextCity.getCity();
					// city/region/country fields should match to the found city
					cityCountryName = nextCity.getCountry();
					cityRegionName = nextCity.getRegion();

					// use the zip code only if no zip code is known
					if(zipStr == null)
						zipStr = nextCity.getZip();
				}
			}

			MapRoad road = (MapRoad) line;
			road.resetImgData();

			City roadCity = calcCity(lbl, cityName, cityRegionName, cityCountryName);
			if (roadCity != null)
				road.addRoadCity(roadCity);

			if (zipStr != null) {
				road.addRoadZip(lbl.createZip(zipStr));
			}

			processRoadNumbers(road, lbl);
		}	
	}

	private void processRoadNumbers(MapRoad road, LBLFile lbl) {
		List<Numbers> numbers = road.getRoadDef().getNumbersList();
		if (numbers == null)
			return;
		for (Numbers num : numbers) {
			for (int i = 0; i < 2; i++) {
				boolean leftRightFlag = (i == 0);
				ZipCodeInfo zipInfo = num.getZipCodeInfo(leftRightFlag);
				if (zipInfo != null && zipInfo.getZipCode() != null) {
					Zip zip = zipInfo.getImgZip();
					if (zip == null) {
						zip = lbl.createZip(zipInfo.getZipCode());
						zipInfo.setImgZip(zip);
					}
					if (zip != null)
						road.addRoadZip(zip);
				}
				CityInfo cityInfo = num.getCityInfo(leftRightFlag);
				if (cityInfo != null) {
					City city = cityInfo.getImgCity();
					if (city == null) {
						city = calcCity(lbl, cityInfo.getCity(), cityInfo.getRegion(), cityInfo.getCountry());
						cityInfo.setImgCity(city);
					}
					if (city != null)
						road.addRoadCity(city);
				}
			}
		}
	}

	private City calcCity(LBLFile lbl, String city, String region, String country) {
		if (city == null && region == null && country == null)
			return null;
		Country cc = (country == null) ? getDefaultCountry()
				: lbl.createCountry(locator.normalizeCountry(country), locator.getCountryISOCode(country));
		Region cr = (region == null) ? getDefaultRegion(cc) : lbl.createRegion(cc, region, null);
		if (city == null) {
			// if city name is unknown and region and/or country is known
			// use empty name for the city
			city = UNKNOWN_CITY_NAME;
		}
		if (cr != null) {
			return lbl.createCity(cr, city, false);
		} else {
			return lbl.createCity(cc, city, false);
		}
	}
	
	private void processPOIs(Map map, MapDataSource src) {

		LBLFile lbl = map.getLblFile();
		boolean checkedForPoiDispFlag = false;

		for (MapPoint p : src.getPoints()) {
			// special handling for highway exits
			if(p.isExit()) {
				processExit(map, (MapExitPoint)p);
			}
			// do not process:
			// * cities (already processed)
			// * extended types (address information not shown in MapSource and on GPS)
			// * all POIs except roads in case the no-poi-address option is set
			else if (!p.isCity() && !p.hasExtendedType() &&  poiAddresses) {
				
				String countryStr = p.getCountry();
				String regionStr  = p.getRegion();
				String zipStr     = p.getZip();
				String cityStr    = p.getCity();

				if (locationAutofill.contains("nearest")
						&& (countryStr == null || regionStr == null || (zipStr == null && cityStr == null))) {
					MapPoint nextCity = locator.findNearbyCityByName(p);

					if (nextCity == null)
						nextCity = locator.findNextPoint(p);

					if (nextCity != null) {
						if (countryStr == null)
							countryStr = nextCity.getCountry();
						if (regionStr == null)
							regionStr = nextCity.getRegion();

						if (zipStr == null) {
							String cityZipStr = nextCity.getZip();

							// Ignore list of Zips separated by ,
							if (cityZipStr != null && cityZipStr.indexOf(',') < 0)
								zipStr = cityZipStr;
						}

						if (cityStr == null)
							cityStr = nextCity.getCity();

					}
				}				
	
				if (countryStr != null && !checkedForPoiDispFlag) {
					// Different countries require different address notation

					poiDisplayFlags = locator.getPOIDispFlag(countryStr);
					checkedForPoiDispFlag = true;
				}

				POIRecord r = lbl.createPOI(p.getName());	
				
				if (cityStr != null || regionStr != null || countryStr != null) {
					r.setCity(calcCity(lbl, cityStr, regionStr, countryStr));
				}

				if (zipStr != null) {
					Zip zip = lbl.createZip(zipStr);
					r.setZip(zip);
				}

				if (p.getStreet() != null) {
					Label streetName = lbl.newLabel(p.getStreet());
					r.setStreetName(streetName);
				}

				String houseNumber = p.getHouseNumber();
				if (houseNumber != null && !houseNumber.isEmpty() && !r.setSimpleStreetNumber(houseNumber)) {
					r.setComplexStreetNumber(lbl.newLabel(houseNumber));
				}

				String phone = p.getPhone();
				if (phone != null && !phone.isEmpty() && !r.setSimplePhoneNumber(phone)) {
					r.setComplexPhoneNumber(lbl.newLabel(phone));
				}
		  	
				poimap.put(p, r);
			}
		}

		lbl.allPOIsDone();
	}

	private void processExit(Map map, MapExitPoint mep) {
		LBLFile lbl = map.getLblFile();
		String exitName = mep.getName();
		String ref = mep.getMotorwayRef();
		String osmId = mep.getOSMId();
		if (ref == null)
			log.warn("Can't create exit", exitName, "(OSM id", osmId, ") doesn't have exit:road_ref tag");
		else {
			Highway hw = highways.get(ref);
			if (hw == null) {
				String countryStr = mep.getCountry();
				Country thisCountry = countryStr != null ? lbl.createCountry(locator.normalizeCountry(countryStr), locator.getCountryISOCode(countryStr)) : getDefaultCountry();
				String regionStr = regionName != null ? regionName : mep.getRegion(); // use --region-name if set because highway will likely span regions
				Region thisRegion = regionStr != null ? lbl.createRegion(thisCountry, regionStr, null) : getDefaultRegion(thisCountry);
				hw = lbl.createHighway(thisRegion, ref);
				log.info("creating highway", ref, "region:", regionStr, "country:", countryStr, "for exit:", exitName);
				highways.put(ref, hw);
			}
			String exitTo = mep.getTo();
			Exit exit = new Exit(hw);
			String facilityDescription = mep.getFacilityDescription();
			log.info("Creating", ref, "exit", exitName, "(OSM id", osmId +") to", exitTo, "with facility", ((facilityDescription == null)? "(none)" : facilityDescription));
			if(facilityDescription != null) {
				// description is TYPE,DIR,FACILITIES,LABEL
				// (same as Polish Format)
				String[] atts = facilityDescription.split(",");
				int type = 0;
				if(atts.length > 0)
					type = Integer.decode(atts[0]);
				char direction = ' ';
				if(atts.length > 1) {
					direction = atts[1].charAt(0);
					if(direction == '\'' && atts[1].length() > 1)
						direction = atts[1].charAt(1);
				}
				int facilities = 0x0;
				if(atts.length > 2)
					facilities = Integer.decode(atts[2]);
				String description = "";
				if(atts.length > 3)
					description = atts[3];
				boolean last = true;
				// FIXME - handle multiple facilities?
				ExitFacility ef = lbl.createExitFacility(type, direction, facilities, description, last);

				exit.addFacility(ef);
			}
			mep.setExit(exit);
			POIRecord r = lbl.createExitPOI(exitName, exit);
			if(exitTo != null) {
				Label ed = lbl.newLabel(exitTo);
				exit.setDescription(ed);
			}
			poimap.put(mep, r);
			// FIXME - set bottom bits of type to reflect facilities available?
		}
	}

	/**
	 * Drive the map generation by stepping through the levels, generating the
	 * subdivisions for the level and filling in the map elements that should
	 * go into the area.
	 *
	 * This is fairly complex: you need to divide into subdivisions depending on
	 * their size and the number of elements that will be contained.
	 *
	 * @param map The map.
	 * @param src The data for the map.
	 */
	private void makeMapAreas(Map map, LoadableMapDataSource src) {
		// The top level has to cover the whole map without subdividing, so
		// do a special check to make sure.
		LevelInfo[] levels = null;
		if (isOverviewCombined) {
			if (mergeShapes)
				prepShapesForMerge(src.getShapes());
			levels = src.mapLevels();
		} else if (isOverviewComponent) {
			levels = src.overviewMapLevels();
		} else {
			levels = src.mapLevels();
		}
		if (levels == null) {
			throw new ExitException("no info about levels available.");
		}
		LevelInfo levelInfo = levels[0];

		// If there is already a top level zoom, then we shouldn't add our own
		Subdivision topdiv;
		if (levelInfo.isTop()) {
			// There is already a top level definition.  So use the values from it and
			// then remove it from the levels definition.

			levels = Arrays.copyOfRange(levels, 1, levels.length);

			Zoom zoom = map.createZoom(levelInfo.getLevel(), levelInfo.getBits());
			topdiv = makeTopArea(src, map, zoom);
		} else {
			// We have to automatically create the definition for the top zoom level.
			int maxBits = getMaxBits(src);
			// If the max is larger than the top-most data level then we
			// decrease it so that it is less.
			if (levelInfo.getBits() <= maxBits)
				maxBits = levelInfo.getBits() - 1;

			// Create the empty top level
			Zoom zoom = map.createZoom(levelInfo.getLevel() + 1, maxBits);
			topdiv = makeTopArea(src, map, zoom);
		}

		// We start with one map data source.
		List<SourceSubdiv> srcList = Collections.singletonList(new SourceSubdiv(src, topdiv));
		if (mergeShapes && improveOverview && isOverviewComponent) {
			recalcMultipolygons(src, levels);
		}
		src.getShapes().forEach(s -> s.setMpRel(null)); // free memory for MultipolygonRelations

		// Now the levels filled with features.
		for (LevelInfo linfo : levels) {
			List<SourceSubdiv> nextList = new ArrayList<>();

			Zoom zoom = map.createZoom(linfo.getLevel(), linfo.getBits());

			for (SourceSubdiv srcDivPair : srcList) {
				MapSplitter splitter = new MapSplitter(srcDivPair.getSource(), zoom);
				MapArea[] areas = splitter.split(orderByDecreasingArea);
				log.info("Map region", srcDivPair.getSource().getBounds(), "split into", areas.length, "areas at resolution", zoom.getResolution());

				for (MapArea area : areas) {
					Subdivision parent = srcDivPair.getSubdiv();
					Subdivision div = makeSubdivision(map, parent, area, zoom);
					if (log.isDebugEnabled())
						log.debug("ADD parent-subdiv", parent, srcDivPair.getSource(), ", z=", zoom, "new=", div);
					nextList.add(new SourceSubdiv(area, div));
				}
				if (!nextList.isEmpty()) {
					Subdivision lastdiv = nextList.get(nextList.size() - 1).getSubdiv();
					lastdiv.setLast(true);
				}
			}
			srcList = nextList;
		}
	}

	/**
	 * for the overview map: 
	 * Make sure that all {@link Coord} instances are
	 * identical when they are equal.
	 * @param shapes the list of shapes
	 */
	private static void prepShapesForMerge(List<MapShape> shapes) {
		Long2ObjectOpenHashMap<Coord> coordMap = new Long2ObjectOpenHashMap<>();
		for (MapShape s : shapes) {
			List<Coord> points = s.getPoints();
			int n = points.size();
			for (int i = 0; i < n; i++) {
				Coord co = points.get(i);
				long key = Utils.coord2Long(co);
				Coord repl = coordMap.get(key);
				if (repl == null)
					coordMap.put(key, co);
				else
					points.set(i, repl);
			}
		}
	}

	/**
	 * Create the top level subdivision.
	 *
	 * There must be an empty zoom level at the least detailed level. As it
	 * covers the whole area in one it must be zoomed out enough so that
	 * this can be done.
	 *
	 * Note that the width is a 16 bit quantity, but the top bit is a
	 * flag and so that leaves only 15 bits into which the actual width
	 * can fit.
	 *
	 * @param src  The source of map data.
	 * @param map  The map being created.
	 * @param zoom The zoom level.
	 * @return The new top level subdivision.
	 */
	private static Subdivision makeTopArea(MapDataSource src, Map map, Zoom zoom) {
		Subdivision topdiv = map.topLevelSubdivision(src.getBounds(), zoom);
		topdiv.setLast(true);
		return topdiv;
	}

	/**
	 * Make an individual subdivision for the map.  To do this we need a link
	 * to its parent and the zoom level that we are working at.
	 *
	 * @param map	The map to add this subdivision into.
	 * @param parent The parent division.
	 * @param ma	 The area of the map that we are fitting into this division.
	 * @param z	  The zoom level.
	 * @return The new subdivsion.
	 */
	private Subdivision makeSubdivision(Map map, Subdivision parent, MapArea ma, Zoom z) {
		List<MapPoint> points = ma.getPoints();
		List<MapLine> lines = ma.getLines();
		List<MapShape> shapes = ma.getShapes();

		Subdivision div = map.createSubdivision(parent, ma.getFullBounds(), z);

		if (ma.hasPoints())
			div.setHasPoints(true);
		if (ma.hasIndPoints())
			div.setHasIndPoints(true);
		if (ma.hasLines())
			div.setHasPolylines(true);
		if (ma.hasShapes())
			div.setHasPolygons(true);

		div.startDivision();

		processPoints(map, div, points);

		final int res = z.getResolution();
		lines = lines.stream().filter(l -> l.getMinResolution() <= res).collect(Collectors.toList());
		shapes = shapes.stream().filter(s -> s.getMinResolution() <= res).collect(Collectors.toList());
		
		if (mergeLines) {
			LineMergeFilter merger = new LineMergeFilter();
			lines = merger.merge(lines, res, !hasNet, allowReverseMerge);
		}

		if (mergeShapes) {
			ShapeMergeFilter shapeMergeFilter = new ShapeMergeFilter(res, orderByDecreasingArea);
			shapes = shapeMergeFilter.merge(shapes);
		}

		// recalculate preserved status for all points in lines and shapes
		shapes.forEach(e -> e.getPoints().forEach(p -> p.preserved(false)));
		if (z.getLevel() == 0 && hasNet) {
			lines.forEach(e -> e.getPoints().forEach(p -> p.preserved(p.isNumberNode())));	
		} else {
			lines.forEach(e -> e.getPoints().forEach(p -> p.preserved(false)));
		}
		preserveFirstLast(lines);
		if (res < 24) {
			preserveHorizontalAndVerticalLines(res, shapes);
		}
		
		processLines(map, div, lines); 
		processShapes(map, div, shapes);

		div.endDivision();

		return div;
	}

	/**
	 * Mark first and last point of each line as preserved 
	 * @param the lines 
	 */
	private static void preserveFirstLast(List<MapLine> lines) {
		for (MapLine l : lines) {
			l.getPoints().get(0).preserved(true);
			l.getPoints().get(l.getPoints().size()-1).preserved(true);
		}
	}


	/**
	 * Create the overview sections.
	 *
	 * @param map The map details.
	 * @param src The map data source.
	 */
	protected void processOverviews(Map map, MapDataSource src) {
		List<Overview> features = src.getOverviews();
		for (Overview ov : features) {
			switch (ov.getKind()) {
			case Overview.POINT_KIND:
				map.addPointOverview((PointOverview) ov);
				break;
			case Overview.LINE_KIND:
				map.addPolylineOverview((PolylineOverview) ov);
				break;
			case Overview.SHAPE_KIND:
				map.addPolygonOverview((PolygonOverview) ov);
				break;
			default:
				break;
			}
		}
	}

	/**
	 * Set all the information that appears in the header.
	 */
	private void getMapInfo() {
		if (licenseFileName != null) {
			List<String> licenseArray = new ArrayList<>();
			try {
				File file = new File(licenseFileName);
				licenseArray = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			}
			catch (Exception e) {
				throw new ExitException("Error reading license file " + licenseFileName, e);
			}
			if ((!licenseArray.isEmpty()) && licenseArray.get(0).startsWith("\ufeff"))
				licenseArray.set(0, licenseArray.get(0).substring(1));
			UnaryOperator<String> replaceVariables = s -> s.replace("$MKGMAP_VERSION$", Version.VERSION)
					.replace("$JAVA_VERSION$", System.getProperty("java.version"))
					.replace("$YEAR$", Integer.toString(now.getYear()))
					.replace("$LONGDATE$", now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)))
					.replace("$SHORTDATE$", now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)))
					.replace("$TIME$", now.toLocalTime().toString().substring(0, 5));
			licenseArray.replaceAll(replaceVariables);
			mapInfo.addAll(licenseArray);
		} else {
			mapInfo.add("Map data (c) OpenStreetMap and its contributors");
			mapInfo.add("http://www.openstreetmap.org/copyright");
			mapInfo.add("");
			mapInfo.add("This map data is made available under the Open Database License:");
			mapInfo.add("http://opendatacommons.org/licenses/odbl/1.0/");
			mapInfo.add("Any rights in individual contents of the database are licensed under the");
			mapInfo.add("Database Contents License: http://opendatacommons.org/licenses/dbcl/1.0/");
			mapInfo.add("");

			// Pad the version number with spaces so that version
			// strings that are different lengths do not change the size and
			// offsets of the following sections.
			mapInfo.add("Map created with mkgmap-r"
					+ String.format("%-10s", Version.VERSION));

			mapInfo.add("Program released under the GPL");
		}
	}
	
	public void setMapInfo(List<String> msgs) {
		mapInfo = msgs;
	}

	public void setCopyrights(List<String> msgs) {
		copyrights = msgs;
	}	
	
	/**
	 * Set all the information that appears in the header.
	 *
	 * @param map The map to write to.
	 * @param src The source of map information.
	 */
	private void processInfo(Map map, LoadableMapDataSource src) {
		// The bounds of the map.
		map.setBounds(src.getBounds());
		if (!isOverviewCombined)
			poiDisplayFlags |= TREHeader.POI_FLAG_DETAIL;
			
		poiDisplayFlags |= src.getPoiDispFlag();

		if(poiDisplayFlags != 0)
			map.addPoiDisplayFlags(poiDisplayFlags);

		// You can add anything here.
		// But there has to be something, otherwise the map does not show up.
		//
		// We use it to add copyright information that there is no room for
		// elsewhere
		StringBuilder info = new StringBuilder();
		for (String s : mapInfo) {
			info.append(s.trim()).append('\n');
		}
		if (info.length() > 0)
			map.addInfo(info.toString());

		if (copyrights.isEmpty()) {
			// There has to be (at least) two copyright messages or else the map
			// does not show up.  The second and subsequent ones will be displayed
			// at startup, although the conditions where that happens are not known.
			// All copyright messages are displayed in BaseCamp.
			String[] copyrightMessages = src.copyrightMessages();
			if (copyrightMessages.length < 2)
				map.addCopyright("program licenced under GPL v2");

			for (String cm : copyrightMessages)
				map.addCopyright(cm);
		} else {
			for (String cm : copyrights)
				map.addCopyright(cm);
		}
	}

	/**
	 * Step through the points, filter and create a map point which is then added
	 * to the map.
	 *
	 * Note that the location and resolution of map elements is relative to the
	 * subdivision that they occur in.
	 *
	 * @param map	The map to add points to.
	 * @param div	The subdivision that the points belong to.
	 * @param points The points to be added.
	 */
	private void processPoints(Map map, Subdivision div, List<MapPoint> points) {
		LBLFile lbl = map.getLblFile();
		div.startPoints();
		int res = div.getResolution();

		boolean haveIndPoints = false;
		int pointIndex = 1;

		// although the non-indexed points are output first,
		// pointIndex must be initialized to the number of indexed
		// points (not 1)
		for (MapPoint point : points) {
			if (point.isCity() && point.getMinResolution() <= res) {
				++pointIndex;
				haveIndPoints = true;
			}
		}

		for (MapPoint point : points) {

			if (point.isCity() || point.getMinResolution() > res)
				continue;

			String name = point.getName();

			Point p = div.createPoint(name);
			p.setType(point.getType());

			if (point.hasExtendedType()) {
				ExtTypeAttributes eta = point.getExtTypeAttributes();
				if (eta != null) {
					eta.processLabels(lbl);
					p.setExtTypeAttributes(eta);
				}
			}

			Coord coord = point.getLocation();
			try {
				p.setLatitude(coord.getLatitude());
				p.setLongitude(coord.getLongitude());
			} catch (AssertionError ae) {
				log.error("Problem with point of type 0x" + Integer.toHexString(point.getType()) + " at " + coord.toOSMURL());
				log.error("  Subdivision shift is " + div.getShift() +
						  " and its centre is at " + div.getCenter().toOSMURL());
				log.error("  " + ae.getMessage());
				continue;
			}

			POIRecord r = poimap.get(point);
			if (r != null)
				p.setPOIRecord(r);

			map.addMapObject(p);
			if (!point.hasExtendedType()) {
				if (name != null && div.getZoom().getLevel() == 0) {
					if (pointIndex > 255) {
						log.error("Too many POIs near location", div.getCenter().toOSMURL(), "-", name,
								"will be ignored");
					} else if (point.isExit()) {
						Exit e = ((MapExitPoint) point).getExit();
						if (e != null)
							e.getHighway().addExitPoint(name, pointIndex, div);
					} else if (makePOIIndex) {
						lbl.createPOIIndex(name, pointIndex, div, point.getType());
					}
				}

				++pointIndex;
			}
		}

		if (haveIndPoints) {
			div.startIndPoints();

			pointIndex = 1; // reset to 1
			for (MapPoint point : points) {

				if (!point.isCity() || point.getMinResolution() > res)
					continue;

				String name = point.getName();

				Point p = div.createPoint(name);
				int fullType = point.getType();
				assert (fullType & 0xff) == 0 : "indPoint " + GType.formatType(fullType) + " has subtype";
				p.setType(fullType);

				Coord coord = point.getLocation();
				try {
					p.setLatitude(coord.getLatitude());
					p.setLongitude(coord.getLongitude());
				} catch (AssertionError ae) {
					log.error("Problem with point of type 0x" + Integer.toHexString(point.getType()) + " at " + coord.toOSMURL());
					log.error("  Subdivision shift is " + div.getShift() +
							  " and its centre is at " + div.getCenter().toOSMURL());
					log.error("  " + ae.getMessage());
					continue;
				}

				map.addMapObject(p);
				if(name != null && div.getZoom().getLevel() == 0) {
					// retrieve the City created earlier for this
					// point and store the point info in it
					City c = cityMap.get(point);

					if(pointIndex > 255) {
						log.error("Can't set city point index for", name, "(too many indexed points in division)\n");
					} else {
						c.setPointIndex(pointIndex);
						c.setSubdivision(div);
					}
				}

				++pointIndex;
			}
		}
	}

	/**
	 * Step through the lines, filter, simplify if necessary, and create a map
	 * line which is then added to the map.
	 *
	 * Note that the location and resolution of map elements is relative to the
	 * subdivision that they occur in.
	 *
	 * @param map	The map to add points to.
	 * @param div	The subdivision that the lines belong to.
	 * @param lines The lines to be added.
	 */
	private void processLines(Map map, Subdivision div, List<MapLine> lines) {
		div.startLines();  // Signal that we are beginning to draw the lines.

		int res = div.getResolution();

		FilterConfig config = new FilterConfig();
		config.setResolution(res);
		config.setLevel(div.getZoom().getLevel());
		config.setHasNet(hasNet);

		LayerFilterChain normalFilters = new LayerFilterChain(config);
		LayerFilterChain keepParallelFilters = new LayerFilterChain(config);
		if (enableLineCleanFilters && (res < 24)) {
			MapFilter rounder = new RoundCoordsFilter();
			MapFilter sizeFilter = new SizeFilter(MIN_SIZE_LINE);
			normalFilters.addFilter(rounder);
			normalFilters.addFilter(sizeFilter);
			double errorForRes = dpFilterLineResMap.ceilingEntry(res).getValue();
			if(errorForRes > 0) {
				DouglasPeuckerFilter dp = new DouglasPeuckerFilter(errorForRes);
				normalFilters.addFilter(dp);
				keepParallelFilters.addFilter(dp);
			}
			keepParallelFilters.addFilter(rounder);
			keepParallelFilters.addFilter(sizeFilter);
		}
		for (MapFilter filter : Arrays.asList(
				new LineSplitterFilter(), 
				new RemoveEmpty(),
				new RemoveObsoletePointsFilter(), 
				new LinePreparerFilter(div), 
				new LineAddFilter(div, map))) {
			normalFilters.addFilter(filter);
			keepParallelFilters.addFilter(filter);
		}
		
		for (MapLine line : lines) {
			if (line.getMinResolution() <= res) {
				if (GType.isContourLine(line) || isOverviewComponent) 
					keepParallelFilters.startFilter(line);
				else 
					normalFilters.startFilter(line);
			}
		}
	}

	/**
	 * Step through the polygons, filter, simplify if necessary, and create a map
	 * shape which is then added to the map.
	 *
	 * Note that the location and resolution of map elements is relative to the
	 * subdivision that they occur in.
	 *
	 * @param map	The map to add polygons to.
	 * @param div	The subdivision that the polygons belong to.
	 * @param shapes The polygons to be added.
	 */
	private void processShapes(Map map, Subdivision div, List<MapShape> shapes) {
		div.startShapes();  // Signal that we are beginning to draw the shapes.

		int res = div.getResolution();

		FilterConfig config = new FilterConfig();
		config.setResolution(res);
		config.setLevel(div.getZoom().getLevel());
		config.setHasNet(hasNet);
		
		if (orderByDecreasingArea && shapes.size() > 1) {
			// sort so that the shape with the largest area is processed first
			shapes.sort((s1,s2) -> Long.compare(Math.abs(s2.getFullArea()), Math.abs(s1.getFullArea())));
		}

		
		LayerFilterChain filters = new LayerFilterChain(config);
		filters.addFilter(new PolygonSplitterFilter());
		if (enableLineCleanFilters && (res < 24)) {
			filters.addFilter(new RoundCoordsFilter());
			int sizefilterVal =  getMinSizePolygonForResolution(res);
			if (sizefilterVal > 0)
				filters.addFilter(new SizeFilter(sizefilterVal));
			//DouglasPeucker behaves at the moment not really optimal at low zooms, but acceptable.
			//Is there a similar algorithm for polygons?
			double errorForRes = dpFilterShapeResMap.ceilingEntry(res).getValue();
			if(errorForRes > 0)
				filters.addFilter(new DouglasPeuckerFilter(errorForRes));
		}
		filters.addFilter(new RemoveObsoletePointsFilter());
		filters.addFilter(new RemoveEmpty());
		filters.addFilter(new LinePreparerFilter(div));
		filters.addFilter(new ShapeAddFilter(div, map));

		for (MapShape shape : shapes) {
			if (shape.getMinResolution() <= res) {
				filters.startFilter(shape);
			}
		}
	}

	/**
	 * Preserve shape points which a) lie on the shape boundary or
	 * b) which appear multiple times in the shape (excluding the start
	 * point which should always be identical to the end point).
	 * The preserved points are kept treated specially in the 
	 * Line-Simplification-Filters, this should avoid artifacts like
	 * white triangles in the sea for lower resolutions.     
	 * @param res the current resolution
	 * @param shapes list of shapes
	 */
	private static void preserveHorizontalAndVerticalLines(int res, List<MapShape> shapes) {
		if (res == 24)
			return;
		for (MapShape shape : shapes) {
			if (shape.getMinResolution() > res)
				continue;
			List<Coord> points = shape.getPoints();
			int n = points.size();
			IdentityHashMap<Coord, Coord> coords = new IdentityHashMap<>(n);
			Coord prev = points.get(0);
			Coord last;
			for (int i = 1; i < n; ++i) {
				last = points.get(i);
				// preserve coord instances which are used more than once,
				// these are typically produced by the ShapeMergerFilter 
				// to connect holes
				if (coords.get(last) == null) {
					coords.put(last, last);
				} else if (!last.preserved()) {
					last.preserved(true);
				}

				// preserve the end points of horizontal and vertical lines 
				// they are very likely produced by cutting 
				if(last.getHighPrecLat() == prev.getHighPrecLat() || last.getHighPrecLon() == prev.getHighPrecLon()) {
					last.preserved(true);
					prev.preserved(true);
				}
				prev = last;
			}
		}
	} 

	/**
	 * It is not possible to represent large maps at the 24 bit resolution.  This
	 * gets the largest resolution that can still cover the whole area of the
	 * map.  It is used for the top most layer.
	 *
	 * @param src The map data.
	 * @return The largest number of bits where we can still represent the
	 *         whole map.
	 */
	private static int getMaxBits(MapDataSource src) {
		int topshift = Integer.numberOfLeadingZeros(src.getBounds().getMaxDimension());
		int minShift = Math.max(CLEAR_TOP_BITS - topshift, 0);
		return 24 - minShift;
	}

	public void setEnableLineCleanFilters(boolean enable) {
		this.enableLineCleanFilters = enable;
	}

	/**
	 * Determine the minimum size for a polygon for the given level.
	 * @param res the resolution
	 * @return the size filter value
	 */
	private int getMinSizePolygonForResolution(int res) {
		if (polygonSizeLimitsOpt == null)
			return minSizePolygon;
	
		if (polygonSizeLimits == null) {
			polygonSizeLimits = new TreeMap<>();
			String[] desc = polygonSizeLimitsOpt.split("[, \\t\\n]+");

			for (String s : desc) {
				String[] keyVal = s.split("[=:]");
				if (keyVal == null || keyVal.length != 2) {
					throw new ExitException("incorrect polygon-size-limits specification " + polygonSizeLimitsOpt);
				}
	
				try {
					int key = Integer.parseInt(keyVal[0]);
					int value = Integer.parseInt(keyVal[1]);
					Integer testDup = polygonSizeLimits.put(key, value);
					if (testDup != null) {
						throw new ExitException("duplicate resolution value in polygon-size-limits specification "
								+ polygonSizeLimitsOpt);
					}
				} catch (NumberFormatException e) {
					throw new ExitException("polygon-size-limits specification not all numbers: " + s);
				}
			}
			if (polygonSizeLimits.get(24) == null) 
				polygonSizeLimits.put(24, 0);
		}
		// return the value for the desired resolution or the next higher one
		return polygonSizeLimits.ceilingEntry(res).getValue();
	}

	/**
	 * Parse an option with pairs of resolution and double values.
	 * 
	 * @param props        the properties
	 * @param optionName   the option name
	 * @param defaultValue the default value for all resolutions if the option is
	 *                     not given
	 * @return the map
	 */
	private TreeMap<Integer, Double> parseLevelOption(EnhancedProperties props, String optionName,
			double defaultValue) {
		String option = props.getProperty(optionName);
		TreeMap<Integer, Double> levelMap = new TreeMap<>();
		if (option != null) {
			String[] desc = option.split("[, \\t\\n]+");

			for (String s : desc) {
				String[] keyVal = s.split("[=:]");
				if (keyVal == null || keyVal.length != 2) {
					throw new ExitException("incorrect " + optionName + " specification " + option + " at " + s);
				}

				try {
					int key = Integer.parseInt(keyVal[0]);
					double value = Double.parseDouble(keyVal[1]);
					Double testDup = levelMap.put(key, value);
					if (testDup != null) {
						throw new ExitException(
								"duplicate resolution value in " + optionName + " specification " + optionName);
					}
				} catch (NumberFormatException e) {
					throw new ExitException(optionName + " specification not all numbers: " + s);
				}
			}
		}
		if (levelMap.get(24) == null)
			levelMap.put(24, defaultValue);
		return levelMap;
	}

	private static class SourceSubdiv {
		private final MapDataSource source;
		private final Subdivision subdiv;

		SourceSubdiv(MapDataSource ds, Subdivision subdiv) {
			this.source = ds;
			this.subdiv = subdiv;
		}

		public MapDataSource getSource() {
			return source;
		}

		public Subdivision getSubdiv() {
			return subdiv;
		}
	}

	private static class LineAddFilter extends BaseFilter implements MapFilter {
		private final Subdivision div;
		private final Map map;

		LineAddFilter(Subdivision div, Map map) {
			this.div = div;
			this.map = map;
		}

		@Override
		public void doFilter(MapElement element, MapFilterChain next) {
			MapLine line = (MapLine) element;
			assert line.getPoints().size() < 255 : "too many points";

			Polyline pl = div.createLine(line.getLabels());
			if (element.hasExtendedType()) {
				ExtTypeAttributes eta = element.getExtTypeAttributes();
				if (eta != null) {
					eta.processLabels(map.getLblFile());
					pl.setExtTypeAttributes(eta);
				}
			} else {
				div.setPolylineNumber(pl);
			}

			pl.setDirection(line.isDirection());

			pl.addCoords(line.getPoints());

			pl.setType(line.getType());
			if (map.getNetFile() != null && line instanceof MapRoad) {
				if (log.isDebugEnabled())
					log.debug("adding road def: " + line.getName());
				MapRoad road = (MapRoad) line;
				RoadDef roaddef = road.getRoadDef();

				pl.setRoadDef(roaddef);
				if (road.hasSegmentsFollowing())
					pl.setLastSegment(false);

				roaddef.addPolylineRef(pl);
			}
			map.addMapObject(pl);
		}
	}
	
	private static class ShapeAddFilter extends BaseFilter implements MapFilter {
		private final Subdivision div;
		private final Map map;

		ShapeAddFilter(Subdivision div, Map map) {
			this.div = div;
			this.map = map;
		}

		@Override
		public void doFilter(MapElement element, MapFilterChain next) {
			MapShape shape = (MapShape) element;
			assert shape.getPoints().size() < 255 : "too many points";

			Polygon pg = div.createPolygon(shape.getName());

			pg.addCoords(shape.getPoints());

			pg.setType(shape.getType());
			if (element.hasExtendedType()) {
				ExtTypeAttributes eta = element.getExtTypeAttributes();
				if (eta != null) {
					eta.processLabels(map.getLblFile());
					pg.setExtTypeAttributes(eta);
				}
			}
			map.addMapObject(pg);
		}
	}

	/**
	 * Find out shapes visible in the overview map which were created from a single multipolygon.
	 * Typically this is the sea polygon. Create a simplified version for each. 
	 * @param src the map source
	 * @param levels levels for the overview map
	 */
	private void recalcMultipolygons(LoadableMapDataSource src, LevelInfo[] levels) {
		final int maxRes = levels[levels.length - 1].getBits();
		java.util.Map<MultiPolygonRelation, List<MapShape>> mpShapes = new LinkedHashMap<>();
		src.getShapes().stream().filter(s -> s.getMpRel() != null && s.getMinResolution() <= maxRes)
				.forEach(s -> mpShapes.computeIfAbsent(s.getMpRel(), k -> new ArrayList<>()).add(s));
		if (mpShapes.isEmpty())
			return;
		MapShapeComparator comparator = new MapShapeComparator(orderByDecreasingArea);
		for (Entry<MultiPolygonRelation, List<MapShape>> e : mpShapes.entrySet()) {
			if (e.getKey().isNoRecalc())
				continue;
			MapShape pattern = e.getValue().get(0);
			boolean matches = true;
			for (MapShape s : e.getValue()) {
				if (s.getMinResolution() != pattern.getMinResolution()
						|| s.getMaxResolution() != pattern.getMaxResolution() 
						|| comparator.compare(s, pattern) != 0) {
					matches = false;
					break;
				}
			}
			if (matches) {
				buildMPRing(src, maxRes, pattern, e.getKey());
				e.getValue().forEach(s -> s.setMinResolution(maxRes + 1));
			}
		}
	}

	/**
	 * Re-Render the multipolygon for the given max. resolution and create new
	 * shapes with this value as maxResolution.
	 * 
	 * @param src     the map source
	 * @param res     the wanted maximum resolution
	 * @param pattern pattern for the new shapes
	 * @param origMp  the multipolygon relation that contains the rings at full
	 *                resolution
	 */
	private void buildMPRing(LoadableMapDataSource src, int res, MapShape pattern, MultiPolygonRelation origMp) {
		List<? extends Way> rings = origMp.getRings();
		Way largest = origMp.getLargestOuterRing();
		int shift = 24 - res;
		int minSize = getMinSizePolygonForResolution(res) * (1 << shift) / 2;
		GeneralRelation gr = new GeneralRelation(FakeIdGenerator.makeFakeId());
		java.util.Map<Long, Way> wayMap = new LinkedHashMap<>();

		final double dpError = dpFilterShapeResMap.ceilingEntry(res).getValue() * (1 << shift);
		for (int i = 0; i < rings.size(); i++) {
			List<Coord> poly = new ArrayList<>(rings.get(i).getPoints());
			boolean isLargest = largest == rings.get(i);
			boolean tooSmall = minSize > 0 && Area.getBBox(poly).getMaxDimension() < minSize;
			if (isLargest && tooSmall && !pattern.isSkipSizeFilter())
				return;
			if (tooSmall)
				continue;
			if (dpError > 0 && !isLargest) {
				DouglasPeuckerFilter.douglasPeucker(poly, 0, poly.size() - 1, dpError);
			}
			if (poly.size() > 3) {
				Way w = new Way(FakeIdGenerator.makeFakeId(), poly);
				wayMap.put(w.getId(), w);
				gr.addElement("", w);
			}
		}
		List<List<Coord>> list = new ArrayList<>();
		if (gr.getElements().isEmpty()) {
			return;
		} else if (gr.getElements().size() == 1) {
			list.addAll(ShapeSplitter.clipToBounds(largest.getPoints(), src.getBounds(), null));
		} else {
			final String codeValue = GType.formatType(pattern.getType());
			gr.addTag("code", codeValue);
			gr.addTag("expect-self-intersection", "true");
			MultiPolygonRelation mp = new MultiPolygonRelation(gr, wayMap, src.getBounds());
			mp.processElements();
			for (Way w : wayMap.values()) {
				if (MultiPolygonRelation.STYLE_FILTER_POLYGON.equals(w.getTag(MultiPolygonRelation.STYLE_FILTER_TAG))
						&& codeValue.equals(w.getTag("code"))) {
					if (src.getBounds().contains(Area.getBBox(w.getPoints()))) {
						list.add(w.getPoints());
					} else {
						// we must clip to tile bounds
						list.addAll(ShapeSplitter.clipToBounds(w.getPoints(), src.getBounds(), null));
					}
				}
			}
		}

		for (int i = 0; i < list.size(); i++) {
			List<Coord> poly = list.get(i);
			MapShape newShape = pattern.copy();

			newShape.setPoints(poly);
			newShape.setOsmid(FakeIdGenerator.makeFakeId());
			newShape.setMaxResolution(res);
			newShape.setMpRel(null);
			src.getShapes().add(newShape);
		}
	}
}
