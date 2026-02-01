package kaiakk.powerhouse.calculations.entity;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;

public class BookCalculator {

	private static final int MAX_PAGES = 128;
	private static final int MAX_CHARS_PER_PAGE = 2000;
	private static final int MAX_TOTAL_CHARS = 50000;

	public static boolean isBookType(ItemStack stack) {
		if (stack == null) return false;
		try {
			Material type = stack.getType();
			return type == Material.WRITTEN_BOOK || type == Material.WRITABLE_BOOK;
		} catch (Throwable ignored) {}
		return false;
	}

	public static boolean isMalicious(ItemStack stack) {
		if (stack == null) return false;
		try {
			if (!isBookType(stack)) return false;
			org.bukkit.inventory.meta.ItemMeta im = stack.getItemMeta();
			if (!(im instanceof BookMeta)) return false;
			BookMeta meta = (BookMeta) im;

			List<String> pages = meta.getPages();
			if (pages == null) return false;

			if (pages.size() == 1 && "[Sanitized by Powerhouse]".equals(pages.get(0))) return false;

			if (pages.size() > MAX_PAGES) return true;

			int total = 0;
			for (String p : pages) {
				if (p == null) continue;
				int len = p.length();
				total += len;
				if (len > MAX_CHARS_PER_PAGE) return true;
				if (total > MAX_TOTAL_CHARS) return true;
				if (len > 500 && (p.contains("{") && p.contains("\"") && p.contains(":"))) return true;
			}

			return false;
		} catch (Throwable ignored) {}
		return false;
	}
}

