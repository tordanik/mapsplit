package dev.osm.mapsplit;

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import gnu.trove.TIntCollection;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

//@formatter:off
/**
 * provides implementation aspects shared between multiple {@link OsmMap} subtypes,
 * particularly relating to the interpretation of the map's values.
 * 
 * Structure of the values:
 *
 *     6                 4                   3    2 2 22
 *     3                 8                   2    8 7 54
 *     XXXX XXXX XXXX XXXX YYYY YYYY YYYY YYYY 1uuu uNNE nnnn nnnn nnnn nnnn nnnn nnnn
 *
 *     X - tile number
 *     Y - tile number
 *     u - unused
 *     1 - always set to 1. This ensures that the value can be distinguished from empty positions in an array.
 *     N - bits indicating immediate "neigbours"
 *     E - extended "neighbour" list used
 *     n - bits for "short" neighbour index, in long list mode used as index
 *
 *     Tiles indexed in "short" list (T original tile)
 *           -  - 
 *           2  1  0  1  2
 *       
 *     -2    0  1  2  3  4
 *     -1    5  6  7  8  9
 *      0   10 11  T 12 13
 *      1   14 15 16 17 18
 *      2   19 20 21 22 23
 * 
 */
//@formatter:on
abstract public class AbstractOsmMap implements OsmMap {

    // see HEAPMAP.md for details
    static final int          TILE_X_SHIFT = 48;
    static final int          TILE_Y_SHIFT = 32;
    private static final long TILE_X_MASK  = Const.MAX_TILE_NUMBER << TILE_X_SHIFT;
    private static final long TILE_Y_MASK  = Const.MAX_TILE_NUMBER << TILE_Y_SHIFT;

    private static final int   TILE_EXT_SHIFT            = 24;
    private static final long  TILE_EXT_MASK             = 1l << TILE_EXT_SHIFT;
    static final long          TILE_MARKER_MASK          = 0xFFFFFFl;
    private static final int   NEIGHBOUR_SHIFT           = TILE_EXT_SHIFT + 1;
    private static final long  NEIGHBOUR_MASK            = 3l << NEIGHBOUR_SHIFT;
    private static final int   ONE_BIT_SHIFT             = 31;
    private static final long  ONE_BIT_MASK              = 1l << ONE_BIT_SHIFT;

    private final ExtendedTileSetStore extendedSets = new ExtendedTileSetStore();

    @Override
    public int tileX(long value) {
        return (int) ((value & TILE_X_MASK) >>> TILE_X_SHIFT);
    }

    @Override
    public int tileY(long value) {
        return (int) ((value & TILE_Y_MASK) >>> TILE_Y_SHIFT);
    }

    @Override
    public int neighbour(long value) {
        return (int) ((value & NEIGHBOUR_MASK) >> NEIGHBOUR_SHIFT);
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#getAllTiles(long)
     */
    @Override
    public TIntCollection getAllTiles(long key) {

        long value = get(key);

        if (value == 0) {
            return null;
        }

        if ((value & TILE_EXT_MASK) != 0) {
            int idx = (int) (value & TILE_MARKER_MASK);
            return new TIntArrayList(extendedSets.getExtendedSet(idx));
        } else {
            TIntList result = parseMarker(value);
            // TODO: some tiles (neighbour-tiles) might be double-included in the list, is this a problem?!
            result.addAll(decode(value));
            return result;
        }

    }

    @Override
    public void updateInt(long key, TIntCollection tiles) {

        List<Long> longTiles = Arrays.stream(tiles.toArray())
                .mapToLong(tile -> createValue(
                        TileCoord.decodeX(tile),
                        TileCoord.decodeY(tile),
                        NEIGHBOURS_NONE))
                .boxed()
                .collect(toList());

        this.update(key, longTiles);

    }

    /**
     * creates a value (representing a set of tile coords) from a pair of x/y tile coords and maybe some neighbors
     * 
     * @param tileX x tile number
     * @param tileY y tile number
     * @param neighbours bit encoded neighbours
     * @return the value encoding the set of tiles represented by the parameters
     */
    protected long createValue(int tileX, int tileY, int neighbours) {
        return ((long) tileX) << TILE_X_SHIFT | ((long) tileY) << TILE_Y_SHIFT
                | ((long) neighbours) << NEIGHBOUR_SHIFT
                | ONE_BIT_MASK;
    }

    /**
     * updates a value by adding a set of tiles to it.
     * Might "overflow" into the list of additional tiles and modify it accordingly.
     * 
     * This can be used to implement {@link #update(long, Collection)}.
     * 
     * @return  the updated value
     */
    protected long updateValue(long originalValue, @NotNull Collection<Long> tiles) {

        long val = originalValue;

        int tx = tileX(val);
        int ty = tileY(val);

        // neighbour list is already too large so we use the "large store"
        if ((val & TILE_EXT_MASK) != 0) {
            int idx = (int) (val & TILE_MARKER_MASK);
            TIntSet oldSet = extendedSets.getExtendedSet(idx);
            TIntSet addedSet = decode(tiles);
            TIntSet newSet = merge(oldSet, addedSet);
            int newSetIndex = extendedSets.addExtendedSet(newSet);
            val |= TILE_EXT_MASK;
            val &= ~TILE_MARKER_MASK; // delete old marker from val
            val |= (long) newSetIndex;
            return val;
        }

        // create a expanded temp set for neighbourhood tiles
        TIntSet expanded = new TIntHashSet();
        for (long tile : tiles) {

            int x = tileX(tile);
            int y = tileY(tile);
            int neighbour = neighbour(tile);

            expanded.add(TileCoord.encode(x, y));

            if ((neighbour & NEIGHBOURS_EAST) != 0) {
                expanded.add(TileCoord.encode(x + 1, y));
            }
            if ((neighbour & NEIGHBOURS_SOUTH) != 0) {
                expanded.add(TileCoord.encode(x, y + 1));
            }
            if (neighbour == NEIGHBOURS_SOUTH_EAST) {
                expanded.add(TileCoord.encode(x + 1, y + 1));
            }
        }

        // now we use the 24 reserved bits for the tiles list..
        boolean extend = false;
        for (int tile : expanded.toArray()) {

            int tmpX = TileCoord.decodeX(tile);
            int tmpY = TileCoord.decodeY(tile);

            if (tmpX == tx && tmpY == ty) {
                continue;
            }

            int dx = tmpX - tx + 2;
            int dy = tmpY - ty + 2;

            int idx = dy * 5 + dx;
            if (idx >= 12) {
                idx--;
            }

            if (dx < 0 || dy < 0 || dx > 4 || dy > 4) {
                // .. damn, not enough space for "small store"
                // -> use "large store" instead
                extend = true;
                break;
            } else {
                val |= 1l << idx;
            }
        }

        if (extend) {
            return extendToNeighbourSet(originalValue, tiles);
        } else {
            return val;
        }

    }

    /**
     * Start using the list of additional tiles instead of the bits directly in the value
     * 
     * @param val the value to extend
     * @param tiles the List of additional tiles
     * @return the updated value
     */
    private long extendToNeighbourSet(long val, @NotNull Collection<Long> tiles) {

        TIntSet tileSet = decode(tiles);

        tileSet.addAll(decode(val));

        if ((val & TILE_MARKER_MASK) != 0) {
            // add current stuff to tile set
            tileSet.addAll(parseMarker(val));
            // delete old marker from val
            val &= ~TILE_MARKER_MASK;
        }

        int extendedSetIndex = extendedSets.addExtendedSet(tileSet);

        val |= TILE_EXT_MASK;
        val |= (long) extendedSetIndex;

        return val;
    }

    /** transforms a map value into a list of integer tile coords (using {@link TileCoord} encoding) */
    TIntSet decode(long value) {
        TIntSet result = new TIntHashSet(4);
        decode(value, result);
        return result;
    }

    /**
     * transforms a list of map values into a list of integer tile coords (using {@link TileCoord} encoding).
     * The result is the union of applying {@link #decode(long)} to each value.
     */
    TIntSet decode(@NotNull Collection<Long> tiles) {
        TIntSet result = new TIntHashSet(tiles.size() * 4);
        for (long l : tiles) {
            decode(l, result);
        }
        return result;
    }

    /**
     * shared implementation of {@link #decode(long)} and {@link #decode(Collection)},
     * adds results to an existing set
     */
    private void decode(long value, TIntSet resultSet) {

        int tx = tileX(value);
        int ty = tileY(value);
        int neighbour = neighbour(value);

        resultSet.add(TileCoord.encode(tx, ty));
        if ((neighbour & NEIGHBOURS_EAST) != 0) {
            resultSet.add(TileCoord.encode(tx + 1, ty));
        }
        if ((neighbour & NEIGHBOURS_SOUTH) != 0) {
            resultSet.add(TileCoord.encode(tx, ty + 1));
        }
        if (neighbour == NEIGHBOURS_SOUTH_EAST) {
            resultSet.add(TileCoord.encode(tx + 1, ty + 1));
        }

    }
    
    private TIntList parseMarker(long value) {
        TIntList result = new TIntArrayList();
        int tx = tileX(value);
        int ty = tileY(value);

        for (int i = 0; i < 24; i++) {
            // if bit is not set, continue..
            if (((value >> i) & 1) == 0) {
                continue;
            }

            int v = i >= 12 ? i + 1 : i;

            int tmpX = v % 5 - 2;
            int tmpY = v / 5 - 2;

            result.add(TileCoord.encode(tx + tmpX, ty + tmpY));
        }
        return result;
    }

    /**
     * Merge two sets of ints, removing dupes
     * 
     * @param old the original set
     * @param add the additional set
     * @return the new set
     */
    private static TIntSet merge(@NotNull TIntSet old, @NotNull TIntSet add) {
        TIntSet result = new TIntHashSet(old);
        result.addAll(add);
        return result;
    }

    /**
     * Return a list with the contents of an int array
     * 
     * @param set the array
     * @return a List of Integer
     */
    @NotNull
    private static List<Integer> asList(@NotNull int[] set) {
        List<Integer> result = new ArrayList<>();

        for (int i = 0; i < set.length; i++) {
            result.add(set[i]);
        }

        return result;
    }

}
