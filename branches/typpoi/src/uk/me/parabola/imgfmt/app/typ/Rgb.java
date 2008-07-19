package uk.me.parabola.imgfmt.app.typ;

import uk.me.parabola.imgfmt.app.ImgFileWriter;

public class Rgb {
	public int idx;
	public final int b;
	public final int g;
	public final int r;

	public Rgb(int r, int g, int b, int i) {
		this.b = b;
		this.g = g;
		this.r = r;
		this.idx = i;
	}

	public Rgb(Rgb rgb, byte idx) {
		this.b = rgb.b;
		this.g = rgb.g;
		this.r = rgb.r;
		this.idx = idx;
	}

	public void write(ImgFileWriter writer, byte type) {
		if (type != 0x10)
			throw new RuntimeException("Invalid color deep");
		writer.put((byte) this.b);
		writer.put((byte) this.g);
		writer.put((byte) this.r);
	}
}
