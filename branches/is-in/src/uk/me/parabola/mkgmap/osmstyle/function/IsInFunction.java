package uk.me.parabola.mkgmap.osmstyle.function;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Way;

public class IsInFunction extends StyleFunction {

	private Map<Long, Way> wayMap;

	public IsInFunction() {
		super(null);
		reqdNumParams = 3;  // ??? maybe have something to indicate variable...
		// maybe param:
		// 1: polygon tagName
		// 2: value for above tag
		// 3: type of accuracy, various keywords
	}

	protected String calcImpl(Element el) {
		boolean answer = false;
		if (wayMap != null) {
			/**
			 * TODO: calculate bbox of element, 
			 * retrieve closed ways which match the tag given with parameters
			 * use spatial index if that improves performance
			 * calculate insideness  
			 */
			
			// this is just some test code to give different answers...
			if (el instanceof Node) {
				answer = params.get(0).equals("polyTag");
			} else if (el instanceof Way) {
				// %%% similar, but for lines/areas
				answer = params.get(2).equals("accuracy");
			}
		}
		return String.valueOf(answer);
	}

	@Override
	public String value(Element el) {
		return calcImpl(el);
	}
    
	@Override
	public String getName() {
		return "is_in";
	}
	
	@Override
	public boolean supportsNode() {
		return true;
	}

	@Override
	public boolean supportsWay() {
		return true;
	}

	@Override
	public Set<String> getUsedTags() {
		return Collections.singleton(params.get(0));
	}

	@Override
	public String toString() {
		// TODO: check what is needed for class ExpressionArranger and RuleSet.compile()
		return super.toString() + params;
	}
	
	@Override
	public void augmentWith(uk.me.parabola.mkgmap.reader.osm.ElementSaver elementSaver) {
		wayMap = elementSaver.getWays();
	}

}
