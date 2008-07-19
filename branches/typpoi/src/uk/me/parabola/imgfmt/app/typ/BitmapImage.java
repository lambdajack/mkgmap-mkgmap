package uk.me.parabola.imgfmt.app.typ;

import java.util.Comparator;
import java.util.Map;

import uk.me.parabola.imgfmt.app.ImgFileWriter;
import uk.me.parabola.imgfmt.app.Writeable;
import uk.me.parabola.log.Logger;

public class BitmapImage implements Writeable, Comparator<BitmapImage> {
	private static final Logger log = Logger.getLogger(BitmapImage.class);
	private int off;
	private byte dayNight;	// 7=Night 1=Day
	private byte width;
	private byte height;
	private String image;
	private byte typ;
	private int cpc;
	private byte subtype;
	private Map<String, Rgb> colors;

	public final byte getTyp() {
		return this.typ;
	}

	public final byte getSubtype() {
		return this.subtype;
	}

	private BitmapImage() { /*for compare*/ }

	protected static BitmapImage comperator() {
		return new BitmapImage();
	}

	public BitmapImage(byte typ_, byte subtype_, byte dayNight_, int width_,
			Map<String, Rgb> colors_, int cpc_, String image_)
	{
		if (image_ == null)
			throw new RuntimeException("NULL Image");
		this.height = (byte) (image_.length() / width_);
		this.colors = colors_;
		if (width_ != 16)
			throw new RuntimeException("Only 16 pixel with supported");
		if (this.height * width_ != image_.length())
			throw new RuntimeException("Only 16 pixel with supported");
		this.cpc = cpc_;
		this.dayNight = dayNight_;
		this.width = (byte) width_;
		this.image = image_;
		this.typ = typ_;
		this.subtype = subtype_;
	}

	public void write(ImgFileWriter writer) {
		this.off = writer.position();
		byte cc = (byte) (this.colors.size());
		// We only Support up to 16 Colors(currently)
		writer.put(this.dayNight);
		writer.put(this.width);
		writer.put(this.height);
		writer.put(cc);
		writer.put((byte) 0x10); // 0x10 => 888 (8Bits per Color)
		// 0x20 => 444 (4Bits per Color)
		int cid = 0;
		for (Rgb rgb : this.colors.values()) {
			rgb.write(writer, (byte) 0x10);
			rgb.idx = cid++;
		}
		int idx = 0;
		try {
			if (cc <= 16)
				for (idx = 0; idx < this.image.length(); idx += 2 * this.cpc) {
					int p2 = this.colors.get(this.image.substring(idx + 0, idx + 0 + this.cpc)).idx;
					int p1 = this.colors.get(this.image.substring(idx + 1, idx + 1 + this.cpc)).idx;
					if (p1 == -1 || p2 == -1)
						throw new RuntimeException("Invalid Color Code");
					byte p = (byte) (p1 << 4 | p2);
					writer.put(p);
				}
			else
				for (idx = 0; idx < this.image.length(); idx += 2) {
					int p = this.colors.get(this.image.substring(idx + 0, idx + 0 + this.cpc)).idx;
					if (p == -1) throw new RuntimeException("Invalid Color Code");
					writer.put((byte) p);
				}
		}
		catch (Throwable ex) {
			log.error(ex.getMessage(), ex);
			for (Map.Entry<String, Rgb> e : this.colors.entrySet())
				log.info("'" + e.getKey() + "' c rgb(" + e.getValue().r + " , " + e
						.getValue().g + " , " + e.getValue().r + ")");
			log.info("bild[idx+0]='" + this.image
					.substring(idx + 0, idx + 0 + this.cpc) + "' => " + this.colors
					.get(this.image.substring(idx + 0, idx + 0 + this.cpc)));
			log.info("bild[idx+1]='" + this.image
					.substring(idx + 0, idx + 1 + this.cpc) + "' => " + this.colors
					.get(this.image.substring(idx + 0, idx + 1 + this.cpc)));
			log.info("bild[?]=' ' => " + this.colors.get(this.image.charAt(idx + 1)));
		}
		// TODO String with names
	}

	public int getOffset() {
		return this.off - TYPHeader.HEADER_LEN;
	}

	public int getSize() {
		return 5 + this.colors.size() * 3 + this.width * this.height / 2;
	}

	public int compare(BitmapImage a, BitmapImage b) {
		if (a == null) return 1;
		if (b == null) return -1;
		if (a.typ < b.typ) return -1;
		if (a.typ > b.typ) return 1;
		if (a.dayNight < b.dayNight) return -1;
		if (a.dayNight > b.dayNight) return 1;
		return 0;
	}

}
