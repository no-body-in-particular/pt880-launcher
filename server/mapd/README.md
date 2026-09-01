# mapd

The map server the watch talks to: tiles, road vectors and routing graphs.

Replaces the PHP that used to serve `/map/`. It runs on localhost and
hiawatha reverse proxies to it, so the watch keeps talking to
`https://coredump.ws/map/` and sees no change at all. That arrangement is
deliberate: the watch's TLS is BouncyCastle speaking to hiawatha's exact
cipher configuration, and getting that working on a 2013 device took long
enough that it is not worth risking to save a hop.

## Why

A block of 256 tiles, dense city, measured on the same machine and the same
data:

| | time | bytes |
|---|---|---|
| PHP + GD | 16,320 ms | 1,441,575 |
| mapd, cold caches | 664 ms | 1,414,675 |
| mapd, warm caches | 408 ms | |

Two things account for it, and neither is Rust being fast at arithmetic.

The tiles in a block are independent and there are 256 of them, but `php-cgi`
is one process per request and rendered them one after another while seven of
the eight cores sat idle. And nothing survived a request: a decoded map cell
was thrown away when the process exited, so a block decoded the same cells a
dozen times over. Here a cell stays decoded for as long as the service runs,
and the tiles of a block are rendered across every core.

The output is an 8-bit palette PNG written without filtering. Filtering helps
a photograph, where neighbouring bytes are nearly equal, and hurts a palette
image, where they are unrelated indices - left on the encoder's default,
tiles came out nearly twice the size GD manages.

## Tiles are not cached to disk

Measured on this data: an average block renders in 120ms and reads back from
disk in 40ms, while transferring it to the watch over wifi takes 2.2 seconds.
So the cache saved four per cent of a download and cost 215MB per country -
which for Europe and America would have been tens of gigabytes.

It also had to be wiped by hand every time the rendering changed, and twice
in one week that was noticed only after the watch had already downloaded the
stale version. Rendering afresh is always correct and nearly always faster
than the network it feeds. A dense city block takes 110ms with the cell
caches warm, which they stay, because this is a service rather than a script.

Twenty recent blocks are kept in memory, which covers the watch retrying one
it failed to read - the only repeat that actually happens, since the watch
stores what it downloads.

Set `MAP_DISK_CACHE=1` to put the disk cache back.

## Running

    rc-service mapd start

Reads `MAP_ROOT` (default `/var/www/hiawatha/map`) and `MAP_ADDR` (default
`127.0.0.1:8088`). Runs as `hiawatha` so the tile cache it writes is the one
the web server can read.

## Alerts

`/alerts.php?c=<country>` returns the speed cameras, motorway junctions and
filling stations for a country, and with `&w=&s=&e=&n=` for a box of it.

Built from the store per request rather than served from a file. It started as
a precomputed artifact and that was the wrong shape twice over: the file goes
stale against the store it came from, and a country is all a caller can ask
for - a continent's cameras are not a sensible download for a watch that only
needs the ones around it. The store is already indexed by cell, so a box costs
a box: the Netherlands is 114 kB whole, 3 kB around one town.

There is no PHP equivalent. Everything else here has one because it was ported
from one; this never existed in PHP, and a second implementation of the format
is a second thing to keep in step.

A store built before `import_points.php` has no points table and gets an empty
layer rather than an error, which the watch reads as "nothing here".

`MAP_OSRM` (default `https://router.project-osrm.org`) is where `/route.php`
goes. The watch routes on the graph on its own card whenever it has one, so
this endpoint only answers for a country that has not been downloaded - but
that is exactly the case where the watch cannot fall back on itself, so it is
worth pointing at an OSRM you run. The default is the project's demo server,
which is explicitly not offered for production use: it rate-limits, it makes
no uptime promise, and it sees every destination the watch is sent to.

## There is no going back

This used to say the PHP was still in `/var/www/hiawatha/map` and still
worked, and that commenting out the `ReverseProxy` line would fall back to it.
Both halves stopped being true on 30 August, when the deployed copies were
deleted - after a fix was made to `route.php`, deployed, and did nothing
whatever, because that file had not answered a request in months. A fallback
nobody exercises is not a fallback. What is left in that directory is the
import and tile-building side, which is a different job and is not what the
watch talks to.

## Endpoints

Identical to the PHP, including the `.php` in the paths - the watch has those
URLs compiled in, and a rename would be a flag day for no benefit.

| | |
|---|---|
| `pack.php` | a 16x16 block of tiles in one response |
| `tile.php` | a single tile |
| `country.php` | which country covers a position, or every country present |
| `graph.php` | a routing graph, whole or cut to a bounding box |
| `route.php` | a route, proxied from OSRM and cached a day |
| `rain.php` | tomorrow's rain over a box, hour by hour |
| `health` | for checking it is up |

A country need not be named: tile numbers are global, so `mapd` works out
which database to render from by where the request is asking about.

## WRT2, the roundabout exit

Done, and this section used to be a TODO saying it was not. `route_encode.rs`
emits `WRT2` with the exit byte after the turn byte, and the cache key carries
`.v2.bin` so the WRT1 files written before the change cannot be read back - the
51 of those still in `tiles/routes` are unreachable by name and older than the
one-day freshness window twice over.

Verify after a deploy with a route fetch: the first four bytes are the format,
and the header is big-endian.

## Rain

`/rain.php` returns the next 24 hours of rain over the Netherlands as an
animated GIF: 24 frames, an hour each, held a second apiece. `px` sets the
width and the height follows the shape of the country -- the watch asks for
200, a browser gets 340.

That is the whole endpoint. There is no `fmt`, no position, no options. It
briefly had four answers behind a `fmt` parameter -- a packed grid for the
watch, a contact sheet, a text listing and an HTML page -- and they all went:
the watch shows this same GIF, and one rendering of a forecast is one thing to
keep working rather than four.

The radar everyone knows extrapolates the last few frames and is honest for
about two hours. This is Buienradar's `rain48hour` product instead -- a weather
model, run hourly, valid to two days -- because the question a watch is asked
is not "is it raining" but "will it be raining when I set off".

`src/rain.rs` carries the derivation: which endpoints, why the timestamps are
UTC, and where the georeference comes from and how it was checked. The short
version:

| | |
|---|---|
| Frames | 24, hourly, first at run+2h |
| Source grid | 1058x915 stretched into 54.8..49.5 N, 0..10 E, Web Mercator |
| Answer | 340 px wide, 147 kB; at 200 px, 62 kB |
| Held | one run, 23 MB, refreshed in the background every ten minutes |

### What it costs

The map under the rain is `src/netherlands-base.png`, drawn once by
`mapd --make-base` and compiled in. Rendering it from the road store instead
took **13 seconds** -- a country at that zoom is a couple of hundred tiles and
each asks for the ground cover across twenty kilometres, overlapping its
neighbours heavily -- and it had to be paid again on every restart, to draw a
coastline that does not change between deploys. Compiled in, a cold request is
0.19 s.

Frames are written as differences: everything unchanged since the previous
frame is left transparent and the one before shows through. The country is
identical in all 24, so this is nearly the whole picture, and it takes the file
from 1.7 MB to 147 kB.

The palette is chosen by a quantiser rather than by counting. Counting does not
work here: the map is scaled down by averaging, which turns 32 flat colours
into thousands of near-identical dark ones, and those are far and away the most
numerous -- they take the whole palette and the rain arrives grey.

### Attribution

Buienradar's terms for the free data ask for the source to be credited. It is
drawn onto the frames themselves rather than served beside them, so it cannot
be separated from the picture by saving it and sending it on.
