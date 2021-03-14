package uk.me.parabola.mkgmap;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.app.srt.Sort;
import uk.me.parabola.util.EnhancedProperties;

public class CommandArgs {
	public static final int DEFAULT_FAMILYID = 6324;
	public static final int DEFAULT_PRODUCTID = 1;

	private final EnhancedProperties currentOptions;
	private Sort sort;

	public CommandArgs(EnhancedProperties args) {
		currentOptions = new EnhancedProperties(args);
		// verify options which expect a comma-separated list
		for (String listOpt : Arrays.asList("mdr7-excl", "mdr7-del", "poi-excl-index", "location-autofill",
				"overview-levels", "levels", "name-tag-list", "polygon-size-limits", "dem", "dem-dists", "drive-on",
				"dead-ends", "add-pois-to-lines", "coastlinefile", "generate-sea", "nearby-poi-rules")) {
			stringToList(get(listOpt,  null), listOpt);
		}
	}

	public EnhancedProperties getProperties() {
		return currentOptions;
	}

	public int get(String name, int def) {
		return currentOptions.getProperty(name, def);
	}

	public String get(String name, String def) {
		return currentOptions.getProperty(name, def);
	}

	public boolean get(String name, boolean def) {
		return currentOptions.getProperty(name, def);
	}

	public String getDescription() {
		return currentOptions.getProperty("description");
	}

	// ////
	// There are a number of methods to get specific arguments that follow.
	// There are many more options in use however.  New code should mostly
	// just use the get methods above.
	// ////

	public int getBlockSize() {
		return get("block-size", 512);
	}

	public String getMapname() {
		return currentOptions.getProperty("mapname");
	}

	public String getCharset() {

		String charset = currentOptions.getProperty("charset");
		if (charset != null)
			return charset;

		int cp = getCodePage();
		if (cp != 0)
			return "cp" + cp;

		return "ascii";
	}

	public int getCodePage() {
		int cp;

		String s = currentOptions.getProperty("code-page");
		try {
			cp = Integer.parseInt(s);
		} catch (NumberFormatException e) {
			cp = 0;
		}

		return cp;
	}

	public String getOutputDir() {
		final String DEFAULT_DIR = ".";
		String fileOutputDir = currentOptions.getProperty("output-dir", DEFAULT_DIR);
 
		// Test if directory exists
		File outputDir = new File(fileOutputDir);
		if (!outputDir.exists()) {
			System.out.println("Output directory not found. Creating directory '" + fileOutputDir + "'");
			outputDir.mkdirs();
			if (!outputDir.exists()) {
				System.err.println("Unable to create output directory! Using default directory instead");
				fileOutputDir = DEFAULT_DIR;
			}
		} else if (!outputDir.isDirectory()) {
			System.err.println("The --output-dir parameter must specify a directory. The parameter is being ignored, writing to default directory instead.");
			fileOutputDir = DEFAULT_DIR;
		}
		
		return fileOutputDir;
	}

	public Sort getSort() {
		assert sort != null;
		return sort;
	}

	public void setSort(Sort sort) {
		this.sort = sort;
	}

	public boolean isForceUpper() {
		return currentOptions.getProperty("lower-case") == null;
	}

	/**
	 * Test for the existence of an argument.
	 */
	public boolean exists(String name) {
		return currentOptions.containsKey(name);
	}
	
	public static List<String> getNameTags(Properties props) {
		String s = props.getProperty("name-tag-list", "name");
		return stringToList(s, "name-tag-list");
	}
	
	public Set<String> argToSet(String name, String def) {
		String optVal = currentOptions.getProperty(name, def);
		return stringToSet(optVal, name);
	}
	
	public List<String> argToList(String name, String def) {
		String optVal = currentOptions.getProperty(name, def);
		return stringToList(optVal, name);
	}
	
	public static Set<String> stringToSet(String opt, String optName) {
		List<String> list = stringToList(opt, optName);
		if (list.isEmpty())
			return Collections.emptySet();
		TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		set.addAll(list);
		return set;
	}

	private static final Pattern COMMA_OR_SPACE_PATTERN = Pattern.compile("[,\\s]+");

	public static List<String> stringToList(String opt, String optName) {
		if (opt == null)
			return Collections.emptyList();
		if (opt.startsWith("'") || opt.startsWith("\""))
			opt = opt.substring(1);
		if (opt.endsWith("'") || opt.endsWith("\""))
			opt = opt.substring(0, opt.length() - 1);
		if (opt.endsWith(",")) {
			throw new ExitException("Option " + optName + " ends in a comma and hence has a missing value");
		}
		return Arrays.asList(COMMA_OR_SPACE_PATTERN.split(opt));
	}
	
}
