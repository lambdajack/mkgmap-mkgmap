package uk.me.parabola.mkgmap.main;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import uk.me.parabola.util.EnhancedProperties;

public abstract class Preparer implements Runnable {

	private ExecutorCompletionService<Object> threadPool;
	
	public boolean init(EnhancedProperties props, ExecutorCompletionService<Object> additionalThreadPool) {
		this.threadPool = additionalThreadPool;
		return true;
	}
	
	protected <V> Future<V> addWorker(Callable<V> worker) {
		if (threadPool == null) {
			// only one thread available for the preparer
			// so execute the task directly
			FutureTask<V> future = new FutureTask<V>(worker);
			future.run();
			return future;
		} else {
			return (Future<V>)threadPool.submit((Callable<Object>)worker);
		}
	}
	
}
