
### General

MapSplit is a small application to split a larger OpenStreetMap data file into tiles. 

It arose by the need of a fast way to split a OSM map file for rendering 3D tiles with OSM2World (www.osm2world.org) and was originally 
created by Peter Barth in 2011.

While conceptually similar to "vector tiles" the tiles generated by MapSplit contain unprocessed, unmodified and complete, OSM data. 
The generated tiles are referentially complete with respect to way nodes if the input file was so too. If the _metadata_ option
is set, the tiles can be used as a read only OSM source for editors that support this format.

### MBTiles output

Tiling larger regions at higher zoom levels will result in a large number (as in 100s of thousands) of files, this is
not only unwieldy, it is slow too. The preferred output format is [MBTiles](https://github.com/mapbox/mbtiles-spec) for such use
cases. 

To make the contents easily identifiable and usable by applications we set the following meta data fields:

* _format_ __application/vnd.openstreetmap.data+pbf__ (note this is not a registered mime type)
* _minzoom_ and _maxzoom_
* _latest___date_ the timestamp of the youngest OSM element added, as the number of seconds since the UNIX epoch

You should __NOT__ confuse this format with Mapbox vector tiles that use PBF encoded tiles for rendering data generated from, among
other sources, OpenStreetMap.

### Limitations

The current implementation keeps the data structures required for assigning OSM objects to tiles in main memory. With 4GB of main memory 
you can parse maps with up to 100 million nodes, however, memory usage also depends on the number of tiles that got changed.

The maximum zoom levels tiles can be produced at is 16, as x and y tile numbers are packed in a 32 bit integer during processing. 
Tiling large areas at zoom level 16 will create large numbers of tiles and should only be used with the optimization pass enabled. 

Note: the incremental update feature likely doesn't really work and should be replaced. For smaller regions re-tiling from an updated 
source file is probably faster in any case.

### Legal

See COPYING for licence information.

OpenStreetMap and the magnifying glass logo are trademarks of the OpenStreetMap Foundation. The MapSplit application is not endorsed by or affiliated with the OpenStreetMap Foundation. 

### Usage

    -b,--border <arg>     enlarge tiles by val ([0-1]) of the tile's size to
                          get a border around the tile.
    -c,--complete         store complete data for multi-polygons
    -d,--date <arg>       file containing the date since when tiles are being
                          considered to have changed after the split the
                          latest change in infile is going to be stored in file
    -O,--optimize <arg>   try to merge tiles with less that <arg> nodes to larger
                          tiles (2'000 might be a good value for this) 
    -f,--maxfiles <arg>   maximum number of open files at a time
    -h,--help             this help
    -i,--input <arg>      a file in OSM pbf format
    -m,--metadata         store metadata in the tiles (version, timestamp), 
                          if the input file is missing the metadata abort
    -o,--output <arg>     if creating a MBTiles file this is the name of the
                          file, otherwise this is the base name of all tiles
                          that will be written. The filename may contain '%x'
                          and '%y' which will be replaced with the tile numbers, 
                          and '%z' that will be replaced with the tile zoom level.
    -p,--polygon <arg>    only save tiles that intersect or lie within the
                          given polygon file.
    -s,--size <arg>       n,w,r the size for the node-, way- and relation
                          maps to use (should be at least twice the number of
                          IDs). If not supplied, defaults will be used.
    -t,--timing           output timing information
    -v,--verbose          verbose information during processing
    -M,--mbtiles          store in a MBTiles format sqlite database
    -z,--zoom <arg>       zoom level to create the tiles at must be between 0 (silly)
                          and 16 (inclusive), default is 13

### Example

    java -Xmx6G -jar mapsplit-all-0.2.0.jar -tvMm -i iraq-latest.osm.pbf -o iraq.msf -f 2000 -z 16 -O 2000

Will generate a 211MB large MBTile format MapSplit file with all the data, including metadata for the Iraq in a couple of minutes.
 
### Testing

The current tests are rather superficial and need to be improved, the high coverage numbers are misleading.
 