# RideSafe selected-rides backup format

## Contract and compatibility

The archive is a machine-oriented, self-contained export of the selected logical rides. It is the
restore contract for a future transactional RideSafe importer; it is not the human-readable sharing
format (PDF/CSV) and is not a third-party interchange standard.

The ZIP root contains `manifest.json`. `formatId` identifies this product contract,
`formatVersion` versions the ZIP/container conventions, and `schemaVersion` versions the manifest
data model. These are independent of `sourceDatabaseVersion`, which is diagnostic producer metadata
and must never be used to decide archive compatibility.

Schema 2 is the first restore-ready schema. A reader must ignore unknown JSON object fields. Missing
optional fields use their documented defaults; missing required fields are invalid. Unknown enum
values are invalid unless a later schema explicitly defines a lossless representation. A reader
must reject unsupported newer schemas before changing any state. Older schemas are accepted only
after an explicit, tested, sequential in-memory upgrade to a supported schema; there is deliberately
no implicit best-effort upgrade from the earlier experimental schema 1.

Every numeric `archiveId` and `*ArchiveId` is scoped to its entity namespace and to this archive.
Values may resemble source database primary keys, but they are references, not destination keys. An
importer must allocate destination IDs and remap every relationship. It must not insert archive IDs
directly. Logical selection IDs are likewise archive-local.

## Manifest entities and relationships

`logicalSelections` partitions all exported rides and preserves which physical rides formed each
selected logbook entry. `mergeGroups` records ordered membership independently of logical selection.
Each ride has a stable `rideUuid` and optionally references one vehicle, one merge group, and saved start/end addresses. Events
and analysis states reference a ride. Refuels reference a vehicle and optionally their selected
journey anchor. Bluetooth devices are owned values nested in their vehicle.

Vehicle type, engine description, and manufacturing country are optional vehicle attributes. They
are stored directly in the manifest and restored with the vehicle; archives created before these
fields existed remain valid.

Every non-null reference must resolve inside the manifest. A merge group's forward membership and
the rides' reverse merge-group references must agree. The exporter validates these rules before ZIP
creation and the reference reader validates them again from the finished ZIP.

## File layout and integrity

Paths are canonical and derived only from archive-local ride IDs:

```text
manifest.json
data/rides/{rideArchiveId}/samples.ndjson.gz
data/rides/{rideArchiveId}/route.v{routeProcessingVersion}
```

Paths must be relative, slash-separated, normalized ASCII paths without empty, `.` or `..`
components. Duplicate paths and unlisted ZIP entries are invalid.

Raw samples are `required_source`; their absence, an invalid gzip stream or a blank NDJSON record
makes export fail. Export checks record *framing* only: the reader that writes an archive already
knows the file came from this device's own recorder, so it drains the gzip stream — which verifies
its CRC32 and length trailer, catching a file truncated by a crash — and rejects blank records,
without deserializing each one. Restore deserializes every record; see below. A route is
`optional_regenerable_derived`: its descriptor is always present, with `status: "absent"`, null size
and null hash when no sidecar exists. Every included file carries its compressed/on-disk byte size
and lowercase SHA-256. Already-compressed raw `.gz` files use ZIP method STORED. ZIP CRC is additional
transport protection and is not a replacement for the manifest hash.

The raw file is UTF-8 newline-delimited JSON inside gzip. Records use discriminator `ty` (`loc` or
`mot`). Records are **not necessarily globally ordered by timestamp**: sensor FIFO batching can write
older motion records after newer GPS records. Each stream is monotonic; consumers needing a unified
timeline must reorder records by `t` with an appropriate bounded window.

## `.route.v2`

The route sidecar is UTF-8 text containing one Google Encoded Polyline. It has no header and carries
only latitude/longitude. Coordinates use the encoded-polyline 1e-5-degree quantization (about 1.11 m
latitude; longitude varies with latitude). No timestamps, altitude, speed, or accuracy are stored.
Before encoding, fixes are Kalman-filtered and RDP-simplified with a 5 m tolerance. The filename's
version is the route processing version; it is also recorded in `processingVersions.route`.

## Consistent snapshot

An active ride (`endedAtEpochMs == null`) cannot be exported. Recording closes and flushes the gzip
writer before it finalizes that field, so an accepted raw file is no longer being appended.

Analysis and export share per-ride locks. Export acquires all selected locks in ascending ID order,
reads related database rows in one Room transaction, and copies raw/derived files into a private
temporary snapshot while the locks remain held. ZIP creation uses only those snapshots. Route
sidecars are published through an fsynced same-directory temporary file and atomic replacement, so
readers see either the old complete sidecar or the new complete sidecar. Unrelated rides remain free
to record or analyze concurrently.

After writing, the production reference reader reopens the finished ZIP, verifies paths, schema,
relationships, sizes, hashes, gzip framing, route decoding, entry set, and STORED handling. Only an
archive accepted by that reader may be published to Downloads.

## Restore behavior

Import is additive for rides and refuels: it never replaces existing rides or settings. The selected content URI is copied
to private storage and the same reference reader validates it again immediately before restoration,
there with per-record deserialization enabled: an archive from another device is the one case where
a malformed record has not already been vouched for by this device's recorder. The import *preview*
uses the cheap framing check, so picking a large archive stays responsive; a record that fails to
deserialize is caught before anything is written.
Every archive-local vehicle, address, ride, event and refuel ID is mapped to a retained or newly
allocated Room ID; merge-group membership and all nullable references are rebuilt from those maps.

Vehicles carry a stable `vehicleUuid` and `updatedAtEpochMs`. Import maps an archived vehicle to the
existing row with the same UUID, preserving the destination database ID and primary status. If the
archive record is newer, its editable vehicle fields replace the older copy; every imported ride and
refuel then points to that retained row. Archives predating these fields are matched only when a
normalized nonblank license plate or Bluetooth hardware address identifies exactly one destination
vehicle. Conflicting or ambiguous legacy evidence never auto-merges. Legacy mileage resolves to the
greater value. If the garage is empty, the archived primary (or first archived vehicle) becomes
primary.

Physical rides carry a stable `rideUuid`. Import maps an archived ride to the existing row with the
same UUID and keeps the destination database ID. If no UUID match exists, the required raw-sample
SHA-256 is used as a content-identity fallback; this safely recognizes pre-UUID archives and the same
pre-existing ride after independent database migrations. Reused rides do not publish another raw or
route file and do not duplicate analysis states or detected events. Only newly inserted rides are
reported in the import result.

Saved places are remapped rather than blindly inserted. Home, Work and School match their existing
singleton kind. Custom places and gas stations match only when their normalized label and postal
address agree, or when the same label is within 15 metres. If an older importer already made
equivalent duplicate rows, import retains the lowest destination ID, repoints existing ride endpoint
references to it, and removes the redundant rows in the same transaction.
The same consolidation runs during the normal saved-place refresh, repairing duplicates created by
older app versions without requiring the archive to be imported again.

Included files are extracted into a private staging directory. Within one Room transaction, imported
rows are inserted and the required raw files are fsynced and atomically moved to collision-safe new
names. A current-version route is restored when present. An absent or older derived route has its
route analysis stamp omitted so the normal pipeline regenerates it from raw samples. If any database
or file operation fails or is cancelled, the Room transaction rolls back and every file already
published by that attempt is deleted.
