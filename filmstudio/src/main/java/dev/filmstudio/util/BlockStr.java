package dev.filmstudio.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** BlockState &lt;-&gt; "minecraft:oak_stairs[facing=north,half=bottom]" without touching NBT APIs. */
public final class BlockStr {
	private BlockStr() {}

	public static String encode(BlockState s) {
		StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
		boolean first = true;
		for (Property<?> p : s.getProperties()) {
			sb.append(first ? '[' : ',');
			first = false;
			sb.append(p.getName()).append('=').append(valueName(s, p));
		}
		if (!first) sb.append(']');
		return sb.toString();
	}

	private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
		return p.getName(s.getValue(p));
	}

	public static BlockState decode(String str) {
		if (str == null || str.isEmpty()) return null;
		try {
			String id = str; String props = "";
			int i = str.indexOf('[');
			if (i >= 0) { id = str.substring(0, i); props = str.substring(i + 1, str.length() - 1); }
			Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
			BlockState st = b.defaultBlockState();
			if (!props.isEmpty()) {
				for (String kv : props.split(",")) {
					String[] a = kv.split("=");
					if (a.length != 2) continue;
					Property<?> p = b.getStateDefinition().getProperty(a[0]);
					if (p != null) st = apply(st, p, a[1]);
				}
			}
			return st;
		} catch (Exception e) {
			return null;
		}
	}

	private static <T extends Comparable<T>> BlockState apply(BlockState st, Property<T> p, String v) {
		return p.getValue(v).map(val -> st.setValue(p, val)).orElse(st);
	}
}
