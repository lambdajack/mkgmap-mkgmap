/*
 * Copyright (C) 2010.
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
package uk.me.parabola.imgfmt;

/**
 * Used for cases where the current map has failed to compile, but the error
 * is expected to be specific to the map (eg it is too big etc).  When this
 * error is thrown it may be possible for other maps on the command line to
 * succeed.
 *
 * If the error is such that processing further maps is not likely to be
 * successful then use {@link ExitException} instead.
 *
 * @author Steve Ratcliffe
 */
public class MapFailedException extends RuntimeException {

	/**
	 * Constructs a new runtime exception with the calling method name
	 * appended to the detail message.
	 * The cause is not initialized, and may subsequently be initialized by a
	 * call to {@link #initCause}.
	 *
	 * @param message the detail message. The detail message is saved for
	 *                later retrieval by the {@link #getMessage()} method.
	 */
	public MapFailedException(String message) {
		super(buildMessage(message));
	}

	/**
	 * Constructs a new runtime exception with the calling method name
	 * appended to the detail message and includes the cause.
	 * <p>Note that the detail message associated with
	 * <code>cause</code> is <i>not</i> automatically incorporated in
	 * this runtime exception's detail message.
	 *
	 * @param message the detail message (which is saved for later retrieval
	 *                by the {@link #getMessage()} method).
	 * @param cause   the cause (which is saved for later retrieval by the
	 *                {@link #getCause()} method).  (A <tt>null</tt> value is
	 *                permitted, and indicates that the cause is nonexistent or
	 *                unknown.)
	 */
	public MapFailedException(String message, Throwable cause) {
		super(buildMessage(message), cause);
	}
	
	/**
	 * Constructs a new runtime exception without appending the calling method
	 * name to the detail message. The calling method can be appended by the
	 * derived class if required.
	 */
	protected MapFailedException(String message, boolean ignored) {
		super(message);
	}

	/**
	 * Appends the calling method name to the supplied message.
	 */
	protected static String buildMessage(String message) {
		String thrownBy = "";
		try{
			StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
			int callerPosInStack = 3; 
			String[] caller = stackTraceElements[callerPosInStack].getClassName().split("\\.");
			thrownBy = " (thrown in " + caller[caller.length-1]+ "." +stackTraceElements[callerPosInStack].getMethodName() + "())";
		} catch(Exception e){
		}
		return message + thrownBy;
	}
}