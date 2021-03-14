/*
 * Copyright (C) 2017.
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
package uk.me.parabola.mkgmap.osmstyle;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import uk.me.parabola.imgfmt.app.mdr.Mdr7;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.scan.TokType;
import uk.me.parabola.mkgmap.scan.Token;
import uk.me.parabola.mkgmap.scan.TokenScanner;
import uk.me.parabola.util.EnhancedProperties;

/**
 * Code to add special Garmin separators 0x1b, 0x1e and 0x1f. 
 * The separator 0x1e tells Garmin that the part of the name before that separator
 * should not be displayed when zooming out enough. It is displayed like a blank. 
 * The separator 0x1f tells Garmin that the part of the name after that separator 
 * should not be displayed when zooming out enough. It is displayed like a blank. 
 * The separator 0x1b works like 0x1e, but is not displayed at all.
 * The separator 0x1c works like 0x1f, but is not displayed at all.
 * See also class {@link Mdr7}. 
 * 
 * @author Gerd Petermann
 *
 */
public class PrefixSuffixFilter {
	private static final Logger log = Logger.getLogger(PrefixSuffixFilter.class);

	private static final int MODE_PREFIX = 0;
	private static final int MODE_SUFFIX = 1;
	
	private boolean enabled;
	private final Set<String> languages = new LinkedHashSet<>();
	private final Map<String, List<String>> langPrefixMap = new HashMap<>();
	private final Map<String, List<String>> langSuffixMap = new HashMap<>();
	private final Map<String, List<String>> countryLanguageMap = new HashMap<>();
	private final Map<String, List<String>> countryPrefixMap = new HashMap<>();
	private final Map<String, List<String>> countrySuffixMap = new HashMap<>();

	private EnhancedProperties options = new EnhancedProperties();

	public PrefixSuffixFilter(EnhancedProperties props) {
		String cfgFile = props.getProperty("road-name-config",null);
		enabled = readConfig(cfgFile);
	}

	/**
	 * Read the configuration file for this filter.
	 * @param cfgFile path to file
	 * @return true if filter can be used, else false.
	 */
	private boolean readConfig(String cfgFile) {
		if (cfgFile == null) 
			return false;
		try (InputStreamReader reader = new InputStreamReader(new FileInputStream(cfgFile), StandardCharsets.UTF_8)) {
			readOptionFile(reader, cfgFile);
			return true;
		} catch (Exception e) {
			log.error(e.getMessage());
			log.error(this.getClass().getSimpleName() + " disabled, failed to read config file " + cfgFile);
			return false;
		}
	}
	
	/**
	 * 
	 * @param r
	 * @param filename
	 */
	private void readOptionFile(Reader r, String filename) {
		BufferedReader br = new BufferedReader(r);
		TokenScanner ts = new TokenScanner(filename, br);
		ts.setExtraWordChars(":");

		while (!ts.isEndOfFile()) {
			Token tok = ts.nextToken();
			if (tok.isValue("#")) {
				ts.skipLine();
				continue;
			}

			String key = tok.getValue();

			ts.skipSpace();
			tok = ts.peekToken();
			
			if (tok.getType() == TokType.SYMBOL) {

				switch (ts.nextValue()) {
				case ":":
				case "=":
					processOption(key, ts.readLine());
					break;
				default:
					ts.skipLine();
				}

			} else if (key != null){
				throw new IllegalArgumentException("don't understand line with " + key );
			} else {
				ts.skipLine();
			}
		}
		
		/**
		 * process lines starting with prefix1 or prefix2. 
		 */
		for (String lang : languages) {
			String prefix1 = options.getProperty("prefix1:" + lang, null);
			if (prefix1 == null)
				continue;
			String prefix2 = options.getProperty("prefix2:" + lang, null);
			List<String> p1 = Arrays.asList(prefix1.split(","));
			List<String> p2 = prefix2 != null ? Arrays.asList(prefix2.split(",")) : Collections.emptyList();
			langPrefixMap.put(lang, genPrefix(p1, p2));
		}
	}

	private void processOption(String key, String val) {
		String[] keysParts = key.split(":");
		String[] valParts = val.split(",");
		if (keysParts.length < 2 || val.isEmpty() || valParts.length < 1) {
			throw new IllegalArgumentException("don't understand " + key + " = " + val);
		}
		switch (keysParts[0].trim()) {
		case "prefix1":
		case "prefix2":
			options.put(key, val); // store for later processing
			break;
		case "suffix":
			List<String> suffixes = new ArrayList<>();
			for (String s : valParts) {
				suffixes.add(stripBlanksAndQuotes(s));
			}
			sortByLength(suffixes);
			langSuffixMap.put(keysParts[1].trim(), suffixes);
			break;
		case "lang":
			String iso = keysParts[1].trim();
			List<String> langs = new ArrayList<>();
			for (String lang : valParts) {
				langs.add(lang.trim());
			}
			countryLanguageMap .put(iso, langs);
			languages.addAll(langs);
			break;
		}
	}
	

	/** Create all combinations of items in prefix1 with items in prefix2 and finally prefix1 with an extra blank.  
	 * @param prefix1 list of prefix words
	 * @param prefix2 list of prepositions
	 * @return all combinations
	 */
	private static List<String> genPrefix (List<String> prefix1, List<String> prefix2) {
		List<String> prefixes = new ArrayList<>();
		for (String p1 : prefix1) {
			p1 = stripBlanksAndQuotes(p1);
			for (String p2 : prefix2) {
				p2 = stripBlanksAndQuotes(p2);
				prefixes.add(p1 + " " + p2);
			}
			prefixes.add(p1 + " ");
		}
		sortByLength(prefixes);
		return prefixes;
	}

	/**
	 * First remove leading and trailing blanks, next check for paired quotes
	 * @param s the string 
	 * @return the modified string
	 */
	private static String stripBlanksAndQuotes(String s) {
		s = s.trim();
		if (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\"")) {
			return s.substring(1, s.length()-1);
		}
		return s;
	}
	
	
	/**
	 * Modify all labels of a road. Each label is checked against country specific lists of 
	 * well known prefixes (e.g. "Rue de la ", "Avenue des "  ) and suffixes (e.g. " Road").
	 * If a well known prefix is found the label is modified. If the prefix ends with a blank,
	 * that blank is replaced by 0x1e, else 0x1b is added after the prefix.
	 * If a well known suffix is found the label is modified. If the suffix starts with a blank,
	 * that blank is replaced by 0x1f, else 0x1c is added before the suffix.
	 * @param road
	 */
	public void filter(MapRoad road) {
		if (!enabled)
			return;
		String country = road.getCountry();
		if (country == null)
			return;
		
		final List<String> prefixesCountry = getSearchStrings(country, MODE_PREFIX);
		final List<String> suffixesCountry = getSearchStrings(country, MODE_SUFFIX);
		
		String[] labels = road.getLabels();
		for (int i = 0; i < labels.length; i++) {
			String label = labels[i];
			if (label == null || label.isEmpty())
				continue;
			label = applyPrefixes(label, prefixesCountry);
			label = applySuffixes(label, suffixesCountry);
			if (!label.equals(labels[i])) {
				labels[i] = label;
				log.debug("modified", label, country, road.getRoadDef());
			}
		}
	}
	
	static String applyPrefixes(String label, List<String> prefixesCountry) {
		if (label.charAt(0) < 7)
			return label; // label starts with shield code
		// perform brute force search, seems to be fast enough
		for (String prefix : prefixesCountry) {
			if (label.length() >= prefix.length() && prefix.equalsIgnoreCase(label.substring(0, prefix.length()))) {
				if (prefix.endsWith(" ")) {
					return prefix.substring(0, prefix.length() - 1) + (char) 0x1e + label.substring(prefix.length());
				}
				return prefix + (char) 0x1b + label.substring(prefix.length());
			}
		}
		return label;
	}

	private static String applySuffixes(String label, List<String> suffixesCountry) {
		// perform brute force search, seems to be fast enough
		for (String suffix : suffixesCountry) {
			int len = label.length();
			int pos = len - suffix.length();
			if (pos >= 0 && suffix.equalsIgnoreCase(label.substring(pos, len))) {
				if (suffix.startsWith(" ")) {
					return label.substring(0, pos) + (char) 0x1f + suffix.substring(1);
				}
				return label.substring(0, pos) + (char) 0x1c + suffix;
			}
		}
		return label;
	}

	/**
	 * Build list of prefixes or suffixes for a given country.
	 * @param country String with 3 letter ISO code
	 * @param mode : signals prefix or suffix
	 * @return List with prefixes or suffixes
	 */
	private List<String> getSearchStrings(String country, int mode) {
		Map<String, List<String>>  cache = (mode == MODE_PREFIX) ? countryPrefixMap : countrySuffixMap;
		return cache.computeIfAbsent(country, k-> {
			// compile the list 
			List<String> languageList = countryLanguageMap.get(country);
			if (languageList == null)
				return Collections.emptyList();
			final Map<String, List<String>> map = mode == MODE_PREFIX ? langPrefixMap : langSuffixMap;
			List<String> res = languageList.stream()
					.map(lang -> map.getOrDefault(lang, Collections.emptyList()))
					.flatMap(List::stream)
					.distinct()
					.collect(Collectors.toList());
			if (res.isEmpty())
				return Collections.emptyList();
			sortByLength(res);
			return res;
		});
	}

	/**
	 * Sort by string length so that longest string comes first.
	 * @param strings
	 */
	private static void sortByLength(List<String> strings) {
		strings.sort((o1, o2) -> Integer.compare(o2.length(), o1.length()));
	}
}
