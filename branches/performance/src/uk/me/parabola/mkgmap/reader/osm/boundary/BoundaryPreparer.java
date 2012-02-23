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

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.main.Preparer;
import uk.me.parabola.util.EnhancedProperties;

public class BoundaryPreparer extends Preparer {
	private static final Logger log = Logger.getLogger(BoundaryPreparer.class);
	private static final List<Class<? extends LoadableBoundaryDataSource>> loaders;
	static {
		String[] sources = {
				"uk.me.parabola.mkgmap.reader.osm.boundary.OsmBinBoundaryDataSource",
				// must be last as it is the default
				"uk.me.parabola.mkgmap.reader.osm.boundary.Osm5BoundaryDataSource", };

		loaders = new ArrayList<Class<? extends LoadableBoundaryDataSource>>();

		for (String source : sources) {
			try {
				@SuppressWarnings({ "unchecked" })
				Class<? extends LoadableBoundaryDataSource> c = (Class<? extends LoadableBoundaryDataSource>) Class
						.forName(source);
				loaders.add(c);
			} catch (ClassNotFoundException e) {
				// not available, try the rest
			} catch (NoClassDefFoundError e) {
				// not available, try the rest
			}
		}
	}

	/**
	 * Return a suitable boundary map reader. The name of the resource to be
	 * read is passed in. This is usually a file name, but could be something
	 * else.
	 * 
	 * @param name
	 *            The resource name to be read.
	 * @return A LoadableBoundaryDataSource that is capable of reading the
	 *         resource.
	 */
	private static LoadableBoundaryDataSource createMapReader(String name) {
		for (Class<? extends LoadableBoundaryDataSource> loader : loaders) {
			try {
				LoadableBoundaryDataSource src = loader.newInstance();
				if (name != null && src.isFileSupported(name))
					return src;
			} catch (InstantiationException e) {
				// try the next one.
			} catch (IllegalAccessException e) {
				// try the next one.
			} catch (NoClassDefFoundError e) {
				// try the next one
			}
		}

		// Give up and assume it is in the XML format. If it isn't we will get
		// an error soon enough anyway.
		return new Osm5BoundaryDataSource();
	}

	private boolean onePass = true;
	private String boundaryFilename;
	private String inDir;
	private String outDir;

	public BoundaryPreparer() {
		
	}

	public boolean init(EnhancedProperties props,
			ExecutorCompletionService<Object> additionalThreadPool) {
		super.init(props, additionalThreadPool);
		
		this.boundaryFilename = props
				.getProperty("createboundsfile", null);
		this.inDir = props.getProperty("bounds", "bounds");
		this.outDir = props.getProperty("preparer-out-dir", null);
		if (this.outDir == null)
			this.outDir = this.inDir;
		this.onePass = props.getProperty("preparer-one-pass", false);
		return boundaryFilename != null;
	}

	/* (non-Javadoc)
	 * @see java.lang.Thread#run()
	 */
	public void run() {
		if (onePass == false && boundaryFilename != null) {
		long t1 = System.currentTimeMillis();
		boolean prepOK = createRawData();
		long t2 = System.currentTimeMillis();
		log.error("BoundaryPreparer pass 1 took " + (t2-t1) + " ms");

		if (!prepOK){
			return;
		}
		}
		long t3 = System.currentTimeMillis();
		workoutBoundaryRelations(inDir, outDir);
		long t4 = System.currentTimeMillis();
		log.error("BoundaryPreparer workoutBoundaryRelations() took " + (t4-t3) + " ms");
	}

	private boolean createRawData(){
		File boundsDirectory = new File(outDir);
		BoundarySaver saver = new BoundarySaver(boundsDirectory, BoundarySaver.RAW_DATA_FORMAT);
		LoadableBoundaryDataSource dataSource = createMapReader(boundaryFilename);
		dataSource.setBoundarySaver(saver);
		log.info("Started loading", boundaryFilename);
		try {
			dataSource.load(boundaryFilename);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return false;
		} catch (FormatException e) {
			e.printStackTrace();
			return false;
		}
		saver.setBbox(dataSource.getBounds());
		log.info("Finished loading", boundaryFilename);
		saver.end();
		return true;
	}
	
	
	public static void main(String[] args) {
		if (args[0].equals("--help")) {
			System.err.println("Usage:");
			System.err
					.println("java -cp mkgmap.jar uk.me.parabola.mkgmap.reader.osm.boundary.BoundaryPreparer [<boundsdir1>] [<boundsdir2>]");
			System.err.println(" <boundsdir1>: optional directory name or zip file with *.bnd files, default is bounds");
			System.err
			.println(" <boundsdir2>: optional output directory, if not specified, files in input are overwritten.");
			System.err
			.println("               If input is a zip file, output directory must be specified");

			System.exit(-1);
		} 
		String in = "bounds";
		String out = "bounds";
		if (args.length >= 1)
			in = args[0];
		if (args.length >= 2)
			out = args[1];
		else
			out = in;
		long t1 = System.currentTimeMillis();
		
		int maxJobs = Runtime.getRuntime().availableProcessors();
		ExecutorService threadPool = Executors.newFixedThreadPool(maxJobs);
		ExecutorCompletionService<Object> cmplSvc = new ExecutorCompletionService<Object>(threadPool);
		EnhancedProperties props = new EnhancedProperties();
		props.setProperty("bounds", in);
		props.setProperty("preparer-out-dir", out);
		props.setProperty("preparer-one-pass", "true");
		// is the separate out parameter required?
		
		BoundaryPreparer p = new BoundaryPreparer();
		p.init(props, (maxJobs > 1 ? cmplSvc : null));
		cmplSvc.submit(p, new Object());
		do {
			try {
				cmplSvc.take();
			} catch (InterruptedException exp) {
			}
		} while (((ThreadPoolExecutor) threadPool).getActiveCount() > 0);

		if (threadPool != null) {
			threadPool.shutdown();
		}
		
		System.out.println("Bnd files converted in " + (System.currentTimeMillis()-t1) + " ms");
	}

	/**
	 * Reworks all bounds files of the given directory so that all boundaries
	 * are applied with the information with which boundary they intersect.<br/>
	 * This information is added as tag "mkgmap:intersects_with" and contains a semicolon 
	 * separated list of boundary ids.<br/>
	 * Example:<br/>
	 * <code>
	 *   mkgmap:intersects_with=2:r51477;4:r87782
	 * </code><br/>
	 * The boundary tagged in such a way intersects with relation 51477 (admin level 2) 
	 * and with relation 87782 (admin level 4).
	 * 
	 * @param boundsDirectory
	 *            the directory of the bounds files
	 */
	public void workoutBoundaryRelations(String inputDirName, String outputDirName) {
		List<String> boundsFileNames = BoundaryUtil.getBoundaryDirContent(inputDirName);
				
		for (String boundsFileName : boundsFileNames) {
			// start workers that rework the boundary files and add the 
			// quadtree information
			addWorker(new QuadTreeWorker(inputDirName, outputDirName, boundsFileName));
		}
	}

	class QuadTreeWorker implements Callable<String> {
		private final String inputDirName;
		private final String outputDirName;
		private final String boundsFileName;
		
		public QuadTreeWorker(String inputDirName, String outputDirName, String boundsFileName) {
			this.inputDirName = inputDirName;
			this.outputDirName = outputDirName;
			this.boundsFileName = boundsFileName;
		}
		
		@Override
		public String call() throws Exception {
					log.info("Workout boundary relations in ", inputDirName + " " + boundsFileName);
					long t1 = System.currentTimeMillis();
					BoundaryQuadTree bqt = BoundaryUtil.loadQuadTree(inputDirName, boundsFileName);
					long t2 = System.currentTimeMillis() - t1;
					log.info("splitting " + boundsFileName + " took " + t2 + " ms");
					if (bqt != null){
						File outDir = new File(outputDirName);
						BoundarySaver saver = new BoundarySaver(outDir, BoundarySaver.QUADTREE_DATA_FORMAT);
						saver.setCreateEmptyFiles(false);

						saver.saveQuadTree(bqt, boundsFileName); 		
						saver.end();
					}
					return boundsFileName;
		}

	}

}

