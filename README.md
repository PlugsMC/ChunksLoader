# ChunksLoader

ChunksLoader is a Spigot/Paper plugin that lets server owners keep areas of the
world permanently loaded by placing special beacon-based "chunk loader" blocks.
The plugin supports Minecraft versions up to **1.21.9**, integrates with
popular web map plugins, and provides tools for managing loaders in game.

## Features

* **Chunk loader item** – Operators can grant players a beacon that forces the
  surrounding chunks to stay loaded while it is active.
* **Placement safeguards** – Loaders cannot be placed in regions that are
  already covered by another loader to prevent overlapping areas.
* **Interactive control menu** – Right-clicking a loader opens a GUI to toggle
  it on or off without breaking the block.
* **Chunk map preview** – `/chunksloader map` displays a coloured overview of
  nearby chunks that indicates active loaders, inactive loaders, spawn chunks,
  and unloaded areas.
* **Dynmap and BlueMap support** – When the respective plugins are installed,
  the loader areas are published to the web map with tooltips that show
  ownership, radius, and status information.
* **Persistent storage** – Loader locations and states are saved to
  `chunkloaders.yml`, ensuring that they survive server restarts.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/chunksloader give [player]` | Gives the chunk loader item to the specified player (or yourself if omitted). | `chunksloader.give` |
| `/chunksloader map` | Shows the chunk status map centred on the executing player. | `chunksloader.use` |

If the plugin command is entered without a sub-command, the available options
are displayed in chat.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `chunksloader.use` | Everyone | Allows using `/chunksloader map` and interacting with loader GUIs. |
| `chunksloader.give` | Operators | Allows giving loader items with `/chunksloader give`. |

## Configuration

The default `config.yml` exposes two options:

```yaml
loader-radius: 1   # How many chunks around the loader stay active (radius).
map-radius: 5      # Radius, in chunks, of the `/chunksloader map` preview.
```

Reload the server or restart it after changing the configuration so the new
values take effect.

## Map integrations

* **Dynmap** – When Dynmap is installed, the plugin creates a dedicated marker
  layer that shows each loader's coverage area with descriptions that include
  coordinates, chunk count, status, and owner information.
* **BlueMap** – When BlueMap is present, the plugin adds a toggleable marker set
  to every rendered world and keeps it synchronised with loader changes.

Both integrations are optional; the plugin operates fully without them.

## Building

```sh
./gradlew build
```

> **Note:** The Gradle wrapper jar is stored in a Base64-encoded form at
> `gradle/wrapper/gradle-wrapper.jar.base64`. Running `./gradlew` restores the
> jar automatically. If you prefer to invoke Gradle directly, decode the file
> yourself with `base64 -d gradle/wrapper/gradle-wrapper.jar.base64 >
> gradle/wrapper/gradle-wrapper.jar` before running any tasks.

The command above generates a shaded jar named using the pattern
`ChunksLoader-<minecraft-version>-<release-tag>.jar` and places it in both
`build/libs/` and the repository-level `assets/` directory. By default the
Minecraft version is set to **1.21.9**.

To produce jars for every supported Minecraft version in one go, use the helper
script:

```sh
./scripts/build-all-assets.sh
```

The script reads the supported Minecraft versions from
`supported-versions.txt`, recompiles the plugin for each entry, and ensures that
every resulting jar is copied into the `assets/` directory using the
`ChunksLoader-<minecraft-version>-<release-tag>.jar` naming scheme so they
can be uploaded directly to a release.
