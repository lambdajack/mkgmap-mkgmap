package uk.me.parabola.imgfmt.app.net;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.util.EnhancedProperties;

public class RoundaboutCheck {
	private final Logger log = Logger.getLogger(RoundaboutCheck.class);
	private boolean checkRoundaboutLoops = false;
	private boolean checkRoundaboutDirections = false;
	private boolean checkRoundaboutOverlaps = false;
	private boolean checkRoundaboutJunctions = false;
	private boolean checkRoundaboutFlares = false;
	private boolean checkRoundabouts = false;
	private int maxFlareLength = 200;
	private int maxFlareLengthMultiple = 3;
	private int flareSeparationOverride = 5;
	private int maxFlareToSeparationMultiple = 7;
	private int maxFlareAngle = 90;
	private int maxFlareBearing = 90;
	private int maxEntryAngle = 145;
	private boolean discardBothFlaresExtend = true;
	private boolean discardMultipleFlares = true;
	private boolean discardDifferentFlareNames = true;
	private boolean discardDifferentAccesses = true;

	private RouteNode nodeToCheck;
	private Coord coord;

	private List<RouteNode> roundaboutNodes = new ArrayList<>();
	
	/**
	 * Show valid report-roundabout-issues options
	 * @param option either "help" or an option that was not recognised
	 */
	private static void printOptionHelpMsg(String option) {
		if(!"help".equals(option))
			System.err.println("Unknown report-roundabout-issues option '" + option + "'");
		
		System.err.println("Report roundabout issues options are:");
		System.err.println("  all         apply all the checks");
		System.err.println("  loop        check that each roundabouts is formed from a single loop with no");
		System.err.println("              forks or gaps");
		System.err.println("  direction   check the direction of travel around the roundabout");
		System.err.println("  overlaps    check that highways do not overlap the roundabout");
		System.err.println("  junctions   check that no more than one connecting highway joins at each node");
		System.err.println("  flares      check that roundabout flare roads are one-way, are in the right");
		System.err.println("              direction, and don't extend beyond the flare");
	}

	/**
	 * parse the roundabout checking options
	 */
	public void config(EnhancedProperties props) {
		if (props.getProperty("check-roundabouts", false)) {
			checkRoundaboutLoops = RouteNode.isWarningLogged();
			checkRoundaboutDirections = checkRoundaboutLoops;
			checkRoundaboutJunctions = checkRoundaboutLoops;
			log.error("The check-roundabouts option is deprecated - use report-roundabout-issues.");
		}
		if (props.getProperty("check-roundabout-flares", false)) {
			checkRoundaboutFlares = RouteNode.isWarningLogged();
			log.error("The check-roundabout-flares option is deprecated - use report-roundabout-issues.");
		}
		String mflr = props.getProperty("max-flare-length-ratio");
		if (mflr != null) {
			maxFlareToSeparationMultiple = props.getProperty("max-flare-length-ratio", maxFlareToSeparationMultiple);
			log.error("The max-flare-length-ratio option is deprecated - use roundabout-flare-rules-config.");
		}
		String checkRoundaboutsOption = props.getProperty("report-roundabout-issues");
		if (checkRoundaboutsOption != null) {
			if ("".equals(checkRoundaboutsOption)) {
				checkRoundaboutLoops = true;
				checkRoundaboutDirections = true;
				checkRoundaboutOverlaps = true;
				checkRoundaboutJunctions = true;
				checkRoundaboutFlares = true;
			}
			for (String option : checkRoundaboutsOption.split(",")) {
				if ("all".equals(option)) {
					checkRoundaboutLoops = true;
					checkRoundaboutDirections = true;
					checkRoundaboutOverlaps = true;
					checkRoundaboutJunctions = true;
					checkRoundaboutFlares = true;
				} else if ("loop".equals(option)) {
					checkRoundaboutLoops = true;
				} else if ("direction".equals(option)) {
					checkRoundaboutDirections = true;
				} else if ("overlaps".equals(option)) {
					checkRoundaboutOverlaps = true;
				} else if ("junctions".equals(option)) {
					checkRoundaboutJunctions = true;
				} else if ("flares".equals(option)) {
					checkRoundaboutFlares = true;
				} else if (option.isEmpty()) {
					// nothing to do
				} else {
					printOptionHelpMsg(option);
				}
			}
		}
		checkRoundabouts = checkRoundaboutLoops /* || checkRoundaboutDirections */ || checkRoundaboutOverlaps || checkRoundaboutJunctions || checkRoundaboutFlares;
		String configFilename = props.getProperty("roundabout-flare-rules-config", "");
		if (!configFilename.isEmpty()) {
			File file = new File(configFilename);
			try {
				List<String> rules = Files.readAllLines(file.toPath());
				for (int i = 0; i < rules.size(); i++) {
					String rule = rules.get(i);
					int hashPos = rule.indexOf('#');
					if (hashPos >= 0)
						rule = rule.substring(0, hashPos);
					rule = rule.replaceAll("\\s+", "");
					if (!rule.isEmpty()) {
						String[] ruleParts = rule.split(":");
						if (ruleParts.length == 2) {
							try {
								switch (ruleParts[0]) {
								case "max-flare-length":
									maxFlareLength = Integer.decode(ruleParts[1]);
									break;

								case "max-flare-length-multiple":
									maxFlareLengthMultiple = Integer.decode(ruleParts[1]);
									break;

								case "flare-separation-override":
									flareSeparationOverride = Integer.decode(ruleParts[1]);
									break;

								case "max-flare-to-separation-multiple":
									maxFlareToSeparationMultiple = Integer.decode(ruleParts[1]);
									break;

								case "max-flare-angle":
									maxFlareAngle = Integer.decode(ruleParts[1]);
									break;

								case "max-flare-bearing":
									maxFlareBearing = Integer.decode(ruleParts[1]);
									break;

								case "max-entry-angle":
									maxEntryAngle = Integer.decode(ruleParts[1]);
									break;

								case "discard-both-flares-extend":
									discardBothFlaresExtend = Boolean.getBoolean(ruleParts[1]);
									break;

								case "discard-multiple-flares":
									discardMultipleFlares = Boolean.getBoolean(ruleParts[1]);
									break;

								case "discard-different-flare-names":
									discardDifferentFlareNames = Boolean.getBoolean(ruleParts[1]);
									break;

								case "discard-different-accesses":
									discardDifferentAccesses = Boolean.getBoolean(ruleParts[1]);
									break;

								default:
									log.error("Unrecognised roundabout flare rule", ruleParts[0], "in", configFilename);
								}
							}
							catch (Exception ex) {
								log.error("Invalid value", ruleParts[1], "for roundabout flare rule", ruleParts[0], "in", configFilename);
							}
						}
						else {
							log.error("Invalid roundabout flare rule", rule, "in", configFilename);
						}
					}
				}
			} catch (IOException ex) {
				log.error("Error reading roundabout flare rules file", configFilename);
			}
		}
	}
	
	public void check(RouteNode routeNode) {
		if (checkRoundabouts) {
			nodeToCheck = routeNode;
			coord = nodeToCheck.getCoord();
			roundaboutNodes.clear();
			List<RouteArc> roundaboutArcs = new ArrayList<>();
			List<RouteArc> nonRoundaboutArcs = new ArrayList<>();
			for(RouteArc ra : nodeToCheck.getArcs()) {
				if (ra.isDirect()) {
					// ignore ways that have been synthesised by mkgmap
					RoadDef rd = ra.getRoadDef();
					if (!rd.isSynthesised()) {
						if (rd.isRoundabout())
							roundaboutArcs.add(ra);
						else
							nonRoundaboutArcs.add(ra);
					}
				}
			}
			if (!roundaboutArcs.isEmpty()) {
				// get the nodes at the roundabout junctions
				if(log.isDebugEnabled())
					log.debug("Roundabout node", coord.toOSMURL());
				roundaboutNodes.add(nodeToCheck);
				boolean roundaboutComplete = true;
				for (RouteArc ra : roundaboutArcs) {
					if (ra.isForward()) {
						for (RouteArc ra1 : nonRoundaboutArcs) {
							if ((ra1.getDirectHeading() == ra.getDirectHeading()) && (ra1.getInitialHeading() == ra.getInitialHeading()) && (ra1.getFinalHeading() == ra.getFinalHeading()) && (ra1.getLengthInMeter() == ra.getLengthInMeter())) {
								// non roundabout highway overlaps roundabout
								nonRoundaboutArcs.remove(ra1);
								if(checkRoundaboutOverlaps && !ra.getRoadDef().messagePreviouslyIssued("overlaps roundabout"))
									log.diagnostic("Highway " + ra1.getRoadDef() + " overlaps roundabout " + ra.getRoadDef() + " at " + coord.toOSMURL());
								break;
							}						
						}
						RouteNode rn = ra.getDest();
						while (!roundaboutNodes.contains(rn)) {
							roundaboutNodes.add(rn);
							if(log.isDebugEnabled())
								log.debug("Roundabout node", rn.getCoord().toOSMURL());
							RouteNode nrn = null;
							for (RouteArc nra : rn.getArcs()) {
								if (nra.isDirect() && nra.isForward()) {
									RoadDef nrd = nra.getRoadDef();
									if (nrd.isRoundabout() && !nrd.isSynthesised())
										nrn = nra.getDest();
								}
							}
							rn = nrn;
							if (rn == null) {
								roundaboutComplete = false;
								log.info("Incomplete roundabout", coord.toOSMURL());
								break;
							}
						}
					}
				}
				Coord roundaboutCentre = getRoundaboutCentre();
	
				if (nonRoundaboutArcs.size() > 1) {
					int countNonRoundaboutRoads = 0;
					int countNonRoundaboutOtherHighways = 0;
					int countHighwaysInsideRoundabout = 0;
					for (RouteArc ra : nonRoundaboutArcs) {
						RoadDef rd = ra.getRoadDef();
						// ignore footpaths and ways with no access
						byte access = rd.getAccess();
						if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
							if (roundaboutComplete && goesInsideRoundabout(ra, nodeToCheck, roundaboutCentre)) {
								countHighwaysInsideRoundabout++;
								//skip flare check for all ways meeting this node
								for (RouteArc ra2 : nonRoundaboutArcs)
									ra2.getRoadDef().doFlareCheck(false);
							}
							else {
								if ((access & AccessTagsAndBits.CAR) != 0)
									countNonRoundaboutRoads++;
								else if ((access & (AccessTagsAndBits.BIKE | AccessTagsAndBits.BUS | AccessTagsAndBits.TAXI | AccessTagsAndBits.TRUCK)) != 0)
									countNonRoundaboutOtherHighways++;
							}
						}
						else
							rd.doFlareCheck(false);
					}
	
					RouteArc roundaboutArc = roundaboutArcs.get(0);
					if (checkRoundaboutLoops) {
						if (nodeToCheck.getArcs().size() > 1 && roundaboutArcs.size() == 1)
							log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + (roundaboutArc.isForward() ? " starts at " : " ends at ") + coord.toOSMURL());
						if(roundaboutArcs.size() > 2) {
							for(RouteArc fa : roundaboutArcs) {
								if(fa.isForward()) {
									RoadDef rd = fa.getRoadDef();
									for(RouteArc fb : roundaboutArcs) {
										if(fb != fa) { 
											if(fa.getPointsHash() == fb.getPointsHash() &&
											   ((fb.isForward() && fb.getDest() == fa.getDest()) ||
												(!fb.isForward() && fb.getSource() == fa.getDest()))) {
												if(!rd.messagePreviouslyIssued("roundabout forks/overlaps")) {
													log.diagnostic("Roundabout " + rd + " overlaps " + fb.getRoadDef() + " at " + coord.toOSMURL());
												}
											}
											else if (fb.isForward()
													&& !rd.messagePreviouslyIssued("roundabout forks/overlaps")) {
												log.diagnostic("Roundabout " + rd + " forks at " + coord.toOSMURL());
											}
										}
									}
								}
							}
						}
					}

					if (checkRoundaboutJunctions) {
						if (countNonRoundaboutRoads > 1) {
							if (!nodeToCheck.getRestrictions().isEmpty())
								log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " with restriction is connected to more than one road at " + coord.toOSMURL());
							else
								log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " is connected to more than one road at " + coord.toOSMURL());
						} else if (countNonRoundaboutRoads == 1) {
							if (countNonRoundaboutOtherHighways > 0) {
								if (countHighwaysInsideRoundabout > 0)
									log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " is connected to a road, " + countNonRoundaboutOtherHighways + " other highway(s) and " + countHighwaysInsideRoundabout + " highways inside the roundabout at " + coord.toOSMURL());
								else
									log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " is connected to a road and " + countNonRoundaboutOtherHighways + " other highway(s) at " + coord.toOSMURL());
							}
							else if (countHighwaysInsideRoundabout > 0)
								log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " is connected to a road and " + countHighwaysInsideRoundabout + " highway(s) inside the roundabout at " + coord.toOSMURL());
						} else if (countNonRoundaboutOtherHighways > 0) {
							if (countHighwaysInsideRoundabout > 0)
								log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " is connected to " + countNonRoundaboutOtherHighways+ " highway(s) and " + countHighwaysInsideRoundabout + " inside the roundabout at " + coord.toOSMURL());
							else if (countNonRoundaboutOtherHighways > 1)
								log.diagnostic("Roundabout " + roundaboutArc.getRoadDef() + " is connected to " + countNonRoundaboutOtherHighways + " highways at " + coord.toOSMURL());
						} else if (countHighwaysInsideRoundabout > 1)
							log.diagnostic("Roundabout " +roundaboutArc.getRoadDef() + " is connected to " + countHighwaysInsideRoundabout + " highways inside the roundabout at " + coord.toOSMURL());
					}
				}
				if (roundaboutComplete && checkRoundaboutFlares)
					checkRoundaboutFlares(roundaboutCentre);
			}
		}
	}

	// check roundabout flare roads - the flare roads connect a
	// two-way road to a roundabout using short one-way segments so
	// the resulting sub-junction looks like a triangle with two
	// corners of the triangle being attached to the roundabout and
	// the last corner being connected to the two-way road

	public void checkRoundaboutFlares(Coord centre) {
		for(RouteArc r : nodeToCheck.getArcs()) {
			// see if node has a forward arc that is part of a roundabout
			if(!r.isForward() || !r.isDirect() || !r.getRoadDef().isRoundabout() || r.getRoadDef().isSynthesised())
				continue;

			// check whether the roundabout has any roads going inside.
			// if so, disable flare checking for roads at those nodes
			RouteNode nextExitNode = null;
			RouteNode nextButOneExitNode = null;
			RouteNode precedingExitNode = null;
			for (RouteNode rn : roundaboutNodes) {
				if (rn == nodeToCheck)
					continue;
				
				for (RouteArc ra : rn.getArcs()) {
					if (!ra.isDirect())
						continue;
					
					RoadDef rd = ra.getRoadDef();
					if (!rd.isSynthesised() && !rd.isRoundabout()) {
						byte access = rd.getAccess();
						if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
							if (goesInsideRoundabout(ra, rn, centre)) {
								for (RouteArc ra2 : rn.getArcs())
									ra2.getRoadDef().doFlareCheck(false);
							}
							if (nextExitNode == null)
								nextExitNode = rn;
							else {
								if (nextButOneExitNode == null)
									nextButOneExitNode = rn;
								precedingExitNode = rn;
							}
						}
						else
							rd.doFlareCheck(false);							
					}
				}
			}
			
			if(nextExitNode == null) // something is not right so give up
				continue;		

			// now try and find the two arcs that make up the
			// triangular flare connected to both ends of the
			// roundabout segment
			for(RouteArc fa : nodeToCheck.getArcs()) {
				if(!fa.isDirect())
					continue;

				RoadDef rda = fa.getRoadDef();
				if (rda.isSynthesised() || !rda.doFlareCheck())
					continue;

				for(RouteArc fb : nextExitNode.getArcs()) {
					if(!fb.isDirect() || (fa == fb))
						continue;

					RoadDef rdb = fb.getRoadDef();
					if(rdb.isSynthesised() || !rdb.doFlareCheck())
						continue;

					if(fa.getDest() == fb.getDest()) {
						// found the 3rd point of the triangle that
						// should be connecting the two flare roads

						// first, special test required to cope with
						// roundabouts that have a single flare and no
						// other connections - only check the flare
						// for the shorter of the two roundabout
						// segments

						if(roundaboutSegmentLength(nodeToCheck, nextExitNode) >=
						   roundaboutSegmentLength(nextExitNode, nodeToCheck))
							continue;
						
						// check that that the flare has a road connected to it
						boolean hasRoad = false;
						for (RouteArc ra2 : fa.getDest().getArcs()) {
							if (ra2.isDirect() && (ra2 != fa) && (ra2 != fb)) {
								RoadDef rd2 = ra2.getRoadDef();
								byte access = rd2.getAccess();
								if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
									hasRoad = true;
									break;
								}
							}
						}
						if (!hasRoad) {
							log.info("Discarding possible roundabout flare", rda, "with", rdb, "no ongoing road");
							continue;							
						}
						
						// check the lengths of the two flares
						if (maxFlareLength > 0) {
							float faLength = fa.getLengthInMeter();
							float fbLength = fb.getLengthInMeter();
							if (faLength > maxFlareLength || fbLength > maxFlareLength) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where the flares are", faLength, "and", fbLength, "m");
								continue;
							}
						}
						
						// check the relative lengths of the two flares
						if (maxFlareLengthMultiple > 0) {
							float faLength = fa.getLengthInMeter();
							float fbLength = fb.getLengthInMeter();
							if (faLength > maxFlareLengthMultiple * fbLength || fbLength > maxFlareLengthMultiple * faLength) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where one flare is", String.format("%.1f", Math.max(faLength, fbLength)/Math.min(faLength, fbLength)), "times longer than the other");
								continue;
							}
						}
						
						//check the angle between the initial bearing from each flare and the centre of the roundabout
						if (maxFlareBearing > 0) {
							double flareAngle = fa.getReverseArc().getInitialHeading() - fa.getDest().getCoord().bearingTo(centre);
							if (flareAngle < -180)
								flareAngle += 360;
							if (flareAngle > 180)
								flareAngle -= 360;
							if (Math.abs(flareAngle) > maxFlareBearing) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where angle to centre is", String.format("%.1f", flareAngle));
								continue;
							}
							flareAngle = fb.getReverseArc().getInitialHeading() - fb.getDest().getCoord().bearingTo(centre);
							if (flareAngle < -180)
								flareAngle += 360;
							if (flareAngle > 180)
								flareAngle -= 360;
							if (Math.abs(flareAngle) > maxFlareBearing) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where angle to centre is", String.format("%.1f", flareAngle));
								continue;
							}
						}

						// check the angle between the flares
						if (maxFlareAngle > 0) {
							double flareAngle = fa.getReverseArc().getInitialHeading() - fb.getReverseArc().getInitialHeading();
							if (flareAngle < -180)
								flareAngle += 360;
							if (flareAngle > 180)
								flareAngle -= 360;
							if (Math.abs(flareAngle) > maxFlareAngle) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where subtended angle is", String.format("%.1f", flareAngle));
								continue;
							}
						}
						
						// check that both roads don't extend beyond the common point
						boolean faExtended = false;
						boolean fbExtended = false;
						boolean thirdArcToRoundabout = false;
						for (RouteArc ra2 : fa.getDest().getArcs()) {
							if (ra2.isDirect()) {
								if (ra2.getDest() != nodeToCheck && ra2.getSource() != nodeToCheck && ra2.getDest() != nextExitNode && ra2.getSource() != nextExitNode)
								{
									RoadDef rd2 = ra2.getRoadDef();
									if (rd2 == rda)
										faExtended = true;
									if (rd2 == rdb)
										fbExtended = true;
									if (!thirdArcToRoundabout) {
										byte access = rd2.getAccess();
										if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
											for (RouteNode rn : roundaboutNodes) {
												if (rn == ra2.getSource() || rn == ra2.getDest()) {
													thirdArcToRoundabout = true;
													break;
												}
											}
										}
									}
								}
							}
						}
						if (faExtended && fbExtended && discardBothFlaresExtend) {
							log.info("Discarding possible roundabout flare", rda, "with", rdb, "where flares both extend at", fa.getDest().getCoord().toOSMURL());
							continue;
						}
						if (discardMultipleFlares && thirdArcToRoundabout) {
							log.info("Discarding possible roundabout flare", rda, "with", rdb, "where more than two roads join roundabout from", fa.getDest().getCoord().toOSMURL());
							continue;
						}
						
						// check whether they have names that are different
						String namea = rda.getName();
						String nameb = rdb.getName();
						if (discardDifferentFlareNames) {
							if (namea != null && nameb != null && !namea.equals(nameb) && !namea.contains(nameb) && !nameb.contains(namea)) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where flares have different names", namea, "and", nameb);
								continue;
							}
						}

						// check whether they have accesses that are different
						byte accessa = rda.getAccess();
						byte accessb = rdb.getAccess();
						if (discardDifferentAccesses) {
							if (accessa != accessb) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where flares have different accesses");
								continue;
							}
						}

						// check whether the two roads are very far apart on the roundabout
						if ((centre != null) && (maxEntryAngle > 0)) {
							double bearingFrom = centre.bearingTo(coord);
							double bearingTo = centre.bearingTo(nextExitNode.getCoord());
							double angle = bearingTo - bearingFrom;
							if (angle < 0)
								angle = -angle;
							if (angle > 180)
								angle = 360 - angle;
							if (angle > maxEntryAngle) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, coord.toOSMURL(), nextExitNode.getCoord().toOSMURL(), "where angle", angle, "from centre is too large");
								continue;
							}
						}
						
						if(maxFlareToSeparationMultiple > 0) {
							// if both of the flare roads are much
							// longer than the length of the
							// roundabout segment, they are probably
							// not flare roads at all but just two
							// roads that meet up - so ignore them
							int roundaboutSegmentLength = roundaboutSegmentLength(nodeToCheck, nextExitNode);
							int maxLength = (flareSeparationOverride == 0 ? roundaboutSegmentLength : Math.max(flareSeparationOverride,  roundaboutSegmentLength)) * maxFlareToSeparationMultiple;
							if(maxLength > 0 &&
							   fa.getLength() > maxLength &&
							   fb.getLength() > maxLength) {
								log.info("Discarding possible roundabout flare", rda, "with", rdb, "where roads are", fa.getLength(), "and", fb.getLength(), "m long with a separation of", roundaboutSegmentLength, "m");
								continue;
							}
						}

						// check whether there is also a bigger flare
						boolean processed = false;
						RoadDef extendedRoad = null;
						for(RouteArc a : fa.getDest().getArcs()) {
							if(a.isDirect() && a.getDest() != nodeToCheck && a.getDest() != nextExitNode) {
								RoadDef ard = a.getRoadDef();
								if((ard == rda) || (ard == rdb)) {
									// one of the two roads goes further than the flare

									if (nextButOneExitNode != null) {
										// check whether there is a bigger flare to the next exit point
										for(RouteArc fc : nextButOneExitNode.getArcs()) {
											if(!fc.isDirect())
												continue;

											RoadDef rdc = fc.getRoadDef();
											if (rdc.isSynthesised())
												continue;

											byte access = rdc.getAccess();
											if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
												if(a.getDest() == fc.getDest()) {
													// we have a bigger flare
													processed = true;
													log.info("Discarding possible roundabout flare", rda, "at", fa.getDest().getCoord().toOSMURL(), "multiple flares found");
													break;
												}
											}
										}
									}
									if (!processed && (precedingExitNode != null)) {
										// check whether there is a bigger flare from the preceding exit point
										for(RouteArc fc : precedingExitNode.getArcs()) {
											if(!fc.isDirect())
												continue;

											RoadDef rdc = fc.getRoadDef();
											if (rdc.isSynthesised())
												continue;

											byte access = rdc.getAccess();
											if (access != 0 && (access != AccessTagsAndBits.FOOT)) {
												if(a.getDest() == fc.getDest()) {
													// we have a bigger flare
													processed = true;
													log.info("Discarding possible roundabout flare", rda, "at", fa.getDest().getCoord().toOSMURL(), "multiple flares found");
													break;
												}
											}
										}
									}
									if (processed)
										break;
									extendedRoad = ard;
								}
							}
						}
						if (processed)
							continue;

						// check the flare roads for one way direction
						if(!rda.isOneway())
							log.diagnostic("Outgoing roundabout flare road " + rda + " is not oneway " + fa.getSource().getCoord().toOSMURL());
						else if(!rdb.isOneway())
							log.diagnostic("Incoming roundabout flare road " + rdb + " is not oneway " + fb.getDest().getCoord().toOSMURL());
						else if(!fa.isForward())
							log.diagnostic("Outgoing roundabout flare road " + rda + " points in wrong direction " + fa.getSource().getCoord().toOSMURL());
						else if(fb.isForward())
							log.diagnostic("Incoming roundabout flare road " + rdb + " points in wrong direction " +  fb.getSource().getCoord().toOSMURL());
						else if (extendedRoad != null) {
							if(extendedRoad == rda)
								log.diagnostic("Outgoing roundabout flare road " + rda + " does not finish at flare " + fa.getDest().getCoord().toOSMURL());
							else if(extendedRoad == rdb)
								log.diagnostic("Incoming roundabout flare road " + rdb + " does not start at flare " + fb.getDest().getCoord().toOSMURL());
						}
						else if (namea != null && nameb != null && !namea.equals(nameb))
							log.diagnostic("Roundabout flare roads " + rda + " and " + rdb + " have different names " + namea + " and " + nameb);
						else if (namea != null && nameb == null)
							log.diagnostic("Roundabout flare road " + rda + " has name " + namea + " but " + rdb + " is unnamed");
						else if (namea == null && nameb != null)
							log.diagnostic("Roundabout flare road " + rdb + " has name " + nameb + " but " + rda + " is unnamed");
					}
				}
			}
		}
	}

	// determine "distance" between two nodes on a roundabout
	private static int roundaboutSegmentLength(final RouteNode n1, final RouteNode n2) {
		List<RouteNode> seen = new ArrayList<>();
		int len = 0;
		RouteNode n = n1;
		boolean checkMoreLinks = true;
		while(checkMoreLinks && !seen.contains(n)) {
			checkMoreLinks = false;
			seen.add(n);
			for(RouteArc a : n.getArcs()) {
				if(a.isForward() &&
				   a.getRoadDef().isRoundabout() &&
				   !a.getRoadDef().isSynthesised()) {
					len += a.getLength();
					n = a.getDest();
					if(n == n2)
						return len;
					checkMoreLinks = true;
					break;
				}
			}
		}
		// didn't find n2
		return Integer.MAX_VALUE;
	}

	/**
	 * determine whether a RouteArc goes inside the roundabout
	 * @param ra a RouteArc that has a node on the roundabout
	 * @param rn the RouteNode that is on the roundabout
	 * @param roundaboutCentre the centre of the roundabout
	 */
	private boolean goesInsideRoundabout(RouteArc ra, RouteNode rn, Coord roundaboutCentre) {
		// check whether the way is inside the roundabout by seeing if the next point is nearer to the centre of the roundabout than this
		RouteNode nextNode = ra.getSource() == rn ? ra.getDest() : ra.getSource();
		Coord nextCoord = nextNode.getCoord();
		boolean rejoinsRoundabout = false;
		for (RouteNode roundaboutNode : roundaboutNodes) {
			if (roundaboutNode == nextNode) {
				// arc rejoins roundabout, so calculate another point to use
				rejoinsRoundabout = true;
				nextCoord = rn.getCoord().offset(ra.getSource() == rn ? ra.getInitialHeading() : ra.getReverseArc().getInitialHeading(), rn.getCoord().distance(nextCoord) / 2);
				break;
			}
		}
		double distanceToCentre = roundaboutCentre.distance(rn.getCoord());
		double nextDistanceToCentre = roundaboutCentre.distance(nextCoord);
		RoadDef rd = ra.getRoadDef();
		if (Math.abs(nextDistanceToCentre - distanceToCentre) < 5) {
			if (rejoinsRoundabout) {
				Coord nextCoord2 = nextCoord.offset(rn.getCoord().bearingTo(nextCoord), nextCoord.distance(rn.getCoord()) / 2);
				double nextDistanceToCentre2 = roundaboutCentre.distance(nextCoord2);
				if (Math.abs(nextDistanceToCentre2 - distanceToCentre) > 2)
					return nextDistanceToCentre2 < distanceToCentre;
			}
			else {
				// have a look at the next point to more accurately determine whether inside
				for (RouteArc nra : nextNode.getArcs()) {
					if ((nra != ra) && (nra.getRoadDef() == rd)) {
						RouteNode nextNode2 = nra.getSource() == nextNode ? nra.getDest() : nra.getSource();
						if (nextNode2 == rn)
							continue;
						
						Coord nextCoord2 = nextNode2.getCoord();
						for (RouteNode roundaboutNode : roundaboutNodes) {
							if (roundaboutNode == nextNode2) {
								// arc rejoins roundabout, so calculate another point to use
								nextCoord2 = nextCoord.offset(nra.getSource() == nextNode ? nra.getInitialHeading() : nra.getReverseArc().getInitialHeading(), nextCoord.distance(nextCoord2) / 2);
								break;
							}
						}
						if (nextCoord.distance(nextCoord2) > distanceToCentre)
							nextCoord2 = nextCoord.offset(nextCoord.bearingTo(nextCoord2), distanceToCentre / 2);
						double nextDistanceToCentre2 = roundaboutCentre.distance(nextCoord2);
						if (Math.abs(nextDistanceToCentre2 - distanceToCentre) > 2)
							return nextDistanceToCentre2 < distanceToCentre;
					}
				}
				// no luck there, so try following the existing bearing further
				Coord nextCoord3 = nextCoord.offset(rn.getCoord().bearingTo(nextCoord), distanceToCentre / 2);
				double nextDistanceToCentre3 = roundaboutCentre.distance(nextCoord3);
				if (Math.abs(nextDistanceToCentre3 - distanceToCentre) > 2)
					return nextDistanceToCentre3 < distanceToCentre;
			}
		}
		if ((Math.abs(nextDistanceToCentre - distanceToCentre) < 2) && !rd.messagePreviouslyIssued("inside roundabout"))
			log.info("Way",rd,"unable to accurately determine whether",nextCoord.toOSMURL()," is inside roundabout centred at", roundaboutCentre.toOSMURL());

		return nextDistanceToCentre < distanceToCentre;
	}
	
	/**
	 * helper class for finding the centre of a roundabout
	 */
	private class CentreFinder {
		double delta;
		RouteNode nearestNodeToCentre;
		RouteNode furthestNodeFromCentre;
	}	
		
	private Coord getRoundaboutCentre() {
		int numPoints = roundaboutNodes.size();
		double centreLat = 0;
		double centreLon = 0;
		for (RouteNode rn : roundaboutNodes) {
			centreLat += rn.getCoord().getHighPrecLat();
			centreLon += rn.getCoord().getHighPrecLon();
		}
		// get centre of gravity as an approximate centre of roundabout
		Coord roundaboutCentre = Coord.makeHighPrecCoord((int)Math.round(centreLat / numPoints), (int)Math.round(centreLon / numPoints));
		if(log.isDebugEnabled())
			log.debug("Roundabout centre of gravity", roundaboutCentre.toOSMURL());
		if (roundaboutNodes.size() > 2) {
			CentreFinder cf = getDeltaDistanceFromCentre(roundaboutCentre);
			while (cf.delta > 1.0) {
				double bearing = roundaboutCentre.bearingTo(cf.furthestNodeFromCentre.getCoord());
				Coord betterCentre = roundaboutCentre.offset(bearing, cf.delta / 3);
				CentreFinder bettercf = getDeltaDistanceFromCentre(betterCentre);
				if(log.isDebugEnabled())
					log.debug("Trying centre", betterCentre.toOSMURL());
				if (bettercf.delta < cf.delta) {
					cf = bettercf;
					roundaboutCentre = betterCentre;
				}
				else {
					bearing = cf.nearestNodeToCentre.getCoord().bearingTo(roundaboutCentre);
					betterCentre = roundaboutCentre.offset(bearing, cf.delta / 3);
					if(log.isDebugEnabled())
						log.debug("Trying centre", betterCentre.toOSMURL());
					bettercf = getDeltaDistanceFromCentre(betterCentre);
					if (bettercf.delta < cf.delta) {
						cf = bettercf;
						roundaboutCentre = betterCentre;
					}
					else
						break;
				}
			}
		}
		if(log.isDebugEnabled())
			log.debug("Roundabout centre", roundaboutCentre.toOSMURL());
		return roundaboutCentre;
	}
		
	private CentreFinder getDeltaDistanceFromCentre(Coord centreCoord) {
		CentreFinder cf = new CentreFinder();
		double minDistanceToCentre = 9999999;
		double maxDistanceToCentre = 0;
		for (RouteNode rn : roundaboutNodes) {
			double distanceToCentre = centreCoord.distance(rn.getCoord());
			if (minDistanceToCentre > distanceToCentre) {
				minDistanceToCentre = distanceToCentre;
				cf.nearestNodeToCentre = rn;
			}
			if (maxDistanceToCentre < distanceToCentre) {
				maxDistanceToCentre = distanceToCentre;
				cf.furthestNodeFromCentre = rn;
			}
		}
		cf.delta = maxDistanceToCentre - minDistanceToCentre;
		return cf;
	}
		
}
