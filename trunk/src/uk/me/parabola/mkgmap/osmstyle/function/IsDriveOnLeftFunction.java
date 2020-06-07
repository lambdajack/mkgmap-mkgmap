package uk.me.parabola.mkgmap.osmstyle.function;

import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.build.LocatorConfig;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.TagDict;

/**
 * Returns the drive-on-left information for an element based on the information
 * stored in tag mkgmap:country and the LocatorConfig.xml. Returns "true" if
 * mkgmap:country is set and contains an iso code that has the driveOnLeft attribute in the LocatorConfig.xml. 
 */
public class IsDriveOnLeftFunction extends CachedFunction {
	private static final Logger log = Logger.getLogger(IsDriveOnLeftFunction.class);
	private static final short TK_M_ADM_LVL2 = TagDict.getInstance().xlate("mkgmap:admin_level2");
	private static final short TK_M_COUNTRY = TagDict.getInstance().xlate("mkgmap:country");

	public IsDriveOnLeftFunction() {
		super(null);
	}

	protected String calcImpl(Element el) {
		String iso = el.getTag(TK_M_ADM_LVL2);
		if (iso == null) 
			iso = el.getTag(TK_M_COUNTRY);
		if (iso == null && log.isInfoEnabled()) {
			log.info(getName(), el.getBasicLogInformation(), "Neither mkgmap:admin_level2 nor mkgmap:country is set, assuming this element is not in a drive-on-left country");
		}
		return Boolean.toString(LocatorConfig.get().getDriveOnLeftFlag(iso));
	}

	@Override
	public String getName() {
		return "is_drive_on_left";
	}
	
	@Override
	public boolean supportsNode() {
		return true;
	}
	
	@Override
	public boolean supportsWay() {
		return true;
	}

}
