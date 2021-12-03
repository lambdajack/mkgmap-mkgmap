package uk.me.parabola.imgfmt;

public class MapTooBigException extends MapFailedException {
	protected final long maxAllowedSize;

	public MapTooBigException(long maxSize, String message, String suggestion) {
		super(message + " The maximum size is " + maxSize + " bytes. " + suggestion, false);
		maxAllowedSize = maxSize;
	}
	
	public long getMaxAllowedSize() {
		return maxAllowedSize;
	}
}
