package com.screener.service.service;

import static org.bytedeco.leptonica.global.leptonica.pixDestroy;
import static org.bytedeco.leptonica.global.leptonica.pixReadMem;
import static org.bytedeco.tesseract.global.tesseract.PSM_SINGLE_BLOCK;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.TessBaseAPI;
import org.springframework.stereotype.Service;

import com.screener.service.dto.Level2Request;
import com.screener.service.model.Level2Entry;

@Service
public class OcrService {
	private static final Pattern ROW_PATTERN = Pattern
			.compile("([0-9]+\\.[0-9]+)\\s+([0-9][0-9.,]*[KkMm]?)\\s*\\(\\s*([0-9]+)\\s*\\)");
	private static final Pattern PCT_PATTERN = Pattern.compile("([0-9]{1,3}\\.[0-9]{1,2})\\s*%");
	private final TessBaseAPI tess;

	public OcrService() throws Exception {
		String tessDataPath = extractTessData();
		this.tess = new TessBaseAPI();
		if (tess.Init(tessDataPath, "eng") != 0) {
			throw new IllegalStateException("Tesseract init failed. tessdata path used: " + tessDataPath);
		}
		tess.SetPageSegMode(PSM_SINGLE_BLOCK);
		tess.SetVariable("tessedit_char_whitelist", "0123456789.,KkMm%() ");
	}

	private String extractTessData() throws Exception {
		Path tessDir = Path.of(System.getProperty("java.io.tmpdir"), "tessdata_bytedeco");
		Files.createDirectories(tessDir);
		Path outFile = tessDir.resolve("eng.traineddata");
		if (!Files.exists(outFile)) {
			try (InputStream in = OcrService.class.getResourceAsStream("/tessdata/eng.traineddata")) {
				if (in == null) {
					throw new IllegalStateException("eng.traineddata missing.\n"
							+ "Download from: https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata\n"
							+ "Place at: src/main/resources/tessdata/eng.traineddata");
				}
				Files.copy(in, outFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return tessDir.toAbsolutePath().toString();
	}

	public Level2Request extract(BufferedImage original) throws Exception {
		BufferedImage processed = preprocess(original);
		int w = processed.getWidth();
		int h = processed.getHeight();
		int headerH = (int) (h * 0.14);
		int rowStartY = (int) (h * 0.16);
		int rowH = h - rowStartY;
		int halfW = w / 2;
		BufferedImage headerRegion = processed.getSubimage(0, 0, w, headerH);
		BufferedImage bidRegion = processed.getSubimage(0, rowStartY, halfW, rowH);
		BufferedImage askRegion = processed.getSubimage(halfW, rowStartY, halfW, rowH);
		String headerText = ocr(headerRegion);
		String bidText = ocr(bidRegion);
		String askText = ocr(askRegion);
		List<Double> pcts = new ArrayList<>();
		Matcher pm = PCT_PATTERN.matcher(headerText);
		while (pm.find())
			pcts.add(Double.parseDouble(pm.group(1)));
		double bidPct = pcts.size() > 0 ? pcts.get(0) : 50.0;
		double askPct = pcts.size() > 1 ? pcts.get(1) : 50.0;
		List<Level2Entry> bids = parseRows(bidText);
		List<Level2Entry> asks = parseRows(askText);
		bids.sort(Comparator.comparingDouble(Level2Entry::price).reversed());
		asks.sort(Comparator.comparingDouble(Level2Entry::price));
		return new Level2Request(bids, asks, bidPct, askPct);
	}

	private String ocr(BufferedImage region) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(region, "png", baos);
		byte[] bytes = baos.toByteArray();
		PIX pix = pixReadMem(bytes, bytes.length);
		tess.SetImage(pix);
		String result = tess.GetUTF8Text().getString();
		pixDestroy(pix);
		tess.Clear();
		return result == null ? "" : result;
	}

	private BufferedImage preprocess(BufferedImage src) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage scaled = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = scaled.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(src, 0, 0, w * 2, h * 2, null);
		g.dispose();
		BufferedImage gray = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g2 = gray.createGraphics();
		g2.drawImage(scaled, 0, 0, null);
		g2.dispose();
		return otsuBinarize(gray);
	}

	private BufferedImage otsuBinarize(BufferedImage gray) {
		int w = gray.getWidth();
		int h = gray.getHeight();
		int[] histogram = new int[256];
		int[] pixels = new int[w * h];
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int px = gray.getRaster().getSample(x, y, 0);
				pixels[y * w + x] = px;
				histogram[px]++;
			}
		int total = w * h;
		double sum = 0;
		for (int i = 0; i < 256; i++)
			sum += i * histogram[i];
		double sumB = 0, wB = 0, maxVar = 0;
		int threshold = 128;
		for (int t = 0; t < 256; t++) {
			wB += histogram[t];
			if (wB == 0)
				continue;
			double wF = total - wB;
			if (wF == 0)
				break;
			sumB += t * histogram[t];
			double mB = sumB / wB;
			double mF = (sum - sumB) / wF;
			double var = wB * wF * (mB - mF) * (mB - mF);
			if (var > maxVar) {
				maxVar = var;
				threshold = t;
			}
		}
		BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				result.getRaster().setSample(x, y, 0, pixels[y * w + x] >= threshold ? 255 : 0);
		return result;
	}

	private List<Level2Entry> parseRows(String text) {
		List<Level2Entry> entries = new ArrayList<>();
		String cleaned = text.replaceAll("[,_]", "").replaceAll("\\s+", " ");
		Matcher m = ROW_PATTERN.matcher(cleaned);
		while (m.find()) {
			double price = Double.parseDouble(m.group(1));
			double volume = parseVolume(m.group(2));
			int orders = Integer.parseInt(m.group(3));
			if (price > 0 && volume > 0)
				entries.add(new Level2Entry(price, volume, orders));
		}
		return entries;
	}

	private double parseVolume(String raw) {
		raw = raw.trim().toUpperCase();
		double multiplier = 1.0;
		if (raw.endsWith("K")) {
			multiplier = 1_000.0;
			raw = raw.replace("K", "");
		} else if (raw.endsWith("M")) {
			multiplier = 1_000_000.0;
			raw = raw.replace("M", "");
		}
		try {
			return Double.parseDouble(raw) * multiplier;
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}