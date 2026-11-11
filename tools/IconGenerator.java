import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.runelite.client.ui.FontManager;

/**
 * Generates the Plebcity icons: yellow "PLEB" in the RuneScape pixel font on black, styled like the
 * game's own text (#FFFF00 with a 1 px black shadow to the bottom right).
 *
 * This file lives outside src/ so it is never part of the plugin jar. Run it with tools/generate-icons.sh.
 *
 * Usage:
 *   IconGenerator preview <pluginRoot>
 *       Writes every variant, 8x enlarged, to tools/preview/ and prints font measurements.
 *   IconGenerator install <pluginRoot> <a|b> <none|brown|gold> <one|two>
 *       Writes the chosen 16x16 sidebar icon to src/main/resources/com/plebcity/plebcity_icon.png
 *       and the 48x72 Plugin Hub icon (same border, one- or two-line layout) to icon.png in the
 *       repository root.
 */
public class IconGenerator
{
	private static final Color YELLOW = new Color(0xFF, 0xFF, 0x00);
	private static final Color SHADOW = Color.BLACK;
	private static final Color BACKGROUND = Color.BLACK;
	private static final int PREVIEW_SCALE = 8;

	/** 1 px frame colours, similar to the game's interface frames. */
	private static final Map<String, Color> BORDERS = new LinkedHashMap<>();

	static
	{
		BORDERS.put("none", null);
		BORDERS.put("brown", new Color(0x4A, 0x3A, 0x22));
		BORDERS.put("gold", new Color(0xA8, 0x86, 0x32));
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length < 2)
		{
			throw new IllegalArgumentException("usage: preview <root> | install <root> <a|b> <none|brown|gold> <one|two>");
		}
		File root = new File(args[1]);
		switch (args[0])
		{
			case "preview":
				preview(root);
				break;
			case "install":
				install(root, args[2], args[3], args[4]);
				break;
			default:
				throw new IllegalArgumentException("unknown mode " + args[0]);
		}
	}

	// ------------------------------------------------------------------

	private static void preview(File root) throws IOException
	{
		Map<String, Font> fonts = new LinkedHashMap<>();
		fonts.put("small", FontManager.getRunescapeSmallFont());
		fonts.put("regular", FontManager.getRunescapeFont());
		fonts.put("bold", FontManager.getRunescapeBoldFont());
		for (Map.Entry<String, Font> f : fonts.entrySet())
		{
			for (String text : new String[]{"PLEB", "PL", "EB"})
			{
				BufferedImage g = glyphs(text, f.getValue());
				System.out.printf("%-7s %-4s -> %d x %d px (without shadow)%n", f.getKey(), text, g.getWidth(), g.getHeight());
			}
		}

		File dir = new File(root, "tools/preview");
		dir.mkdirs();
		List<String> written = new ArrayList<>();
		for (String border : BORDERS.keySet())
		{
			written.add(write(dir, "nav16_a_one_line_" + border, navIcon("a", border)));
			written.add(write(dir, "nav16_b_two_lines_" + border, navIcon("b", border)));
			written.add(write(dir, "hub48x72_one_line_" + border, hubIcon("one", border)));
			written.add(write(dir, "hub48x72_two_lines_" + border, hubIcon("two", border)));
		}
		written.forEach(p -> System.out.println("preview: " + p));
	}

	private static void install(File root, String variant, String border, String hubLayout) throws IOException
	{
		if (!BORDERS.containsKey(border) || !(variant.equals("a") || variant.equals("b"))
			|| !(hubLayout.equals("one") || hubLayout.equals("two")))
		{
			throw new IllegalArgumentException("variant must be a|b, border none|brown|gold, hub layout one|two");
		}
		File nav = new File(root, "src/main/resources/com/plebcity/plebcity_icon.png");
		File hub = new File(root, "icon.png");
		ImageIO.write(navIcon(variant, border), "png", nav);
		ImageIO.write(hubIcon(hubLayout, border), "png", hub);
		System.out.println("wrote " + nav);
		System.out.println("wrote " + hub);
	}

	// ------------------------------------------------------------------

	/** 16x16 sidebar icon. a = "PLEB" on one line in the small font, b = "PL" / "EB" on two lines. */
	static BufferedImage navIcon(String variant, String border)
	{
		BufferedImage img = canvas(16, 16, border);
		Font font = FontManager.getRunescapeSmallFont();
		if (variant.equals("a"))
		{
			BufferedImage text = glyphs("PLEB", font);
			drawCentered(img, text, (img.getHeight() - text.getHeight() - 1) / 2);
		}
		else
		{
			// Two 8 px lines fill all 16 rows, so there is no gap and the bottom shadow falls off the icon.
			BufferedImage top = glyphs("PL", font);
			BufferedImage bottom = glyphs("EB", font);
			int y = Math.max(0, (img.getHeight() - top.getHeight() - bottom.getHeight()) / 2);
			drawCentered(img, top, y);
			drawCentered(img, bottom, y + top.getHeight());
		}
		return img;
	}

	/**
	 * 48x72 Plugin Hub icon in the bold font, centered and scaled up by the largest whole factor that fits.
	 * layout "one" = "PLEB" on one line, "two" = "PL" / "EB" on two lines.
	 */
	static BufferedImage hubIcon(String layout, String border)
	{
		BufferedImage img = canvas(48, 72, border);
		Font font = FontManager.getRunescapeBoldFont();
		String[] lines = layout.equals("two") ? new String[]{"PL", "EB"} : new String[]{"PLEB"};
		List<BufferedImage> masks = new ArrayList<>();
		int width = 0;
		for (String line : lines)
		{
			BufferedImage m = glyphs(line, font);
			masks.add(m);
			width = Math.max(width, m.getWidth());
		}
		int inner = 48 - 2 * 4; // keep a margin inside the frame
		int scale = Math.max(1, inner / (width + 1)); // +1: room for the shadow
		int gap = 2 * scale;
		int total = -gap;
		List<BufferedImage> scaled = new ArrayList<>();
		for (BufferedImage m : masks)
		{
			BufferedImage s = scale(m, scale);
			scaled.add(s);
			total += s.getHeight() + gap;
		}
		int y = (img.getHeight() - total - scale) / 2;
		for (BufferedImage s : scaled)
		{
			drawCentered(img, s, y, scale);
			y += s.getHeight() + gap;
		}
		return img;
	}

	// ------------------------------------------------------------------

	private static BufferedImage canvas(int w, int h, String border)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, w, h);
		Color frame = BORDERS.get(border);
		if (frame != null)
		{
			g.setColor(frame);
			g.drawRect(0, 0, w - 1, h - 1);
		}
		g.dispose();
		return img;
	}

	/**
	 * Renders text without anti-aliasing and crops it to the lit pixels, so it can be placed exactly.
	 * The result is a mask: opaque pixels are glyph pixels.
	 */
	static BufferedImage glyphs(String text, Font font)
	{
		BufferedImage big = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = big.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.setFont(font);
		g.setColor(Color.WHITE);
		g.drawString(text, 10, 10 + g.getFontMetrics().getAscent());
		g.dispose();

		Rectangle box = null;
		for (int y = 0; y < big.getHeight(); y++)
		{
			for (int x = 0; x < big.getWidth(); x++)
			{
				if ((big.getRGB(x, y) >>> 24) != 0)
				{
					Rectangle px = new Rectangle(x, y, 1, 1);
					box = box == null ? px : box.union(px);
				}
			}
		}
		if (box == null)
		{
			throw new IllegalStateException("nothing rendered for " + text);
		}
		return big.getSubimage(box.x, box.y, box.width, box.height);
	}

	private static void drawCentered(BufferedImage img, BufferedImage mask, int y)
	{
		drawCentered(img, mask, y, 1);
	}

	/** Draws the mask in yellow with a black shadow offset by one source pixel (shadowStep) right and down. */
	private static void drawCentered(BufferedImage img, BufferedImage mask, int y, int shadowStep)
	{
		int x = (img.getWidth() - mask.getWidth() - shadowStep) / 2;
		paint(img, mask, x + shadowStep, y + shadowStep, SHADOW);
		paint(img, mask, x, y, YELLOW);
	}

	private static void paint(BufferedImage img, BufferedImage mask, int ox, int oy, Color color)
	{
		for (int y = 0; y < mask.getHeight(); y++)
		{
			for (int x = 0; x < mask.getWidth(); x++)
			{
				int tx = ox + x;
				int ty = oy + y;
				if ((mask.getRGB(x, y) >>> 24) != 0 && tx >= 0 && ty >= 0 && tx < img.getWidth() && ty < img.getHeight())
				{
					img.setRGB(tx, ty, color.getRGB());
				}
			}
		}
	}

	/** Nearest-neighbour scaling. */
	static BufferedImage scale(BufferedImage src, int factor)
	{
		BufferedImage out = new BufferedImage(src.getWidth() * factor, src.getHeight() * factor, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(src.getScaledInstance(out.getWidth(), out.getHeight(), Image.SCALE_REPLICATE), 0, 0, null);
		g.dispose();
		return out;
	}

	private static String write(File dir, String name, BufferedImage img) throws IOException
	{
		File file = new File(dir, name + "_x" + PREVIEW_SCALE + ".png");
		ImageIO.write(scale(img, PREVIEW_SCALE), "png", file);
		return file.getPath();
	}
}
