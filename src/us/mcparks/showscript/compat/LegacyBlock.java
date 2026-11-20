package us.mcparks.showscript.compat;

/** A block as 1.12 numeric id + data value, the form rebuild files store. */
public final class LegacyBlock {
    public final int id;
    public final byte data;
    /**
     * True if the block has no 1.12 equivalent (a 1.13+-only block, read on a modern server) and was written as
     * air. Callers must tell the user: the rebuild will not restore it.
     */
    public final boolean unmapped;
    /**
     * True if the block type has a 1.12 id but its exact state doesn't (e.g. waterlogged stairs, stairs with a
     * corner shape, connected fences, read on a modern server): {@link #id}/{@link #data} is the closest 1.12 block
     * of the same type and the rest of the state is lost. {@link #lostState} says which properties differ.
     * Callers must tell the user; physics does not reliably restore these (a waterlogged stair comes back dry).
     */
    public final boolean approximate;
    /** For {@link #approximate} blocks: the original state and what it was reduced to; null otherwise. */
    public final String lostState;

    public LegacyBlock(int id, byte data, boolean unmapped) {
        this(id, data, unmapped, false, null);
    }

    public LegacyBlock(int id, byte data, boolean unmapped, boolean approximate, String lostState) {
        this.id = id;
        this.data = data;
        this.unmapped = unmapped;
        this.approximate = approximate;
        this.lostState = lostState;
    }

    /** True if writing this block to a rebuild file and building it again gives back the same block. */
    public boolean isExact() {
        return !unmapped && !approximate;
    }
}
