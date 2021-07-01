package uk.me.parabola.imgfmt.app.net;

/**
 * Exception to throw when we detect that we do not know how to encode a particular case.
 * This should not be thrown any more, when the preparers is called correctly.
 *
 * If it is, then the number preparer is marked as invalid and the data is not written to the
 * output file.
 */
class Abandon extends RuntimeException {
	private static final long serialVersionUID = 1L;

	Abandon(String message) {
		super("HOUSE NUMBER RANGE: " + message);
	}
}