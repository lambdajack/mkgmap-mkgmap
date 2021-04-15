package uk.me.parabola.log;

import java.util.logging.Level;

public class LogLevel extends Level {
	
    public static final LogLevel DIAGNOSTIC = new LogLevel("DIAGNOSTIC", 1100);
    
    public static final LogLevel ECHO = new LogLevel("ECHO", 1200);

    public static final LogLevel OVERRIDE = new LogLevel("OVERRIDE", 1300);

    protected LogLevel(String name, int value) {
    	super(name, value);
    }

}
