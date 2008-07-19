package uk.me.parabola.imgfmt.app.typ;

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.Writeable;

public class DrawOrder implements Writeable {
	private final char typ;
	private final char unk1;
	private final byte unk2;
	public DrawOrder(char typ_, char unk1_, byte unk2_) {
		super();
		this.typ  = typ_;
		this.unk1 = unk1_;
		this.unk2 = unk2_;
	}
	
	public void write(ImgFileWriter writer) {
		writer.putChar(this.typ);
		writer.putChar(this.unk1);
		writer.put    (this.unk2);
	}

}
